package dev.antigravity.fluidtransit.ui.map

import dev.antigravity.fluidtransit.routing.StopGroups
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Fermate vicine" nella barra di ricerca: una riga per fermata vera, non per
 * banchina. Il difetto erano "TORRE GALLI - a 40 m" e "TORRE GALLI - a 60 m"
 * una sotto l'altra, e la stessa fermata due volte fra i recenti.
 */
class NearbyStopsTest {

    private val lat0 = 43.5
    private val lon0 = 11.0
    private val dLat = 1.0 / 110_540.0

    private fun gruppi(nomi: List<String>, metri: List<Double>) = StopGroups.build(
        count = nomi.size,
        name = { nomi[it] },
        lat = { lat0 + dLat * metri[it] },
        lon = { lon0 },
    )

    @Test
    fun `due banchine della stessa fermata sono una riga sola`() {
        // 0 e 2 sono TORRE GALLI, a 40 m l'una dall'altra; 1 e' un'altra fermata.
        val g = gruppi(listOf("TORRE GALLI", "PIAZZA", "TORRE GALLI"), listOf(0.0, 30.0, 40.0))
        val r = nearbyStops(listOf(0, 1, 2), g, limit = 5)

        assertEquals(2, r.size)
        assertEquals(0, r[0].nearest)
        assertEquals(1, r[1].nearest)
    }

    @Test
    fun `la riga porta il rappresentante del gruppo, come la ricerca`() {
        // La banchina piu' vicina e' la 2, ma il rappresentante e' la 0: e'
        // la sua chiave che la ricerca usa, e i recenti deduplicano per chiave.
        val g = gruppi(listOf("TORRE GALLI", "PIAZZA", "TORRE GALLI"), listOf(0.0, 30.0, 40.0))
        val r = nearbyStops(listOf(2, 1, 0), g, limit = 5)

        assertEquals(2, r[0].nearest)
        assertEquals(g.members(g.groupOf(2)).first(), r[0].representative)
        assertEquals(0, r[0].representative)
    }

    @Test
    fun `il limite conta le fermate vere, non le banchine`() {
        val g = gruppi(
            listOf("A", "A", "B", "B", "C"),
            listOf(0.0, 10.0, 20.0, 30.0, 40.0),
        )
        val r = nearbyStops(listOf(0, 1, 2, 3, 4), g, limit = 3)

        assertEquals(listOf(0, 2, 4), r.map { it.nearest })
    }

    @Test
    fun `senza gruppi ogni banchina vale per se'`() {
        val r = nearbyStops(listOf(3, 1, 2), null, limit = 2)

        assertEquals(listOf(3, 1), r.map { it.nearest })
        assertEquals(listOf(3, 1), r.map { it.representative })
    }
}
