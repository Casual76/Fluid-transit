package dev.antigravity.fluidtransit.routing

/**
 * Quando dire "sei arrivato", e quando no.
 *
 * L'orario di discesa e' una previsione, non un fatto. Fino al 30/09 la
 * navigazione chiudeva il viaggio appena l'orologio raggiungeva l'ora di
 * discesa, e i due modi in cui l'orologio sbaglia erano entrambi nel verso
 * peggiore:
 *
 * - **bus in ritardo**: il feed ne segue il 76%; per il resto il ritardo e'
 *   zero per costruzione, e con cinque minuti di ritardo reale all'ora di
 *   tabella compariva "Scendi qui" forte e definitivo — il ciclo si
 *   fermava, il GPS si spegneva, e il vero "scendi alla prossima" non
 *   arrivava mai;
 * - **camminata finale**: le camminate del piano hanno epoche di
 *   pianificazione fisse, quindi un bus in ritardo di quanto dura la
 *   camminata la faceva risultare gia' finita nell'istante in cui si
 *   scendeva, e "Sei arrivato" partiva con seicento metri ancora da fare.
 *
 * Qui si sceglie la parte prudente: restare a bordo con una tolleranza
 * costa qualche minuto di frase non aggiornata, dichiarare l'arrivo in
 * anticipo costa una persona che si fida e scende dove non deve.
 */
object NavArrival {

    /**
     * Tolleranza oltre l'orario di discesa quando di quella corsa non
     * abbiamo una previsione per quella fermata (o la fermata e' saltata):
     * il bus puo' essere in ritardo e nessuno lo sa.
     */
    const val GRACE_SECONDS = 4 * 60L

    /**
     * Con la posizione che dice "sei ancora lontano" la tolleranza e' molto
     * piu' larga: e' l'unico segnale che non dipende ne' dall'orologio ne'
     * dal feed. Non e' infinita perche' chi e' sceso prima, o ha cambiato
     * idea, non deve restare per sempre su una card che dice "scendi".
     */
    const val GRACE_FAR_SECONDS = 20 * 60L

    /** Oltre questa distanza dalla discesa, a fix noto, non si e' arrivati. */
    const val FAR_METERS = 400

    /** Entro questa, la posizione dice che si e' arrivati: niente tolleranza. */
    const val NEAR_METERS = 300

    /**
     * Si resta "a bordo" anche se l'orario di discesa e' passato?
     *
     * @param lateSeconds quanto e' passato dall'orario di discesa (>= 0).
     * @param followed il feed ha una previsione per QUESTA fermata: il
     *   ritardo e' gia' dentro l'orario, e la tolleranza serve meno.
     * @param alightPassed il feed dichiara SERVITA la fermata di discesa: il
     *   bus e' davvero andato oltre, non c'e' niente da aspettare.
     * @param alightSkipped il feed dichiara SALTATA la fermata di discesa:
     *   "sei a DEST" sarebbe falso anche con l'orologio giusto.
     * @param metersToAlight metri dalla discesa, -1 se la posizione non si sa
     *   (modo Bilanciato, o fix vecchio o impreciso).
     */
    fun holdRide(
        lateSeconds: Long,
        followed: Boolean,
        alightPassed: Boolean,
        alightSkipped: Boolean,
        metersToAlight: Int,
    ): Boolean {
        if (lateSeconds < 0) return true
        if (alightPassed) return false
        if (metersToAlight in 0..NEAR_METERS) return false
        if (metersToAlight > FAR_METERS) return lateSeconds < GRACE_FAR_SECONDS
        // Senza posizione, o sulla soglia fra vicino e lontano: conta solo
        // quanto ci si puo' fidare dell'orologio.
        return if (!followed || alightSkipped) lateSeconds < GRACE_SECONDS else false
    }

    /**
     * Da quando decorre la camminata che segue una corsa.
     *
     * Le camminate del piano portano l'epoca in cui il piano faceva scendere
     * dal bus; se il bus arriva dopo, la camminata comincia dopo. [alightedAt]
     * e' l'istante in cui si e' scesi davvero (il piu' tardo fra l'orario di
     * discesa e il giro in cui ce ne siamo accorti), zero se la camminata non
     * segue una corsa che abbiamo visto finire.
     */
    fun walkStart(planStart: Long, alightedAt: Long): Long =
        if (alightedAt > 0L) alightedAt else planStart

    /** Una fermata dove scendere al posto di quella saltata. */
    class Alternative(val position: Int, val before: Boolean)

    /**
     * La fermata piu' vicina a [alight] dove il bus si ferma davvero: prima
     * la piu' vicina PRIMA (fra [from] e [alight], escluse), poi quella
     * dopo. Null se il feed le dichiara saltate tutte.
     *
     * Prima quella prima: e' la sola che si raggiunge senza che il bus si
     * sia gia' portato via.
     */
    fun alternativeStop(
        skipped: (position: Int) -> Boolean,
        from: Int,
        alight: Int,
        stopCount: Int,
    ): Alternative? {
        var p = alight - 1
        while (p >= from) {
            if (!skipped(p)) return Alternative(p, before = true)
            p--
        }
        p = alight + 1
        while (p < stopCount) {
            if (!skipped(p)) return Alternative(p, before = false)
            p++
        }
        return null
    }

    /**
     * La riga sotto "Scendi a X" quando l'orario e' passato e non siamo
     * ancora arrivati: si dice la verita' — non lo sappiamo — e si manda a
     * guardare fuori.
     */
    fun overdueLine(): String =
        "l'orario e' passato: guarda fuori, il bus potrebbe essere in ritardo"
}
