package dev.antigravity.fluidtransit.data.nav

/**
 * Quando un fix GPS dice dove sei, e quando e' solo un'ipotesi.
 *
 * Il servizio usava `latitude` e `longitude` come un punto esatto e guardava
 * solo quanto il fix fosse VECCHIO. Ma con la sola posizione approssimativa
 * (accettata dall'app) `NETWORK_PROVIDER` su Android 12+ da' un punto
 * sfumato a qualche chilometro e lo tiene fermo: "350 m" in camminata non
 * voleva dire niente, e a bordo la soglia dei 300 m per "Stai per arrivare"
 * scattava a molte fermate dalla discesa — o non scattava mai. Vale anche
 * col GPS vero: duecento metri di errore in un canyon urbano bastano a far
 * suonare l'avviso dalla parte sbagliata del quartiere.
 *
 * Meglio nessuna distanza che una distanza inventata: gli avvisi che non
 * dipendono dalla posizione (orari e feed) continuano a funzionare.
 */
object NavFix {

    /** Oltre questo errore dichiarato, un fix non dice piu' in che via sei. */
    const val MAX_ACCURACY_M = 100f

    /** Oltre due minuti, un fix non dice piu' dove sei. */
    const val MAX_AGE_NANOS = 120_000_000_000L

    /**
     * @param hasAccuracy il provider dichiara un'accuratezza; senza, non si
     *   sa quanto fidarsi e si scarta.
     */
    fun usable(ageNanos: Long, hasAccuracy: Boolean, accuracyM: Float): Boolean =
        ageNanos <= MAX_AGE_NANOS && hasAccuracy && accuracyM <= MAX_ACCURACY_M
}
