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

    /**
     * La riga del widget. [compact] e' il formato che non disegna il
     * sottotitolo e ha meno di 240 dp di larghezza: li' il posto e' del solo
     * titolo, e [goodOnWidget] lo sa.
     */
    fun widget(r: Routines.Routine, day: LocalDate, nowEpoch: Long, compact: Boolean): Riga =
        when (Routines.adviceState(r, day, nowEpoch)) {
            AdviceState.GOOD -> goodOnWidget(r, compact)
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
     * l'ora del bus in forma corta ("bus 07:28"), e la linea e la fermata di
     * salita aprono il sottotitolo. A destra NON sta la frase "linea 23 alle
     * 07:28": la colonna di destra ha larghezza fissa e il titolo prende il
     * resto, quindi una scritta di venti caratteri (o di trenta con "LAM
     * ROSSA") schiacciava il titolo fino a "E...", cioe' l'ora di uscita, che
     * e' l'unica cosa che serve sulla home. Nel formato stretto, dove il
     * sottotitolo non si disegna, a destra non sta niente: il titolo ha tutto
     * il posto, e chi vuole il bus tocca la riga.
     *
     * Il testo salvato lo scrive `RoutineScheduler` con [advice] e resta
     * l'unica fonte; se un giorno non tornasse, [parseAdvice] da' null e si
     * torna alla frase intera, tagliata ma completa.
     */
    private fun goodOnWidget(r: Routines.Routine, compact: Boolean): Riga {
        val pezzi = parseAdvice(r.lastAdviceText)
            ?: return Riga(
                title = r.lastAdviceText.ifEmpty { "Calcolo in corso" },
                subtitle = calcolato(r),
                trailing = Times.hhmm(r.lastAdviceEpoch),
            )
        val bus = if (pezzi.line != null) {
            "linea ${pezzi.line} da ${pezzi.boardStop}"
        } else {
            null
        }
        val sub = if (bus != null) "$bus - ${calcolato(r)}" else calcolato(r)
        val trailing = when {
            compact -> null
            pezzi.line != null -> "bus ${pezzi.busHm}"
            else -> WALK
        }
        return Riga(title = pezzi.leave, subtitle = sub, trailing = trailing)
    }

    /**
     * Le parti di "Esci alle 07:25 - linea 23 alle 07:28 da SODERINI".
     * [line], [busHm] e [boardStop] sono null, tutti e tre, per "a piedi".
     */
    class AdviceParts(
        val leaveHm: String,
        val line: String?,
        val busHm: String?,
        val boardStop: String?,
    ) {
        /** "Esci alle 07:25". */
        val leave: String get() = "$LEAVE$leaveHm"

        /** "linea 23 alle 07:28", oppure "a piedi". */
        val ride: String get() = if (line != null) "linea $line alle $busHm" else WALK
    }

    private const val LEAVE = "Esci alle "
    private const val SEP = " — "
    private const val WALK = "a piedi"

    /**
     * Il consiglio come lo legge chi lo riceve: e' l'UNICA costruzione della
     * frase, usata dallo scheduler per scriverla e da [parseAdvice] per
     * rileggerla. Prima lo scheduler la scriveva a mano e qui due regex la
     * rileggevano: bastava ritoccare una preposizione da una parte e il
     * widget, in silenzio, tornava alla frase intera tagliata a una riga.
     * Senza [line] e' un tragitto a piedi.
     */
    fun advice(leaveHm: String, line: String?, busHm: String?, boardStop: String?): String =
        if (line != null) {
            "$LEAVE$leaveHm${SEP}linea $line alle $busHm da $boardStop"
        } else {
            "$LEAVE$leaveHm$SEP$WALK"
        }

    private val ADVICE = Regex("""^${Regex.escape(LEAVE)}(\d{2}:\d{2})${Regex.escape(SEP)}(.+)$""")
    private val ADVICE_RIDE = Regex("""^linea (.+?) alle (\d{2}:\d{2}) da (.+)$""")

    /**
     * Il testo di [Routines.Routine.lastAdviceText] a pezzi, o null se non ha
     * la forma che scrive [advice].
     */
    fun parseAdvice(text: String): AdviceParts? {
        val m = ADVICE.matchEntire(text) ?: return null
        val resto = m.groupValues[2]
        if (resto == WALK) return AdviceParts(m.groupValues[1], null, null, null)
        val corsa = ADVICE_RIDE.matchEntire(resto) ?: return null
        return AdviceParts(
            m.groupValues[1],
            corsa.groupValues[1],
            corsa.groupValues[2],
            corsa.groupValues[3],
        )
    }

    /** La riga sotto la routine nella scheda Oggi; null se non c'e' niente da dire (o e' in pausa). */
    fun today(r: Routines.Routine, day: LocalDate, nowEpoch: Long): String? =
        if (!r.enabled) null else when (Routines.adviceState(r, day, nowEpoch)) {
            AdviceState.GOOD -> r.lastAdviceText.ifEmpty { null }
            AdviceState.NO_BUS ->
                "$NESSUN_BUS (calcolato alle ${Times.hhmm(r.lastComputeEpoch)})"
            AdviceState.PASSED -> "L'ora di uscire era le ${Times.hhmm(r.lastAdviceEpoch)}"
            else -> null
        }

    /**
     * Cosa l'assistente puo' riferire del consiglio; null se non ce n'e' uno
     * di oggi, o se la routine e' in pausa (la regola sta qui, non nella
     * schermata: "spenta - Esci alle 07:25" faceva ripetere a voce un avviso
     * che non arriva piu'). Prima riceveva il testo salvato qualunque fosse la sua eta', e
     * ripeteva "Esci alle 07:25" alle dieci del mattino come un consiglio.
     */
    fun assistant(r: Routines.Routine, day: LocalDate, nowEpoch: Long): String? =
        if (!r.enabled) null else when (Routines.adviceState(r, day, nowEpoch)) {
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
     * Dove stanno pausa ed eliminazione di una routine. Stanno nel menu della
     * tenuta premuta e nessuna schermata lo diceva: il gesto che fa cose senza
     * dirlo non lo scopre nessuno.
     */
    const val GESTIONE_TITOLO = "Tieni premuta una routine"
    const val GESTIONE_DETTAGLIO = "per metterla in pausa, riattivarla o eliminarla"

    /**
     * La notifica che ritratta un "Esci tra X min": al giro successivo il
     * bus non c'e' piu' (cancellato, o non piu' raggiungibile in tempo). Senza,
     * l'avviso vecchio restava in tendina e chi usciva di casa per un bus
     * sparito non lo sapeva.
     */
    fun busGoneTitle(routineName: String): String = "Cambio di programma — $routineName"

    /** [leaveEpoch] e' l'ora di uscita che l'avviso di prima aveva dato. */
    fun busGone(leaveEpoch: Long): String =
        "L'uscita delle ${Times.hhmm(leaveEpoch)} non c'e' piu': oggi nessun bus utile. " +
            "Tocca per le alternative."

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
