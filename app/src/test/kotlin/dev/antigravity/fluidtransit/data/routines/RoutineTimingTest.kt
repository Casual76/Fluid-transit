package dev.antigravity.fluidtransit.data.routines

import dev.antigravity.fluidtransit.routing.Ftb
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Quando suona la prossima sveglia di una routine.
 *
 * Il caso che ha fatto nascere questa classe: dopo l'ultima rifinitura, due
 * minuti prima di uscire, la routine si riarmava fra cinque secondi — e la
 * notifica suonava ogni cinque secondi fino all'ora della routine.
 */
class RoutineTimingTest {

    // 30/09/2026 e' un mercoledi' (3); 01/10 giovedi' (4).
    private val mercoledi = LocalDate.of(2026, 9, 30)

    private fun alle(giorno: LocalDate, ora: Int, minuti: Int = 0) =
        giorno.atTime(ora, minuti).atZone(Ftb.ROME).toEpochSecond()

    private fun routine(
        anchor: String,
        ora: Int,
        minuti: Int = 0,
        days: Set<Int> = setOf(1, 2, 3, 4, 5),
        leave: Long = 0,
    ) = Routines.Routine(
        id = 7, label = "Al lavoro", fromLat = 43.0, fromLon = 11.0,
        toLat = 43.1, toLon = 11.1, toName = "Ufficio", days = days,
        anchor = anchor, anchorMinutes = ora * 60 + minuti, enabled = true,
        lastAdviceEpoch = leave, lastAdviceText = if (leave > 0) "Esci alle ..." else "",
    )

    @Test
    fun `una partenza si avvisa tre quarti d'ora prima`() {
        val a = RoutineTiming.next(routine("depart", 8), alle(mercoledi, 6))
        assertNotNull(a)
        assertEquals(RoutineTiming.COMPUTE, a!!.phase)
        assertEquals(alle(mercoledi, 7, 15), a.atEpoch)
        assertEquals(mercoledi, a.day)
    }

    @Test
    fun `un arrivo si pianifica in silenzio con tre ore di anticipo`() {
        // Calcolato 45 minuti prima delle 9, un viaggio di un'ora avvisava
        // quando si doveva gia' essere per strada.
        val a = RoutineTiming.next(routine("arrive", 9), alle(mercoledi, 5))!!
        assertEquals(RoutineTiming.PLAN, a.phase)
        assertEquals(alle(mercoledi, 6), a.atEpoch)
    }

    @Test
    fun `un arrivo gia' pianificato avvisa tre quarti d'ora prima di uscire`() {
        // Pianificato: uscita alle 07:50 per arrivare alle 9. Riaprendo l'app
        // alle 06:30, l'avviso resta alle 07:05, non adesso.
        val r = routine("arrive", 9, leave = alle(mercoledi, 7, 50))
        val a = RoutineTiming.next(r, alle(mercoledi, 6, 30))!!
        assertEquals(RoutineTiming.COMPUTE, a.phase)
        assertEquals(alle(mercoledi, 7, 5), a.atEpoch)
    }

    @Test
    fun `chiusa la giornata si passa al giorno dopo, non a fra cinque secondi`() {
        val r = routine("depart", 8, leave = alle(mercoledi, 7, 58))
        val a = RoutineTiming.next(r, alle(mercoledi, 7, 56), doneDay = mercoledi)!!
        assertEquals(mercoledi.plusDays(1), a.day)
        assertEquals(alle(mercoledi.plusDays(1), 7, 15), a.atEpoch)
    }

    @Test
    fun `aprire l'app nella finestra non fa risuonare l'avviso`() {
        // Consiglio gia' dato per oggi: si aggiorna in silenzio.
        val r = routine("depart", 8, leave = alle(mercoledi, 7, 58))
        val a = RoutineTiming.next(r, alle(mercoledi, 7, 30))!!
        assertEquals(RoutineTiming.REFINE, a.phase)
        // Senza consiglio di oggi (quello salvato e' di ieri) si avvisa.
        val ieri = routine("depart", 8, leave = alle(mercoledi.minusDays(1), 7, 58))
        assertEquals(RoutineTiming.COMPUTE, RoutineTiming.next(ieri, alle(mercoledi, 7, 30))!!.phase)
    }

    @Test
    fun `una routine poco dopo mezzanotte e' del giorno dopo`() {
        // Giovedi' alle 00:20: la finestra comincia mercoledi' alle 23:35.
        val r = routine("depart", 0, 20, days = setOf(4))
        val sera = RoutineTiming.next(r, alle(mercoledi, 10))!!
        assertEquals(mercoledi.plusDays(1), sera.day)
        assertEquals(alle(mercoledi, 23, 35), sera.atEpoch)
        val dentro = RoutineTiming.next(r, alle(mercoledi, 23, 40))!!
        assertEquals(mercoledi.plusDays(1), dentro.day)
        assertEquals(RoutineTiming.COMPUTE, dentro.phase)
    }

    @Test
    fun `passata l'ora di oggi si guarda il prossimo giorno buono`() {
        // Mercoledi' alle 10 la routine delle 8 e' andata; giovedi' non e'
        // fra i giorni, venerdi' si'.
        val r = routine("depart", 8, days = setOf(3, 5))
        val a = RoutineTiming.next(r, alle(mercoledi, 10))!!
        assertEquals(mercoledi.plusDays(2), a.day)
    }

