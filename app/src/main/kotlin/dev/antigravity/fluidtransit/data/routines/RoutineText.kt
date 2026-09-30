package dev.antigravity.fluidtransit.data.routines

import dev.antigravity.fluidtransit.data.routines.Routines.Companion.AdviceState
import dev.antigravity.fluidtransit.routing.Times
import java.time.LocalDate

/**
 * Come si dice a che punto e' una routine, oggi.
 *
 * Il widget, la scheda Oggi e l'assistente se lo dicevano ognuno a modo suo,
 * e tutti e tre sbagliavano lo stesso caso: il calcolo gia' fatto che non
 * trova bus. Lo scheduler scriveva "Oggi nessun bus utile" con l'ora di
 * uscita a zero, e siccome zero voleva dire anche "non ancora calcolato", il
 * widget diceva "Il consiglio arriva da solo" la mattina di uno sciopero, e
 * Oggi non diceva niente. E un consiglio valido si presentava "coi ritardi
 * live" anche se era stato calcolato tre quarti d'ora prima.
 */
object RoutineText {

    /** Una riga: titolo, sottotitolo, e l'orario a destra se c'e'. */
    class Riga(val title: String, val subtitle: String, val trailing: String? = null)

    fun widget(r: Routines.Routine, day: LocalDate, nowEpoch: Long): Riga =
        when (Routines.adviceState(r, day, nowEpoch)) {
            AdviceState.GOOD -> Riga(
                title = r.lastAdviceText.ifEmpty { "Calcolo in corso" },
                subtitle = calcolato(r),
                trailing = Times.hhmm(r.lastAdviceEpoch),
            )
            AdviceState.NO_BUS -> Riga(
                title = NESSUN_BUS,
                subtitle = "calcolato alle ${Times.hhmm(r.lastComputeEpoch)}: " +
                    "tocca per le alternative",
            )
            AdviceState.PASSED -> Riga(
                title = "L'ora di uscire era le ${Times.hhmm(r.lastAdviceEpoch)}",
                subtitle = "tocca per il prossimo viaggio",
            )
            AdviceState.NOT_YET -> Riga(
                title = "Il consiglio arriva da solo",
                subtitle = "tre quarti d'ora prima di uscire",
            )
            AdviceState.DONE -> Riga(
                title = "Per oggi e' andata",
                subtitle = "il prossimo consiglio al prossimo giorno della routine",
            )
        }

    /** La riga sotto la routine nella scheda Oggi; null se non c'e' niente da dire. */
    fun today(r: Routines.Routine, day: LocalDate, nowEpoch: Long): String? =
        when (Routines.adviceState(r, day, nowEpoch)) {
            AdviceState.GOOD -> r.lastAdviceText.ifEmpty { null }
            AdviceState.NO_BUS ->
                "$NESSUN_BUS (calcolato alle ${Times.hhmm(r.lastComputeEpoch)})"
            AdviceState.PASSED -> "L'ora di uscire era le ${Times.hhmm(r.lastAdviceEpoch)}"
            else -> null
        }

    /**
     * Cosa l'assistente puo' riferire del consiglio; null se non ce n'e' uno
     * di oggi. Prima riceveva il testo salvato qualunque fosse la sua eta', e
     * ripeteva "Esci alle 07:25" alle dieci del mattino come un consiglio.
     */
    fun assistant(r: Routines.Routine, day: LocalDate, nowEpoch: Long): String? =
        when (Routines.adviceState(r, day, nowEpoch)) {
            AdviceState.GOOD ->
                r.lastAdviceText.ifEmpty { null }?.let { "$it (${calcolato(r)})" }
            AdviceState.NO_BUS ->
                "oggi nessun bus utile, calcolato alle ${Times.hhmm(r.lastComputeEpoch)}"
            AdviceState.PASSED ->
                "l'ora di uscire di oggi era le ${Times.hhmm(r.lastAdviceEpoch)}, gia' passata"
            else -> null
        }

    private fun calcolato(r: Routines.Routine): String =
        if (r.lastComputeEpoch > 0) {
            "calcolato alle ${Times.hhmm(r.lastComputeEpoch)} coi ritardi di allora"
        } else {
            "calcolato coi ritardi live"
        }

    const val NESSUN_BUS = "Oggi nessun bus utile"
}
