package dev.antigravity.fluidtransit.data.routines

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La riga della routine sul widget.
 *
 * Il titolo e' una riga sola e non va a capo: con l'intera frase dentro, su
 * un widget largo come un telefono restavano visibili una trentina di
 * caratteri, "Esci alle 07:25 - linea 23 alle", e a sparire era proprio la
 * parte che serve — a che ora passa il bus e da dove si sale — mentre l'ora
 * di uscita a destra ripeteva quella con cui il titolo cominciava.
 */
class RoutineWidgetTextTest {

    private val mezzanotte = 1_790_719_200L // 30/09/2026 00:00 a Roma
    private val oggi = LocalDate.of(2026, 9, 30)
    private val testo = "Esci alle 07:25 — linea 23 alle 07:28 da SODERINI TORRINO SANTA ROSA"

    private fun routine(text: String, leave: Long) = Routines.Routine(
        id = 9, label = "r", fromLat = 43.0, fromLon = 11.0, toLat = 43.1, toLon = 11.1,
        toName = "x", days = setOf(3), anchor = "arrive", anchorMinutes = 8 * 60,
        enabled = true, lastAdviceEpoch = leave, lastAdviceText = text,
        lastComputeEpoch = mezzanotte + 6 * 3600 + 55 * 60,
    )

    @Test
    fun `il consiglio si spezza in uscita, bus e fermata`() {
        val p = RoutineText.parseAdvice(testo)!!
        assertEquals("Esci alle 07:25", p.leave)
        assertEquals("linea 23 alle 07:28", p.ride)
        assertEquals("SODERINI TORRINO SANTA ROSA", p.boardStop)
    }

    @Test
    fun `a piedi non ha ne' bus ne' fermata`() {
        val p = RoutineText.parseAdvice("Esci alle 07:25 — a piedi")!!
        assertEquals("Esci alle 07:25", p.leave)
        assertEquals("a piedi", p.ride)
        assertNull(p.boardStop)
    }

    @Test
    fun `un testo di un'altra forma non si inventa`() {
        assertNull(RoutineText.parseAdvice("Calcolo in corso"))
        assertNull(RoutineText.parseAdvice(""))
    }

    @Test
    fun `sul widget il titolo sta in una riga e non ripete l'ora`() {
        val leave = mezzanotte + 7 * 3600 + 25 * 60
        val riga = RoutineText.widget(routine(testo, leave), oggi, leave - 10 * 60)
        assertEquals("Esci alle 07:25", riga.title)
        assertEquals("linea 23 alle 07:28", riga.trailing)
        assertTrue(riga.title, riga.title.length < 20)
        // La fermata apre il sottotitolo, dove c'e' posto e il taglio non la tocca.
        assertTrue(riga.subtitle, riga.subtitle.startsWith("da SODERINI TORRINO SANTA ROSA"))
        assertTrue(riga.subtitle, riga.subtitle.contains("06:55"))
    }

    @Test
    fun `se il testo non torna si mostra intero, come prima`() {
        val leave = mezzanotte + 7 * 3600 + 25 * 60
        val riga = RoutineText.widget(routine("qualcosa di nuovo", leave), oggi, leave - 10 * 60)
        assertEquals("qualcosa di nuovo", riga.title)
        assertEquals("07:25", riga.trailing)
    }
}