    @Test
    fun `toccare la routine apre il suo viaggio, non quello di adesso`() {
        // Prima delle 9 di oggi: il viaggio di oggi, "arriva entro le 9".
        val r = routine("arrive", 9, days = setOf(3))
        val prima = Routines.journeyIntent(r, alle(mercoledi, 7))
        assertEquals("arrive", prima.timeMode)
        assertEquals(alle(mercoledi, 9), prima.timeEpoch)
        // Passate le 9: quello del prossimo mercoledi'.
        val dopo = Routines.journeyIntent(r, alle(mercoledi, 10))
        assertEquals(alle(mercoledi.plusDays(7), 9), dopo.timeEpoch)
        // Una routine senza giorni si apre su "adesso".
        assertEquals("now", Routines.journeyIntent(routine("depart", 8, days = emptySet()), alle(mercoledi, 7)).timeMode)
    }

    @Test
    fun `toccare la rifinitura dopo l'ancora apre il bus di oggi, non quello di domani`() {
        // "Parti alle 08:00", il bus ha 4 minuti di ritardo e l'avviso
        // "esci fra 2 min" cade alle 08:03: passata l'ancora, la prossima
        // occorrenza e' domani, ma il viaggio e' quello di oggi, da adesso.
        val r = routine("depart", 8, leave = alle(mercoledi, 8, 4))
        val adesso = alle(mercoledi, 8, 3)
        val senzaGiorno = Routines.journeyIntent(r, adesso)
        assertEquals(alle(mercoledi.plusDays(1), 8), senzaGiorno.timeEpoch)
        val conGiorno = Routines.journeyIntent(r, adesso, mercoledi)
        assertEquals("depart", conGiorno.timeMode)
        assertEquals(adesso, conGiorno.timeEpoch)
        // Prima dell'ancora il giorno non cambia niente: e' l'ancora di oggi.
        assertEquals(
            alle(mercoledi, 8),
            Routines.journeyIntent(r, alle(mercoledi, 7), mercoledi).timeEpoch,
        )
        // Un avviso vecchio toccato dopo la finestra: si torna alla regola
        // "prossima occorrenza", non a un viaggio nel passato.
        assertEquals(
            alle(mercoledi.plusDays(1), 8),
            Routines.journeyIntent(r, alle(mercoledi, 12), mercoledi).timeEpoch,
        )
    }

    @Test
    fun `un arriva entro passato non apre un viaggio nel passato`() {
        val r = routine("arrive", 9, days = setOf(3))
        val t = Routines.tapEpoch(r, mercoledi, alle(mercoledi, 9, 10))
        assertEquals(alle(mercoledi.plusDays(7), 9), t)
    }

    // --- all'avvio del processo ----------------------------------------

    @Test
    fun `se la sveglia appena suonata ha fatto nascere il processo non si riarma`() {
        // "Parti alle 08:00": la sveglia delle 07:15 avvia il processo, e
        // onCreate gira mentre il giro vero e' ancora in corso. Riarmare
        // dava una seconda sveglia a cinque secondi e due "Esci tra 45 min".
        val armata = RoutineTiming.Armed(RoutineTiming.COMPUTE, alle(mercoledi, 7, 15))
        val adesso = alle(mercoledi, 7, 15) + 2
        assertEquals(false, RoutineTiming.shouldRearmAtStart(armata, adesso, pendingExists = true))
        assertEquals(false, RoutineTiming.shouldRearmAtStart(armata, adesso, pendingExists = false))
    }

    @Test
    fun `una sveglia suonata da un pezzo e mai sostituita si riarma`() {
        val armata = RoutineTiming.Armed(RoutineTiming.COMPUTE, alle(mercoledi, 7, 15))
        val adesso = alle(mercoledi, 7, 15) + RoutineTiming.IN_FLIGHT_GRACE_SECONDS + 1
        assertEquals(true, RoutineTiming.shouldRearmAtStart(armata, adesso, pendingExists = true))
    }

    @Test
    fun `una sveglia nel futuro resta se il sistema la ha, si riarma se e' sparita`() {
        val armata = RoutineTiming.Armed(RoutineTiming.COMPUTE, alle(mercoledi, 7, 15))
        val adesso = alle(mercoledi, 6)
        assertEquals(false, RoutineTiming.shouldRearmAtStart(armata, adesso, pendingExists = true))
        // Dopo "termina forzatamente": il ricordo dice futuro, il sistema no.
        assertEquals(true, RoutineTiming.shouldRearmAtStart(armata, adesso, pendingExists = false))
    }

    @Test
    fun `senza niente di ricordato si arma`() {
        assertEquals(true, RoutineTiming.shouldRearmAtStart(null, alle(mercoledi, 6), false))
    }

    @Test
    fun `il ricordo della sveglia va e torna come testo`() {
        val a = RoutineTiming.Armed(RoutineTiming.REFINE, 1_790_000_000L)
        assertEquals(a, RoutineTiming.Armed.parse(a.format()))
        assertEquals(null, RoutineTiming.Armed.parse(null))
        assertEquals(null, RoutineTiming.Armed.parse("rotto"))
        assertEquals(null, RoutineTiming.Armed.parse("compute|domani"))
    }
}
