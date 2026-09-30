package dev.antigravity.fluidtransit.routing

/**
 * Un viaggio proposto, detto a voce.
 *
 * Una riga della lista dei viaggi e' fatta di segni: due orari con una
 * freccia in mezzo, un pedone con un numero accanto, pastiglie colorate
 * separate da "›", un pallino che pulsa. A chi guarda si legge in un colpo.
 * A un lettore di schermo arrivava "07:12, 07:40, 3, 20, ›, 7, 28 min, 1
 * cambio": quel 3 sono tre minuti a piedi, ma suona come la linea 3 o come
 * una terza tappa, e subito prima di "1 cambio" si contraddiceva da solo.
 * Il pallino, poi, era l'unico modo di sapere che gli orari erano dal
 * vivo, e per chi non lo vede non esisteva.
 *
 * Qui il viaggio si scrive per intero e con le parole del resto dell'app:
 * i minuti a piedi si dicono "a piedi", le corse si dicono "linea", la
 * provenienza e' quella di [DepartureText]. Sta in `:core-routing` e prende
 * numeri e stringhe, non i tipi della schermata, perche' il vocabolario ha
 * un posto solo e questa frase la puo' voler dire anche un widget.
 */
object JourneyText {

    /** Una tappa, in primitivi: la schermata la traduce dalle sue. */
    sealed class Step {
        /** Si cammina per [seconds] secondi. */
        class Walk(val seconds: Int) : Step()

        /**
         * Si sale sulla [line]; [live] se il feed sta seguendo QUESTA corsa, e
         * [delaySeconds] il ritardo che il viaggio usa.
         */
        class Ride(val line: String, val live: Boolean, val delaySeconds: Int = 0) : Step()
    }

    /**
     * Il viaggio in una frase per un lettore di schermo.
     *
     * "Parti alle 07:12, arrivi alle 07:40, 28 min, 1 cambio. Percorso: 3 min
     * a piedi, linea 7, 2 min a piedi, linea 12. Orari dal bus."
     *
     * I minuti a piedi si dicono coi secondi veri, non coi minuti gia'
     * arrotondati che la striscia disegna, e una camminata di mezzo minuto o
     * meno non si dice: e' il marciapiede della fermata, e "meno di un
     * minuto a piedi" in mezzo a un percorso e' rumore. Le camminate si
     * dicono anche quando la striscia le nasconde per fare spazio.
     */
    fun spoken(
        depTime: String,
        arrTime: String,
        durationLabel: String,
        transfers: Int,
        steps: List<Step>,
        walkOnly: Boolean,
        walkSeconds: Int,
    ): String {
        val head = "Parti alle $depTime, arrivi alle $arrTime"
        // Un viaggio a piedi non ha ne' cambi ne' linee: la riga dice "solo a
        // piedi" e la durata a parole, e la durata in testa sarebbe la
        // stessa cosa detta due volte ("8 min, 8 min a piedi").
        if (walkOnly) {
            return "$head, solo a piedi, ${Times.durationOrUnderMinute(walkSeconds)}."
        }

        val out = StringBuilder("$head, $durationLabel, ${transfersLabel(transfers)}.")

        val percorso = steps.mapNotNull { spokenStep(it) }
        if (percorso.isNotEmpty()) {
            out.append(" Percorso: ").append(percorso.joinToString(", ")).append('.')
        }

        // La provenienza del pallino, con le parole del tabellone: "dal bus"
        // se lo dicono tutte le corse, "in parte dal bus" se solo alcune —
        // la stessa distinzione di `DepartureText.boardSource`, perche' una
        // frase che promette "dal bus" sopra una corsa da tabella e' proprio
        // il difetto che quel vocabolario esiste per evitare.
        val corse = steps.filterIsInstance<Step.Ride>()
        val dalBus = corse.count { it.live }
        val dalBusParole = DepartureText.source(Certainty.DECLARED).orEmpty()
        when {
            dalBus == 0 -> Unit
            dalBus == corse.size -> out.append(" Orari ").append(dalBusParole).append('.')
            else -> out.append(" Orari in parte ").append(dalBusParole).append('.')
        }
        return out.toString()
    }

    /**
     * I cambi in due parole: "diretto", "1 cambio", "2 cambi".
     *
     * La scrivevano in casa la riga della lista e la testata del dettaglio,
     * e adesso anche la frase a voce: e' la stessa cosa detta tre volte, e
     * una sola copia ha il singolare giusto quando cambia.
     */
    fun transfersLabel(count: Int): String =
        if (count == 0) "diretto" else Words.count(count, "cambio", "cambi")

    private fun spokenStep(step: Step): String? = when (step) {
        is Step.Walk ->
            if (step.seconds <= Times.NOW_SECONDS) null
            else "${Times.durationLabel(step.seconds)} a piedi"

        // Il ritardo si dice quando conta, con le soglie del colore: un bus
        // con venti minuti di ritardo si leggeva come uno puntuale, perche' a
        // voce restava solo il nome della linea e il rosso non si sente.
        is Step.Ride -> {
            val tono = DepartureText.toneOf(step.delaySeconds.takeIf { step.live })
            if (tono == DepartureText.Tone.LATE || tono == DepartureText.Tone.VERY_LATE) {
                "${spokenLine(step.line)}, ${Times.delayLabel(step.delaySeconds)}"
            } else {
                spokenLine(step.line)
            }
        }
    }

    /**
     * "linea 7", ma non "linea Firenze - Prato".
     *
     * Quando una linea non ha il nome breve il chiamante ripiega sul nome
     * lungo, e anteporre "linea" a un nome fatto di piu' parole fa una frase
     * storta. Un nome breve non ha spazi dentro ("7", "T1", "LAM"): e' il
     * segno che basta per distinguere i due casi senza sapere quale dei due
     * sia arrivato.
     */
    private fun spokenLine(line: String): String = when {
        line.isBlank() -> "una linea"
        line.any { it.isWhitespace() } -> line
        else -> "linea $line"
    }
}
