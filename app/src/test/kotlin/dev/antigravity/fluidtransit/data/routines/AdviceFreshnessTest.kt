package dev.antigravity.fluidtransit.data.routines

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per quanto vale il consiglio di una routine.
 *
 * `lastAdviceEpoch` e' l'ora a cui USCIRE, non l'ora in cui il consiglio e'
 * stato calcolato. Chi lo leggeva come "e' di oggi" lo teneva in vetrina
 * fino a mezzanotte: alle 09:47 il widget sulla home e la scheda Oggi
 * dicevano tutti e due "Esci alle 07:25 — linea 23 alle 07:28", sotto una
 * riga che prometteva "coi ritardi live di adesso".
 */
class AdviceFreshnessTest {

    private fun routine(adviceEpoch: Long, text: String = "Esci alle 07:25") = Routines.Routine(
        id = 1, label = "Al lavoro", fromLat = 43.0, fromLon = 11.0,
        toLat = 43.1, toLon = 11.1, toName = "Ufficio", days = setOf(1, 2, 3, 4, 5),
        anchor = "arrive", anchorMinutes = 9 * 60, enabled = true,
        lastAdviceEpoch = adviceEpoch, lastAdviceText = text,
    )

    private val uscita = 1_789_500_000L

    private fun alle(ora: Int, id: Long, advice: Long = 0, text: String = "") = Routines.Routine(
        id = id, label = "r$id", fromLat = 43.0, fromLon = 11.0,
        toLat = 43.1, toLon = 11.1, toName = "x", days = setOf(3),
        anchor = "depart", anchorMinutes = ora * 60, enabled = true,
        lastAdviceEpoch = advice, lastAdviceText = text,
    )

    private val mezzanotte = 1_790_719_200L // 30/09/2026 00:00 a Roma, mercoledi'

    @Test
    fun `passata la routine della mattina, conta quella della sera`() {
        // Il widget diceva "Per oggi e' andata" alle dieci del mattino,
        // con il ritorno delle 18 ancora da venire.
        val mattina = alle(8, id = 1)
        val sera = alle(18, id = 2)
        val adesso = mezzanotte + 10 * 3600
        assertEquals(2L, Routines.relevantToday(listOf(mattina, sera), 3, mezzanotte, adesso)?.id)
    }

    @Test
    fun `un consiglio ancora buono passa davanti`() {
        val sera = alle(18, id = 2, advice = mezzanotte + 17 * 3600 + 30 * 60, text = "Esci alle 17:30")
        val tardi = alle(21, id = 3)
        val adesso = mezzanotte + 17 * 3600
        assertEquals(2L, Routines.relevantToday(listOf(tardi, sera), 3, mezzanotte, adesso)?.id)
    }

    @Test
    fun `passate tutte, resta l'ultima, e un giorno senza routine non ne ha`() {
        val mattina = alle(8, id = 1)
        val sera = alle(18, id = 2)
        val notte = mezzanotte + 23 * 3600
        assertEquals(2L, Routines.relevantToday(listOf(mattina, sera), 3, mezzanotte, notte)?.id)
        assertNull(Routines.relevantToday(listOf(mattina, sera), 4, mezzanotte, notte))
    }

    @Test
    fun `prima dell'ora di uscire il consiglio vale`() {
        assertTrue(Routines.adviceStillGood(routine(uscita), uscita - 40 * 60))
        assertTrue(Routines.adviceStillGood(routine(uscita), uscita))
    }

    @Test
    fun `cinque minuti di grazia, per chi guarda appena uscito`() {
        assertTrue(Routines.adviceStillGood(routine(uscita), uscita + 4 * 60))
        assertFalse(Routines.adviceStillGood(routine(uscita), uscita + 6 * 60))
    }

    @Test
    fun `due ore dopo non e' piu' un consiglio, e' un ricordo`() {
        // Il caso visto sulla home: consiglio delle 07:25, telefono guardato
        // alle 09:47.
        assertFalse(Routines.adviceStillGood(routine(uscita), uscita + 2 * 3600))
    }

    @Test
    fun `senza consiglio non c'e' niente da mostrare`() {
        assertFalse(Routines.adviceStillGood(routine(0), uscita))
        assertFalse(Routines.adviceStillGood(routine(uscita, text = ""), uscita))
    }
}
