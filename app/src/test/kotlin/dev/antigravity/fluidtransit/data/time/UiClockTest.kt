package dev.antigravity.fluidtransit.data.time

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il battito unico, misurato invece che promesso.
 *
 * Il difetto che questo file difende e' quello che l'utente aveva descritto
 * come "si comporta in modo diverso ogni volta": la stessa fermata scriveva
 * "3 min" nella mappa e "4 min" nella scheda Oggi, perche' i due battiti
 * erano partiti a sette secondi di distanza e giravano il minuto in momenti
 * diversi. Il rimedio e' l'allineamento al muro, ed e' una proprieta'
 * aritmetica: si puo' interrogare senza aspettare davvero dieci secondi,
 * perche' il tempo qui e' finto e la sorgente dell'ora e' un parametro.
 */
class UiClockTest {

    /**
     * Un `nowMillis` che legge l'orologio virtuale del test, partendo da `base`.
     *
     * Il momento in cui si chiama fa da zero: dentro uno stesso test il tempo
     * finto e' gia' avanzato, e senza questo scarto il secondo iscritto
     * partirebbe dove ha finito il primo invece che da `base`.
     */
    private fun kotlinx.coroutines.test.TestScope.orologio(base: Long): () -> Long {
        val inizio = currentTime
        return { base + (currentTime - inizio) }
    }

    @Test
    fun `il primo battito arriva subito, senza aspettare l'intervallo`() = runTest {
        // Chi apre una schermata vede i minuti adesso, non fra dieci secondi:
        // era il motivo per cui ogni schermata si era scritta il suo ciclo.
        val t = UiClock.ticks(orologio(7_000)).take(1).toList()
        assertEquals(listOf(7L), t)
        assertEquals("non deve essere passato tempo", 0L, currentTime)
    }

    @Test
    fun `i battiti cadono su multipli dell'intervallo`() = runTest {
        val t = UiClock.ticks(orologio(7_000)).take(4).toList()
        assertEquals(listOf(7L, 10L, 20L, 30L), t)
    }

    @Test
    fun `due iscritti in momenti diversi battono insieme dal secondo battito`() = runTest {
        // E' la frase intera del difetto: due schermate aperte a distanza di
        // secondi devono girare il minuto nello stesso istante.
        val presto = UiClock.ticks(orologio(3_000)).take(4).toList()
        val tardi = UiClock.ticks(orologio(9_000)).take(4).toList()
        assertEquals(listOf(3L, 10L, 20L, 30L), presto)
        assertEquals(listOf(9L, 10L, 20L, 30L), tardi)
        assertEquals("dal secondo battito in poi devono coincidere", presto.drop(1), tardi.drop(1))
    }

    @Test
    fun `chi si iscrive esattamente sul muro non batte due volte nello stesso istante`() = runTest {
        // Il caso limite dell'aritmetica: con `ms % periodo == 0` l'attesa
        // calcolata e' l'intervallo intero, non zero. Scritta male, quella
        // riga emetterebbe due volte lo stesso secondo e farebbe girare a
        // vuoto il ciclo.
        val t = UiClock.ticks(orologio(20_000)).take(3).toList()
        assertEquals(listOf(20L, 30L, 40L), t)
    }

    @Test
    fun `l'intervallo e' piu' corto di un minuto`() {
        // Il numero mostrato e' arrotondato al minuto, quindi cambia a meta'
        // minuto: un battito al minuto lo lascerebbe indietro fino a trenta
        // secondi, ed e' esattamente il ritardo che si vede in faccia.
        assertTrue("il battito deve stare sotto il mezzo minuto", UiClock.TICK_SECONDS <= 30)
        assertEquals("e deve dividere il minuto", 0L, 60L % UiClock.TICK_SECONDS)
    }

    @Test
    fun `il tempo che passa fra un battito e l'altro e' l'intervallo`() = runTest {
        val partenza = currentTime
        UiClock.ticks(orologio(0)).take(3).toList()
        val trascorso = currentTime - partenza
        assertEquals(
            "due intervalli per tre battiti allineati partendo dal muro",
            2 * UiClock.TICK_SECONDS * 1000,
            trascorso,
        )
    }
}
