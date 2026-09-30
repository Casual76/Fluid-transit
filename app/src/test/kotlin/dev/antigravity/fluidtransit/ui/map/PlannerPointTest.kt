package dev.antigravity.fluidtransit.ui.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nel pianificatore la ricerca offre posti, non linee: toccare la 23 in "Dove
 * vuoi andare?" faceva partire il viaggio verso il suo primo capolinea, o
 * verso NaN se la linea non aveva pattern.
 */
class PlannerPointTest {

    private fun s(kind: String, lat: Double = 43.77, lon: Double = 11.25) =
        Suggestion(kind, "k", "titolo", "sotto", 0, lat, lon)

    @Test
    fun `fermate, luoghi, civici e posti salvati sono punti`() {
        for (k in listOf("stop", "place", "civic", "saved")) {
            assertTrue(k, s(k).isPlannerPoint())
        }
    }

    @Test
    fun `una linea non e' un punto, anche con le coordinate del capolinea`() {
        assertFalse(s("route").isPlannerPoint())
    }

    @Test
    fun `senza coordinate vere non e' un punto`() {
        assertFalse(s("stop", lat = Double.NaN).isPlannerPoint())
        assertFalse(s("place", lon = Double.NaN).isPlannerPoint())
    }
}
