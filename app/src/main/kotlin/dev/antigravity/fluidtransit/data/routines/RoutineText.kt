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
            AdviceState.GOOD -> goodOnWidget(r)
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

    /**
     * Il consiglio valido, spezzato per una riga da widget.
     *
     * Il widget metteva l'intera frase nel titolo — "Esci alle 07:25 - linea
     * 23 alle 07:28 da SODERINI TORRINO SANTA ROSA", una sessantina di
     * caratteri — e a destra l'ora di uscita, la stessa che il titolo gia'
     * apriva. Il titolo e' una riga sola e non va a capo: su un widget largo
     * come un telefono ne restavano visibili trenta o poco piu', cioe'
     * "Esci alle 07:25 - linea 23 alle", e a sparire era la meta' utile — a
     * che ora passa il bus e da dove si sale — mentre l'ora di uscita si
     * leggeva due volte.
     *
     * Adesso il titolo e' il verbo e l'ora ("Esci alle 07:25"), a destra sta
     * il bus ("linea 23 alle 07:28"), e la fermata di salita apre il
     * sottotitolo, che il formato piccolo nasconde ma quello grande mostra.
     * Il testo salvato lo scrive `RoutineScheduler` e resta l'unica fonte;
     * se un giorno cambiasse forma e qui non tornasse, [parseAdvice] da' null
     * e si torna alla frase intera, tagliata ma completa.
     */
    private fun goodOnWidget(r: Routines.Routine): Riga {
        val pezzi = parseAdvice(r.lastAdviceText)
            ?: return Riga(
                title = r.lastAdviceText.ifEmpty { "Calcolo in corso" },
                subtitle = calcolato(r),
                trailing = Times.hhmm(r.lastAdviceEpoch),
            )
        val sub = if (pezzi.boardStop != null) {
            "da ${pezzi.boardStop} - ${calcolato(r)}"
        } else {
            calcolato(r)
        }
        return Riga(title = pezzi.leave, subtitle = sub, trailing = pezzi.ride)
    }

    /** Le parti di "Esci alle 07:25 - linea 23 alle 07:28 da SODERINI". */
    class AdviceParts(val leave: String, val ride: String, val boardStop: String?)

    private val ADVICE = Regex("""^(Esci alle \d{2}:\d{2}) — (.+)$""")
    private val ADVICE_RIDE = Regex("""^(linea .+? alle \d{2}:\d{2}) da (.+)$""")

    /**
     * Il testo di [Routines.Routine.lastAdviceText] a pezzi, o null se non ha
     * la forma che scrive lo scheduler ("Esci alle HH:MM" + trattino lungo +
     * "linea X alle HH:MM da FERMATA" oppure "a piedi").
     */
    fun parseAdvice(text: String): AdviceParts? {
        val m = ADVICE.matchEntire(text) ?: return null
        val resto = m.groupValues[2]
        val corsa = ADVICE_RIDE.matchEntire(resto)
        return if (corsa != null) {
            AdviceParts(m.groupValues[1], corsa.groupValues[1], corsa.groupValues[2])
        } else {
            AdviceParts(m.groupValues[1], resto, null)
        }
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

    /**
     * Cosa si perde a notifiche spente: la routine resta, l'avviso no.
     *
     * Il pannello scriveva "ti diro' io quando uscire" appena si toccava
     * "Crea la routine", qualunque cosa rispondesse Android alla richiesta
     * delle notifiche. Con le notifiche spente la promessa era falsa: la
     * routine risultava attiva in Oggi e ogni "Esci alle..." cadeva in
     * silenzio, e chi lo scopriva lo scopriva perdendo l'autobus.
     */
    const val ALERTS_OFF =
        "L'avviso per uscire di casa non ti arrivera': il consiglio lo trovi solo " +
            "nella scheda Oggi."

    /**
     * La frase sotto il tasto, a routine creata. [alertsOn] vuol dire che
     * Android lascia arrivare le notifiche dell'app adesso, non che una volta
     * lo facesse.
     */
    fun created(alertsOn: Boolean): String =
        if (alertsOn) {
            "Routine creata: la trovi nella scheda Oggi. Nei giorni scelti " +
                "ti diro' io quando uscire."
        } else {
            "Routine creata, ma le notifiche sono spente. $ALERTS_OFF"
        }
}
