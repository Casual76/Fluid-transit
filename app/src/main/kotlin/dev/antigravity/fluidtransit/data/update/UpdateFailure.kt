package dev.antigravity.fluidtransit.data.update

import dev.antigravity.fluidtransit.data.bundle.BundleFailure

/**
 * Perche' il controllo degli aggiornamenti (o l'installazione) non e'
 * riuscito, in italiano.
 *
 * In Impostazioni > L'app si leggeva `L'ultimo controllo non e' riuscito:
 * Unable to resolve host "raw.githubusercontent.com": No address associated
 * with hostname`, cioe' il genere di frase che [BundleFailure] ha gia'
 * tradotto per la schermata di benvenuto. Su un telefono appena aperto e'
 * anche il caso piu' probabile, perche' il controllo parte quando la rete e'
 * ancora chiusa al processo.
 *
 * Le cause che valgono identiche per gli orari e per gli aggiornamenti (il
 * DNS, il certificato, il collegamento rifiutato) prendono la frase da
 * [BundleFailure]: una seconda copia scritta in casa e' come nascono le
 * divergenze. Le altre hanno qui la loro, perche' il soggetto e' l'app e non
 * gli orari.
 */
object UpdateFailure {

    private val ERRORE_HTTP =
        Regex("""(richiesta|download) non riuscit[oa] \([45]\d\d\)""")

    /**
     * @param message il messaggio dell'eccezione, o dell'errore
     *   dell'installazione.
     * @param installing true se l'errore viene dal download o
     *   dall'installazione e non dal controllo: quei messaggi nascono gia' in
     *   italiano dal motore ("Abilita l'installazione da questa app..."), e
     *   nasconderli dietro una frase generica toglierebbe proprio
     *   l'istruzione utile.
     */
    fun words(message: String?, installing: Boolean = false): BundleFailure.Words {
        val raw = message?.trim().orEmpty()
        val m = raw.lowercase()
        return when {
            raw.isEmpty() -> BundleFailure.Words(
                if (installing) {
                    "L'installazione non e' riuscita, e non sappiamo dire perche'."
                } else {
                    "Non riusciamo a controllare gli aggiornamenti, e non sappiamo dire perche'."
                },
                null,
            )

            "unable to resolve host" in m || "no address associated" in m ||
                "unknownhost" in m ||
                ("connect" in m && ("refused" in m || "failed" in m)) ||
                "ssl" in m || "certificate" in m || "trust anchor" in m ->
                BundleFailure.words(raw)

            "timeout" in m || "timed out" in m || "connection reset" in m ||
                "econnreset" in m || "unexpected end of stream" in m ->
                BundleFailure.Words(
                    if (installing) {
                        "La rete si e' interrotta durante il download. Riprovare di solito basta."
                    } else {
                        "La rete ha smesso di rispondere mentre controllavamo. " +
                            "Riprovare di solito basta."
                    },
                    raw,
                )

            "enospc" in m || "no space left" in m ->
                BundleFailure.Words(
                    "Non c'e' abbastanza spazio sul telefono per scaricare l'aggiornamento.",
                    raw,
                )

            // Il manifest o l'APK non stanno dove dovrebbero: non e' colpa
            // del telefono. Le stringhe sono quelle VERE dell'engine
            // (EngineHttp: "Richiesta non riuscita (404)." per il manifest,
            // "Download non riuscito (404)." per l'APK): prima il ramo
            // cercava "http 4", che scrivono solo i controlli del bundle, e
            // un 404 sul manifest finiva nella frase generica.
            ERRORE_HTTP.containsMatchIn(m) || "http 4" in m || "http 5" in m ->
                BundleFailure.Words(
                    "Il server degli aggiornamenti non risponde come dovrebbe. " +
                        "Non e' un problema del telefono: riprova fra poco.",
                    raw,
                )

            // Gli errori dell'installazione nascono dal motore gia' scritti
            // per chi legge, e dicono cosa fare.
            installing -> BundleFailure.Words(raw, null)

            else -> BundleFailure.Words("Non riusciamo a controllare gli aggiornamenti.", raw)
        }
    }
}
