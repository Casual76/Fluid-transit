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
    fun `il consiglio si spezza in uscita, linea, ora del bus e fermata`() {
        val p = RoutineText.parseAdvice(testo)!!
        assertEquals("Esci alle 07:25", p.leave)
        assertEquals("23", p.line)
        assertEquals("07:28", p.busHm)
        assertEquals("linea 23 alle 07:28", p.ride)
        assertEquals("SODERINI TORRINO SANTA ROSA", p.boardStop)
    }

    @Test
    fun `a piedi non ha ne' bus ne' fermata`() {
        val p = RoutineText.parseAdvice("Esci alle 07:25 — a piedi")!!
        assertEquals("Esci alle 07:25", p.leave)
        assertEquals("a piedi", p.ride)
        assertNull(p.line)
        assertNull(p.boardStop)
    }

    @Test
    fun `un testo di un'altra forma non si inventa`() {
        assertNull(RoutineText.parseAdvice("Calcolo in corso"))
        assertNull(RoutineText.parseAdvice(""))
        // Il verbo giusto ma un resto che non e' ne' "a piedi" ne' una corsa.
        assertNull(RoutineText.parseAdvice("Esci alle 07:25 — qualcosa"))
    }

    @Test
    fun `quello che scrive lo scheduler si rilegge, andata e ritorno`() {
        // La frase ha una costruzione sola, RoutineText.advice: se qualcuno
        // ne ritocca la forma, il lettore la segue e questo test non cambia.
        for (linea in listOf("23", "LAM ROSSA", "301A", "Linea alle 9")) {
            val scritto = RoutineText.advice("07:25", linea, "07:28", "SODERINI TORRINO")
            val p = RoutineText.parseAdvice(scritto)!!
            assertEquals(scritto, linea, p.line)
            assertEquals(scritto, "07:25", p.leaveHm)
            assertEquals(scritto, "07:28", p.busHm)
            assertEquals(scritto, "SODERINI TORRINO", p.boardStop)
        }
        val piedi = RoutineText.parseAdvice(RoutineText.advice("07:25", null, null, null))!!
        assertNull(piedi.line)
        assertEquals("Esci alle 07:25", piedi.leave)
    }

    @Test
    fun `sul widget largo il titolo e' l'uscita e a destra sta l'ora del bus`() {
        val leave = mezzanotte + 7 * 3600 + 25 * 60
        val riga = RoutineText.widget(routine(testo, leave), oggi, leave - 10 * 60, compact = false)
        assertEquals("Esci alle 07:25", riga.title)
        assertEquals("bus 07:28", riga.trailing)
        assertTrue(riga.title, riga.title.length < 20)
        // A destra una scritta corta, qualunque sia la linea: la colonna e'
        // a larghezza fissa e quello che prende lo toglie al titolo.
        assertTrue(riga.trailing!!, riga.trailing!!.length <= 10)
        // Linea e fermata aprono il sottotitolo, dove il taglio non le tocca.
        assertTrue(riga.subtitle, riga.subtitle.startsWith("linea 23 da SODERINI TORRINO SANTA ROSA"))
        assertTrue(riga.subtitle, riga.subtitle.contains("06:55"))
    }

    @Test
    fun `sul widget stretto il titolo ha tutto il posto e a destra non c'e' niente`() {
        // Sotto i 240 dp il sottotitolo non si disegna e il titolo prende
        // quello che la colonna di destra non chiede: con "linea LAM ROSSA
        // alle 07:28" a destra restavano venti dp, cioe' "E...".
        val leave = mezzanotte + 7 * 3600 + 25 * 60
        val lunga = RoutineText.advice("07:25", "LAM ROSSA", "07:28", "SODERINI")
        val riga = RoutineText.widget(routine(lunga, leave), oggi, leave - 10 * 60, compact = true)
        assertEquals("Esci alle 07:25", riga.title)
        assertNull(riga.trailing)
    }

    @Test
    fun `se il testo non torna si mostra intero, come prima`() {
        val leave = mezzanotte + 7 * 3600 + 25 * 60
        for (compact in listOf(false, true)) {
            val riga = RoutineText.widget(
                routine("qualcosa di nuovo", leave), oggi, leave - 10 * 60, compact,
            )
            assertEquals("qualcosa di nuovo", riga.title)
            assertEquals("07:25", riga.trailing)
        }
    }
}
