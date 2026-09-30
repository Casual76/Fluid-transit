package dev.antigravity.fluidtransit.data.routines

import dev.antigravity.fluidtransit.routing.Ftb
import java.time.Instant
import java.time.LocalDate

/**
 * Quando suona la prossima sveglia di una routine, e per fare cosa.
 *
 * Stava dentro lo scheduler, mescolata alle chiamate ad AlarmManager, e
 * aveva tre difetti che nessun test poteva vedere:
 *
 * - dopo l'ultima rifinitura, due minuti prima di uscire, la routine si
 *   riarmava "adesso piu' cinque secondi" perche' l'ora della routine non era
 *   ancora passata: un giro di calcolo e una notifica che suonava ogni cinque
 *   secondi fino all'ora d'arrivo, e ogni apertura dell'app dentro quella
 *   finestra la faceva risuonare;
 * - una routine "arriva entro le 9" si calcolava 45 minuti prima delle 9,
 *   cioe' dopo l'ora di uscita per qualunque viaggio piu' lungo di cosi';
 * - una routine poco dopo mezzanotte si calcolava la sera prima, e quel
 *   giro guardava l'ora della routine del giorno sbagliato.
 *
 * Qui la regola e' una funzione pura. Le fasi sono tre: [PLAN] calcola in
 * silenzio l'ora di uscita di una routine "arriva entro"; [COMPUTE] avvisa,
 * col suono; [REFINE] aggiorna in silenzio mentre l'uscita si avvicina.
 */
object RoutineTiming {

    const val PLAN = "plan"
    const val COMPUTE = "compute"
    const val REFINE = "refine"

    /** L'avviso arriva tre quarti d'ora prima di uscire. */
    const val COMPUTE_LEAD_SECONDS = 45 * 60L

    /**
     * Quanto prima dell'arrivo si calcola, in silenzio, l'ora di uscita di
     * una routine "arriva entro". Tre ore coprono i viaggi lunghi della
     * regione — Firenze-Grosseto in bus sono due ore e mezza — e restano
     * dentro la finestra in cui il motore cerca (tre ore prima dell'arrivo).
     */
    const val PLAN_LEAD_SECONDS = 3 * 3600L

    /** Dentro la finestra si parte subito, ma non nello stesso istante. */
    const val NOW_DELAY_SECONDS = 5L

    /** La prossima sveglia: fase, istante, e di quale giorno e' la routine. */
    data class Alarm(val phase: String, val atEpoch: Long, val day: LocalDate)

    /**
     * @param doneDay il giorno appena chiuso (ultima rifinitura fatta, o
     *   nessun bus utile): non si riprende, si passa al prossimo.
     */
    fun next(r: Routines.Routine, nowEpoch: Long, doneDay: LocalDate? = null): Alarm? {
        if (r.days.isEmpty()) return null
        val today = Instant.ofEpochSecond(nowEpoch).atZone(Ftb.ROME).toLocalDate()
        // Il giorno e' quello della routine, non quello della sveglia: una
        // routine delle 00:20 di domani ha la finestra che comincia stasera.
        for (offset in 0..8) {
            val day = today.plusDays(offset.toLong())
            if (day == doneDay) continue
            if (day.dayOfWeek.value !in r.days) continue
            val anchor = Routines.anchorEpoch(day, r.anchorMinutes)
            if (anchor <= nowEpoch) continue
            val arrive = r.anchor == "arrive"
            val firstAt = anchor - if (arrive) PLAN_LEAD_SECONDS else COMPUTE_LEAD_SECONDS
            val firstPhase = if (arrive) PLAN else COMPUTE
            if (firstAt > nowEpoch) return Alarm(firstPhase, firstAt, day)

            // Dentro la finestra: dipende da cosa sappiamo gia' di oggi.
            val leave = r.lastAdviceEpoch.takeIf { adviceBelongsTo(it, anchor) }
            if (leave == null) return Alarm(firstPhase, nowEpoch + NOW_DELAY_SECONDS, day)
            // Un "arriva entro" gia' pianificato aspetta il suo avviso.
            if (arrive && leave - COMPUTE_LEAD_SECONDS > nowEpoch) {
                return Alarm(COMPUTE, leave - COMPUTE_LEAD_SECONDS, day)
            }
            // L'avviso c'e' gia' stato: si aggiorna, senza suonare di nuovo.
            return Alarm(REFINE, nowEpoch + NOW_DELAY_SECONDS, day)
        }
        return null
    }

