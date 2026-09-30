package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Le linee che vanno bene lo stesso.
 *
 * Il difetto da evitare e' uno e si vede solo in citta': quasi ogni linea
 * che passa dalla tua fermata tocca anche quella dove devi scendere — nel
 * verso che torna indietro. Mostrarla come alternativa vuol dire mandare
 * qualcuno dalla parte opposta con la benedizione dell'app.
 *
 * Il secondo, piu' raro e piu' cattivo: gli anelli. 215 pattern su 8.331
 * passano due volte dalla stessa fermata, e le due occorrenze sono lo
 * stesso mezzo che ripassa dopo un giro.
 */
class NavAlternativesTest {

    /** Una rete finta: ogni pattern e' la sua fila di fermate. */
    private class Rete(
        private val patterns: List<IntArray>,
        private val routes: IntArray,
    ) : PatternTopology {
        override fun patternsAtStop(stop: Int): IntArray =
            patterns.indices.filter { p -> patterns[p].any { it == stop } }.toIntArray()

        override fun patternStopCount(p: Int): Int = patterns[p].size
        override fun patternStop(p: Int, position: Int): Int = patterns[p][position]
        override fun patternRoute(p: Int): Int = routes[p]
    }

    /** Salgo alla 10, scendo alla 20. */
    private val salita = intArrayOf(10)
    private val discesa = intArrayOf(20)

    @Test
    fun `una linea che passa dalla mia fermata ma non arriva dove vado non e' utile`() {
        val rete = Rete(listOf(intArrayOf(5, 10, 11, 12, 13)), intArrayOf(1))
        assertTrue(NavAlternatives.serving(rete, salita, discesa).isEmpty())
    }

    @Test
    fun `una linea che tocca la discesa prima della salita va nel verso opposto`() {
        // Il caso di tutti i giorni: la stessa linea, il ritorno. Tocca
        // entrambe le fermate e non serve a niente.
        val rete = Rete(listOf(intArrayOf(20, 19, 10, 9)), intArrayOf(2))
        assertTrue(
            NavAlternatives.serving(rete, salita, discesa).isEmpty(),
            "la discesa e' alle spalle della salita",
        )
    }

    @Test
    fun `la linea del mio viaggio e' fra le utili`() {
        // Non e' un'ovvieta': la mappa disegna le utili in un layer solo, e
        // se la mia ne restasse fuori sparirebbe quando il viaggio cambia
        // fase.
        val rete = Rete(listOf(intArrayOf(1, 10, 11, 12, 20, 30)), intArrayOf(7))
        val utili = NavAlternatives.serving(rete, salita, discesa)
        assertEquals(1, utili.size)
        assertEquals(7, utili[0].route)
        assertEquals(1, utili[0].boardPosition)
        assertEquals(4, utili[0].alightPosition)
        assertEquals(3, utili[0].stops)
    }

    @Test
    fun `due pattern della stessa linea danno una linea sola`() {
        // Andata, ritorno e varianti sono pattern diversi della stessa
        // linea. In una riga di testo "22" va scritto una volta.
        val rete = Rete(
            listOf(intArrayOf(10, 14, 15, 16, 20), intArrayOf(10, 20)),
            intArrayOf(3, 3),
        )
        val utili = NavAlternatives.serving(rete, salita, discesa)
        assertEquals(1, utili.size, "una linea, non due")
        assertEquals(1, utili[0].stops, "vince il pattern con meno fermate in mezzo")
    }

    @Test
    fun `un anello che passa due volte dalla mia fermata si aggancia al passaggio che porta a destinazione`() {
        // Le due occorrenze sono lo stesso mezzo: salire al secondo giro
        // vuol dire aspettare un giro intero.
        val rete = Rete(listOf(intArrayOf(7, 10, 8, 10, 9, 20)), intArrayOf(5))
        val alt = assertNotNull(NavAlternatives.serving(rete, salita, discesa).firstOrNull())
        assertEquals(1, alt.boardPosition, "il primo passaggio, non il secondo")
        assertEquals(5, alt.alightPosition)
    }

    @Test
    fun `nessuna alternativa e' una lista vuota, e la lista vuota qui vuol dire davvero nessuna`() {
        val rete = Rete(listOf(intArrayOf(1, 2, 3)), intArrayOf(0))
        assertTrue(NavAlternatives.serving(rete, salita, discesa).isEmpty())
        assertTrue(NavAlternatives.serving(rete, intArrayOf(), discesa).isEmpty())
        assertTrue(NavAlternatives.serving(rete, salita, intArrayOf()).isEmpty())
    }

    @Test
    fun `le utili arrivano in ordine, dalla piu' diretta`() {
        val rete = Rete(
            listOf(
                intArrayOf(1, 10, 11, 12, 20, 30), // linea 0: tre fermate
                intArrayOf(10, 20), // linea 3: una fermata
                intArrayOf(7, 10, 8, 10, 9, 20), // linea 5: quattro fermate
                intArrayOf(5, 10, 11, 12, 13), // linea 1: non ci arriva
            ),
            intArrayOf(0, 3, 5, 1),
        )
        val utili = NavAlternatives.serving(rete, salita, discesa)
        assertEquals(listOf(3, 0, 5), utili.map { it.route })
    }

    @Test
    fun `il tetto taglia le utili e non le inventa`() {
        val patterns = (0 until 20).map { p ->
            intArrayOf(10) + IntArray(p) { i -> 100 + i } + intArrayOf(20)
        }
        val rete = Rete(patterns, IntArray(20) { it })
        assertEquals(5, NavAlternatives.serving(rete, salita, discesa, limit = 5).size)
        assertEquals(20, NavAlternatives.serving(rete, salita, discesa, limit = 99).size)
    }
}
