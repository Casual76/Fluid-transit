package dev.antigravity.fluidtransit.data.ai

import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.ai.orchestrator.ActionExecutor
import dev.antigravity.fluidtransit.ai.tools.ActionOutcome
import dev.antigravity.fluidtransit.ai.tools.AssistantAction
import dev.antigravity.fluidtransit.ai.tools.LiveVehicle
import dev.antigravity.fluidtransit.ai.tools.NamedPoint
import dev.antigravity.fluidtransit.ai.tools.RouteHit
import dev.antigravity.fluidtransit.ai.tools.RoutineInfo
import dev.antigravity.fluidtransit.ai.tools.SavedPlaceInfo
import dev.antigravity.fluidtransit.ai.tools.StarredRoute
import dev.antigravity.fluidtransit.ai.tools.StarredStop
import dev.antigravity.fluidtransit.ai.tools.StopHit
import dev.antigravity.fluidtransit.ai.tools.TransitBridge
import dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState
import dev.antigravity.fluidtransit.data.places.PlacesManager
import dev.antigravity.fluidtransit.data.routines.Routines
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.DelayModel
import dev.antigravity.fluidtransit.routing.LiveAge
import dev.antigravity.fluidtransit.routing.PlacesSearch
import dev.antigravity.fluidtransit.routing.Raptor
import dev.antigravity.fluidtransit.routing.Reference
import dev.antigravity.fluidtransit.routing.TripProgress
import dev.antigravity.fluidtransit.routing.Words
import dev.antigravity.fluidtransit.ui.map.ResolvedRt
import dev.antigravity.fluidtransit.ui.map.SearchIndex
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Il ponte fra l'assistente e l'app.
 *
 * Sta qui e non in `:core-ai` perche' e' l'unico punto in cui i due mondi si
 * toccano: il modulo dell'assistente non sa niente di Compose, di MapLibre o
 * di come e' fatta questa schermata; l'app non sa niente di provider e di
 * cicli di strumenti.
 *
 * Le tre cose che cambiano mentre l'app vive — l'indice di ricerca, lo
 * snapshot realtime, dove sono la persona e la mappa — arrivano da fuori:
 * la schermata mappa le deposita qui appena le ha.
 */
class AssistantBridge(private val app: FluidTransitApp) : TransitBridge, ActionExecutor {

    /** L'indice di fermate e linee, dall'Application: non dipende da nessuna schermata. */
    private val searchIndex: SearchIndex? get() = app.searchIndex.value

    // Senza indice — ancora in costruzione dopo l'attesa, o fallito — la
    // ricerca per nome non c'e', e gli strumenti devono dirlo invece di
    // rispondere "non trovo".
    override val searchAvailable: Boolean get() = app.searchIndex.value != null

    /**
     * L'ultimo snapshot realtime risolto.
     *
     * Lo depositava la schermata mappa, e quindi chi parlava con l'assistente
     * senza averla mai aperta non aveva ne' bus vivi ne' ritardi dentro i
     * viaggi calcolati: le risposte erano quelle di tabella, senza dirlo. Qui
     * si risolve da se', e si tiene finche' lo snapshot non cambia — l'uguale
     * e' per identita' perche' i due flussi pubblicano un oggetto nuovo solo
     * quando c'e' davvero qualcosa di nuovo.
     */
    private var resolvedFromVehicles: Any? = null
    private var resolvedFromDelays: Any? = null
    private var resolvedCache: ResolvedRt? = null

    /**
     * Quando lo snapshot e' stato risolto, in secondi. Serve a riportare ad
     * adesso l'eta' dei rilevamenti: `fixAgeSec` dentro la cache e' quella del
     * momento in cui e' stata costruita, e la cache vive finche' nessuno
     * scarica altro — con l'app in secondo piano, ore.
     */
    private var resolvedAtEpoch: Long = 0L

