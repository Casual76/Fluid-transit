package dev.antigravity.fluidtransit.ui.nav

import dev.antigravity.fluidtransit.data.nav.NavFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cosa resta acceso sulla mappa, fase per fase.
 *
 * E' una traduzione, e le traduzioni si sbagliano in silenzio: un campo
 * girato male non fa crashare niente, fa solo riapparire mezza rete toscana
 * addosso a chi sta aspettando un autobus — che e' esattamente il difetto
 * da cui e' partito tutto questo lavoro.
 */
class NavMapFocusTest {

    private fun fuoco(
        routeHash: String = "aaa",
        utili: Array<String> = arrayOf("aaa", "bbb"),
    ) = NavFocus(
        buildId = 1L,
        legIndex = 1,
        trip = 10,
        boardPosition = 2,
        alightPosition = 6,
        colorRgb = 0x2277CC,
        routeHashHex = routeHash,
        stopHashes = arrayOf("s2", "s3", "s4", "s5", "s6"),
        usefulRouteHashes = utili,
        useful = emptyList(),
        path = null,
        sBoard = 0.0,
        sAlight = 0.0,
        fallbackLat = DoubleArray(0),
        fallbackLon = DoubleArray(0),
        bounds = DoubleArray(4),
        boardLat = 43.0,
        boardLon = 11.0,
        alightLat = 43.1,
        alightLon = 11.1,
    )

    @Test
    fun `le linee utili si vedono mentre aspetti e spariscono quando sali`() {
        assertTrue("a piedi verso la fermata servono", mostraUtili("walk"))
        assertTrue("in attesa servono piu' che mai", mostraUtili("wait"))
        // A bordo la scelta e' fatta: sapere che sarebbe andata bene anche
        // la 22 non aiuta piu' nessuno, e toglie spazio a dove scendere.
        assertFalse("a bordo no", mostraUtili("ride"))
    }

    @Test
    fun `nell'ultima camminata e all'arrivo le linee utili si spengono`() {
        // Dopo l'ultimo bus non si aspetta piu' niente: le alternative
        // riaccese dicevano "va bene anche la 22" a chi stava camminando
        // verso casa.
        assertFalse("ultima camminata", mostraUtili("walk", finished = true))
        assertFalse("arrivato", mostraUtili("arrived"))
        assertFalse(navMapFocus(fuoco(), "walk", finished = true).showUseful)
    }

    @Test
    fun `la mia linea c'e' sempre, anche quando compare fra le utili`() {
        // La mappa disegna le utili in un layer solo: se la mia ne restasse
        // fuori, sparirebbe al cambio di fase.
        val f = navMapFocus(fuoco(), "wait")
        assertTrue(f.usefulRouteHashes.contains("aaa"))
        assertEquals("aaa", f.routeHashHex)
    }

    @Test
    fun `solo le fermate fra salita e discesa finiscono sulla mappa`() {
        // Non tutte le fermate della linea: quelle prima di dove salgo e
        // dopo dove scendo sono fermate di qualcun altro.
        val f = navMapFocus(fuoco(), "ride")
        assertEquals(5, f.stopHashes.size)
        assertEquals("s2", f.stopHashes.first())
        assertEquals("s6", f.stopHashes.last())
    }
}
