package dev.antigravity.fluidtransit.ui.map

import dev.antigravity.fluidtransit.data.rt.GtfsRtLite
import dev.antigravity.fluidtransit.routing.AlertText

/**
 * Gli avvisi che una scheda dice a chi la guarda: fermata, linea, corsa, viaggio.
 *
 * Le quattro schede avevano ognuna la sua copia dello stesso filtro, e tutte
 * e quattro tenevano solo cio' che era GIA' cominciato. Uno sciopero
 * annunciato per le 08:30, guardando la fermata alle 07:40, non compariva: la
 * scheda taceva, mentre "Oggi" e la schermata degli Avvisi lo dicevano con le
 * stesse parole di sempre — "oggi alle 08:30". Chi e' alla fermata e' proprio
 * quello a cui serve saperlo prima di salire su un bus che non passera'.
 *
 * La regola sola sta in `AlertText.relevant`, la stessa di "Oggi": quello che
 * e' in corso e quello che comincia entro l'orizzonte, non di piu'. Qui resta
 * quello che e' di una scheda: le linee sono il criterio — il feed non nomina
 * mai le fermate, e un avviso di rete (senza linee) non riguarda questa
 * scheda piu' di un'altra — e l'ordine in cui si leggono.
 */
internal object SheetAlerts {

    /**
     * Le righe di una scheda, nell'ordine in cui vanno lette.
     *
     * [lines] sono le linee della scheda (hash -> nome): un avviso entra solo
     * se ne nomina almeno una. Con [withLineNames] la riga comincia col nome
     * delle linee toccate — utile dove le linee sono piu' d'una, come su una
     * fermata o su un viaggio; sulla scheda di UNA linea sarebbe ripetere il
     * titolo.
     *
     * Prima quelli in corso, il piu' recente per primo: su una fermata di
     * stazione ce ne sono venti, e due sole righe stanno in cima. Poi quelli
     * che devono cominciare, dal piu' vicino. Non si ordina tutto per inizio
     * decrescente: un avviso di domani ha l'inizio piu' grande di tutti, e
     * spingerebbe fuori dalle due righe la deviazione che c'e' adesso.
     *
     * Di quelli che cominciano si scrive il periodo, davanti: le righe stanno
     * su due linee e si tagliano in fondo, e il quando e' proprio quello per
     * cui l'avviso si legge. Quelli in corso non lo ripetono: e' "adesso".
     */
    fun rows(
        alerts: List<GtfsRtLite.RtAlert>,
        lines: Map<Long, String>,
        nowEpoch: Long,
        withLineNames: Boolean,
        maxBodyChars: Int,
    ): List<String> {
        val delleLinee = alerts.filter { a ->
            a.routeHashes.any { lines.containsKey(it) } &&
                AlertText.relevant(a.startEpoch, a.endEpoch, nowEpoch)
        }
        val (inCorso, prossimi) = delleLinee.partition { it.startEpoch <= nowEpoch }
        val ordinati = inCorso.sortedByDescending { it.startEpoch } +
            prossimi.sortedBy { it.startEpoch }
        return ordinati.map { a ->
            val testo = a.header.ifEmpty { AlertText.body(a.description).take(maxBodyChars) }
            val quali = if (withLineNames) {
                a.routeHashes.mapNotNull { lines[it] }.distinct().take(3)
            } else {
                emptyList()
            }
            val riga = if (quali.isEmpty()) testo else quali.joinToString(", ") + " · " + testo
            val periodo = if (a.startEpoch > nowEpoch) {
                AlertText.period(a.startEpoch, a.endEpoch, nowEpoch)
            } else {
                null
            }
            if (periodo != null) "$periodo · $riga" else riga
        }
    }
}