    @get:Synchronized
    private val resolved: ResolvedRt?
        get() {
            val r = reader ?: return null
            val v = app.realtime.vehicles.value ?: return null
            val d = app.realtime.delays.value
            if (resolvedCache != null && resolvedFromVehicles === v && resolvedFromDelays === d) {
                return resolvedCache
            }
            val out = runCatching {
                dev.antigravity.fluidtransit.ui.map.resolveRt(r, v, d)
            }.getOrNull()
            resolvedFromVehicles = v
            resolvedFromDelays = d
            resolvedCache = out
            resolvedAtEpoch = Instant.now().epochSecond
            return out
        }

    /** Dove si trova la persona, secondo il GPS della mappa. */
    @Volatile
    var location: (() -> Pair<Double, Double>?)? = null

    /** Il centro della mappa che sta guardando. */
    @Volatile
    var camera: (() -> Pair<Double, Double>?)? = null

    /**
     * Un'azione che solo la mappa sa fare, con la risposta di chi la fa.
     *
     * Era un flusso di sole azioni, e `tryEmit` torna true appena l'evento e'
     * nel buffer — anche senza nessuno in ascolto: la mappa non c'era (scheda
     * Oggi, assistente esterno) o non riusciva a fare niente (navigazione di
     * notte, routine senza posizione) e l'assistente diceva "fatto" lo stesso.
     * Adesso chi esegue risponde con un esito, e senza nessuno che risponde
     * l'esito e' un fallimento.
     */
    class MapRequest(val action: AssistantAction) {
        val result = CompletableDeferred<ActionOutcome>()
    }

    private val mapRequests = MutableSharedFlow<MapRequest>(extraBufferCapacity = 8)
    val requests: SharedFlow<MapRequest> = mapRequests

    /** Una routine e' stata creata dal ponte: la mappa, se c'e', chiede il permesso delle notifiche. */
    private val routineCreatedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val routineCreated: SharedFlow<Unit> = routineCreatedFlow

    /** Posti salvati o stelle cambiati dal ponte: la mappa rilegge i suoi suggerimenti. */
    private val dataChangedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val dataChanged: SharedFlow<Unit> = dataChangedFlow

    // ---------------------------------------------------------- TransitBridge

    override val reader: BundleReader?
        get() = (app.bundleManager.state.value as? BundleState.Ready)?.reader

    override val places: PlacesSearch?
        get() = (app.placesManager.state.value as? PlacesManager.State.Ready)?.search

    override val delays: DelayModel get() = app.delayModel

    /**
     * La stessa fonte delle quattro schermate e del widget, niente di meno.
     *
     * Con un palo solo `DepartureBoards.compute` estende da se' il tabellone a tutte le
     * banchine del gruppo, mettendo per prima quella chiesta: e' la regola della scheda
     * fermata, quindi chi chiama passa il palo trovato e non deve espandere i fratelli.
     */
    override suspend fun board(
        stopIndex: Int,
        limit: Int,
        horizonSeconds: Int,
    ) = app.departureBoards.snapshot(listOf(stopIndex), limit, horizonSeconds)

    override val here: Pair<Double, Double>? get() = location?.invoke()

    override val looking: Pair<Double, Double>? get() = camera?.invoke()

    override suspend fun plan(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        departAtEpoch: Long?,
        arriveByEpoch: Long?,
    ): List<Raptor.Journey> {
        val r = reader ?: return emptyList()
        val rt = resolved
        // Il numero unico per corsa solo se e' ancora buono: vedi delaysFresh.
        val fresco = app.realtime.delaysFresh()
        val live = if (rt != null) {
            Raptor.Realtime(
                if (fresco) rt.delayByTrip else emptyMap(),
                if (fresco) rt.canceledTrips else emptySet(),
                java.time.Instant.now().epochSecond,
                // Le stesse previsioni del tabellone e della mappa: se
                // l'assistente calcolasse su un altro numero, direbbe a voce
                // un orario diverso da quello scritto due centimetri sopra.
                live = app.departureBoards.live(),
            )
        } else {
            Raptor.Realtime.NONE
        }
        // RAPTOR e' single-thread per costruzione (lo scratch e' riusato):
        // gli strumenti girano in parallelo, il motore no.
        return withContext(app.routingDispatcher) {
            val raptor = app.raptorFor(r)
            val from = Raptor.Place(fromLat, fromLon)
            val to = Raptor.Place(toLat, toLon)
            when {
                arriveByEpoch != null ->
                    raptor.planArriveBy(
                        from, to, Instant.ofEpochSecond(arriveByEpoch), live,
                        notBefore = Instant.now(),
                    )

                else -> raptor.plan(
                    from, to,
                    Instant.ofEpochSecond(departAtEpoch ?: (System.currentTimeMillis() / 1000)),
                    live,
                )
            }
        }
    }

