package dev.antigravity.fluidtransit.routing

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Le fermate vicine" devono essere vicine, e in ordine.
 *
 * La scansione della griglia esce cella per cella, quindi l'ordine che ne
 * veniva fuori era quello della griglia. Non si vedeva nella rete di prova —
 * tre fermate in fila in una cella sola escono per indice, che li' e' anche
 * l'ordine di distanza — ma si vede da chi guarda dal fondo della fila: chi
 * sta a Corso Gamma si sentiva elencare prima Piazza Alfa, a duecento metri.
 *
 * In centro citta', dove dentro settecento metri ci sono decine di fermate,
 * questo voleva dire che "le prime otto" erano otto fermate qualsiasi del
 * cerchio, e la fermata sotto i piedi poteva non essere fra quelle.
 */
class StopsNearOrderTest {

    private val tmp = mutableListOf<File>()

    @AfterTest
    fun pulisci() {
        tmp.forEach { it.delete() }
    }

    private fun bundle() = BundleReader(TestBundle.write(tmp))

    @Test
    fun `le fermate escono dalla piu' vicina alla piu' lontana`() {
        bundle().use { r ->
            // Poco oltre Corso Gamma, guardando indietro lungo la fila: la
            // distanza mette C prima di B prima di A, la griglia il contrario.
            val near = r.stopsNear(43.00210, 11.0, 500.0)
            assertContentEquals(listOf(2, 1, 0), near)
        }
    }

    @Test
    fun `l'ordine e' quello vero, misurato una distanza alla volta`() {
        bundle().use { r ->
            val lat = 43.00210
            val lon = 11.0
            val near = r.stopsNear(lat, lon, 500.0)
            val distanze = near.map { BundleReader.haversine(lat, lon, r.stopLat(it), r.stopLon(it)) }
            assertEquals(
                distanze.sorted(),
                distanze,
                "le distanze non salgono: l'ordine non e' quello di vicinanza",
            )
        }
    }

    @Test
    fun `dal centro della fila l'ordine cambia di conseguenza`() {
        bundle().use { r ->
            // Da Via Beta, che sta in mezzo: prima se stessa, poi le due
            // ai lati. Quale delle due venga prima non conta, basta che
            // vengano dopo.
            val near = r.stopsNear(43.00100, 11.0, 500.0)
            assertEquals(1, near.first())
            assertEquals(setOf(0, 2), near.drop(1).toSet())
        }
    }

    @Test
    fun `prendere le prime N vuol dire prendere le N piu' vicine`() {
        bundle().use { r ->
            // E' la ragione per cui questo ordine esiste: chi tronca la lista
            // deve ottenere le piu' vicine, non quelle che capitano.
            val due = r.stopsNear(43.00210, 11.0, 500.0).take(2)
            assertContentEquals(listOf(2, 1), due)
        }
    }

    @Test
    fun `la lontana resta fuori, e il cerchio vuoto resta vuoto`() {
        bundle().use { r ->
            // Borgo Delta e' a cinquanta chilometri: l'ordinamento non deve
            // aver allargato il raggio per sbaglio.
            assertTrue(r.stopsNear(43.00210, 11.0, 500.0).none { it == 3 })
            assertTrue(r.stopsNear(42.0, 10.0, 500.0).isEmpty())
        }
    }
}
