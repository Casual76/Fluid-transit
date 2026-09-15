package dev.antigravity.fluidtransit.data.departures

import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState
import dev.antigravity.fluidtransit.data.time.UiClock
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.DepartureBoard
import dev.antigravity.fluidtransit.routing.Departures
import dev.antigravity.fluidtransit.routing.LiveTimes
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * L'unica fonte delle prossime partenze, per tutta l'app.
 *
 * Prima ogni schermata se le calcolava per conto suo: sei `produceState`
 * diversi, sei battiti, sei idee di cosa mostrare. Oltre a dare numeri
 * discordanti, lo stesso lavoro si faceva quattro volte in parallelo — con la
 * scheda fermata aperta su una fermata stellata, quella fermata veniva
 * ricalcolata dalla mappa, da Oggi e dai Preferiti, ognuno al suo ritmo.
 *
 * Adesso chi vuole un tabellone chiede qui. Chi chiede la stessa fermata
 * riceve lo stesso identico flusso, quindi lo stesso identico numero: e'
 * `stateIn` a garantirlo, non la disciplina di chi scrive le schermate.
 *
 * ## Il giro dei ritardi
 *
 * Vive qui anche il polling. Prima lo facevano la mappa e la scheda Oggi,
 * ciascuna ogni trenta secondi e senza sapere dell'altra: aprendo Oggi con la
 * mappa viva si raddoppiava il consumo del budget richieste del proxy. E i
 * Preferiti avevano un battito ma non chiedevano niente, quindi ricalcolavano
 * all'infinito sugli stessi dati fermi. Ora c'e' un giro solo, vivo finche'
 * qualcuno guarda un tabellone.
 */
class DepartureBoards(private val app: FluidTransitApp) {

    private val cache = ConcurrentHashMap<Key, StateFlow<DepartureBoard>>()
    private val watchers = AtomicInteger(0)
    private var pump: Job? = null

    private data class Key(val stops: List<Int>, val limit: Int, val horizon: Int)

    /** Il tabellone di una fermata, che si aggiorna da solo. */
    fun board(
        stopIndex: Int,
        limit: Int = 10,
        horizonSeconds: Int = DEFAULT_HORIZON,
    ): StateFlow<DepartureBoard> = flowFor(Key(listOf(stopIndex), limit, horizonSeconds))

    /**
     * Le partenze di piu' fermate, mescolate per orario.
     *
     * E' quello che serve a "cosa passa qui intorno" e alla scheda Oggi: la
     * domanda non e' "cosa passa da ognuna di queste fermate".
     */
    fun merged(
        stops: List<Int>,
        limit: Int = 10,
        horizonSeconds: Int = DEFAULT_HORIZON,
    ): StateFlow<DepartureBoard> = flowFor(Key(stops.sorted(), limit, horizonSeconds))

    /**
     * Un tabellone, una volta sola.
     *
     * Per chi non puo' iscriversi a un flusso: il widget Glance, che vive il
     * tempo di disegnarsi, e gli strumenti dell'assistente.
     */
    suspend fun snapshot(
        stops: List<Int>,
        limit: Int = 10,
        horizonSeconds: Int = DEFAULT_HORIZON,
    ): DepartureBoard = withContext(Dispatchers.Default) {
        compute(Key(stops.sorted(), limit, horizonSeconds), Instant.now())
    }

    // --------------------------------------------------------------- interni

    private fun flowFor(key: Key): StateFlow<DepartureBoard> = cache.getOrPut(key) {
        combine(
            app.bundleManager.state,
            UiClock.ticks(),
            app.liveVersion,
        ) { _, _, _ -> Unit }
            .onStart { startPump() }
            .onCompletion { stopPump() }
            .map { withContext(Dispatchers.Default) { compute(key, Instant.now()) } }
            .stateIn(
                scope = app.applicationScope,
                // Cinque secondi di tolleranza: un cambio di scheda non deve
                // spegnere e riaccendere il giro dei ritardi, che al riavvio
                // spara subito una richiesta.
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                initialValue = empty(key),
            )
    }

    private fun compute(key: Key, now: Instant): DepartureBoard {
        val reader = readerOrNull() ?: return empty(key, now)
        val live = liveTimes()
        return if (key.stops.size == 1) {
            Departures.build(reader, key.stops[0], now, key.limit, key.horizon, live)
        } else {
            Departures.merged(reader, key.stops, now, key.limit, key.horizon, live)
        }
    }

    private fun readerOrNull(): BundleReader? =
        (app.bundleManager.state.value as? BundleState.Ready)?.reader

    private fun liveTimes(): LiveTimes = LiveFromFeed(
        delays = app.delayModel,
        canceled = app.canceledTrips.value,
        withVehicle = app.tripsWithVehicle.value,
    )

    private fun empty(key: Key, now: Instant = Instant.now()): DepartureBoard {
        val stop = key.stops.firstOrNull() ?: -1
        val name = if (key.stops.size == 1) {
            runCatching { readerOrNull()?.stopName(stop) }.getOrNull() ?: ""
        } else {
            ""
        }
        return DepartureBoard.empty(stop, name, now.epochSecond)
    }

    @Synchronized
    private fun startPump() {
        if (watchers.incrementAndGet() > 1) return
        pump = app.applicationScope.launch {
            while (true) {
                runCatching { app.realtime.refreshDelays() }
                delay(POLL_MS)
            }
        }
    }

    @Synchronized
    private fun stopPump() {
        if (watchers.decrementAndGet() > 0) return
        pump?.cancel()
        pump = null
    }

    companion object {
        /**
         * Due ore. Oltre, un tabellone smette di essere "i prossimi passaggi"
         * e diventa l'orario della linea, che e' un'altra schermata.
         */
        const val DEFAULT_HORIZON = 2 * 3600

        /**
         * Il ritmo dei ritardi. L'origine si rigenera ogni ~2 minuti e il
         * proxy tiene la risposta in cache 35 s: piu' spesso di cosi' si
         * chiederebbero byte che non sono cambiati.
         */
        const val POLL_MS = 30_000L
    }
}
