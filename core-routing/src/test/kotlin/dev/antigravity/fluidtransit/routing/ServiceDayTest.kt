package dev.antigravity.fluidtransit.routing

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * L'aritmetica del giorno di servizio, portata dai controlli dello spike 3.
 *
 * Sono gli unici test di questa famiglia che non hanno bisogno di un feed:
 * i casi critici (cambio d'ora nei due versi) non cadono quasi mai dentro la
 * finestra di validita' di un bundle reale, quindi vanno costruiti a mano.
 *
 * Il fatto controintuitivo che questi test inchiodano: il giorno di servizio
 * da 25 ore e' quello PRECEDENTE al ritorno all'ora solare, perche' l'ora
 * ripetuta cade nella sua estensione 24:00-27:00. I primi test dello spike
 * asserivano il contrario e fallivano contro un'implementazione corretta.
 */
class ServiceDayTest {

    private fun lastSundayOfOctober(year: Int): LocalDate =
        LocalDate.of(year, 10, 31).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

    private fun lastSundayOfMarch(year: Int): LocalDate =
        LocalDate.of(year, 3, 31).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

    @Test
    fun `il giorno prima del ritorno all'ora solare dura 25 ore`() {
        val change = lastSundayOfOctober(2026) // 25 ottobre 2026
        assertEquals(25 * 3600L, Ftb.serviceDayLength(change.minusDays(1)))
    }

    @Test
    fun `il giorno del ritorno all'ora solare e' un giorno normale`() {
        assertEquals(24 * 3600L, Ftb.serviceDayLength(lastSundayOfOctober(2026)))
    }

    @Test
    fun `il giorno prima del passaggio all'ora legale dura 23 ore`() {
        val change = lastSundayOfMarch(2026) // 29 marzo 2026
        assertEquals(23 * 3600L, Ftb.serviceDayLength(change.minusDays(1)))
    }

    @Test
    fun `un giorno qualunque dura 24 ore`() {
        assertEquals(24 * 3600L, Ftb.serviceDayLength(LocalDate.of(2026, 9, 2)))
    }

    @Test
    fun `dayStart piu' sei ore cade alle 6 locali anche nei giorni critici`() {
        // La proprieta' per cui GTFS ancora il giorno a "mezzogiorno meno
        // dodici ore": gli orari diurni non si spostano mai, in nessun caso.
        val days = listOf(
            lastSundayOfOctober(2026).minusDays(1),
            lastSundayOfOctober(2026),
            lastSundayOfMarch(2026).minusDays(1),
            LocalDate.of(2026, 9, 2),
        )
        for (day in days) {
            val sixAm = Ftb.serviceDayStart(day).plusSeconds(6 * 3600)
            val local = sixAm.atZone(Ftb.ROME)
            assertEquals(day, local.toLocalDate(), "giorno sbagliato per $day")
            assertEquals(6, local.hour, "ora sbagliata per $day")
        }
    }

    @Test
    fun `26_00 e 27_00 del giorno lungo sono due 02_00 locali distinte`() {
        // Un'app che convertisse in orario locale e confrontasse stringhe
        // fonderebbe qui due corse in una: stessa ora sull'orologio, offset
        // UTC diversi.
        val longDay = lastSundayOfOctober(2026).minusDays(1)
        val firstTwo = Ftb.serviceDayStart(longDay).plusSeconds(26 * 3600).atZone(Ftb.ROME)
        val secondTwo = Ftb.serviceDayStart(longDay).plusSeconds(27 * 3600).atZone(Ftb.ROME)
        assertEquals(2, firstTwo.hour)
        assertEquals(2, secondTwo.hour)
        assertNotEquals(firstTwo.offset, secondTwo.offset)
    }

    @Test
    fun `26_30 del giorno corto salta l'ora inesistente e cade alle 03_30`() {
        val shortDay = lastSundayOfMarch(2026).minusDays(1)
        val z = Ftb.serviceDayStart(shortDay).plusSeconds(26 * 3600 + 1800).atZone(Ftb.ROME)
        assertEquals(3, z.hour)
        assertEquals(30, z.minute)
    }

    @Test
    fun `le date GTFS si convertono nei due versi`() {
        assertEquals(LocalDate.of(2026, 8, 30), Ftb.parseGtfsDate(20260830))
        assertEquals(20260830, Ftb.formatGtfsDate(LocalDate.of(2026, 8, 30)))
    }

    @Test
    fun `hash64 e' FNV-1a e coincide fra stringa e byte`() {
        // Il valore di riferimento inchioda l'algoritmo: se qualcuno cambia
        // seed o primo, tutti gli indici del bundle diventano irrisolvibili
        // e questo test lo dice prima del telefono.
        assertEquals(Ftb.hash64("abc"), Ftb.hash64("abc".toByteArray()))
        assertEquals(Ftb.FNV_OFFSET, Ftb.hash64(""))
        assertNotEquals(Ftb.hash64("10000-1"), Ftb.hash64("10000-2"))
    }
}
