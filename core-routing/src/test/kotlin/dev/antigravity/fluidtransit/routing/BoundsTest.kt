package dev.antigravity.fluidtransit.routing

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Dove arrivano i nostri dati.
 *
 * "Qui intorno non passa niente a breve" e "qui non arriviamo" erano la
 * stessa frase. La seconda si vede aprendo l'app fuori dalla Toscana — in
 * vacanza, sceso dal treno a Bologna, o sull'emulatore, che nasce a Mountain
 * View — e una mappa vuota che dice che non passa niente fa pensare che
 * l'app sia rotta, invece di dire una cosa semplicissima.
 */
class BoundsTest {

    private val tmp = ArrayList<File>()

    @AfterTest
    fun pulisci() {
        tmp.forEach { it.delete() }
    }

    private fun reader() = BundleReader(TestBundle.write(tmp))

    @Test
    fun `il rettangolo copre tutte le fermate della rete di prova`() {
        reader().use { r ->
            val b = r.bounds
            for (i in 0 until r.stopCount) {
                assertTrue(
                    b.contains(r.stopLat(i), r.stopLon(i)),
                    "la fermata $i doveva starci dentro",
                )
            }
        }
    }

    @Test
    fun `Mountain View non e' in Toscana`() {
        reader().use { r ->
            assertFalse(r.bounds.contains(37.4220, -122.0841))
        }
    }

    @Test
    fun `appena fuori dal bordo vale il margine`() {
        reader().use { r ->
            val b = r.bounds
            // Duecento metri a sud del bordo inferiore: fuori dal rettangolo,
            // dentro il perdono. Il confine di una regione non e' un
            // rettangolo, e la griglia e' grossolana.
            val poco = b.minLat - 200.0 / 111_320.0
            assertFalse(b.contains(poco, b.minLon))
            assertTrue(b.contains(poco, b.minLon, marginMeters = 1_000.0))
        }
    }
}