    override fun findStops(query: String, limit: Int): List<StopHit> {
        val r = reader ?: return emptyList()
        val ref = referencePoint()
        return searchIndex
            ?.search(query, limit * 2, ref?.first ?: Double.NaN, ref?.second ?: Double.NaN)
            .orEmpty()
            .filterIsInstance<SearchIndex.Hit.Stop>()
            .take(limit)
            .map {
                StopHit(
                    idHashHex = java.lang.Long.toHexString(r.stopIdHash(it.stopIndex)),
                    stopIndex = it.stopIndex,
                    name = it.title,
                    lat = it.lat,
                    lon = it.lon,
                )
            }
    }

    /**
     * Le linee per nome, col punto di riferimento della ricerca.
     *
     * Senza, il bonus di vicinanza non scattava mai e "il 6" da Firenze dava
     * il 6 che capitava per primo a parita' di punteggio — quello di Empoli
     * — mentre la barra di ricerca, che il riferimento lo passa, dava quello
     * giusto: l'assistente e la barra rispondevano in modo diverso alla
     * stessa parola, che e' proprio quello che questo ponte promette di non
     * fare (vedi [TransitBridge.findStops]).
     */
    override fun findRoutes(query: String, limit: Int): List<RouteHit> {
        val ref = referencePoint()
        return searchIndex
            ?.search(query, limit * 3, ref?.first ?: Double.NaN, ref?.second ?: Double.NaN)
            .orEmpty()
            .filterIsInstance<SearchIndex.Hit.Route>()
            .take(limit)
            .map { RouteHit(it.routeIndex, it.title, it.destination) }
    }

    override fun siblings(stopIndex: Int): IntArray =
        app.stopGroups.value?.siblings(stopIndex)?.takeIf { it.isNotEmpty() } ?: intArrayOf(stopIndex)

    // Ultima volta che il ponte ha chiesto i feed, in ms: un giro ogni tanto, non uno per domanda.
    @Volatile
    private var liveAskedAt = 0L

    @Volatile
    private var vehiclesAskedAt = 0L

