package dev.antigravity.fluidtransit.routing

/**
 * Lo scarto fra lo `stop_sequence` del feed e la posizione nel pattern.
 *
 * GTFS lascia libero lo `stop_sequence`: deve solo crescere. Il bundle invece
 * indicizza le fermate di un pattern da 0 e i numeri originali li butta, dopo
 * averli usati per ordinare. Il feed di at, misurato, parte da 1 — ma "parte
 * da 1" non e' un contratto, e' un'osservazione, e attribuire un ritardo alla
 * fermata sbagliata e' un errore che non si vede: i minuti restano
 * plausibili, solo appartengono a un'altra fermata. E' gia' costato una
 * fermata di scarto su tutto il tabellone una volta.
 *
 * Quindi lo scarto non si assume: si VERIFICA. Il proxy manda, per ogni
 * corsa, i 32 bit bassi dell'hash dello `stop_id` della prima e dell'ultima
 * previsione; qui si cerca lo scarto che porta quelle due sequenze proprio su
 * quelle due fermate. Se si trova, vale per tutte le fermate in mezzo, perche'
 * lo `stop_sequence` e' monotono per definizione e i due estremi fissano una
 * corrispondenza affine. Se non si trova, si risponde null e chi chiama
 * ripiega sulla stima — che sara' meno precisa ma non sara' sbagliata.
 */
object StopSequenceMapping {

    /**
     * Lo scarto `seq - posizione`, o null se non si riesce a verificarlo.
     *
     * [stopId32At] sono i 32 bit bassi dell'hash dello `stop_id` della fermata
     * in quella posizione del pattern. Le ancore a 0 vogliono dire "il feed non
     * ha dichiarato lo stop_id": senza almeno la prima non si puo' verificare
     * niente, e si risponde null.
     */
    fun offset(
        stopCount: Int,
        firstSeq: Int,
        firstStopId32: Int,
        lastSeq: Int,
        lastStopId32: Int,
        stopId32At: (position: Int) -> Int,
    ): Int? {
        if (stopCount <= 0 || firstSeq < 0) return null
        if (firstStopId32 == 0) return null

        // I candidati: tutte le posizioni del pattern che portano l'ancora di
        // testa. Di solito e' una sola; su una tratta che passa due volte
        // dalla stessa fermata sono due, e allora decide l'ancora di coda.
        for (position in 0 until stopCount) {
            if (stopId32At(position) != firstStopId32) continue
            val candidate = firstSeq - position
            if (verifies(candidate, stopCount, lastSeq, lastStopId32, stopId32At)) return candidate
        }
        return null
    }

    private fun verifies(
        offset: Int,
        stopCount: Int,
        lastSeq: Int,
        lastStopId32: Int,
        stopId32At: (position: Int) -> Int,
    ): Boolean {
        // Senza ancora di coda si accetta lo scarto trovato in testa: e' meno
        // di una verifica, ma succede solo quando la corsa ha una previsione
        // sola, e li' non c'e' un "in mezzo" da sbagliare.
        if (lastStopId32 == 0 || lastSeq < 0) return true
        val position = lastSeq - offset
        if (position < 0 || position >= stopCount) return false
        return stopId32At(position) == lastStopId32
    }
}
