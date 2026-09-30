package dev.antigravity.fluidtransit.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Lo scambio di bundle con l'app in uso: il pannello aperto non sparisce a
 * caso. Il difetto era doppio — una fermata aperta si perdeva senza motivo, e
 * con una linea aperta la mappa restava ridotta a quella linea senza uscita —
 * quindi si inchioda il destino di ogni tipo di pannello.
 */
class PanelAfterSwapTest {

    private val luogo = PlaceRef("Ponte all'Asse", "Firenze", 43.78, 11.22)
    private val trovaCorsa: (Long) -> Int = { if (it == 10L) 77 else -1 }
    private val trovaLinea: (Long) -> Int = { if (it == 20L) 5 else -1 }

    private fun fate(p: Panel?) = panelAfterSwap(p, trovaCorsa, trovaLinea)

    @Test
    fun `fermata, luogo, vicino ed elenco viaggi restano dove sono`() {
        for (p in listOf<Panel>(
            Panel.Stop(StopTap("abc", "TORRE GALLI")),
            Panel.Place(luogo),
            Panel.Nearby,
            Panel.Journeys(luogo),
        )) {
            assertSame(PanelFate.Keep, fate(p))
        }
        assertSame(PanelFate.Keep, fate(null))
    }

    @Test
    fun `una corsa ritrova i suoi indici dagli hash, nello stesso formato`() {
        val ref = TripRef(vehKey = 9, tripHash = 10L, routeHash = 20L, tripIndex = 1, routeIndex = 2)
        val mini = fate(Panel.TripMini(ref)) as PanelFate.Replace
        val full = fate(Panel.TripFull(ref)) as PanelFate.Replace
        val m = (mini.panel as Panel.TripMini).ref
        val f = (full.panel as Panel.TripFull).ref
        assertEquals(77, m.tripIndex)
        assertEquals(5, m.routeIndex)
        assertEquals(9, m.vehKey)
        assertEquals(77, f.tripIndex)
    }

    @Test
    fun `una corsa che il bundle nuovo non conosce resta aperta con gli indici a meno uno`() {
        val ref = TripRef(vehKey = 9, tripHash = 11L, routeHash = 0L, tripIndex = 1, routeIndex = 2)
        val r = (fate(Panel.TripMini(ref)) as PanelFate.Replace).panel as Panel.TripMini
        assertEquals(-1, r.ref.tripIndex)
        assertEquals(-1, r.ref.routeIndex)
    }

    @Test
    fun `una linea, che porta solo l'indice, si butta`() {
        assertSame(PanelFate.Drop, fate(Panel.RouteMini(3)))
        assertSame(PanelFate.Drop, fate(Panel.RouteFull(3)))
    }

    @Test
    fun `il dettaglio di un viaggio torna all'elenco della stessa destinazione`() {
        val r = fate(Panel.JourneyDetail(luogo, 2)) as PanelFate.Replace
        val p = r.panel as Panel.Journeys
        assertSame(luogo, p.to)
    }
}