    /**
     * Chiede i feed prima di rispondere, come fanno widget e routine.
     *
     * Dei gestori dei feed (mappa, pompa dei tabelloni, widget, routine,
     * navigazione) nessuno lavora quando a chiedere e' l'assistente con l'app
     * chiusa o in secondo piano: in un processo nuovo il realtime e' a
     * SCHEDULE_ONLY e senza snapshot, e "quando passa il 6" tornava tutto
     * "orario da tabella" senza che nessuno avesse provato a chiedere. Con un
     * tetto di tempo (il feed e' un'aggiunta, non una condizione) e non piu'
     * di un giro ogni tanto: un secondo "quando passa" di seguito non costa
     * un'altra richiesta.
     */
    override suspend fun ensureLive(vehicles: Boolean) {
        val now = System.currentTimeMillis()
        val serveRitardi = now - liveAskedAt > LIVE_FRESH_MS
        val serveMezzi = vehicles && now - vehiclesAskedAt > LIVE_FRESH_MS
        if (!serveRitardi && !serveMezzi) return
        if (serveRitardi) liveAskedAt = now
        if (serveMezzi) vehiclesAskedAt = now
        // I giri partono nello scope dell'app e qui si aspetta soltanto: la
        // richiesta di rete e' una chiamata bloccante (callTimeout 30 s) e non
        // si interrompe, quindi un tetto messo attorno a un coroutineScope
        // aspettava comunque i figli fino alla fine. `join` invece e'
        // cancellabile: dopo LIVE_WAIT_MS si torna, e i giri finiscono da soli.
        val giri = buildList {
            if (serveRitardi) {
                // In fila e non in parallelo: fetchPredictions esce subito se
                // la sorgente non e' ancora PROXY, e in un processo freddo a
                // stabilirla e' il giro dei ritardi (vedi fetchDelays).
                add(
                    app.applicationScope.launch {
                        runCatching { app.realtime.refreshDelays() }
                        runCatching { app.realtime.refreshPredictions() }
                    },
                )
            }
            if (serveMezzi) {
                add(app.applicationScope.launch { runCatching { app.realtime.refreshVehicles() } })
            }
        }
        // Niente runCatching attorno: la cancellazione di chi chiede deve passare.
        withTimeoutOrNull(LIVE_WAIT_MS) { giri.joinAll() }
    }

    /**
     * I mezzi di una linea, con la fermata verso cui stanno andando.
     *
     * Il nome della prossima fermata era sempre nullo, e lo strumento
     * prometteva nella sua descrizione di darlo: chiedendo "dov'e' il 6"
     * l'assistente rispondeva con una distanza in linea d'aria — "a 1,2 km
     * da te" — che e' il numero meno utile fra quelli che aveva. Adesso dice
     * la fermata, e la sceglie con la stessa regola della scheda della corsa,
     * cosi' a voce e sullo schermo non escono due fermate diverse.
     */
    override fun vehiclesOfRoute(routeIndex: Int): List<LiveVehicle>? {
        val r = reader ?: return null
        val rt = resolved ?: return null
        val now = System.currentTimeMillis() / 1000
        val generatedAt = app.realtime.vehicles.value?.generatedAt ?: 0L
        // Uno snapshot di ore fa non dice dove sono i bus adesso: "non lo so",
        // che e' diverso da "non ce ne sono". La mappa a queste condizioni li
        // toglie dallo schermo.
        if (LiveAge.snapshotStale(generatedAt, resolvedAtEpoch, now)) return null
        val live = app.departureBoards.live()
        // Il numero unico per corsa solo se e' ancora buono, come in plan().
        val fresco = app.realtime.delaysFresh()
        return rt.busMetaByKey.values
            .filter { it.routeIndex == routeIndex }
            .mapNotNull { meta ->
                // L'eta' di adesso, non quella della risoluzione: senza, la
                // soglia "posizione di X fa" non scattava mai e un bus visto
                // un'ora fa era "qui".
                val fixAge = LiveAge.fixNow(meta.fixAgeSec, resolvedAtEpoch, generatedAt, now)
                if (fixAge > LiveAge.STALE_SECONDS) return@mapNotNull null
                val pattern = if (meta.tripIndex >= 0) r.tripPattern(meta.tripIndex) else -1
                val delay = if (fresco) rt.delayByTrip[meta.tripIndex] else null
                LiveVehicle(
                    routeShortName = r.routeShortName(routeIndex)
                        .ifEmpty { r.routeLongName(routeIndex) },
                    headsign = if (pattern >= 0) r.patternDestination(pattern) else "",
                    lat = meta.lat,
                    lon = meta.lon,
                    delaySeconds = delay,
                    nextStopName = nextStopName(r, live, meta.tripIndex, pattern, delay, now),
                    fixAgeSeconds = fixAge.coerceAtLeast(0),
                )
            }
    }

