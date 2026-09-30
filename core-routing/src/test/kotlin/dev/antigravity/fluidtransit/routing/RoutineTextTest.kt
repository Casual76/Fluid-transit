package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * La routine appena creata non promette un avviso che non puo' arrivare.
 *
 * Il difetto: negando il permesso delle notifiche il pannello continuava a
 * dire "ti diro' io quando uscire". Nessun errore da cercare, una frase che
 * mentiva.
 */
class RoutineTextTest {

    @Test
    fun `con le notifiche accese si promette l'avviso`() {
        assertTrue(RoutineText.created(alertsOn = true).contains("ti diro' io quando uscire"))
    }

    @Test
    fun `con le notifiche spente non si promette niente e si dice dove trovare il consiglio`() {
        val spente = RoutineText.created(alertsOn = false)
        assertFalse(spente.contains("ti diro'"), spente)
        assertTrue(spente.contains("notifiche sono spente"), spente)
        assertTrue(spente.contains(RoutineText.ALERTS_OFF), spente)
        assertTrue(spente.contains("scheda Oggi"), spente)
    }

    @Test
    fun `le due frasi sono diverse`() {
        assertNotEquals(RoutineText.created(true), RoutineText.created(false))
    }
}
