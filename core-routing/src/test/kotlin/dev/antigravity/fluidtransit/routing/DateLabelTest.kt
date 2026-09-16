package dev.antigravity.fluidtransit.routing

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Le date come le direbbe una persona.
 *
 * La schermata dello stato dei dati scriveva "Validi dal 2026-09-15 al
 * 2026-10-05", cioe' `LocalDate.toString()`. Si capisce, ma e' la data di un
 * file di log: nessuno la dice a voce cosi', e per sapere se gli orari
 * scadono presto bisogna contare sulle dita.
 */
class DateLabelTest {

    private val oggi = LocalDate.of(2026, 9, 16)

    @Test
    fun `i tre giorni intorno a oggi hanno un nome`() {
        assertEquals("oggi", Times.dateLabel(oggi, oggi))
        assertEquals("domani", Times.dateLabel(oggi.plusDays(1), oggi))
        assertEquals("ieri", Times.dateLabel(oggi.minusDays(1), oggi))
    }

    @Test
    fun `gli altri giorni dello stesso anno si dicono senza l'anno`() {
        assertEquals("5 ottobre", Times.dateLabel(LocalDate.of(2026, 10, 5), oggi))
        assertEquals("1 gennaio", Times.dateLabel(LocalDate.of(2026, 1, 1), oggi))
    }

    @Test
    fun `un altro anno si dice tutto`() {
        // "5 gennaio" letto a dicembre puo' essere quello appena passato o
        // quello che viene, ed e' la stessa ambiguita' che gli avvisi di
        // deviazione per lavori incontrano ogni anno.
        assertEquals("5 gennaio 2027", Times.dateLabel(LocalDate.of(2027, 1, 5), oggi))
    }

    @Test
    fun `dentro una frase i giorni vicini si dicono per data`() {
        // "Validi dal ieri al 5 ottobre" era scritto su un telefono: i nomi
        // dei giorni vicini funzionano da soli, non dopo una preposizione
        // articolata.
        assertEquals("16 settembre", Times.dateLabel(oggi, oggi, relative = false))
        assertEquals("15 settembre", Times.dateLabel(oggi.minusDays(1), oggi, relative = false))
    }

    @Test
    fun `i mesi sono in italiano`() {
        val mesi = (1..12).map { Times.dateLabel(LocalDate.of(2026, it, 10), oggi) }
        assertEquals(
            listOf(
                "10 gennaio", "10 febbraio", "10 marzo", "10 aprile", "10 maggio",
                "10 giugno", "10 luglio", "10 agosto", "10 settembre", "10 ottobre",
                "10 novembre", "10 dicembre",
            ),
            mesi,
        )
    }
}