    /** La fermata verso cui il mezzo sta andando, se si riesce a saperlo. */
    private fun nextStopName(
        r: BundleReader,
        live: dev.antigravity.fluidtransit.routing.LiveTimes,
        tripIndex: Int,
        pattern: Int,
        delaySec: Int?,
        nowEpoch: Long,
    ): String? {
        if (tripIndex < 0 || pattern < 0) return null
        val n = r.patternStopCount(pattern)
        val dayStart = TripProgress.serviceDayStart(r, tripIndex, nowEpoch)
        val dep0 = r.tripDeparture0(tripIndex)
        val profile = r.tripProfile(tripIndex)
        val pos = TripProgress.nextPosition(live, tripIndex, n, nowEpoch, delaySec ?: 0) { p ->
            dayStart + dep0 + r.profileOffset(profile, p)
        }
        if (pos < 0) return null
        return r.stopName(r.patternStop(pattern, pos))
    }

    override fun savedPlaces(): List<NamedPoint> =
        app.savedPlaces.load().map { NamedPoint(it.label, "Il tuo posto", it.lat, it.lon) }

    override fun favouriteStops(): List<NamedPoint> {
        val r = reader ?: return emptyList()
        return app.favorites.stops().mapNotNull { s ->
            val hash = s.idHashHex.toULongOrNull(16)?.toLong() ?: return@mapNotNull null
            val idx = r.findStopByIdHash(hash)
            if (idx < 0) null else NamedPoint(r.stopName(idx), "Fermata", r.stopLat(idx), r.stopLon(idx))
        }
    }

    override fun favouriteRouteNames(): List<String> = app.favorites.routes().map { it.shortName }

    /**
     * Gli avvisi di servizio, per l'assistente.
     *
     * Una lista vuota, letta da un modello, diventa "non ci sono avvisi": e'
     * un'affermazione, e con la rete giu' e' falsa. Qui il fallimento
     * diventa una riga che dice cosa e' successo, perche' e' l'unica forma
     * che il contratto di questo strumento ammette — e una riga cosi' il
     * modello la riferisce invece di inventarci sopra.
     */
    override suspend fun alerts(): List<String> {
        val lista = app.realtime.fetchAlertsOrNull()
            ?: return listOf(
                "Non e' stato possibile scaricare gli avvisi di servizio: " +
                    "non sappiamo se ce ne siano.",
            )
        val righe = lista.map { a ->
            listOf(a.header, a.description).filter { it.isNotBlank() }.joinToString(" — ")
        }
        // Vecchi: lo dice prima, cosi' il modello non li racconta come la
        // situazione di adesso.
        val vecchiDa = app.realtime.alertsStaleSinceEpoch() ?: return righe
        return listOf(
            dev.antigravity.fluidtransit.routing.AlertText.stale(
                vecchiDa,
                java.time.Instant.now().epochSecond,
            ) + ".",
        ) + righe
    }

    override fun realtimeStatus(): String {
        val status = app.realtime.status.value
        val source = when (status.source) {
            dev.antigravity.fluidtransit.data.rt.RealtimeClient.Source.PROXY -> "dal servizio Pampa"
            dev.antigravity.fluidtransit.data.rt.RealtimeClient.Source.DIRECT -> "dal feed ufficiale"
            dev.antigravity.fluidtransit.data.rt.RealtimeClient.Source.SCHEDULE_ONLY -> "nessuno: solo orari di tabella"
        }
        // L'eta' con le parole del resto dell'app: qui erano secondi nudi, e
        // "aggiornati 1200s fa" letto ad alta voce e' un numero da dividere.
        val age = feedAgeNow(status)?.let { "aggiornati ${Words.age(it)} fa" }
            ?: "mai aggiornati"
        val counts = Words.count(status.vehicleCount, "mezzo", "mezzi") + ", " +
            Words.count(status.delayCount, "ritardo", "ritardi")
        val error = status.lastError?.takeIf { it.isNotBlank() }?.let { " · ultimo errore: $it" } ?: ""
        return "$source · $age · $counts$error"
    }

