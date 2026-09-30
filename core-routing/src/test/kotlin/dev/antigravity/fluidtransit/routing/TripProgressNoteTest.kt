package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Cosa si dice di una fermata lungo il percorso, oltre al suo orario.
 *
 * Nella scheda linea il tratto gia' percorso si distingueva solo perche' si
 * spegneva, e il ritardo solo dal colore dell'orario: con un lettore di
 * schermo restava una lista di orari uguali, senza sapere quali fermate il
 * bus se le fosse lasciate alle spalle ne' che avesse un quarto d'ora di
 * ritardo.
 */
class TripProgressNoteTest {

    private val tabella = 1_700_000_000L

    private fun frase(delay: Int?, certainty: Certainty?, age: Int = 0) =
        DepartureText.alongTrip(
            scheduledEpoch = tabella,
            delaySeconds = delay,
            certainty = certainty,
            nowEpoch = tabella - 600,
            ageSeconds = age,
        )

    @Test
    fun `una fermata servita lo dice, e non dice altro`() {
        // L'orario che le resta e' quello di tabella e il ritardo di adesso
        // non la riguarda piu': parlarne sarebbe inventare.
        val nota = TripProgress.spokenNote(true, frase(null, null))
        assertEquals(TripProgress.SERVED_NOTE, nota)
        assertEquals("il bus e' gia' passato", nota)
    }

    @Test
    fun `servita vince anche su un ritardo che il feed dichiara`() {
        assertEquals(
            TripProgress.SERVED_NOTE,
            TripProgress.spokenNote(true, frase(20 * 60, Certainty.DECLARED)),
        )
    }

    @Test
    fun `servita si dice anche senza un orario da leggere`() {
        assertEquals(TripProgress.SERVED_NOTE, TripProgress.spokenNote(true, null))
    }

    @Test
    fun `un ritardo che conta si dice con le parole della scheda fermata`() {
        val nota = TripProgress.spokenNote(false, frase(7 * 60, Certainty.DECLARED))
        assertEquals("dal bus, +7 min di ritardo", nota)
    }

    @Test
    fun `un ritardo grande si dice allo stesso modo`() {
        val nota = TripProgress.spokenNote(false, frase(20 * 60, Certainty.PROPAGATED))
        assertEquals("dal bus, +20 min di ritardo", nota)
    }

    @Test
    fun `una stima nostra dice che e' una stima`() {
        val nota = TripProgress.spokenNote(false, frase(8 * 60, Certainty.ESTIMATED))
        assertEquals("stimato, +8 min di ritardo", nota)
    }

    @Test
    fun `un numero vecchio dice di quando e'`() {
        val nota = TripProgress.spokenNote(false, frase(7 * 60, Certainty.DECLARED, age = 15 * 60))
        assertEquals("dal bus, +7 min di ritardo, visto 15 min fa", nota)
    }

    @Test
    fun `il punto di mezzo non arriva al sintetizzatore`() {
        val nota = TripProgress.spokenNote(false, frase(7 * 60, Certainty.DECLARED))
        assertEquals(false, nota?.contains('·'))
    }

    @Test
    fun `in orario e' silenzio`() {
        // "In orario" ripetuto su trenta righe di fila e' rumore.
        assertNull(TripProgress.spokenNote(false, frase(0, Certainty.DECLARED)))
        assertNull(TripProgress.spokenNote(false, frase(4 * 60, Certainty.DECLARED)))
    }

    @Test
    fun `senza dati dal vivo e' silenzio`() {
        assertNull(TripProgress.spokenNote(false, frase(null, null)))
    }

    @Test
    fun `senza un orario non c'e' niente da dire`() {
        assertNull(TripProgress.spokenNote(false, null))
    }
}
