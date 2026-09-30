package dev.antigravity.fluidtransit.ui.map

/**
 * Quando rifare i viaggi perche' il tempo reale e' cambiato.
 *
 * Gli itinerari si calcolavano una volta e poi restavano quelli: aperto
 * "Parti ora" e lasciato li' dieci minuti, i ritardi erano quelli di quando lo
 * si era aperto, mentre tabelloni e schede si muovevano ogni dieci secondi.
 * Peggio per chi arriva da una notifica di routine con l'app chiusa: il
 * calcolo parte appena il bundle e' pronto, il realtime ancora non c'e', e
 * quando i ritardi arrivano l'elenco non si rifa' piu' — il dettaglio diceva
 * orari di tabella e nessun pallino live, mentre la notifica aveva calcolato
 * l'uscita con i ritardi.
 */
internal object JourneyLiveRefresh {

    /**
     * Al massimo un ricalcolo al minuto: un giro sono fino a otto scansioni e
     * i ritardi nuovi arrivano ogni 30-120 s, quindi ricalcolare a ogni
     * arrivo vorrebbe dire ricalcolare sempre.
     */
    const val MIN_GAP_MS = 60_000L

    /**
     * Il primo arrivo del tempo reale e' un'altra cosa: il calcolo era partito
     * SENZA, quindi l'elenco e' di sola tabella e ogni secondo in cui resta
     * cosi' e' un elenco sbagliato. Basta dare al calcolo il tempo di finire.
     */
    const val FIRST_LIVE_GAP_MS = 5_000L

    /**
     * Quanto aspettare prima di ricalcolare per dati nuovi, o null se non si
     * deve.
     *
     * Solo con l'ELENCO aperto, non col dettaglio di un viaggio: un elenco che
     * cambia sotto il viaggio che si sta guardando e' peggio di un ritardo di
     * un minuto. E solo se un calcolo e' gia' partito ([lastCalcAtMs] a zero
     * vuol dire che non c'e' niente da rifare: il prossimo partira' da solo,
     * con i dati di adesso).
     *
     * [hadLive] dice se quel calcolo aveva gia' del tempo reale.
     */
    fun waitMs(listOpen: Boolean, lastCalcAtMs: Long, nowMs: Long, hadLive: Boolean = true): Long? {
        if (!listOpen || lastCalcAtMs == 0L) return null
        val gap = if (hadLive) MIN_GAP_MS else FIRST_LIVE_GAP_MS
        return (lastCalcAtMs + gap - nowMs).coerceAtLeast(0L)
    }
}
