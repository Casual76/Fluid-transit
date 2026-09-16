package dev.antigravity.fluidtransit.data.bundle

/**
 * Perche' gli orari non sono arrivati, detto a chi non ha scritto l'app.
 *
 * La schermata di benvenuto — la prima cosa che si vede, e su un telefono
 * nuovo l'unica — stampava il messaggio dell'eccezione cosi' com'era. Cioe'
 * `Unable to resolve host "github.com": No address associated with
 * hostname`: in inglese, con un nome di dominio dentro, sopra un tasto
 * "Riprova" che in quel caso non serve a niente perche' il problema non e'
 * qui. Sono le prime dieci parole che l'app dice di se stessa.
 *
 * Il testo tecnico non si butta: resta sotto, in piccolo, perche' e' l'unica
 * cosa utile quando il caso non e' fra questi. Ma sopra c'e' una frase che
 * dice cosa e' successo e, dove ha senso, cosa si puo' fare.
 *
 * Sta in `:app` e non in `:core-routing` perche' parla di rete e di download,
 * che il modulo degli orari non sa nemmeno cosa siano.
 */
object BundleFailure {

    class Words(
        /** La frase leggibile. */
        val title: String,
        /**
         * Il testo originale dell'errore, o null quando la frase lo contiene
         * gia' tutto. Va mostrato in piccolo: serve a chi indaga, non a chi
         * aspetta l'autobus.
         */
        val technical: String?,
    )

    /**
     * @param message il messaggio dell'eccezione, quello che finiva in faccia.
     */
    fun words(message: String?): Words {
        val raw = message?.trim().orEmpty()
        val m = raw.lowercase()
        return when {
            raw.isEmpty() -> Words(
                "Il download degli orari non e' riuscito, e non sappiamo dire perche'.",
                null,
            )

            // Il DNS non risolve: quasi sempre vuol dire che il telefono
            // crede di essere online e non lo e' — un Wi-Fi con la pagina di
            // accesso non ancora accettata e' il caso tipico.
            "unable to resolve host" in m || "no address associated" in m ||
                "unknownhost" in m ->
                Words(
                    "Il telefono non arriva a internet. Se sei su un Wi-Fi che " +
                        "chiede di accettare qualcosa, di solito e' quello.",
                    raw,
                )

            "timeout" in m || "timed out" in m ->
                Words(
                    "La rete ha smesso di rispondere mentre scaricavamo. " +
                        "Riprovare di solito basta.",
                    raw,
                )

            "econnreset" in m || "connection reset" in m || "unexpected end of stream" in m ->
                Words(
                    "Il download si e' interrotto a meta'. Riprovare di solito basta.",
                    raw,
                )

            "connect" in m && ("refused" in m || "failed" in m) ->
                Words("Il collegamento e' stato rifiutato. Riprova fra poco.", raw)

            "ssl" in m || "certificate" in m || "trust anchor" in m ->
                Words(
                    "Il collegamento sicuro non e' riuscito. Succede sulle reti che " +
                        "filtrano il traffico, e con la data del telefono sbagliata.",
                    raw,
                )

            // Il nostro controllo: il file e' arrivato diverso da com'e'
            // stato pubblicato. Non si installa, e riprovare ha senso.
            "sha256" in m || "corrotto" in m ->
                Words(
                    "Gli orari sono arrivati danneggiati e non li abbiamo installati. " +
                        "Riprova: si riscaricano da capo.",
                    raw,
                )

            "enospc" in m || "no space left" in m || "space" in m && "left" in m ->
                Words(
                    "Non c'e' abbastanza spazio sul telefono: gli orari di tutta la " +
                        "Toscana sono una cinquantina di megabyte.",
                    raw,
                )

            // I nostri `check`: "HTTP 404 su …", "download fallito: HTTP 503".
            "http 4" in m ->
                Words(
                    "Gli orari non sono al loro posto sul server. Non e' un problema " +
                        "del telefono: si risolve da solo quando il job notturno " +
                        "ripubblica.",
                    raw,
                )

            "http 5" in m ->
                Words("Il server degli orari non sta bene. Riprova fra qualche minuto.", raw)

            "senza fermate" in m || "senza corse" in m ->
                Words(
                    "Gli orari scaricati erano vuoti, quindi non li abbiamo installati.",
                    raw,
                )

            else -> Words("Il download degli orari non e' riuscito.", raw)
        }
    }
}
