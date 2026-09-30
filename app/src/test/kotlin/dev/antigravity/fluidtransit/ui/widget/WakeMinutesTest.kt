package dev.antigravity.fluidtransit.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Quanto dorme il widget fra un disegno e l'altro.
 *
 * La regola e' "sveglia quando serve": cinque minuti se un bus sta per
 * passare, altrimenti fino a mezz'ora prima del prossimo. Quello che la
 * regola riceve e' il passaggio piu' vicino del tabellone che il widget
 * disegna, non quello di una sola banchina: con la banchina stellata a 55
 * minuti e l'altro verso a 3, il widget dormiva 25 minuti con due righe che
 * dicevano "3 min" e "13 min".
 */
class WakeMinutesTest {

    @Test
    fun `senza passaggi si dorme il massimo`() {
        assertEquals(30L, WidgetRefresher.wakeMinutes(null))
    }

    @Test
    fun `con un bus entro la mezz'ora si sta svegli`() {
        assertEquals(5L, WidgetRefresher.wakeMinutes(3 * 60L))
        assertEquals(5L, WidgetRefresher.wakeMinutes(30 * 60L))
        // Un bus in ritardo gia' oltre l'ora prevista e' comunque "in arrivo".
        assertEquals(5L, WidgetRefresher.wakeMinutes(-90L))
    }

    @Test
    fun `lontano si dorme fino a mezz'ora prima, ma mai oltre il massimo`() {
        assertEquals(10L, WidgetRefresher.wakeMinutes(40 * 60L))
        assertEquals(25L, WidgetRefresher.wakeMinutes(55 * 60L))
        assertEquals(30L, WidgetRefresher.wakeMinutes(3 * 3600L))
    }
}