    override fun dataStatus(): String = when (val state = app.bundleManager.state.value) {
        is BundleState.Ready -> "pronto (versione ${state.buildId})"
        is BundleState.Downloading -> "in scaricamento (${(state.progress * 100).toInt()}%)"
        is BundleState.AskMetered -> "in attesa: servono ${state.bytes / 1_000_000} MB su rete a consumo"
        BundleState.WaitingForWifi -> "in attesa del Wi-Fi"
        BundleState.Missing -> "mancante: l'orario non e' ancora stato scaricato"
        // La stessa frase delle due schermate. Qui conta il doppio: quello
        // che legge l'assistente finisce in una risposta a voce, e un
        // messaggio di eccezione letto ad alta voce e' una supercazzola.
        is BundleState.Failed -> "non scaricato: " +
            dev.antigravity.fluidtransit.data.bundle.BundleFailure.words(state.message).title
    }

    /**
     * Com'e' andato l'aggiornamento chiesto con l'azione RefreshData.
     *
     * L'azione ha gia' lanciato il controllo (dopo la conferma dell'utente):
     * qui non se ne lancia un secondo, se ne aspetta l'esito. Prima si
     * richiamava retry() e si rispondeva subito col solo stato, cioe'
     * "pronto (versione vecchia)" mentre il controllo doveva ancora partire,
     * e l'assistente lo riferiva come "fatto".
     */
    override suspend fun refreshData(): String {
        val prima = (app.bundleManager.state.value as? BundleState.Ready)?.buildId
        val finito = app.bundleManager.awaitRetry(REFRESH_WAIT_MS)
        val dopo = app.bundleManager.state.value
        val offerta = app.bundleManager.updateOffer.value
        return when {
            offerta is dev.antigravity.fluidtransit.data.bundle.BundleManager.UpdateOffer.Failed ->
                "non aggiornati: " +
                    dev.antigravity.fluidtransit.data.bundle.BundleFailure.words(offerta.message).title
            offerta is dev.antigravity.fluidtransit.data.bundle.BundleManager.UpdateOffer.Downloading ->
                "in scaricamento (${(offerta.progress * 100).toInt()}%)"
            !finito -> "controllo avviato, ancora in corso"
            dopo is BundleState.Ready && prima != null && dopo.buildId != prima ->
                "aggiornati: versione ${dopo.buildId}"
            dopo is BundleState.Ready -> "gia' aggiornati (versione ${dopo.buildId})"
            else -> dataStatus()
        }
    }

    override fun routines(): List<RoutineInfo> = app.routines.list().map { r ->
        RoutineInfo(
            id = r.id,
            label = r.label,
            destination = r.toName,
            days = r.days,
            anchor = r.anchor,
            anchorMinutes = r.anchorMinutes,
            enabled = r.enabled,
            lastAdvice = dev.antigravity.fluidtransit.data.routines.RoutineText.assistant(
                r,
                java.time.LocalDate.now(dev.antigravity.fluidtransit.routing.Ftb.ROME),
                java.time.Instant.now().epochSecond,
            ),
        )
    }

    override fun savedPlacesWithId(): List<SavedPlaceInfo> =
        app.savedPlaces.load().map { SavedPlaceInfo(it.id, it.label, it.lat, it.lon) }

    override fun starredStops(): List<StarredStop> = app.favorites.stops().map { StarredStop(it.idHashHex, it.name) }

    override fun starredRoutes(): List<StarredRoute> = app.favorites.routes().map { StarredRoute(it.idHashHex, it.shortName) }

    override fun navigationLabel(): String? = app.navigation.state.value?.let { "${it.destName} · ${it.headline}" }

    // ---------------------------------------------------------- ActionExecutor

