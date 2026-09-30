package dev.antigravity.fluidtransit.routing

import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Le parole del "quando".
 *
 * Alle 23:00 `quando_uscire` rispondeva "parti alle 06:40 (461 min)": la partenza di domattina
 * scritta come un numero da dividere per sessanta, senza il giorno, e letta ad alta voce come
 * "quattrocentosessantuno minuti". E `orari_fermata_giorno` scriveva "01:13" per un bus che passa
 * di notte, che sembra stamattina.
 */
class WhenTextTest {

    private val zone = Ftb.ROME

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int, s: Int = 0): Long =
        ZonedDateTime.of(y, m, d, h, min, s, 0, zone).toEpochSecond()

    // ------------------------------------------------------- quando_uscire

    @Test
    fun `la partenza di domattina dice domani e le ore, non 461 min`() {
        val adesso = at(2026, 9, 30, 23, 0)
        val partenza = at(2026, 10, 1, 6, 40)
        val testo = WhenText.atClockWithWait(partenza, adesso, zone)
        assertEquals("domani alle 06:40 (fra 7 h 40 min)", testo)
        assertFalse("461" in testo)
    }

    @Test
    fun `sotto l'ora restano i minuti`() {
        val adesso = at(2026, 9, 30, 10, 0)
        assertEquals("alle 10:20 (fra 20 min)", WhenText.atClockWithWait(at(2026, 9, 30, 10, 20), adesso, zone))
    }

    @Test
    fun `sopra l'ora si dicono le ore anche nello stesso giorno`() {
        val adesso = at(2026, 9, 30, 10, 0)
        assertEquals("alle 11:10 (fra 1 h 10 min)", WhenText.atClockWithWait(at(2026, 9, 30, 11, 10), adesso, zone))
        assertEquals("alle 13:00 (fra 3 h)", WhenText.atClockWithWait(at(2026, 9, 30, 13, 0), adesso, zone))
    }

    @Test
    fun `una partenza imminente dice ora, e non 0 min`() {
        val adesso = at(2026, 9, 30, 10, 0)
        assertEquals("alle 10:00 (ora)", WhenText.atClockWithWait(at(2026, 9, 30, 10, 0, 10), adesso, zone))
        // Il primo bus utile puo' essere partito da pochi minuti: non si dice "-3 min".
        assertEquals("alle 09:57 (ora)", WhenText.atClockWithWait(at(2026, 9, 30, 9, 57), adesso, zone))
    }

    @Test
    fun `l'ora dice il giorno solo quando non e' oggi`() {
        val adesso = at(2026, 9, 30, 23, 0)
        assertEquals("23:30", WhenText.clock(at(2026, 9, 30, 23, 30), adesso, zone))
        assertEquals("domani 07:30", WhenText.clock(at(2026, 10, 1, 7, 30), adesso, zone))
        assertEquals("domani alle 06:10", WhenText.atClock(at(2026, 10, 1, 6, 10), adesso, zone))
        assertEquals("alle 23:30", WhenText.atClock(at(2026, 9, 30, 23, 30), adesso, zone))
        assertNull(WhenText.dayWord(at(2026, 9, 30, 23, 59), adesso, zone))
        assertEquals("5 ottobre", WhenText.dayWord(at(2026, 10, 5, 8, 0), adesso, zone))
    }

    // -------------------------------------------- orari_fermata_giorno, le ore

    @Test
    fun `dopo la mezzanotte la tabella dice di notte`() {
        val giorno = LocalDate.of(2026, 10, 1)
        assertEquals("05:20", WhenText.clockOnDay(at(2026, 10, 1, 5, 20), giorno, zone))
        assertEquals("23:59", WhenText.clockOnDay(at(2026, 10, 1, 23, 59), giorno, zone))
        assertEquals("00:30 di notte", WhenText.clockOnDay(at(2026, 10, 2, 0, 30), giorno, zone))
        assertEquals("01:13 di notte", WhenText.clockOnDay(at(2026, 10, 2, 1, 13), giorno, zone))
    }

    @Test
    fun `l'orologio della tabella e' quello della pensilina anche nel giorno dell'ora legale`() {
        // Il 29 marzo 2026 le 02:00 diventano le 03:00: i secondi dal "mezzogiorno meno dodici
        // ore" sfasano di un'ora, l'orologio a muro no.
        val giorno = LocalDate.of(2026, 3, 29)
        assertEquals("03:30", WhenText.clockOnDay(at(2026, 3, 29, 3, 30), giorno, zone))
        assertEquals("00:30 di notte", WhenText.clockOnDay(at(2026, 3, 30, 0, 30), giorno, zone))
    }

    @Test
    fun `una fascia che arriva al mattino dopo non e' di notte`() {
        assertEquals("02:00 di notte", WhenText.serviceClock(26 * 3600))
        assertEquals("06:10 di notte", WhenText.serviceClock(30 * 3600 + 10 * 60))
        // "Dalle 20 alle 10" finisce alle 34: scritta "di notte" sarebbe una frase falsa.
        assertEquals("10:00 del giorno dopo", WhenText.serviceClock(34 * 3600))
        assertEquals("07:05", WhenText.serviceClock(7 * 3600 + 5 * 60))
    }
}
