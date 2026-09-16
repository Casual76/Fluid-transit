package dev.antigravity.fluidtransit.data.routines

import org.junit.Assert.assertFalse
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
