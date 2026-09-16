package dev.antigravity.fluidtransit.data.departures

import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState
import dev.antigravity.fluidtransit.data.rt.RtPredictionSet
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
     *
     * **L'ordine di [stops] conta**, quindi non si riordina per fare una
     * chiave piu' compatta: e' l'ordine di preferenza con cui `Departures`
     * decide da quale fermata mostrare un autobus che ne tocca piu' d'una.
     * Riordinando, "qui intorno" tornava a mostrarlo dal palo con l'indice
     * piu' basso invece che dal piu' vicino — cioe' da uno a caso.
     */
    fun merged(
        stops: List<Int>,
        limit: Int = 10,
        horizonSeconds: Int = DEFAULT_HORIZON,
    ): StateFlow<DepartureBoard> = flowFor(Key(stops, limit, horizonSeconds))

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
        compute(Key(stops, limit, horizonSeconds), Instant.now())
    }

    // --------------------------------------------------------------- interni

    /**
     * I tabelloni che nessuno guarda piu' si buttano.
     *
     * La cache non toglieva mai una riga, e "qui intorno" ne fabbrica una
     * nuova ogni volta che ci si sposta di duecento metri: girando per la
     * regione le chiavi si accumulavano per tutta la vita del processo. Non
     * si vedeva — un tabellone da dodici righe sono pochi kilobyte — ma non
     * aveva tetto.
     *
     * Chi guarda non si conta: lo dice gia' il tabellone. Un flusso vivo si
     * ricalcola a ogni battito dell'orologio, cioe' ogni dieci secondi, e
     * `computedAtEpoch` e' l'ora dell'ultimo ricalcolo; un flusso che nessuno
     * collezona smette di ricalcolarsi — e' `WhileSubscribed` a fermarlo — e
     * quel numero resta indietro. Cinque minuti di ritardo vogliono dire che
     * nessuno guarda quel tabellone da cinque minuti.
     *
     * Il riferimento debole, che sarebbe la soluzione elegante, non
     * funzionerebbe: il flusso lo tiene vivo la coroutine che `stateIn`
     * lancia nello scope dell'Application, e quella non finisce mai.
     */
    private fun evictStale() {
        val nowEpoch = System.currentTimeMillis() / 1000
        val it = cache.entries.iterator()
        while (it.hasNext()) {
            val computedAt = it.next().value.value.computedAtEpoch
            if (computedAt > 0 && nowEpoch - computedAt > IDLE_SECONDS) it.remove()
        }
    }

    private fun flowFor(key: Key): StateFlow<DepartureBoard> {
        // Solo quando si crea una chiave nuova: e' li' che la cache cresce, e
        // una passata su qualche decina di voci non costa niente.
        if (!cache.containsKey(key)) evictStale()
        return cacheFlow(key)
    }

    private fun cacheFlow(key: Key): StateFlow<DepartureBoard> = cache.getOrPut(key) {
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
        if (key.stops.size != 1) {
            return Departures.merged(reader, key.stops, now, key.limit, key.horizon, live)
        }

        // Il tabellone di UNA banchina e' il tabellone della fermata intera.
        //
        // Chi tocca una fermata sulla mappa vuole sapere cosa passa di li', non
        // cosa passa da quel palo: le due direzioni sono due fermate separate
        // nel feed (e senza `parent_station` che le leghi), e la destinazione
        // in ogni riga dice gia' quale sia quale. Prima bisognava aprirle tutte
        // e due per sapere quando passa il bus.
        val stop = key.stops[0]
        val siblings = app.stopGroups.value?.siblings(stop)
        if (siblings == null || siblings.size <= 1) {
            return Departures.build(reader, stop, now, key.limit, key.horizon, live)
        }
        // La banchina chiesta per prima: se una corsa tocca due banchine
        // dello stesso gruppo — capita ai capolinea, dove il bus arriva su
        // un palo e riparte dall'altro — si mostra quella che si e' toccata.
        val ordinate = listOf(stop) + siblings.filter { it != stop }
        val merged = Departures.merged(reader, ordinate, now, key.limit, key.horizon, live)
        return DepartureBoard(
            stopIndex = stop,
            stopName = reader.stopName(stop),
            computedAtEpoch = merged.computedAtEpoch,
            rows = merged.rows,
            outsideValidity = merged.outsideValidity,
        )
    }

    private fun readerOrNull(): BundleReader? =
        (app.bundleManager.state.value as? BundleState.Ready)?.reader

    /**
     * Cosa si sa del tempo reale, adesso.
     *
     * Le previsioni per fermata quando ci sono, il modello dei ritardi come
     * ripiego sotto. Non e' un o-l'uno-o-l'altro: le previsioni coprono le
     * corse che il feed sta seguendo, e per tutte le altre vale il modello,
     * dentro la stessa interrogazione.
     *
     * E' pubblico perche' i tabelloni non sono l'unico posto che mostra un
     * orario: la scheda linea e la scheda corsa mostrano gli stessi orari
     * visti dall'altro lato — non "cosa passa di qui" ma "dove passa questo"
     * — e leggevano il solo modello dei ritardi, senza le previsioni per
     * fermata. Lo stesso bus, alla stessa fermata, poteva dire due numeri
     * diversi a seconda di quale scheda avevi aperto.
     */
    fun live(): LiveTimes = liveTimes()

    private fun liveTimes(): LiveTimes {
        val base = LiveFromFeed(
            delays = app.delayModel,
            canceled = app.canceledTrips.value,
            withVehicle = app.tripsWithVehicle.value,
        )
        val grezzo = app.realtime.predictions.value ?: return base
        val gia = app.livePredictions.value
        if (gia != null && gia.set === grezzo) return gia
        return risolvi(grezzo, base)
    }

    /**
     * Le previsioni risolte adesso, se nessuno le ha ancora risolte.
     *
     * Chi le risolve di solito e' un collettore dell'Application, che gira
     * per conto suo appena il proxy consegna uno snapshot nuovo. Ma il widget
     * Glance vive dentro una ricevente: sveglia il processo, chiede il
     * refresh, e disegna. Nel processo appena nato il collettore non ha
     * ancora avuto il suo turno, quindi il widget leggeva "nessuna previsione
     * risolta" e ripiegava sulla stima — con il risultato, visto sulla home,
     * di un "stimato" accanto a un passaggio che nell'app diceva "dal bus".
     * Lo stesso bus, la stessa fermata, due parole diverse a due centimetri
     * di distanza.
     *
     * Risolvere qui costa una scansione delle corse del feed, qualche
     * millisecondo, e succede una volta per snapshot: il risultato si
     * pubblica dove tutti lo leggono.
     */
    @Synchronized
    private fun risolvi(grezzo: RtPredictionSet, base: LiveTimes): LiveTimes {
        val gia = app.livePredictions.value
        if (gia != null && gia.set === grezzo) return gia
        val reader = readerOrNull() ?: return base
        val risolte = runCatching {
            LiveFromPredictions.resolve(
                reader = reader,
                set = grezzo,
                fallback = base,
                canceledTrips = app.canceledTrips.value,
                withVehicle = app.tripsWithVehicle.value,
            )
        }.getOrNull() ?: return base
        app.livePredictions.value = risolte
        return risolte
    }

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
                // Le previsioni per fermata viaggiano insieme ai ritardi: se
                // il proxy le serve valgono quelle, altrimenti vale il
                // modello. Chi chiede non deve sapere quale delle due.
                runCatching { app.realtime.refreshPredictions() }
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

        /**
         * Da quanto un tabellone deve essere fermo per essere buttato.
         *
         * Cinque minuti: un flusso vivo si ricalcola ogni dieci secondi,
         * quindi cinque minuti di fermo sono trenta battiti mancati. Largo
         * abbastanza da non buttare mai niente che qualcuno stia guardando,
         * stretto abbastanza da non tenere in giro una giornata di panoramiche.
         */
        const val IDLE_SECONDS = 300L
    }
}