    /**
     * Le azioni che non hanno bisogno della mappa si fanno qui: cosi' funzionano anche quando a
     * chiedere e' un assistente esterno e l'app non ha nessuna schermata aperta. Le altre restano
     * un messaggio verso chi la mappa ce l'ha in mano.
     */
    override suspend fun execute(action: AssistantAction): ActionOutcome = when (action) {
        is AssistantAction.UnstarStop -> {
            if (app.favorites.isStopFavorite(action.idHashHex)) app.favorites.toggleStop(action.idHashHex, action.name)
            dataChangedFlow.tryEmit(Unit)
            ActionOutcome.DONE
        }
        is AssistantAction.UnstarRoute -> {
            if (app.favorites.isRouteFavorite(action.idHashHex)) app.favorites.toggleRoute(action.idHashHex, action.shortName, 0)
            dataChangedFlow.tryEmit(Unit)
            ActionOutcome.DONE
        }
        is AssistantAction.RemoveSavedPlace -> {
            app.savedPlaces.remove(action.id)
            dataChangedFlow.tryEmit(Unit)
            ActionOutcome.DONE
        }
        // Salvare un posto e mettere una stella sono scritture di dati: stavano nella
        // mappa, e da un assistente esterno (PampAI) l'azione si perdeva senza una parola
        // mentre la risposta diceva "salvato". Si fanno qui, e la mappa ne riceve solo la
        // notizia per rileggere i suoi suggerimenti.
        is AssistantAction.SavePlace -> {
            app.savedPlaces.add(action.label, action.point.lat, action.point.lon)
            dataChangedFlow.tryEmit(Unit)
            ActionOutcome.DONE
        }
        is AssistantAction.StarStop -> {
            // `toggleStop` inverte: una fermata che la stella ce l'ha gia' se la
            // vedrebbe togliere da un "mettila fra i preferiti".
            if (!app.favorites.isStopFavorite(action.idHashHex)) {
                app.favorites.toggleStop(action.idHashHex, action.name)
            }
            dataChangedFlow.tryEmit(Unit)
            ActionOutcome.DONE
        }
        is AssistantAction.StarRoute -> {
            val r = reader
            if (r == null) {
                ActionOutcome.NO_DATA
            } else {
                val hash = java.lang.Long.toHexString(r.routeIdHash(action.routeIndex))
                if (!app.favorites.isRouteFavorite(hash)) {
                    app.favorites.toggleRoute(hash, action.shortName, r.routeDisplayColor(action.routeIndex))
                }
                dataChangedFlow.tryEmit(Unit)
                ActionOutcome.DONE
            }
        }
        AssistantAction.StopNavigation -> {
            app.navigation.stop(app)
            ActionOutcome.DONE
        }
        is AssistantAction.SetRoutineEnabled -> setRoutineEnabled(action)
        is AssistantAction.CreateRoutine -> createRoutine(action)
        is AssistantAction.RemoveRoutine -> {
            app.routines.remove(action.id)
            dev.antigravity.fluidtransit.data.routines.RoutineScheduler.cancel(app, action.id)
            ActionOutcome.DONE
        }
        AssistantAction.RefreshData -> {
            app.bundleManager.retry()
            ActionOutcome.DONE
        }
        else -> onMap(action)
    }

    /**
     * Accende o spegne una routine, e arma o toglie la sveglia.
     *
     * Cambiava solo il flag: dopo "metti in pausa" la sveglia gia' armata
     * scattava, trovava la routine spenta e non ne armava un'altra; il
     * "riattivala" detto dopo lasciava la routine "attiva" in Oggi e nessuna
     * sveglia in nessun posto, fino al prossimo avvio a freddo. La scheda
     * Oggi fa gia' cosi' (aggiorna, poi arma o cancella): qui lo stesso.
     * Ricostruita a mano perche' `Routine` non e' una data class, con
     * `lastComputeEpoch` incluso — senza, un "oggi nessun bus utile" tornava
     * "il consiglio arriva da solo".
     */
    private fun setRoutineEnabled(action: AssistantAction.SetRoutineEnabled): ActionOutcome {
        if (app.routines.list().none { it.id == action.id }) return ActionOutcome.NOT_FOUND
        // La stessa strada di Oggi: spegnere toglie avviso e sveglia,
        // riaccendere ne arma una. Due copie si erano gia' staccate una volta.
        dev.antigravity.fluidtransit.data.routines.RoutineScheduler.setEnabled(app, action.id, action.enabled)
        return ActionOutcome.DONE
    }

