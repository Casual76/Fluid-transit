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

    fun phrase(row: NextDeparture, nowEpoch: Long): Phrase = phraseOf(
        scheduledEpoch = row.scheduledEpoch,
        delaySeconds = row.delaySeconds,
        certainty = row.certainty,
        canceled = row.canceled,
        skipped = row.skipped,
        monitored = row.monitored,
        nowEpoch = nowEpoch,
    )

    /**
     * Una fermata lungo il percorso di una corsa.
     *
     * Le schede linea e corsa mostrano gli stessi orari della scheda fermata,
     * viste dall'altro lato: non "cosa passa di qui" ma "dove passa questo".
     * Erano rimaste fuori dal vocabolario comune e si vedeva — scrivevano
     * "previsto 14:32" dove la scheda fermata scrive "da tabella alle 14:32",
     * e coloravano di verde anche le stime, che e' proprio la distinzione che
     * questo file esiste per tenere.
     *
     * Stessa funzione sotto, quindi stesse parole per forza.
     */
    fun alongTrip(
        scheduledEpoch: Long,
        delaySeconds: Int?,
        certainty: Certainty?,
        canceled: Boolean = false,
        skipped: Boolean = false,
        monitored: Boolean = false,
        nowEpoch: Long,
    ): Phrase = phraseOf(
        scheduledEpoch, delaySeconds, certainty, canceled, skipped, monitored, nowEpoch,
    )

    private fun phraseOf(
        scheduledEpoch: Long,
        delaySeconds: Int?,
        certainty: Certainty?,
        canceled: Boolean,
        skipped: Boolean,
        monitored: Boolean,
        nowEpoch: Long,
    ): Phrase {
        if (canceled) {
            return Phrase(
                headline = "Cancellata",
                support = "La corsa delle ${Times.hhmm(scheduledEpoch)} non ci sara'",
                tone = Tone.CANCELED,
                pulse = false,
            )
        }
        if (skipped) {
            // Il feed dice che questa fermata, oggi, questa corsa la salta.
            // E' un'informazione che l'app aveva e non diceva: il tabellone
            // della fermata la toglie dall'elenco, e chi guarda il percorso
            // della corsa deve poterla vedere barrata.
            return Phrase(
                headline = "Non ferma",
                support = "Il bus oggi salta questa fermata",
                tone = Tone.CANCELED,
                pulse = false,
            )
        }

        val effective = scheduledEpoch + (delaySeconds ?: 0)
        val minutes = Times.minutesUntil(nowEpoch, effective)
        val headline = if (minutes < CLOCK_AFTER_MINUTES) {
            Times.minutesLabel(nowEpoch, effective)
        } else {
            Times.hhmm(effective)
        }

        val live = delaySeconds != null
        val fromFeed = certainty == Certainty.DECLARED || certainty == Certainty.PROPAGATED
        val tone = when {
            fromFeed -> Tone.LIVE
            live -> Tone.ESTIMATED
            else -> Tone.SCHEDULED
        }
        val source = if (fromFeed) "dal bus" else "stimato"

        val support = when {
            // Il ritardo e' zero ma il feed sta seguendo la corsa: "in orario"
            // e' un'informazione, ed e' diversa da "non sappiamo niente".
            live && delaySeconds == 0 -> "$source · in orario"
            live -> "$source · da tabella alle ${Times.hhmm(scheduledEpoch)}"
            // Il feed vede il mezzo ma non dice di quanto e' in ritardo. E'
            // meno di una previsione e piu' di niente, e in una riga sola ci
            // sta solo se si dice corto.
            monitored -> "orario da tabella · il mezzo e' in strada"
            else -> "orario da tabella"
        }

        return Phrase(
            headline = headline,
            support = support,
            tone = tone,
            pulse = certainty == Certainty.DECLARED,
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

    /** La spiegazione di UN orario: cosa ha detto il feed, e cosa ci mettiamo noi. */
    class Why(val title: String, val lines: List<String>)

    /**
     * Perche' questo numero.
     *
     * La riga dice "dal bus" o "stimato" in due parole, che e' quanto ci sta
     * in un tabellone. Ma "stimato" e' una promessa su come lavora l'app, e
     * una promessa che non si puo' aprire e' una cosa da prendere sulla
     * fiducia — cioe' esattamente quello che qui manca.
     *
     * Le frasi non sono generiche: dicono cosa ha dichiarato il feed, per
     * quale fermata, e cosa ci ha aggiunto l'app.
     */
    fun why(row: NextDeparture, nowEpoch: Long): Why {
        val tabella = "Orario di tabella: ${Times.hhmm(row.scheduledEpoch)}"
        val mostrato = "Orario mostrato: ${Times.hhmm(row.effectiveEpoch)}"
        val scarto = Times.delayLabel(row.delaySeconds)

        if (row.canceled) {
            return Why(
                title = "Corsa cancellata",
                lines = listOf(
                    "Il feed dichiara che questa corsa oggi non verra' fatta.",
                    tabella,
                    "La mostriamo lo stesso, dichiarata: sapere che il bus non " +
                        "viene e' piu' utile che aspettarlo senza saperlo.",
                ),
            )
        }
        if (row.skipped) {
            return Why(
                title = "Il bus non ferma qui",
                lines = listOf(
                    "Il feed dichiara che questa corsa, oggi, salta questa fermata.",
                    tabella,
                ),
            )
        }

        return when (row.certainty) {
            Certainty.DECLARED -> Why(
                title = "Lo dice il bus, per questa fermata",
                lines = listOf(
                    "Il feed pubblica una previsione per QUESTA fermata di questa " +
                        "corsa: $scarto.",
                    tabella,
                    mostrato,
                    "E' il dato migliore che esista: e' lo stesso numero che " +
                        "usano le app ufficiali.",
                ),
            )

            Certainty.PROPAGATED -> Why(
                title = "Lo dice il bus, per una fermata prima",
                lines = listOf(
                    "Il feed pubblica una previsione per una fermata precedente di " +
                        "questa corsa: $scarto.",
                    "La regola di GTFS-RT dice che quella previsione vale per tutte " +
                        "le fermate seguenti finche' non ce n'e' un'altra. Quindi " +
                        "vale anche qui.",
                    tabella,
                    mostrato,
                ),
            )

            Certainty.ESTIMATED -> Why(
                title = "Questo numero lo stimiamo noi",
                lines = listOf(
                    "Nessuna previsione del feed copre questa fermata.",
                    "Partiamo dall'ultimo ritardo che il feed ha dichiarato per " +
                        "questa corsa e lo portiamo avanti, consumandone un pezzo " +
                        "verso il capolinea: $scarto.",
                    tabella,
                    mostrato,
                    "Puo' non combaciare con le app ufficiali, ed e' per questo che " +
                        "c'e' scritto \"stimato\" e non \"dal bus\".",
                ),
            )

            Certainty.SERVED -> Why(
                title = "Il bus e' gia' passato di qui",
                lines = listOf(
                    "Il feed dice che questa corsa ha gia' servito questa fermata.",
                    tabella,
                ),
            )

            null -> Why(
                title = "Vale l'orario di tabella",
                lines = if (row.monitored) {
                    listOf(
                        "Il feed vede il mezzo in strada ma non dice di quanto sia " +
                            "in ritardo.",
                        tabella,
                        "E' meno di una previsione e piu' di niente: il bus c'e', " +
                            "l'orario e' quello pubblicato.",
                    )
                } else {
                    listOf(
                        "Il feed non parla di questa corsa: nessuna previsione, " +
                            "nessun mezzo agganciato.",
                        tabella,
                        "Succede per le linee che il tempo reale non copre, e la " +
                            "notte quando i mezzi sono spenti.",
                    )
                },
            )
        }
    }

    /**
     * Da dove viene un numero, in due parole.
     *
     * Serve dove non c'e' spazio per una frase: la notifica della
     * navigazione, che ha una riga sola e prima diceva "ritardo live".
     */
    fun source(certainty: Certainty?): String? = when (certainty) {
        Certainty.DECLARED, Certainty.PROPAGATED -> "dal bus"
        Certainty.ESTIMATED, Certainty.SERVED -> "stimato"
        null -> null
    }

    /**
     * La provenienza di un tabellone intero, per un titolo.
     *
     * "Prossimi passaggi · dal bus" ha senso solo se almeno una riga viene dal
     * feed; altrimenti promette una cosa che non c'e'.
     */
    fun boardSource(board: DepartureBoard): String {
        val dalBus = board.rows.count { it.fromFeed }
        return when {
            board.rows.isEmpty() -> "orari da tabella"
            dalBus == board.rows.size -> "dal bus"
            // Il caso di mezzo, che prima diceva "dal bus" e basta.
            //
            // Con dieci righe in memoria e sei a schermo, bastava che una
            // riga nascosta venisse dal feed perche' il piede promettesse
            // "dal bus" sopra sei righe che dicevano tutte "orario da
            // tabella". Una contraddizione dentro lo stesso pannello, a due
            // centimetri di distanza: e' esattamente il genere di cosa che fa
            // dire che l'app non si spiega.
            dalBus > 0 -> "in parte dal bus"
            board.rows.any { it.live } -> "stimati"
            else -> "orari da tabella"
        }
    }

}
