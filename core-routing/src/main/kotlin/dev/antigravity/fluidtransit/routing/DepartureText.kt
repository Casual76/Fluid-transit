package dev.antigravity.fluidtransit.routing

/**
 * Le parole di una partenza, in un posto solo.
 *
 * Prima di questo file la stessa partenza si leggeva in quattro modi:
 *
 *   scheda fermata   i minuti in verde col pallino, e sotto "previsto 14:32"
 *   scheda Oggi      "14:35 · dal bus", oppure "previsto 14:32"
 *   Preferiti        "5 3 min · 12 8 min · previsti", tutto su una riga
 *   widget           i minuti nudi, la provenienza solo nel titolo
 *
 * Quattro grammatiche per lo stesso fatto, e nessun modo di accorgersene
 * guardando un file solo.
 *
 * ## Una parola, una cosa
 *
 * Nel vocabolario di prima "previsto" voleva dire due cose opposte:
 * "l'orario di tabella" in `previsto 14:32`, e "non abbiamo dati dal vivo" in
 * `orario previsto`. Sono esattamente i due significati fra cui la persona
 * deve distinguere, e li chiamavamo con la stessa parola. Adesso:
 *
 *   **da tabella**  l'orario pubblicato, quello che il bus dovrebbe fare
 *   **dal bus**     il numero lo dice il mezzo, attraverso il feed
 *   **stimato**     il numero lo mettiamo noi, perche' il feed non copre
 *                   questa fermata
 *
 * Il colore e il pallino pulsante restano, ma smettono di essere una
 * convenzione che vale solo dentro la mappa: [Tone] e' un enum, non un
 * colore, perche' questo modulo non puo' vedere ne' Compose ne' Glance — ma
 * la mappatura tono-colore adesso sta in un posto per toolkit invece che in
 * quattro punti a caso.
 */
object DepartureText {

    enum class Tone {
        /** Il numero viene dal mezzo: e' la cosa migliore che abbiamo. */
        LIVE,

        /** Il numero e' una nostra stima a partire da un dato piu' a monte. */
        ESTIMATED,

        /** Nessun dato dal vivo: vale l'orario pubblicato. */
        SCHEDULED,

        /** La corsa non ci sara'. */
        CANCELED,
    }

    class Phrase(
        /** Il numero che si legge per primo: minuti, oppure l'orologio. */
        val headline: String,
        /** Da dove viene quel numero, e l'orario di tabella se serve. */
        val support: String,
        val tone: Tone,
        /** Il pallino che pulsa: solo quando il feed segue QUESTA fermata. */
        val pulse: Boolean,
    )

    /**
     * Oltre un'ora i minuti non dicono niente a nessuno: si passa
     * all'orologio. E' la regola che la scheda fermata aveva gia' e le altre
     * tre no.
     */
    const val CLOCK_AFTER_MINUTES = 60

    fun phrase(row: NextDeparture, nowEpoch: Long): Phrase {
        if (row.canceled) {
            return Phrase(
                headline = "Cancellata",
                support = "La corsa delle ${Times.hhmm(row.scheduledEpoch)} non ci sara'",
                tone = Tone.CANCELED,
                pulse = false,
            )
        }

        val minutes = Times.minutesUntil(nowEpoch, row.effectiveEpoch)
        val headline = if (minutes < CLOCK_AFTER_MINUTES) {
            Times.minutesLabel(nowEpoch, row.effectiveEpoch)
        } else {
            Times.hhmm(row.effectiveEpoch)
        }

        val tone = when {
            row.fromFeed -> Tone.LIVE
            row.live -> Tone.ESTIMATED
            else -> Tone.SCHEDULED
        }

        val support = when {
            // Il ritardo e' zero ma il feed sta seguendo la corsa: "in orario"
            // e' un'informazione, ed e' diversa da "non sappiamo niente".
            row.live && row.delaySeconds == 0 ->
                "${source(row)} · in orario"
            row.live ->
                "${source(row)} · da tabella alle ${Times.hhmm(row.scheduledEpoch)}"
            // Il feed vede il mezzo ma non dice di quanto e' in ritardo. E'
            // meno di una previsione e piu' di niente, e in una riga sola ci
            // sta solo se si dice corto.
            row.monitored ->
                "orario da tabella · il mezzo e' in strada"
            else ->
                "orario da tabella"
        }

        return Phrase(
            headline = headline,
            support = support,
            tone = tone,
            pulse = row.certainty == Certainty.DECLARED,
        )
    }

    /**
     * La stessa partenza in una riga sola.
     *
     * Serve dove non c'e' spazio per due righe: il widget, l'elenco dei
     * preferiti, le risposte dell'assistente. Le parole sono le stesse di
     * [phrase], perche' il punto di tutto questo file e' che siano le stesse.
     */
    fun compact(row: NextDeparture, nowEpoch: Long): String {
        if (row.canceled) return "${row.line} cancellata"
        val when_ = if (Times.minutesUntil(nowEpoch, row.effectiveEpoch) < CLOCK_AFTER_MINUTES) {
            Times.minutesLabel(nowEpoch, row.effectiveEpoch)
        } else {
            Times.hhmm(row.effectiveEpoch)
        }
        return "${row.line} $when_"
    }

    /**
     * La provenienza di un tabellone intero, per un titolo.
     *
     * "Prossimi passaggi · dal bus" ha senso solo se almeno una riga viene dal
     * feed; altrimenti promette una cosa che non c'e'.
     */
    fun boardSource(board: DepartureBoard): String = when {
        board.rows.any { it.fromFeed } -> "dal bus"
        board.rows.any { it.live } -> "stimati"
        else -> "orari da tabella"
    }

    private fun source(row: NextDeparture): String = if (row.fromFeed) "dal bus" else "stimato"
}
