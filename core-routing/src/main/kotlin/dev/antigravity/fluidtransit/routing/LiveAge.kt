package dev.antigravity.fluidtransit.routing

/**
 * Quanto e' vecchio un dato dal vivo, ADESSO.
 *
 * Le eta' che il feed e lo stato si portano dietro sono quelle del momento in
 * cui sono state prese: "aggiornati 40 s fa" e' vero nell'istante del poll, e
 * dieci minuti dopo — se nessuno ha piu' scaricato niente — dice ancora 40.
 * Per l'assistente e' un difetto doppio: nessuno dei suoi strumenti scarica
 * (a differenza di mappa, widget e routine), quindi con l'app in secondo
 * piano legge snapshot di ore fa con l'eta' congelata, e la soglia "posizione
 * di X fa" oltre i 180 s non scatta mai. Qui l'eta' si riporta ad adesso.
 */
object LiveAge {

    /** Oltre questa eta' un rilevamento non e' piu' "dov'e' il bus": la mappa lo toglie, e gli strumenti non lo dicono. */
    const val STALE_SECONDS = 600

    /** Il tetto con cui `resolveRt` riporta lo snapshot ad adesso: oltre non e' latenza, e' un orologio sbagliato. */
    const val RESOLVE_CLAMP_SECONDS = 300L

    /**
     * L'eta' del feed ora, dato quella vista al poll.
     *
     * @param ageAtPoll l'eta' dell'origine quando e' stato fatto l'ultimo poll riuscito.
     * @param polledAtEpoch quando e' stato fatto, in secondi; null se non si sa.
     */
    fun feedNow(ageAtPoll: Long?, polledAtEpoch: Long?, nowEpoch: Long): Long? {
        if (ageAtPoll == null) return null
        val passed = if (polledAtEpoch == null) 0L else (nowEpoch - polledAtEpoch).coerceAtLeast(0L)
        return ageAtPoll + passed
    }

    /**
     * L'eta' del rilevamento di un mezzo ora.
     *
     * @param fixAgeAtResolve l'eta' che portava quando lo snapshot e' stato risolto (-1 = ignota,
     *   e ignota resta: non si inventa).
     * @param resolvedAtEpoch quando lo snapshot e' stato risolto contro il bundle.
     * @param generatedAtEpoch quando il proxy lo ha generato (0 = non lo dice).
     */
    fun fixNow(
        fixAgeAtResolve: Int,
        resolvedAtEpoch: Long,
        generatedAtEpoch: Long,
        nowEpoch: Long,
    ): Int {
        if (fixAgeAtResolve < 0) return -1
        val sinceResolve = (nowEpoch - resolvedAtEpoch).coerceAtLeast(0L)
        // La risoluzione sommava al rilevamento il tempo dalla generazione ma al massimo
        // RESOLVE_CLAMP_SECONDS: se lo snapshot era gia' piu' vecchio di cosi' quando e' stato
        // risolto, il resto non e' stato contato e va aggiunto.
        val clampedAway = if (generatedAtEpoch > 0) {
            ((resolvedAtEpoch - generatedAtEpoch) - RESOLVE_CLAMP_SECONDS).coerceAtLeast(0L)
        } else {
            0L
        }
        return (fixAgeAtResolve + sinceResolve + clampedAway).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Lo snapshot dei veicoli e' troppo vecchio per dire dove sono i mezzi?
     *
     * Con la data di generazione si usa quella; senza, si e' fermi a quando e' stato risolto.
     */
    fun snapshotStale(generatedAtEpoch: Long, resolvedAtEpoch: Long, nowEpoch: Long): Boolean {
        val since = if (generatedAtEpoch > 0) generatedAtEpoch else resolvedAtEpoch
        return nowEpoch - since > STALE_SECONDS
    }
}
