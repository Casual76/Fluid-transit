package dev.antigravity.fluidtransit.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.data.rt.GtfsRtLite
import dev.antigravity.fluidtransit.data.rt.RealtimeClient
import dev.antigravity.fluidtransit.routing.AlertText
import kotlinx.coroutines.delay

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

    /** Dopo un download andato male si riprova presto: la rete torna in un attimo. */
    const val RETRY_MS = 30_000L

    /**
     * Quanto aspettare prima del prossimo giro.
     *
     * Gli avvisi delle schede si scaricavano UNA volta all'apertura: uno
     * sciopero annunciato alle 07:20 non compariva sulla fermata aperta alle
     * 07:00, e un download fallito in galleria lasciava "Avvisi non arrivati"
     * anche a rete tornata, fino a chiudere e riaprire la scheda. Dopo un
     * successo si aspetta come "Oggi" e la schermata Avvisi (la cache di
     * cinque minuti assorbe i giri in piu'); dopo un fallimento, o servendo
     * una lista vecchia, si riprova in fretta.
     */
    fun nextPollMs(feed: Feed): Long =
        if (feed.alerts == null || feed.staleSinceEpoch != null) RETRY_MS
        else RealtimeClient.ALERTS_POLL_MS

    /** Quello che l'ultimo giro ha riportato: `alerts` null = non scaricati. */
    class Feed(
        val alerts: List<GtfsRtLite.RtAlert>?,
        /** Da quando la lista e' vecchia, se l'ultimo tentativo e' fallito. */
        val staleSinceEpoch: Long?,
    )

    /** Cio' che una scheda disegna: le righe (null = non scaricati) e l'eta' se vecchie. */
    class View(val rows: List<String>?, val staleNote: String?) {
        companion object {
            val EMPTY = View(emptyList(), null)
        }
    }

    /**
     * Le righe di una scheda piu' la nota "Aggiornati alle...", se la lista
     * servita e' quella di un giro vecchio.
     *
     * Un dato vecchio si mostra ma dice di quando e' — la stessa regola dei
     * minuti, e la stessa frase di "Oggi" e della schermata Avvisi.
     */
    fun view(
        feed: Feed,
        lines: Map<Long, String>,
        nowEpoch: Long,
        withLineNames: Boolean,
        maxBodyChars: Int,
    ): View {
        val lista = feed.alerts ?: return View(null, null)
        return View(
            rows = rows(lista, lines, nowEpoch, withLineNames, maxBodyChars),
            staleNote = feed.staleSinceEpoch?.let { AlertText.stale(it, nowEpoch) },
        )
    }

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

/**
 * Il giro degli avvisi per le schede della mappa: scarica mentre una scheda
 * che li mostra e' aperta e la schermata e' davanti, e riprova da solo.
 *
 * Null finche' il primo giro non e' finito: chi legge non deve scambiare
 * "sto ancora scaricando" per "non sono arrivati".
 */
@Composable
internal fun rememberSheetAlertsFeed(app: FluidTransitApp, active: Boolean): State<SheetAlerts.Feed?> {
    val feed = remember { mutableStateOf<SheetAlerts.Feed?>(null) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val lista = app.realtime.fetchAlertsOrNull()
                val nuovo = SheetAlerts.Feed(lista, app.realtime.alertsStaleSinceEpoch())
                feed.value = nuovo
                delay(SheetAlerts.nextPollMs(nuovo))
            }
        }
    }
    return feed
}
