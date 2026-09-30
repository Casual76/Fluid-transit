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

    /** La sveglia armata per una routine, come la ricorda lo scheduler. */
    data class Armed(val phase: String, val atEpoch: Long) {
        fun format(): String = "$phase|$atEpoch"

        companion object {
            fun parse(s: String?): Armed? {
                val parts = s?.split('|') ?: return null
                if (parts.size != 2) return null
                val at = parts[1].toLongOrNull() ?: return null
                return Armed(parts[0], at)
            }
        }
    }

    /**
     * Quanto si considera "ancora in corso" il giro di una sveglia appena
     * suonata: otto secondi di attesa del bundle, tre giri di rete e RAPTOR,
     * con un margine. Oltre, se nessuno ha riarmato, il giro e' morto.
     */
    const val IN_FLIGHT_GRACE_SECONDS = 3 * 60L

    /**
     * All'avvio del processo: serve riarmare la routine?
     *
     * @param armed cio' che lo scheduler ricorda di aver armato (null: niente).
     * @param pendingExists vero se il sistema ha ancora il PendingIntent di
     *   quella sveglia. Conta solo per una sveglia nel futuro: dopo un
     *   "termina forzatamente" sparisce, mentre per una gia' suonata resta
     *   (e' il PendingIntent a sopravvivere alla sveglia, non il contrario).
     *
     * - niente ricordato: si arma;
     * - sveglia nel futuro: si lascia stare, se il sistema la ha davvero;
     * - sveglia appena suonata: e' quella che ha fatto nascere il processo e
     *   il suo giro e' ancora in corso. Riarmare qui faceva partire una
     *   seconda sveglia a cinque secondi, e "Esci tra 45 min" suonava due
     *   volte di seguito;
     * - sveglia suonata da un pezzo e mai sostituita: il giro e' morto, si
     *   riarma.
     */
    fun shouldRearmAtStart(armed: Armed?, nowEpoch: Long, pendingExists: Boolean): Boolean {
        if (armed == null) return true
        if (armed.atEpoch > nowEpoch) return !pendingExists
        return nowEpoch - armed.atEpoch > IN_FLIGHT_GRACE_SECONDS
    }

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
