package dev.antigravity.fluidtransit.data.update

/**
 * Quando ricontrollare se e' uscita una versione nuova.
 *
 * Il controllo girava una volta sola, in `Application.onCreate`, e non
 * tornava piu'. Due conseguenze, tutte e due viste: un telefono che tiene
 * l'app in memoria per giorni — il caso normale — non vedeva mai la capsula
 * "Aggiornamento disponibile", che per un'app fuori dagli store e' l'unico
 * avviso; e se quell'unico giro cadeva nella frazione di secondo in cui la
 * rete e' ancora chiusa al processo appena nato, il controllo falliva per
 * tutta la sessione e Impostazioni mostrava l'eccezione.
 *
 * Quindi si ricontrolla al ritorno in primo piano e quando la rete torna, ma
 * con un intervallo: il manifest cambia poche volte l'anno, e una richiesta a
 * ogni apertura sarebbe rumore. Un controllo FALLITO invece non vale sei ore
 * di silenzio (come per gli orari: un giro che non ha controllato niente non
 * e' un controllo), e si riprova presto.
 */
object UpdateCheckPolicy {

    /** Dopo un controllo riuscito: lo stesso ritmo del manifest remoto. */
    const val INTERVAL_MS = 6L * 60 * 60 * 1000

    /**
     * Dopo un controllo fallito. Abbastanza lungo da non martellare con la
     * rete che va e viene, abbastanza corto da non lasciare una sessione
     * intera senza risposta per un errore di un istante.
     */
    const val RETRY_AFTER_FAILURE_MS = 2L * 60 * 1000

    /**
     * @param lastCheckAtMs quando e' finito l'ultimo controllo, o null se
     *   non ne e' mai finito uno in questo processo.
     * @param lastCheckOk com'e' andato.
     */
    fun due(lastCheckAtMs: Long?, lastCheckOk: Boolean, nowMs: Long): Boolean {
        val last = lastCheckAtMs ?: return true
        val elapsed = nowMs - last
        // L'orologio del telefono e' tornato indietro: non si sa quanto sia
        // passato, e meglio un controllo in piu' che una sessione senza.
        if (elapsed < 0) return true
        return elapsed >= if (lastCheckOk) INTERVAL_MS else RETRY_AFTER_FAILURE_MS
    }
}
