package dev.antigravity.fluidtransit.data.places

/**
 * Quando il file dei luoghi si prova a scaricare, senza rete e senza Android:
 * cosi' si puo' provare.
 *
 * Il file si tentava una volta sola, all'avvio del PROCESSO. Su dati mobili o
 * senza rete il tentativo tornava in silenzio, su un errore anche, e da li'
 * in poi non lo rivedeva nessuno: un Wi-Fi arrivato a app aperta non
 * scaricava niente, e la ricerca degli indirizzi restava spenta fino a che il
 * sistema non uccideva il processo — mentre la barra di ricerca diceva "si
 * stanno ancora scaricando" e lo Stato dei dati "arrivano da soli col Wi-Fi".
 * Adesso il tentativo ha piu' di un innesco (avvio, ritorno in primo piano,
 * rete non a consumo che compare, un nuovo giro dopo un errore), e quello che
 * impedisce a tanti inneschi di diventare tanti download e' questa soglia.
 */
internal object PlacesRefreshPolicy {

    /** Ogni quanto si ricontrolla l'indice quando l'ultimo giro e' andato bene. */
    const val AFTER_SUCCESS_MS = 60L * 60 * 1000

    /**
     * Dopo un errore si riprova prima: il file manca, e la rete che ha fallito
     * un minuto fa (un Wi-Fi con la pagina di accesso, una galleria) spesso
     * non e' quella di cinque minuti dopo.
     */
    const val AFTER_FAILURE_MS = 5L * 60 * 1000

    /** Com'e' finito l'ultimo tentativo vero, cioe' quello che ha toccato la rete. */
    enum class Last { Never, Fine, Failed }

    enum class Verdict {
        /** Rete non a consumo: si prova. */
        Attempt,

        /** A consumo o senza rete e il file manca: si aspetta una rete non a consumo. */
        WaitForWifi,

        /** A consumo o senza rete ma il file c'e': quello in tasca basta, non si dice niente. */
        Leave,
    }

    /**
     * E' ora di un altro tentativo?
     *
     * I tentativi che non hanno toccato la rete — "sei a consumo, aspetto" —
     * non contano: il tentativo che aspettava un Wi-Fi deve poter partire nel
     * momento in cui il Wi-Fi arriva, non fra un'ora.
     *
     * [now] e [lastAttemptAt] sono letture dello stesso orologio monotono, in
     * millisecondi: la differenza non torna mai indietro, nemmeno se
     * l'utente cambia l'ora del telefono.
     */
    fun due(now: Long, lastAttemptAt: Long, last: Last): Boolean = when (last) {
        Last.Never -> true
        Last.Fine -> now - lastAttemptAt >= AFTER_SUCCESS_MS
        Last.Failed -> now - lastAttemptAt >= AFTER_FAILURE_MS
    }

    /**
     * Che cosa fare con la rete di adesso.
     *
     * @param metered `true` a consumo, `false` no, `null` se una rete per noi
     *   ancora non c'e' (vedi `Metered`).
     * @param ready il file dei luoghi e' gia' in tasca e in uso.
     */
    fun verdict(metered: Boolean?, ready: Boolean): Verdict = when {
        metered == false -> Verdict.Attempt
        // Undici megabyte non si scaricano sui dati mobili senza chiedere, e
        // qui non c'e' nessuno a cui chiedere: i luoghi sono un di piu', e
        // possono aspettare il Wi-Fi.
        ready -> Verdict.Leave
        else -> Verdict.WaitForWifi
    }
}
