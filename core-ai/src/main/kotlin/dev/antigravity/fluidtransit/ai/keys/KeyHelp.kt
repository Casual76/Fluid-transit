package dev.antigravity.fluidtransit.ai.keys

import dev.antigravity.fluidengine.ai.provider.ProviderId

/**
 * Le parole di Fluid Transit sull'assistente: quello che l'engine non puo'
 * sapere perche' e' dell'app.
 *
 * Come si crea una chiave, i passi e gli indirizzi dei tre servizi sono nelle
 * guide di `engine-ai-ui` (`FluidProviderGuides`), uguali per tutte le app.
 * Qui resta la parte onesta sul dominio: accendendo l'assistente la
 * REGISTRAZIONE DELLA VOCE e le domande (con la fermata vicina, i posti
 * salvati, le linee preferite) partono verso un servizio esterno. L'unica
 * garanzia che c'era riguardava la chiave, e si leggeva come "non esce
 * niente". Qui c'e' la versione onesta.
 */
object KeyHelp {

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

    /** Le due frasi insieme, come le mostra la scheda di una chiave. */
    fun disclosure(provider: ProviderId): String = "${whatIsSent(provider)} ${keyStaysHere(provider)}"

    /** Il sottotitolo di "Attiva l'assistente", a seconda di chiave e interruttore. */
    fun enableSubtitle(anyKeyVerified: Boolean, enabled: Boolean): String = when {
        anyKeyVerified && enabled ->
            "Chiedi a voce o scrivendo: cerca, calcola viaggi, dice dove sono i bus. " +
                "Voce e domande partono verso il servizio che hai scelto"
        // La chiave c'e' e funziona, ma l'interruttore e' spento: il sottotitolo che promette
        // l'assistente compariva lo stesso, e il microfono restava quello di sistema senza
        // che nessuno dicesse perche'.
        anyKeyVerified -> "Chiave a posto: accendi l'interruttore per usarlo"
        else -> "Serve la chiave gratuita di un servizio, qui sotto: \"Come si fa\" spiega dove prenderla"
    }
}
