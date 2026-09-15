package dev.antigravity.fluidtransit.ui.map

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il pannello aperto sopravvive alla rotazione, al cambio di scheda e allo
 * sfratto dalla memoria.
 *
 * Il modo in cui un saver si rompe e' sempre lo stesso: un campo scritto in
 * un ordine e riletto in un altro. Non esplode — restituisce un pannello
 * plausibile con dentro i numeri sbagliati, e quello e' molto peggio, perche'
 * si vede solo come "ha aperto la linea che non avevo toccato". Quindi si
 * prova il giro completo, campo per campo, su ognuno dei nove pannelli.
 */
class PanelSaverTest {

    /** Il contenitore accetta solo tipi che sa scrivere: niente oggetti nostri. */
    private val scope = SaverScope { it is String || it is Int || it is Long || it is Double }

    private fun <T> round(saver: Saver<T, Any>, value: T): T? {
        val saved = with(saver) { scope.save(value) }
        // Una lista vuota vuol dire "niente da salvare": il contenitore non
        // la scrive, e al ritorno vale il valore iniziale.
        if (saved == null) return null
        assertTrue(
            "il saver ha prodotto qualcosa che il contenitore non sa scrivere",
            (saved as List<*>).all { scope.canBeSaved(it!!) },
        )
        return saver.restore(saved)
    }

    private fun panel(p: Panel?): Panel? = round(PanelSaver, p)

    private val luogo = PlaceRef(
        name = "Ponte all'Asse",
        context = "Firenze",
        lat = 43.7812,
        lon = 11.2213,
        savedId = 77L,
    )

    private val corsa = TripRef(
        vehKey = 123456,
        tripHash = -8_000_000_000_000_000_001L,
        routeHash = 42L,
        tripIndex = 9001,
        routeIndex = 12,
    )

    private fun assertLuogo(r: PlaceRef) {
        assertEquals("Ponte all'Asse", r.name)
        assertEquals("Firenze", r.context)
        assertEquals(43.7812, r.lat, 0.0)
        assertEquals(11.2213, r.lon, 0.0)
        assertEquals(77L, r.savedId)
    }

    private fun assertCorsa(r: TripRef) {
        assertEquals(123456, r.vehKey)
        assertEquals(-8_000_000_000_000_000_001L, r.tripHash)
        assertEquals(42L, r.routeHash)
        assertEquals(9001, r.tripIndex)
        assertEquals(12, r.routeIndex)
    }

    @Test
    fun `nessun pannello resta nessun pannello`() {
        assertNull(panel(null))
    }

    @Test
    fun `la fermata si porta dietro hash e nome`() {
        val p = panel(Panel.Stop(StopTap("e76ddd4605d9f3d5", "SODERINI TORRINO"))) as Panel.Stop
        assertEquals("e76ddd4605d9f3d5", p.tap.idHashHex)
        assertEquals("SODERINI TORRINO", p.tap.name)
    }

    @Test
    fun `la linea ridotta e quella intera restano distinte`() {
        // Sono lo stesso indice in due stati diversi: se il tag si perde, si
        // torna sulla mappa con la scheda gia' aperta a tutta altezza.
        val mini = panel(Panel.RouteMini(31)) as Panel.RouteMini
        val full = panel(Panel.RouteFull(31)) as Panel.RouteFull
        assertEquals(31, mini.routeIndex)
        assertEquals(31, full.routeIndex)
    }

    @Test
    fun `la corsa ridotta e quella intera si riportano i cinque campi`() {
        assertCorsa((panel(Panel.TripMini(corsa)) as Panel.TripMini).ref)
        assertCorsa((panel(Panel.TripFull(corsa)) as Panel.TripFull).ref)
    }

    @Test
    fun `il luogo si riporta i cinque campi`() {
        assertLuogo((panel(Panel.Place(luogo)) as Panel.Place).ref)
    }

    @Test
    fun `qui intorno non ha campi e non ne inventa`() {
        assertSame(Panel.Nearby, panel(Panel.Nearby))
    }

    @Test
    fun `i viaggi e il dettaglio di un viaggio`() {
        assertLuogo((panel(Panel.Journeys(luogo)) as Panel.Journeys).to)
        val d = panel(Panel.JourneyDetail(luogo, 3)) as Panel.JourneyDetail
        assertEquals(3, d.index)
        assertLuogo(d.to)
    }

    @Test
    fun `un luogo che non e' salvato torna senza id`() {
        val r = round(PlaceRefSaver, PlaceRef("Via Roma", "", 43.0, 11.0, null))
        assertNull("il -1 e' finito in un id vero", r!!.savedId)
        assertEquals("Via Roma", r.name)
    }

    @Test
    fun `le coordinate della partenza`() {
        assertNull(round(LatLonSaver, null))
        val p = round(LatLonSaver, 43.7812 to 11.2213)!!
        assertEquals(43.7812, p.first, 0.0)
        assertEquals(11.2213, p.second, 0.0)
    }

    @Test
    fun `un pannello scritto da una versione che non conosciamo non apre niente`() {
        // Il contenitore puo' restituire quello che ci aveva messo una
        // versione precedente dell'app, dopo un aggiornamento in-app.
        assertNull(PanelSaver.restore(listOf("qualcosaltro", 1)))
        assertNull(PanelSaver.restore(emptyList<Any>()))
    }
}
