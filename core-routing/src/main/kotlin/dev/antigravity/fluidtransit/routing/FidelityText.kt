package dev.antigravity.fluidtransit.routing

/**
 * Il verdetto del confronto con la fonte, detto a parole.
 *
 * Due volte al giorno un banco scarica il feed GTFS-RT grezzo della Regione e
 * la sezione che il nostro proxy serve all'app, e confronta i ritardi uno per
 * uno. E' l'unica risposta possibile a "non so nemmeno se i dati sono
 * accurati": qualunque cosa mostri l'app, guardandola da sola la si sta
 * confrontando con se' stessa.
 *
 * Fino a ieri quella risposta viveva nei log di un workflow, cioe' in nessun
 * posto. Qui diventa una riga che si puo' leggere.
 *
 * Le parole sono qui e non nella schermata per la solita ragione: questo
 * modulo non vede Compose, e una frase che si puo' interrogare in un test e'
 * una frase che non cambia per sbaglio.
 */
object FidelityText {

    /** Cosa e' successo l'ultima volta che si e' confrontato. */
    class Verdict(
        val atEpoch: Long,
        /** "uguale" | "diverso" | "poco" | "sfasato" */
        val esito: String,
        val punti: Int,
        val diversi: Int,
    )

    class Words(val title: String, val detail: String)

    /**
     * Oltre questa eta' il verdetto non descrive piu' l'oggi.
     *
     * Due giorni: il banco gira due volte al giorno, quindi se l'ultimo che
     * si trova ha piu' di due giorni vuol dire che qualcosa si e' fermato, e
     * dirlo e' meglio che mostrare un numero vecchio come se fosse di adesso.
     */
    const val STALE_SECONDS = 2 * 24 * 3600L

    fun words(v: Verdict?, nowEpoch: Long): Words {
        if (v == null) {
            return Words(
                "Non ancora",
                "Il confronto con la fonte gira due volte al giorno. " +
                    "Il primo esito non e' ancora arrivato quaggiu'.",
            )
        }
        val quando = AlertText.moment(v.atEpoch, nowEpoch)
        if (nowEpoch - v.atEpoch > STALE_SECONDS) {
            return Words(
                "Vecchio di piu' di due giorni",
                "L'ultimo confronto e' di $quando. Gira due volte al giorno: " +
                    "se l'ultimo e' cosi' indietro, si e' fermato qualcosa.",
            )
        }
        return when (v.esito) {
            "uguale" -> Words(
                "Combaciano",
                "${punti(v.punti)} confrontati con quelli della Regione $quando, " +
                    "nessuna differenza.",
            )

            "diverso" -> Words(
                "NON combaciano",
                "Su ${punti(v.punti)} confrontati $quando, ${diversi(v.diversi)} " +
                    "diverso da quello che pubblica la Regione.",
            )

            // Non e' un via libera e non e' un allarme: e' che non c'era
            // niente da guardare. Di notte la Regione pubblica zero corse.
            "poco" -> Words(
                "Non c'era abbastanza da confrontare",
                "L'ultimo giro, $quando, ha trovato ${punti(v.punti)} da " +
                    "confrontare: troppo pochi perche' il confronto dica qualcosa.",
            )

            "sfasato" -> Words(
                "Confronto non riuscito",
                "L'ultimo giro, $quando, ha letto i due lati in due momenti " +
                    "troppo distanti: il confronto non avrebbe detto niente.",
            )

            else -> Words(
                "Esito sconosciuto",
                "L'ultimo giro, $quando, ha risposto qualcosa che questa " +
                    "versione dell'app non sa leggere.",
            )
        }
    }

    private fun punti(n: Int): String = if (n == 1) "un orario" else "$n orari"

    private fun diversi(n: Int): String = if (n == 1) "uno era" else "$n erano"
}
