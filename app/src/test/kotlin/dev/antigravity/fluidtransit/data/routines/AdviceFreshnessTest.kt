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

    private fun alle(
        ora: Int,
        id: Long,
        advice: Long = 0,
        text: String = "",
        days: Set<Int> = setOf(3),
    ) = Routines.Routine(
        id = id, label = "r$id", fromLat = 43.0, fromLon = 11.0,
        toLat = 43.1, toLon = 11.1, toName = "x", days = days,
        anchor = "depart", anchorMinutes = ora * 60, enabled = true,
        lastAdviceEpoch = advice, lastAdviceText = text,
    )

    private val mezzanotte = 1_790_719_200L // 30/09/2026 00:00 a Roma, mercoledi'
    private val OGGI = java.time.LocalDate.of(2026, 9, 30)

    private fun domenica(ora: Int, id: Long) = alle(ora, id, days = setOf(7))

    private fun romaAlle(giorno: java.time.LocalDate, ora: Int, minuti: Int) =
        giorno.atTime(ora, minuti).atZone(dev.antigravity.fluidtransit.routing.Ftb.ROME).toEpochSecond()

    @Test
    fun `passata la routine della mattina, conta quella della sera`() {
        // Il widget diceva "Per oggi e' andata" alle dieci del mattino,
        // con il ritorno delle 18 ancora da venire.
        val mattina = alle(8, id = 1)
        val sera = alle(18, id = 2)
        val adesso = mezzanotte + 10 * 3600
        assertEquals(2L, Routines.relevantToday(listOf(mattina, sera), OGGI, adesso)?.id)
    }

    @Test
    fun `un consiglio ancora buono passa davanti`() {
        // L'ora della mattina e' passata da tre minuti, ma il consiglio e'
        // nei cinque di grazia: deve restare lui, non saltare alla sera. Con
        // la mattina ancora da venire il test non distinguerebbe il primo
        // ramo dal secondo, che sceglierebbe la stessa routine.
        val mattina = alle(8, id = 1, advice = mezzanotte + 8 * 3600, text = "Esci alle 08:00")
        val sera = alle(18, id = 2)
        val adesso = mezzanotte + 8 * 3600 + 3 * 60
        assertEquals(1L, Routines.relevantToday(listOf(sera, mattina), OGGI, adesso)?.id)
    }

    @Test
    fun `il giorno da 25 ore, la routine delle 8 e' ancora da venire alle 7 e mezza`() {
        // 25/10/2026, ritorno all'ora solare. Contando i minuti da mezzanotte
        // l'ancora cadeva alle 07:00, e alle 07:30 il widget passava alla sera.
        val giorno = java.time.LocalDate.of(2026, 10, 25)
        val adesso = romaAlle(giorno, 7, 30)
        assertEquals(
            1L,
            Routines.relevantToday(listOf(domenica(8, 1), domenica(18, 2)), giorno, adesso)?.id,
        )
    }

    @Test
    fun `il giorno da 23 ore, alle 8 e mezza quella delle 8 e' passata`() {
        // 29/03/2026, ora legale: l'ancora cadeva alle 09:00.
        val giorno = java.time.LocalDate.of(2026, 3, 29)
        val adesso = romaAlle(giorno, 8, 30)
        assertEquals(
            2L,
            Routines.relevantToday(listOf(domenica(8, 1), domenica(18, 2)), giorno, adesso)?.id,
        )
    }

    @Test
    fun `un'ancora a mezzanotte passa al giorno dopo, senza eccezioni`() {
        // Un arrivo alle 23:57 arrotondato ai cinque minuti fa 1440.
        assertEquals(
            romaAlle(OGGI.plusDays(1), 0, 0),
            Routines.anchorEpoch(OGGI, 1440),
        )
    }

    @Test
    fun `passate tutte, resta l'ultima, e un giorno senza routine non ne ha`() {
        val mattina = alle(8, id = 1)
        val sera = alle(18, id = 2)
        val notte = mezzanotte + 23 * 3600
        assertEquals(2L, Routines.relevantToday(listOf(mattina, sera), OGGI, notte)?.id)
        assertNull(Routines.relevantToday(listOf(mattina, sera), OGGI.plusDays(1), notte))
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

    // ------------------------------------------------ a che punto e' oggi

    private fun conCalcolo(ora: Int, leave: Long, computed: Long, text: String = "Esci alle ...") =
        Routines.Routine(
            id = 9, label = "r", fromLat = 43.0, fromLon = 11.0, toLat = 43.1, toLon = 11.1,
            toName = "x", days = setOf(3), anchor = "arrive", anchorMinutes = ora * 60,
            enabled = true, lastAdviceEpoch = leave, lastAdviceText = text,
            lastComputeEpoch = computed,
        )

    @Test
    fun `nessun bus utile si vede, e non e' 'il consiglio arriva da solo'`() {
        // Mattina di sciopero: il calcolo delle 07:15 non trova bus. Lo zero
        // dell'ora di uscita voleva dire anche "non ancora calcolato", e il
        // widget prometteva un consiglio che non sarebbe mai arrivato.
        val r = conCalcolo(8, leave = 0, computed = mezzanotte + 7 * 3600 + 15 * 60, text = RoutineText.NESSUN_BUS)
        assertEquals(Routines.Companion.AdviceState.NO_BUS, Routines.adviceState(r, OGGI, mezzanotte + 7 * 3600 + 30 * 60))
        // Quello di ieri non vale per oggi.
        val ieri = conCalcolo(8, leave = 0, computed = mezzanotte - 24 * 3600 + 7 * 3600)
        assertEquals(Routines.Companion.AdviceState.NOT_YET, Routines.adviceState(ieri, OGGI, mezzanotte + 7 * 3600))
    }

    @Test
    fun `passata l'ora di uscire lo si dice, fino all'ora della routine`() {
        val esci = mezzanotte + 7 * 3600 + 40 * 60
        val r = conCalcolo(8, leave = esci, computed = esci - 45 * 60)
        assertEquals(Routines.Companion.AdviceState.GOOD, Routines.adviceState(r, OGGI, esci - 10 * 60))
        assertEquals(Routines.Companion.AdviceState.PASSED, Routines.adviceState(r, OGGI, esci + 10 * 60))
        assertEquals(Routines.Companion.AdviceState.DONE, Routines.adviceState(r, OGGI, mezzanotte + 8 * 3600 + 5 * 60))
    }

    @Test
    fun `un consiglio dice quando e' stato calcolato`() {
        val esci = mezzanotte + 7 * 3600 + 40 * 60
        val r = conCalcolo(8, leave = esci, computed = mezzanotte + 6 * 3600 + 55 * 60)
        val riga = RoutineText.widget(r, OGGI, esci - 10 * 60)
        assertTrue(riga.subtitle, riga.subtitle.contains("06:55"))
    }
}
