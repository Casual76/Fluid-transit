package dev.antigravity.fluidtransit.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La fascia oraria di `orari_fermata_giorno`.
 *
 * A "fra le 23 e le 2" lo strumento cercava per un minuto e rispondeva "non passa niente" su una
 * linea che di notte passa. Le parole del "quando" (`WhenText`) stanno in `:core-routing`, con i
 * loro test, che in CI girano: qui resta solo la lettura delle ore chieste dal modello.
 */
class DayWindowTest {

    // ---------------------------------------------- orari_fermata_giorno, la fascia

    @Test
    fun `le ore si leggono come le scrive il modello`() {
        assertEquals(450, DayWindow.minutesOf("07:30"))
        assertEquals(450, DayWindow.minutesOf("7.30"))
        assertEquals(1380, DayWindow.minutesOf("23"))
        assertEquals(1560, DayWindow.minutesOf("26:00"))
        assertNull(DayWindow.minutesOf("mattina"))
        assertNull(DayWindow.minutesOf(""))
        assertNull(DayWindow.minutesOf("30:00"))
        // 7:75 non e' un'ora: prima diventava le 08:15 in silenzio.
        assertNull(DayWindow.minutesOf("7:75"))
    }

    @Test
    fun `una fascia che attraversa la mezzanotte non si riduce a un minuto`() {
        val fascia = DayWindow.parse("23:00", "02:00", 1810)
        assertNotNull(fascia)
        assertEquals(23 * 60, fascia!!.fromMinutes)
        assertEquals(26 * 60, fascia.toMinutes)
        // Prima: (2 - 23) * 60 secondi, con un minimo di 60. Ora tre ore.
        assertEquals(3 * 3600, fascia.horizonSeconds)
    }

    @Test
    fun `una fascia normale resta quella chiesta`() {
        val fascia = DayWindow.parse("07:00", "09:00", 1810)!!
        assertEquals(7 * 60, fascia.fromMinutes)
        assertEquals(9 * 60, fascia.toMinutes)
    }

    @Test
    fun `dalle 20 alle 10 finisce la mattina dopo`() {
        val fascia = DayWindow.parse("20:00", "10:00", 1810)!!
        assertEquals(20 * 60, fascia.fromMinutes)
        assertEquals(34 * 60, fascia.toMinutes)
        assertEquals(14 * 3600, fascia.horizonSeconds)
    }

    @Test
    fun `senza fascia si guarda tutto il giorno di servizio, comprese le corse di notte`() {
        // Con le 6-22 di prima il primo bus delle 05:20 e gli ultimi della notte non comparivano.
        val giornata = DayWindow.parse(null, null, 1810)!!
        assertEquals(0, giornata.fromMinutes)
        assertEquals(1810, giornata.toMinutes)
    }

    @Test
    fun `senza ora di fine si va fino all'ultima corsa`() {
        val dalleVentitre = DayWindow.parse("23:00", null, 1810)!!
        assertEquals(23 * 60, dalleVentitre.fromMinutes)
        assertEquals(1810, dalleVentitre.toMinutes)
    }

    @Test
    fun `un bundle senza corse notturne guarda comunque le ventiquattr'ore`() {
        assertEquals(1440, DayWindow.serviceDayEndMinutes(23 * 3600))
        assertEquals(1810, DayWindow.serviceDayEndMinutes(30 * 3600 + 10 * 60))
        // Un valore assurdo nel bundle non allunga la ricerca all'infinito.
        assertEquals(36 * 60, DayWindow.serviceDayEndMinutes(100 * 3600))
    }

    @Test
    fun `mezzanotte alle mezzanotte e il giorno intero`() {
        val giorno = DayWindow.parse("00:00", "00:00", 1810)!!
        assertEquals(0, giorno.fromMinutes)
        assertEquals(1440, giorno.toMinutes)
    }

    @Test
    fun `una fascia che non si capisce si dice, non si sostituisce con un'altra`() {
        assertNull(DayWindow.parse("mattina", null, 1810))
        assertNull(DayWindow.parse(null, "boh", 1810))
        assertNull(DayWindow.parse("07:00", "99", 1810))
    }

    @Test
    fun `una fascia non e mai vuota ne negativa`() {
        for (dalle in listOf("00:00", "12:00", "23:59", "29:59")) {
            for (alle in listOf(null, "00:00", "06:00", "12:00", "23:59", "29:59")) {
                val fascia = DayWindow.parse(dalle, alle, 1810)!!
                assertTrue("$dalle-$alle", fascia.horizonSeconds > 0)
            }
        }
    }
}
