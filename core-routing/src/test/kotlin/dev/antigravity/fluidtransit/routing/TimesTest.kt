package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * La regola unica del tempo.
 *
 * Prima ce n'erano quattro nello stesso prodotto, e sullo stesso schermo la
 * capsula del ritardo e il conteggio sotto non tornavano fra loro. Questi
 * test esistono per non tornarci.
 */
class TimesTest {

    @Test
    fun `si arrotonda al minuto piu' vicino, in tutti e due i versi`() {
        assertEquals(0, Times.toMinutes(29))
        assertEquals(1, Times.toMinutes(30))
        assertEquals(1, Times.toMinutes(89))
        assertEquals(2, Times.toMinutes(90))
        assertEquals(-1, Times.toMinutes(-30))
        assertEquals(-2, Times.toMinutes(-90))
    }

    @Test
    fun `i minuti che mancano non vanno sottozero`() {
        // Il widget mostrava "-5 min" per una corsa il cui orario era
        // passato: mancava un coerceAtLeast(0).
        assertEquals(0, Times.minutesUntil(1_000, 700))
        assertEquals(5, Times.minutesUntil(1_000, 1_300))
    }

    @Test
    fun `sotto la mezzo minuto si dice ora`() {
        assertEquals("ora", Times.minutesLabel(1_000, 1_020))
        assertEquals("1 min", Times.minutesLabel(1_000, 1_045))
        assertEquals("3 min", Times.minutesLabel(1_000, 1_180))
    }

    @Test
    fun `orario previsto e in orario sono due cose diverse`() {
        // Prima si guardava `delay != 0`, quindi una corsa monitorata e
        // puntuale era indistinguibile da una senza dati live.
        assertEquals("orario previsto", Times.delayLabel(null))
        assertEquals("in orario", Times.delayLabel(0))
        assertEquals("in orario", Times.delayLabel(20))
        assertEquals("+3 min di ritardo", Times.delayLabel(170))
        assertEquals("2 min in anticipo", Times.delayLabel(-100))
    }

    @Test
    fun `l'orologio ha sempre due cifre, e l'ora e' quella di Roma`() {
        val sette05 = java.time.ZonedDateTime
            .of(2026, 9, 3, 7, 5, 0, 0, Ftb.ROME)
            .toEpochSecond()
        assertEquals("07:05", Times.hhmm(sette05))
        val zeroSei = java.time.ZonedDateTime
            .of(2026, 1, 12, 6, 9, 0, 0, Ftb.ROME)
            .toEpochSecond()
        assertEquals("06:09", Times.hhmm(zeroSei))
    }

    @Test
    fun `le corse oltre la mezzanotte lo dicono`() {
        assertEquals("07:05", Times.serviceTime(7 * 3600 + 5 * 60))
        // La scheda linea stampava le 25:30 come "1:30", senza dire che quel
        // bus passa domani mattina.
        assertEquals("01:30 (domani)", Times.serviceTime(25 * 3600 + 30 * 60))
    }
}
