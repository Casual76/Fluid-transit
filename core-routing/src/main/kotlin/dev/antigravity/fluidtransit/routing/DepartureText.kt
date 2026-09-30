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

    /**
     * Il colore di un orario dice se il bus e' in orario, non da dove viene
     * il numero.
     *
     * Fino al 16/09 diceva la PROVENIENZA: verde voleva dire "lo dice il
     * mezzo". Era coerente con il resto dell'app e sbagliato per chi guarda:
     * sulla scheda di un bus si leggeva "+33 min di ritardo" in verde, e il
     * verde, per chiunque, vuol dire che va tutto bene. La provenienza ha
     * gia' due modi di dirsi — il pallino che pulsa e le parole sotto — e
     * sono i due che non si possono fraintendere.
     *
     * Deciso con Alessio il 16/09.
     */
    enum class Tone {
        /** Entro i cinque minuti di ritardo: il bus e' quello che dice. */
        ON_TIME,

        /** Qualche minuto di ritardo: si vede, ma il piano regge. */
        LATE,

        /** Tanto ritardo: cambia quello che decidi di fare. */
        VERY_LATE,

        /** Nessun dato dal vivo: vale l'orario pubblicato, e il colore tace. */
        SCHEDULED,

        /** La corsa non ci sara'. */
        CANCELED,
    }

    /**
     * Da qui in su un numero dal vivo si presenta con la sua eta'.
     *
     * Dieci minuti: e' la stessa soglia oltre la quale il dato smetteva di
     * essere mostrato del tutto. Adesso non sparisce, si data.
     */
    const val VECCHIO_SECONDS = 600

    /** Da qui in su il ritardo si vede: il colore passa all'ambra. */
    const val LATE_SECONDS = 5 * 60

    /** Da qui in su il ritardo cambia i piani: il colore passa al rosso. */
    const val VERY_LATE_SECONDS = 15 * 60

    /** Il tono di un ritardo, quando un ritardo c'e'. */
    fun toneOf(delaySeconds: Int?): Tone = when {
        delaySeconds == null -> Tone.SCHEDULED
        delaySeconds >= VERY_LATE_SECONDS -> Tone.VERY_LATE
        delaySeconds >= LATE_SECONDS -> Tone.LATE
        else -> Tone.ON_TIME
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
        ageSeconds = row.ageSeconds,
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
        ageSeconds: Int = 0,
    ): Phrase = phraseOf(
        scheduledEpoch, delaySeconds, certainty, canceled, skipped, monitored, nowEpoch,
        ageSeconds,
    )

    private fun phraseOf(
        scheduledEpoch: Long,
        delaySeconds: Int?,
        certainty: Certainty?,
        canceled: Boolean,
        skipped: Boolean,
        monitored: Boolean,
        nowEpoch: Long,
        ageSeconds: Int = 0,
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
        val fromFeed = fromFeed(certainty)
        val tone = toneOf(delaySeconds)
        val source = if (fromFeed) "dal bus" else "stimato"

        val support = when {
            // Un numero vecchio si mostra, ma dicendo di quando e'.
            //
            // Quando l'origine si ferma — succede, ed e' stato misurato: un
            // quarto d'ora in piena mattina — il ritardo di prima resta
            // l'informazione migliore che abbiamo, e buttarlo faceva tornare
            // tutte le righe all'orario di tabella. Ma un numero vecchio
            // spacciato per fresco e' peggio di nessun numero: qui si dice
            // l'eta' al posto dell'orario di tabella, che in quel momento e'
            // la cosa meno interessante.
            //
            // E se e' un ritardo che conta, si dice anche quello, prima
            // dell'eta': il tono resta ambra o rosso, e chi non vede i colori
            // leggeva solo "visto 15 min fa". Prima dell'eta' perche' su una
            // riga del widget tagliata in fondo si perde l'eta', non il
            // ritardo.
            live && ageSeconds >= VECCHIO_SECONDS -> {
                val ritardo = if (tone == Tone.LATE || tone == Tone.VERY_LATE) {
                    " · ${Times.delayLabel(delaySeconds)}"
                } else {
                    ""
                }
                "$source$ritardo · visto ${Words.age(ageSeconds.toLong())} fa"
            }
            // Il ritardo e' zero ma il feed sta seguendo la corsa: "in orario"
            // e' un'informazione, ed e' diversa da "non sappiamo niente".
            // Un ritardo che conta si dice anche a parole. Il colore da solo
            // lo diceva a chi distingue l'ambra dal verde, e non a chi usa
            // un lettore di schermo o vede i colori in un altro modo: un bus
            // con un quarto d'ora di ritardo si leggeva "12 min" e "da
            // tabella alle 14:05", cioe' come uno puntuale.
            live && (tone == Tone.LATE || tone == Tone.VERY_LATE) ->
                "$source · ${Times.delayLabel(delaySeconds)}"
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
        return "${row.line} ${whenPhrase(nowEpoch, row.effectiveEpoch)}"
    }

    /**
     * Il quando di una partenza, con la preposizione: "fra 3 min", "alle 18:04", "ora".
     *
     * "fra" e "alle" non sono ornamenti: senza, la linea e il numero si
     * toccano e si leggono come uno solo. Nei Preferiti c'era scritto
     * "6 3 min - 12 3 min", che sono la 6 fra tre minuti e la 12 fra
     * tre minuti, ma si legge come due numeri appiccicati. Le linee a
     * una cifra sono fra le piu' usate di Firenze, quindi il caso non e'
     * raro: e' quello di tutti i giorni.
     *
     * Lo usano [compact] e [spoken], cosi' la riga del widget e la frase per
     * il lettore di schermo non possono dire due "quando" diversi.
     */
    private fun whenPhrase(nowEpoch: Long, effectiveEpoch: Long): String {
        if (Times.minutesUntil(nowEpoch, effectiveEpoch) >= CLOCK_AFTER_MINUTES) {
            return "alle ${Times.hhmm(effectiveEpoch)}"
        }
        val label = Times.minutesLabel(nowEpoch, effectiveEpoch)
        return if (label.firstOrNull()?.isDigit() == true) {
            "fra $label"
        } else {
            // "ora" sta da solo: "la 6 fra ora" non lo dice nessuno.
            label
        }
    }

    /**
     * La riga di provenienza, letta ad alta voce.
     *
     * I separatori " · " servono all'occhio: un lettore di schermo li dice
     * "punto centrato" o li salta, e in entrambi i casi fa una frase sola di
     * "dal bus · +9 min di ritardo". La virgola e' la pausa che voleva.
     */
    fun spokenSupport(phrase: Phrase): String = phrase.support.replace(" · ", ", ")

    /**
     * Una partenza intera, detta come la direbbe una persona.
     *
     * Nella scheda fermata ogni riga era quattro o cinque tappe separate per
     * TalkBack — la pastiglia della linea, la destinazione, "stimato", il
     * tasto "vola sul bus" e, per ultimi, i minuti — e con dieci righe sono
     * una cinquantina di scorrimenti, col numero che e' il motivo per cui si
     * e' aperta la scheda letto dopo tutto il resto e slegato dalla linea che
     * lo riguarda. Qui i minuti seguono subito la linea e la destinazione, e
     * il resto (da dove viene il numero, di quanto e' in ritardo) viene dopo.
     *
     * @param stopLabel da che fermata parte, quando la lista ne mescola piu' di una.
     * @param stopDistance quanto dista, gia' scritto come si vede a schermo
     *   ("a 250 m"). Senza [stopLabel] non si dice.
     */
    fun spoken(
        row: NextDeparture,
        nowEpoch: Long,
        stopLabel: String? = null,
        stopDistance: String? = null,
    ): String {
        val p = phrase(row, nowEpoch)
        val fermata = if (stopLabel.isNullOrEmpty()) {
            ""
        } else if (stopDistance.isNullOrEmpty()) {
            "Fermata $stopLabel. "
        } else {
            "Fermata $stopLabel, $stopDistance. "
        }
        val corsa = if (row.destination.isEmpty()) {
            "Linea ${row.line}"
        } else {
            "Linea ${row.line} verso ${row.destination}"
        }
        // Una corsa cancellata o che salta la fermata non ha un "quando": il
        // suo orario di tabella e' gia' dentro la frase di supporto.
        val resto = if (p.tone == Tone.CANCELED) {
            "${p.headline.lowercase()}. ${p.support}"
        } else {
            "${whenPhrase(nowEpoch, row.effectiveEpoch)}, ${spokenSupport(p)}"
        }
        return "$fermata$corsa, $resto"
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
    /**
     * Com'e' il collegamento al tempo reale, per chi spiega una riga senza
     * dati: "il feed non parla di questa corsa" e' vero solo se il feed lo
     * stiamo ricevendo. Col telefono offline era una colpa data alla Regione
     * per un buco nostro.
     */
    enum class LiveLink {
        /** Posizioni e ritardi arrivano: se una corsa non ha dati, e' il feed. */
        FULL,

        /** Arrivano solo le posizioni, dall'origine: i ritardi no. */
        VEHICLES_ONLY,

        /** Non arriva niente: la rete, o il nostro servizio. */
        NONE,
    }

    fun why(row: NextDeparture, nowEpoch: Long, link: LiveLink = LiveLink.FULL): Why {
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
                    // "consumandone un pezzo verso il capolinea: in orario"
                    // si leggeva sul telefono con un ritardo zero: di un
                    // ritardo che non c'e' non si consuma niente. Il come
                    // e il risultato stanno in due frasi.
                    "Partiamo dall'ultimo ritardo che il feed ha dichiarato per " +
                        "questa corsa e lo portiamo fino a questa fermata, facendolo " +
                        "calare un po' verso il capolinea. Il risultato, qui: $scarto.",
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
                lines = if (link == LiveLink.NONE) {
                    listOf(
                        "Adesso il telefono non riceve il tempo reale: di questa corsa " +
                            "non sappiamo niente, e non e' colpa del feed.",
                        tabella,
                        "Quando il collegamento torna, i minuti dal bus tornano da soli.",
                    )
                } else if (link == LiveLink.VEHICLES_ONLY && !row.monitored) {
                    listOf(
                        "Adesso arrivano solo le posizioni dei mezzi, non i ritardi: il " +
                            "nostro servizio non risponde e si legge direttamente il feed " +
                            "della Regione.",
                        tabella,
                    )
                } else if (row.monitored) {
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
                        // Non "per le linee": misurato il 16/09, la linea 6
                        // aveva trentasei corse seguite e questa no, e dire
                        // "linea" fa concludere che la 6 non sia seguita. La
                        // copertura e' per CORSA — su 809 in strada il feed
                        // ne seguiva 612 — ed e' quello che si vede.
                        "Succede per le singole corse che il tempo reale non segue, " +
                            "anche su una linea per il resto seguita, e la notte " +
                            "quando i mezzi sono spenti.",
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
     * Il numero lo dice il mezzo? E' la domanda dietro "dal bus" e "stimato",
     * e chi disegna un pallino pieno o vuoto deve darsi la stessa risposta.
     *
     * La barra della navigazione se ne dava un'altra: trattava SERVED come
     * vivo e riempiva i pallini, mentre la riga sotto diceva "stimato".
     */
    fun fromFeed(certainty: Certainty?): Boolean =
        certainty == Certainty.DECLARED || certainty == Certainty.PROPAGATED

    /**
     * Una corsa cancellata in una frase: "La 6 e' stata cancellata".
     *
     * La navigazione la scriveva in casa due volte, nel titolo della card e
     * nell'avviso che fa vibrare: due copie che aspettavano solo di divergere.
     */
    fun canceledLine(line: String): String = "La $line e' stata cancellata"

    /**
     * La provenienza di un tabellone intero, per un titolo.
     *
     * "Prossimi passaggi · dal bus" ha senso solo se almeno una riga viene dal
     * feed; altrimenti promette una cosa che non c'e'.
     */
    fun boardSource(board: DepartureBoard): String = boardSource(board.rows)

    /**
     * La stessa cosa, sulle righe che si vedono davvero.
     *
     * Il widget ne calcola cinque e ne disegna due o tre, a seconda di quanto
     * e' grande: il piede riassumeva tutte e cinque. Sulla home, misurato:
     * "Prossimi passaggi · in parte dal bus" sopra due righe che dicevano
     * tutt'e due "orario da tabella". E' lo stesso difetto gia' corretto nel
     * pannello di una fermata, ripetuto dove le righe nascoste non sono
     * nemmeno scorrevoli.
     */
    fun boardSource(rows: List<NextDeparture>): String {
        val dalBus = rows.count { it.fromFeed }
        return when {
            rows.isEmpty() -> "orari da tabella"
            dalBus == rows.size -> "dal bus"
            // Il caso di mezzo, che prima diceva "dal bus" e basta.
            //
            // Con dieci righe in memoria e sei a schermo, bastava che una
            // riga nascosta venisse dal feed perche' il piede promettesse
            // "dal bus" sopra sei righe che dicevano tutte "orario da
            // tabella". Una contraddizione dentro lo stesso pannello, a due
            // centimetri di distanza: e' esattamente il genere di cosa che fa
            // dire che l'app non si spiega.
            dalBus > 0 -> "in parte dal bus"
            rows.any { it.live } -> "stimati"
            else -> "orari da tabella"
        }
    }

    /**
     * Perche' un tabellone non ha niente da mostrare.
     *
     * Un tabellone vuoto e' la cosa piu' facile da raccontare male, perche'
     * cinque situazioni diverse finiscono tutte nella stessa lista vuota: la
     * fermata non l'hai ancora scelta, gli orari non sono ancora aperti, la
     * fermata salvata non esiste piu' in quelli di oggi, gli orari sono
     * scaduti, oppure e' semplicemente notte. Le prime tre sono nostre e si
     * risolvono; le ultime due no. Dirle tutte con le parole della quinta —
     * "Nessun passaggio a breve" — fa sembrare fermo il servizio quando a
     * essere ferma e' l'app, ed e' esattamente la frase che il widget
     * mostrava per tutte e cinque.
     *
     * "Qui intorno" ne aggiunge una sesta: non c'e' nessuna fermata nel
     * raggio, e quindi non c'e' niente di cui dire che non parte.
     */
    enum class Trouble {
        /** Nessuna fermata scelta: solo un widget puo' trovarsi cosi'. */
        NESSUNA_FERMATA,

        /** Gli orari non erano aperti in tempo per questo disegno. */
        ORARI_NON_PRONTI,

        /**
         * La fermata salvata non compare negli orari di oggi.
         *
         * Succede davvero: i preferiti e i widget salvano l'hash dell'id, che
         * sopravvive allo scambio notturno, ma una fermata tolta o rinominata
         * dalla fonte no. Prima il widget diceva "Nessun passaggio", che e'
         * la lettura piu' sbagliata possibile di una fermata sparita.
         */
        FERMATA_SCONOSCIUTA,

        /** Gli orari ci sono ma non coprono piu' oggi. */
        ORARI_SCADUTI,

        /** Tutto a posto: da li' non parte niente a breve. */
        NIENTE_A_BREVE,

        /**
         * "Qui intorno": nel raggio che si guarda non c'e' nessuna fermata.
         *
         * Succede in campagna, in montagna, o zoomando la mappa su un punto
         * lontano da tutto. Il pannello diceva "non parte niente nelle
         * prossime due ore", che e' un'affermazione sul servizio: qui il
         * servizio non c'entra, e' la fermata che manca. E non diceva cosa
         * fare — spostare la mappa su un paese, cercare una fermata per nome.
         */
        NESSUNA_FERMATA_VICINA,
    }

    /**
     * Fino a dove si guarda per "qui intorno": dieci minuti a piedi scarsi.
     *
     * Sta qui e non nel pannello che fa la ricerca perche' la frase "nel
     * raggio di 700 m non ci sono fermate" e la query che le cerca devono
     * dire lo stesso numero: due costanti separate erano il modo di dire 700
     * a schermo e cercarne 500.
     */
    const val NEARBY_RADIUS_METERS = 700.0

    /**
     * Lo `stopIndex` di un tabellone "qui intorno" costruito su ZERO fermate.
     *
     * Un tabellone di piu' fermate non ha una fermata sua, quindi quel campo
     * e' libero: qui porta il fatto che la ricerca e' andata a vuoto, cosi' i
     * pannelli che mostrano il tabellone lo distinguono da "ci sono fermate e
     * non parte niente" senza un secondo valore da portarsi dietro.
     * Negativo, come il -1 di "nessuna fermata" dei tabelloni vuoti, ma
     * diverso da quello.
     */
    const val NEARBY_NO_STOPS = -2

    /**
     * Il tabellone di "qui intorno" quando non serve interrogare gli orari.
     *
     * [stops] sono le fermate trovate nel raggio: null se non si sa ancora
     * dove si sta guardando (l'ancora della mappa arriva dopo un paio di
     * secondi dall'avvio, e il lettore degli orari dopo ancora), lista vuota
     * se si sa e non c'e' niente. Le due risposte vanno tenute distinte
     * perche' chiedere a `DepartureBoards.merged` di una lista vuota dava lo
     * stesso tabellone per tutte e due — datato adesso, senza righe — e la
     * capsula scriveva "non passa niente a breve" anche mentre l'app non
     * sapeva ancora dove guardare.
     *
     * Ritorna:
     *  - il tabellone NON calcolato (`computedAtEpoch` a zero) se non si sa: la
     *    capsula resta nascosta e il pannello mostra la rotella;
     *  - un tabellone vuoto e datato, con [NEARBY_NO_STOPS], se non ci sono
     *    fermate: e' uno stato vero, e [trouble] lo racconta a parole;
     *  - null se le fermate ci sono e il tabellone va chiesto agli orari.
     */
    fun nearbyBoardWithoutAsking(stops: List<Int>?, nowEpoch: Long): DepartureBoard? = when {
        stops == null -> DepartureBoard.empty(-1, "", 0L)
        // Datato non zero, perche' zero e' proprio "non calcolato".
        stops.isEmpty() -> DepartureBoard.empty(NEARBY_NO_STOPS, "", nowEpoch.coerceAtLeast(1L))
        else -> null
    }

    /**
     * Le parole di un tabellone vuoto.
     *
     * [detail] e' la frase intera, per una schermata; [short] e' la riga
     * corta del widget, che ha lo spazio di un sottotitolo.
     */
    class Empty(val title: String, val detail: String, val short: String)

    /**
     * @param oneStop se il tabellone e' di una fermata sola. Cambia solo il
     *   soggetto della frase — "da questa fermata" contro "dalle tue
     *   fermate" — ma e' la differenza fra una frase scritta per te e una
     *   frase generica.
     */
    fun empty(trouble: Trouble, oneStop: Boolean = true): Empty = when (trouble) {
        Trouble.NESSUNA_FERMATA -> Empty(
            "Scegli una fermata preferita",
            "Questo widget mostra i passaggi di una fermata che hai messo fra i preferiti.",
            "dalla configurazione del widget",
        )

        Trouble.ORARI_NON_PRONTI -> Empty(
            "Orari non ancora pronti",
            "Gli orari si stanno ancora aprendo. Non vuol dire che non passi niente: " +
                "vuol dire che non l'abbiamo ancora letto.",
            "tocca per aprire l'app",
        )

        Trouble.FERMATA_SCONOSCIUTA -> if (oneStop) {
            Empty(
                "Questa fermata non c'e' piu'",
                "Negli orari di oggi non compare: puo' essere stata rinominata o tolta " +
                    "dalla fonte. Sceglierne un'altra rimette a posto.",
                "non compare negli orari di oggi",
            )
        } else {
            Empty(
                "Le tue fermate non ci sono piu'",
                "Negli orari di oggi non ne compare nessuna: possono essere state " +
                    "rinominate o tolte dalla fonte. Stellarne altre rimette a posto.",
                "non compaiono negli orari di oggi",
            )
        }

        // Non "non ne arrivano di nuovi": sui dati mobili l'app ne trova di
        // nuovi e chiede prima di scaricarli, e senza rete non lo sa. La
        // frase dice dove guardare, e resta vera in tutti e due i casi.
        Trouble.ORARI_SCADUTI -> Empty(
            "Gli orari sono scaduti",
            "Quelli che abbiamo non coprono piu' oggi: se ce ne sono di nuovi li " +
                "aggiorni da Impostazioni, Stato dei dati. Non vuol dire che i bus " +
                "non passino: vuol dire che non sappiamo quando.",
            "orari da aggiornare",
        )

        Trouble.NIENTE_A_BREVE -> Empty(
            "Nessun passaggio a breve",
            if (oneStop) {
                "Da questa fermata non parte niente nelle prossime due ore."
            } else {
                "Dalle tue fermate non parte niente nelle prossime due ore."
            },
            "nelle prossime due ore",
        )

        Trouble.NESSUNA_FERMATA_VICINA -> Empty(
            "Nessuna fermata qui intorno",
            // Non "sposta la mappa": col GPS acceso "qui intorno" si misura da dove
            // sei, e spostare la mappa non cambiava niente. Queste due cose
            // funzionano con tutte e due le ancore.
            "Nel raggio di ${Words.distance(NEARBY_RADIUS_METERS)} non ce ne sono: " +
                "cerca una fermata per nome o toccane una sulla mappa.",
            "nessuna fermata nel raggio",
        )
    }

    /**
     * Il guaio di un tabellone gia' calcolato e senza righe.
     *
     * Le altre situazioni le conosce solo chi ha provato a costruirlo.
     * Quella di "qui intorno" senza fermate si riconosce dal suo
     * [NEARBY_NO_STOPS], e ha la precedenza: non aver chiesto gli orari non
     * puo' essere scambiato per orari scaduti o per una notte tranquilla.
     */
    fun trouble(board: DepartureBoard): Trouble = when {
        board.stopIndex == NEARBY_NO_STOPS -> Trouble.NESSUNA_FERMATA_VICINA
        board.outsideValidity -> Trouble.ORARI_SCADUTI
        else -> Trouble.NIENTE_A_BREVE
    }
}
