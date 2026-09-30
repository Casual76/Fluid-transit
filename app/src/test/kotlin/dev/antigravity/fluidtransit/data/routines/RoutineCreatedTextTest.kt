package dev.antigravity.fluidtransit.data.routines

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La routine appena creata non promette un avviso che non puo' arrivare.
 *
 * Il difetto: negando il permesso delle notifiche il pannello continuava a
 * dire "ti diro' io quando uscire". Nessun errore da cercare, una frase che
 * mentiva.
 */
class RoutineCreatedTextTest {

    @Test
    fun `con le notifiche accese si promette l'avviso`() {
        assertTrue(RoutineText.created(alertsOn = true).contains("ti diro' io quando uscire"))
    }

    @Test
    fun `con le notifiche spente non si promette niente e si dice dove trovare il consiglio`() {
        val spente = RoutineText.created(alertsOn = false)
        assertFalse(spente, spente.contains("ti diro'"))
        assertTrue(spente, spente.contains("notifiche sono spente"))
        assertTrue(spente, spente.contains(RoutineText.ALERTS_OFF))
        assertTrue(spente, spente.contains("scheda Oggi"))
    }

    @Test
    fun `quando il bus sparisce la notifica lo dice con l'ora di prima`() {
        // 07:45 di mercoledi' 30/09/2026 a Roma = 05:45 UTC.
        val leave = java.time.LocalDate.of(2026, 9, 30).atTime(7, 45)
            .atZone(dev.antigravity.fluidtransit.routing.Ftb.ROME).toEpochSecond()
        val testo = RoutineText.busGone(leave)
        assertTrue(testo, testo.contains("07:45"))
        assertTrue(testo, testo.contains(RoutineText.NESSUN_BUS))
        assertTrue(RoutineText.busGoneTitle("Al lavoro").contains("Al lavoro"))
    }

    @Test
    fun `Oggi dice dove stanno pausa ed eliminazione`() {
        assertTrue(RoutineText.GESTIONE_TITOLO.contains("premuta"))
        assertTrue(RoutineText.GESTIONE_DETTAGLIO.contains("pausa"))
        assertTrue(RoutineText.GESTIONE_DETTAGLIO.contains("eliminarla"))
    }

    @Test
    fun `le due frasi sono diverse`() {
        assertNotEquals(RoutineText.created(true), RoutineText.created(false))
    }
}