    /**
     * Crea una routine. Stava nella mappa, e senza mappa — Oggi, Preferiti, un
     * assistente esterno — l'azione si perdeva mentre la risposta diceva
     * "routine creata"; e con la posizione spenta la mappa tornava senza
     * fare niente, con la stessa risposta.
     *
     * La partenza e' quella detta, altrimenti dove si trova la persona.
     * Senza nessuna delle due niente routine e si dice perche': il centro della
     * mappa non e' un'alternativa onesta, sarebbe un posto che nessuno ha scelto.
     */
    private fun createRoutine(action: AssistantAction.CreateRoutine): ActionOutcome {
        val origin = action.from?.let { it.lat to it.lon } ?: here ?: return ActionOutcome.NO_ORIGIN
        val routine = Routines.Routine(
            id = System.currentTimeMillis(),
            label = action.label,
            fromLat = origin.first,
            fromLon = origin.second,
            toLat = action.to.lat,
            toLon = action.to.lon,
            toName = action.to.name,
            days = action.days,
            anchor = action.anchor,
            anchorMinutes = action.anchorMinutes,
            enabled = true,
        )
        app.routines.add(routine)
        dev.antigravity.fluidtransit.data.routines.RoutineScheduler.scheduleNextCompute(app, routine)
        // Il permesso delle notifiche lo chiede la mappa, se c'e': la strada manuale lo chiedeva,
        // e senza una routine "attiva" in Oggi non avverte mai.
        routineCreatedFlow.tryEmit(Unit)
        return ActionOutcome.DONE
    }

    /**
     * Un'azione della mappa, con la sua risposta.
     *
     * Se la mappa non e' in ascolto si aspetta un momento (l'app puo' essere
     * appena stata portata davanti da un assistente esterno), poi e' un
     * fallimento detto come tale.
     */
    private suspend fun onMap(action: AssistantAction): ActionOutcome {
        if (mapRequests.subscriptionCount.value == 0) {
            withTimeoutOrNull(MAP_LISTEN_WAIT_MS) { mapRequests.subscriptionCount.first { it > 0 } }
                ?: return ActionOutcome.NO_MAP
        }
        val request = MapRequest(action)
        if (!mapRequests.tryEmit(request)) return ActionOutcome.NO_MAP
        return withTimeoutOrNull(MAP_ANSWER_WAIT_MS) { request.result.await() } ?: ActionOutcome.NO_MAP
    }

    // ------------------------------------------------------------------ interni

    /** L'eta' del feed ADESSO: quella dello stato e' del momento dell'ultimo poll. */
    fun feedAgeNow(status: dev.antigravity.fluidtransit.data.rt.RealtimeClient.Status): Long? =
        LiveAge.feedNow(
            status.feedAgeSeconds,
            status.lastSuccessAt?.epochSecond,
            Instant.now().epochSecond,
        )

    /** La stessa regola della ricerca, e adesso lo e' davvero: [Reference]. */
    private fun referencePoint(): Pair<Double, Double>? = Reference.point(here, looking)
}

/** Quanto l'assistente aspetta l'esito di un aggiornamento prima di rispondere. */
private const val REFRESH_WAIT_MS = 20_000L

/** Quanto si aspettano i feed prima di rispondere con quello che c'e': e' un'aggiunta, non una condizione. */
private const val LIVE_WAIT_MS = 4_000L

/** Sotto questa eta' l'ultimo giro sui feed e' abbastanza fresco da non rifarlo. */
private const val LIVE_FRESH_MS = 45_000L

/** Quanto si aspetta che la mappa si metta in ascolto (app appena portata davanti), prima di dire che non c'e'. */
private const val MAP_LISTEN_WAIT_MS = 8_000L

/** Quanto si aspetta che la mappa risponda a un'azione. */
private const val MAP_ANSWER_WAIT_MS = 15_000L
