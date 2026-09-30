package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals

class DaysTextTest {

    @Test
    fun `i raggruppamenti noti hanno le loro parole`() {
        assertEquals("dal lunedi' al venerdi'", DaysText.label(setOf(1, 2, 3, 4, 5)))
        assertEquals("tutti i giorni", DaysText.label((1..7).toSet()))
        assertEquals("sabato e domenica", DaysText.label(setOf(7, 6)))
    }

    @Test
    fun `un elenco qualsiasi e' in ordine e con i nomi nostri`() {
        assertEquals("lun, mer, ven", DaysText.label(setOf(5, 1, 3)))
        assertEquals("lun, mar, mer, gio, ven, sab", DaysText.label(setOf(1, 2, 3, 4, 5, 6)))
    }

    @Test
    fun `nessun giorno valido e' mai, non un elenco vuoto`() {
        assertEquals("mai", DaysText.label(emptySet()))
        assertEquals("mai", DaysText.label(setOf(0, 8)))
    }

    @Test
    fun `la versione stretta salta i giorni che non sono giorni`() {
        assertEquals("Tutti i giorni", DaysText.short((1..7).toSet()))
        assertEquals("Lun–Ven", DaysText.short(setOf(1, 2, 3, 4, 5)))
        assertEquals("Sab Dom", DaysText.short(setOf(7, 6)))
        assertEquals("Lun Mer", DaysText.short(setOf(3, 0, 1, 8)))
        assertEquals("Mai", DaysText.short(setOf(0, 8)))
    }
}
