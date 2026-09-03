package dev.antigravity.fluidtransit.data.ai

import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.ai.orchestrator.ActionExecutor
import dev.antigravity.fluidtransit.ai.tools.AssistantAction
import dev.antigravity.fluidtransit.ai.tools.LiveVehicle
import dev.antigravity.fluidtransit.ai.tools.NamedPoint
import dev.antigravity.fluidtransit.ai.tools.RouteHit
import dev.antigravity.fluidtransit.ai.tools.StopHit
import dev.antigravity.fluidtransit.ai.tools.TransitBridge
import dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState
import dev.antigravity.fluidtransit.data.places.PlacesManager
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.DelayModel
import dev.antigravity.fluidtransit.routing.PlacesSearch
import dev.antigravity.fluidtransit.routing.Raptor
import dev.antigravity.fluidtransit.ui.map.ResolvedRt
import dev.antigravity.fluidtransit.ui.map.SearchIndex
import java.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext

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

    /** L'indice di fermate e linee: lo costruisce la mappa, una volta per bundle. */
    @Volatile
    var searchIndex: SearchIndex? = null

    /** L'ultimo snapshot realtime risolto. */
    @Volatile
    var resolved: ResolvedRt? = null

    /** Dove si trova la persona, secondo il GPS della mappa. */
    @Volatile
    var location: (() -> Pair<Double, Double>?)? = null

    /** Il centro della mappa che sta guardando. */
    @Volatile
    var camera: (() -> Pair<Double, Double>?)? = null

    /**
     * Le azioni da eseguire, verso la schermata mappa. Non le fa il ponte:
     * le fa chi ha in mano la mappa, che e' l'unico a poterle fare davvero.
     */
    private val actionFlow = MutableSharedFlow<AssistantAction>(extraBufferCapacity = 8)
    val actions: SharedFlow<AssistantAction> = actionFlow

    // ---------------------------------------------------------- TransitBridge

    override val reader: BundleReader?
        get() = (app.bundleManager.state.value as? BundleState.Ready)?.reader

    override val places: PlacesSearch?
        get() = (app.placesManager.state.value as? PlacesManager.State.Ready)?.search

    override val delays: DelayModel get() = app.delayModel

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
        val live = if (rt != null) {
            Raptor.Realtime(rt.delayByTrip, rt.canceledTrips)
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
                    raptor.planArriveBy(from, to, Instant.ofEpochSecond(arriveByEpoch), live)

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

    override fun findRoutes(query: String, limit: Int): List<RouteHit> = searchIndex
        ?.search(query, limit * 3)
        .orEmpty()
        .filterIsInstance<SearchIndex.Hit.Route>()
        .take(limit)
        .map { RouteHit(it.routeIndex, it.title, it.destination) }

    override fun vehiclesOfRoute(routeIndex: Int): List<LiveVehicle> {
        val r = reader ?: return emptyList()
        val rt = resolved ?: return emptyList()
        return rt.busMetaByKey.values
            .filter { it.routeIndex == routeIndex }
            .map { meta ->
                val pattern = if (meta.tripIndex >= 0) r.tripPattern(meta.tripIndex) else -1
                LiveVehicle(
                    routeShortName = r.routeShortName(routeIndex)
                        .ifEmpty { r.routeLongName(routeIndex) },
                    headsign = if (pattern >= 0) r.patternDestination(pattern) else "",
                    lat = meta.lat,
                    lon = meta.lon,
                    delaySeconds = rt.delayByTrip[meta.tripIndex],
                    nextStopName = null,
                    fixAgeSeconds = meta.fixAgeSec.coerceAtLeast(0),
                )
            }
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

    override suspend fun alerts(): List<String> = runCatching {
        app.realtime.fetchAlerts().map { a ->
            listOf(a.header, a.description).filter { it.isNotBlank() }.joinToString(" — ")
        }
    }.getOrDefault(emptyList())

    // ---------------------------------------------------------- ActionExecutor

    override suspend fun execute(action: AssistantAction): Boolean = actionFlow.tryEmit(action)

    // ------------------------------------------------------------------ interni

    /** La stessa regola della ricerca: dove sei, ma la mappa vince se l'hai spostata. */
    private fun referencePoint(): Pair<Double, Double>? {
        val h = here ?: return looking
        val l = looking ?: return h
        val away = BundleReader.haversine(h.first, h.second, l.first, l.second)
        return if (away > 20_000.0) l else h
    }
}
