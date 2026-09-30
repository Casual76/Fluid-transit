package dev.antigravity.fluidtransit.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Il ricalcolo dei viaggi quando arrivano i ritardi: al massimo uno al
 * minuto, e solo con l'elenco aperto.
 */
class JourneyLiveRefreshTest {

    @Test
    fun `col dettaglio aperto o senza pannello non si ricalcola`() {
        assertNull(JourneyLiveRefresh.waitMs(listOpen = false, lastCalcAtMs = 50_000, nowMs = 100_000))
    }

    @Test
    fun `senza un calcolo partito non c'e' niente da rifare`() {
        assertNull(JourneyLiveRefresh.waitMs(listOpen = true, lastCalcAtMs = 0, nowMs = 100_000))
    }

    @Test
    fun `un calcolo partito senza tempo reale si rifa' in pochi secondi, non in un minuto`() {
        assertEquals(
            2_000L,
            JourneyLiveRefresh.waitMs(true, lastCalcAtMs = 100_000, nowMs = 103_000, hadLive = false),
        )
    }

    @Test
    fun `un calcolo finito da piu' di un minuto si rifa' subito`() {
        assertEquals(0L, JourneyLiveRefresh.waitMs(true, lastCalcAtMs = 1_000, nowMs = 62_000))
    }

    @Test
    fun `un calcolo fresco fa aspettare il resto del minuto`() {
        assertEquals(45_000L, JourneyLiveRefresh.waitMs(true, lastCalcAtMs = 100_000, nowMs = 115_000))
    }
}