    /**
     * La sveglia di una routine, come la ricorda lo scheduler.
     *
     * [atEpoch] e' quando DOVEVA suonare; [firedEpoch] quando e' suonata
     * davvero (0 = non ancora), scritto dal ricevitore prima di avviare il
     * giro. Il margine del "giro in corso" si misura da li': con la sveglia
     * in ritardo (Doze, ripiego sull'inesatta su Android 12 senza permesso)
     * l'ora programmata e' gia' lontana quando il processo nasce, e contarla
     * dall'ora programmata faceva riarmare un giro a meta' — due "Esci tra
     * 45 min". [day] e' il giorno della routine, per poter riarmare la
     * STESSA sveglia senza ricalcolarla.
     */
    data class Armed(
        val phase: String,
        val atEpoch: Long,
        val day: LocalDate? = null,
        val firedEpoch: Long = 0L,
    ) {
        fun format(): String = "$phase|$atEpoch|${day?.toEpochDay() ?: ""}|$firedEpoch"

        companion object {
            fun parse(s: String?): Armed? {
                val parts = s?.split('|') ?: return null
                if (parts.size != 2 && parts.size != 4) return null
                val at = parts[1].toLongOrNull() ?: return null
                if (parts.size == 2) return Armed(parts[0], at)
                val day = parts[2].takeIf { it.isNotEmpty() }
                    ?.let { d -> d.toLongOrNull() ?: return null }
                    ?.let { d -> runCatching { LocalDate.ofEpochDay(d) }.getOrNull() ?: return null }
                val fired = parts[3].toLongOrNull() ?: return null
                return Armed(parts[0], at, day, fired)
            }
        }
    }

    /**
     * Quanto si considera "ancora in corso" il giro di una sveglia appena
     * suonata: otto secondi di attesa del bundle, tre giri di rete e RAPTOR,
     * con un margine. Oltre, se nessuno ha riarmato, il giro e' morto.
     */
    const val IN_FLIGHT_GRACE_SECONDS = 3 * 60L

    /** Cosa fare di una routine quando il processo nasce. */
    enum class StartAction {
        /** C'e' un giro in corso: riarmare lo farebbe suonare due volte. */
        LEAVE,

        /** La sveglia ricordata si rimette com'era, senza ricalcolare. */
        REARM_SAME,

        /** Si decide da capo con [next]. */
        REARM_NEXT,
    }

    /**
     * All'avvio del processo: che fare di una routine?
     *
     * - niente ricordato: si decide da capo;
     * - sveglia suonata da poco (misurata dallo scatto reale, non dall'ora
     *   programmata): e' quella che ha fatto nascere il processo e il suo
     *   giro e' ancora in corso. Riarmare qui faceva partire una seconda
     *   sveglia a cinque secondi, e "Esci tra 45 min" suonava due volte;
     * - sveglia suonata da un pezzo e mai sostituita: il giro e' morto, si
     *   decide da capo;
     * - sveglia mai suonata (futura, o in ritardo): si rimette la STESSA.
     *   Non ci si fida che il sistema la abbia ancora: il PendingIntent
     *   sopravvive anche quando le sveglie vengono cancellate (un permesso
     *   delle sveglie esatte revocato su Android 12), e rimettere la stessa e'
     *   idempotente perche' il PendingIntent e' lo stesso.
     */
    fun atStart(armed: Armed?, nowEpoch: Long): StartAction {
        if (armed == null) return StartAction.REARM_NEXT
        if (armed.firedEpoch > 0) {
            return if (nowEpoch - armed.firedEpoch > IN_FLIGHT_GRACE_SECONDS) {
                StartAction.REARM_NEXT
            } else {
                StartAction.LEAVE
            }
        }
        // Un ricordo senza giorno non si puo' rimettere com'era.
        if (armed.day == null) return StartAction.REARM_NEXT
        return StartAction.REARM_SAME
    }

    /** Quando rimettere la sveglia di [atStart] `REARM_SAME`: mai nel passato. */
    fun rearmAt(armed: Armed, nowEpoch: Long): Long =
        maxOf(armed.atEpoch, nowEpoch + NOW_DELAY_SECONDS)

    /**
     * Il consiglio salvato e' di questa occorrenza della routine?
     * L'ora di uscita cade nelle ore prima dell'ancora (e poco dopo, per una
     * "parti alle" che trova il primo bus qualche minuto piu' in la').
     */
    fun adviceBelongsTo(leaveEpoch: Long, anchorEpoch: Long): Boolean =
        leaveEpoch > 0 &&
            leaveEpoch >= anchorEpoch - PLAN_LEAD_SECONDS - 3600 &&
            leaveEpoch <= anchorEpoch + 2 * 3600
}
