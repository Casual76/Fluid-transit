package dev.antigravity.fluidtransit.ai.keys

import dev.antigravity.fluidtransit.ai.provider.ProviderId

/**
 * Le parole con cui l'app spiega la chiave dell'assistente.
 *
 * L'assistente e' spento finche' non c'e' una chiave, e la chiave e' una cosa
 * che un utente nuovo non ha e non sa dove prendere: la schermata diceva solo
 * "Incolla la tua chiave" e "Gratis su console.groq.com", senza dire che
 * cos'e', come si crea, ne' — soprattutto — che accendendo l'assistente la
 * REGISTRAZIONE DELLA VOCE e le domande (con la fermata vicina, i posti
 * salvati, le linee preferite) partono verso un servizio esterno. L'unica
 * garanzia che c'era riguardava la chiave, e si leggeva come "non esce
 * niente". Qui c'e' la versione onesta.
 *
 * Gli indirizzi sono solo quelli dei siti dei servizi, gia' citati nelle
 * righe della schermata: nessun percorso profondo, che cambierebbe senza
 * che nessuno se ne accorga.
 */
object KeyHelp {

    /** Cos'e' la chiave, per chi non l'ha mai vista. */
    const val WHAT_IS_A_KEY =
        "La chiave e' una chiave API gratuita: una specie di password che l'app usa per " +
            "parlare col servizio. Si crea sul sito del servizio, con un account gratuito."

    /** I passi, uguali per tutti e tre: nessun nome di pulsante che un sito puo' cambiare. */
    const val STEPS =
        "Crea l'account, cerca la voce per creare una chiave API (\"API key\"), copiala e " +
            "incollala qui sotto."

    /** Il sito del servizio, da aprire: solo l'indirizzo principale. */
    fun url(provider: ProviderId): String = when (provider) {
        ProviderId.GROQ -> "https://console.groq.com"
        ProviderId.GEMINI -> "https://aistudio.google.com"
        ProviderId.OPENROUTER -> "https://openrouter.ai"
    }

    /** Il nome del sito come si legge sul pulsante, senza il protocollo. */
    fun host(provider: ProviderId): String = url(provider).removePrefix("https://")

    /** Quale scegliere per cominciare: uno solo, detto. */
    fun recommended(provider: ProviderId): Boolean = provider == ProviderId.GROQ

    /**
     * Cosa parte verso il servizio quando l'assistente lavora.
     *
     * La voce e' separata dal resto perche' e' la parte che sorprende: a
     * `RECORD_AUDIO` Android chiede di registrare, e non dice dove va il file.
     */
    fun whatIsSent(provider: ProviderId): String =
        "Quando usi l'assistente partono verso ${provider.label} la registrazione della tua " +
            "voce, le tue domande, la fermata vicina a te, i nomi dei posti salvati, le linee " +
            "preferite e quello che l'app trova per risponderti."

    /** La garanzia che c'era, ma come una frase a parte e riferita alla chiave. */
    fun keyStaysHere(provider: ProviderId): String =
        "La chiave resta su questo telefono, cifrata: viaggia solo verso ${provider.label}."

    /** Il sottotitolo di "Attiva l'assistente", a seconda di chiave e interruttore. */
    fun enableSubtitle(anyKeyVerified: Boolean, enabled: Boolean): String = when {
        anyKeyVerified && enabled ->
            "Chiedi a voce o scrivendo: cerca, calcola viaggi, dice dove sono i bus. " +
                "Voce e domande partono verso il servizio che hai scelto"
        // La chiave c'e' e funziona, ma l'interruttore e' spento: il sottotitolo che promette
        // l'assistente compariva lo stesso, e il microfono restava quello di sistema senza
        // che nessuno dicesse perche'.
        anyKeyVerified -> "Chiave a posto: accendi l'interruttore per usarlo"
        else -> "Serve la chiave gratuita di un servizio, qui sotto: toccane uno per vedere come averla"
    }

    /** La riga di un servizio senza chiave: dove prenderla, e chi conviene per primo. */
    fun rowHint(provider: ProviderId): String {
        val cosa = when (provider) {
            ProviderId.GROQ -> "gratis su ${host(provider)}, il piu' veloce dei tre"
            ProviderId.GEMINI -> "gratis su ${host(provider)}"
            ProviderId.OPENROUTER -> "${host(provider)}: un unico accesso a molti modelli"
        }
        return if (recommended(provider)) "Consigliato per cominciare: $cosa" else cosa.replaceFirstChar { it.uppercase() }
    }
}
