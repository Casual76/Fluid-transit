package dev.antigravity.fluidtransit

import android.app.Application
import dev.antigravity.fluidengine.config.EngineConfigSource
import dev.antigravity.fluidengine.config.EngineRemoteConfig
import dev.antigravity.fluidengine.foundation.EngineFlag
import dev.antigravity.fluidengine.net.EngineHttp
import dev.antigravity.fluidengine.storage.EngineConfigCache
import dev.antigravity.fluidengine.storage.EngineSettingsStore
import dev.antigravity.fluidtransit.data.bundle.BundleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import androidx.glance.appwidget.updateAll

/**
 * I feature flag dell'app, con il valore con cui la build e' stata provata.
 * Si spengono da remoto via manifest.json, senza una release: e' il piano di
 * controllo unico deciso nel piano (EngineRemoteConfig, nessun secondo
 * meccanismo).
 */
object Flags {
    /** Il proxy realtime su Cloudflare. Spento = fallback ai feed diretti. */
    val RtProxy = EngineFlag(key = "rt.proxy.enabled", default = true)

    /** La modalita' ibrida con le ortofoto: l'unica funzione il cui traffico cresce con gli utenti. */
    val Ortofoto = EngineFlag(key = "map.ortofoto.enabled", default = true)

    /** Il rilevatore di bus fantasma (Fase 8): parte spento per contratto. */
    val GhostBus = EngineFlag(key = "rt.ghostbus.enabled", default = false)
}

class FluidTransitApp : Application() {

    /** Vive quanto il processo: niente di quello che parte qui ha qualcosa da cui essere cancellato. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsStore by lazy { EngineSettingsStore(this) }

    val remoteConfig by lazy {
        EngineRemoteConfig(
            http = EngineHttp(userAgent = BundleManager.USER_AGENT),
            cache = EngineConfigCache(this),
            source = EngineConfigSource(
                manifestUrl = MANIFEST_URL,
                applicationId = BuildConfig.APPLICATION_ID,
            ),
        )
    }

    val bundleManager by lazy { BundleManager(this, applicationScope) }

    /**
     * Il client realtime a tre stati. Il kill switch remoto del proxy passa
     * dal manifest dell'engine: spento = si va diretti all'origine.
     */
    val realtime by lazy {
        dev.antigravity.fluidtransit.data.rt.RealtimeClient(
            proxyAllowed = { remoteConfig.current().isEnabled(Flags.RtProxy) },
        )
    }

    /**
     * Come il ritardo di ogni corsa evolve lungo il percorso.
     *
     * Vive qui e non in una schermata perche' ha bisogno di vedere TUTTI i
     * giri di trip-updates per leggere un andamento: se lo alimentasse la
     * mappa, passare alla scheda Oggi azzererebbe la memoria del modello
     * proprio mentre serve.
     */
    val delayModel by lazy { dev.antigravity.fluidtransit.routing.DelayModel() }

    /**
     * L'assistente. Vive qui, nello scope dell'Application, perche' una
     * domanda deve poter sopravvivere a un cambio di scheda o a un minuto in
     * cui l'app resta in secondo piano: se stesse in un composable morirebbe
     * con lui, proprio mentre il modello sta finendo di rispondere.
     */
    val assistantBridge by lazy { dev.antigravity.fluidtransit.data.ai.AssistantBridge(this) }

    val assistant by lazy {
        dev.antigravity.fluidtransit.ai.AiAssistant(
            context = this,
            scope = applicationScope,
            transit = assistantBridge,
            status = ::assistantStatus,
            executor = assistantBridge,
            userAgent = dev.antigravity.fluidtransit.data.bundle.BundleManager.USER_AGENT,
        )
    }

    /** Quello che il prompt racconta al modello dello stato dell'app, adesso. */
    private fun assistantStatus(): dev.antigravity.fluidtransit.ai.orchestrator.AppStatusInfo {
        val ready = bundleManager.state.value as?
            dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState.Ready
        val rt = realtime.status.value
        val here = assistantBridge.here
        // Niente reverse geocoding: la fermata piu' vicina e' un modo di dire
        // "dove sei" che l'utente riconosce, e costa una scansione della
        // griglia invece di una chiamata di rete.
        val label = if (ready != null && here != null) {
            val r = ready.reader
            r.stopsNear(here.first, here.second, 700.0)
                .minByOrNull {
                    dev.antigravity.fluidtransit.routing.BundleReader.haversine(
                        here.first, here.second, r.stopLat(it), r.stopLon(it),
                    )
                }
                ?.let { "vicino alla fermata ${r.stopName(it)}" }
        } else {
            null
        }
        return dev.antigravity.fluidtransit.ai.orchestrator.AppStatusInfo(
            placeLabel = label,
            bundleReady = ready != null,
            placesReady = placesManager.state.value is
                dev.antigravity.fluidtransit.data.places.PlacesManager.State.Ready,
            realtimeState = when (rt.source) {
                dev.antigravity.fluidtransit.data.rt.RealtimeClient.Source.SCHEDULE_ONLY ->
                    "non disponibili: solo orari programmati"
                else -> "disponibili"
            },
            feedAgeSeconds = rt.feedAgeSeconds?.toInt(),
        )
    }

    /** Il geocoding offline (luoghi.bin) e i posti dell'utente. */
    val placesManager by lazy { dev.antigravity.fluidtransit.data.places.PlacesManager(this, applicationScope) }
    val savedPlaces by lazy { dev.antigravity.fluidtransit.data.places.SavedPlaces(this) }
    val favorites by lazy { dev.antigravity.fluidtransit.data.favorites.Favorites(this) }
    val routines by lazy { dev.antigravity.fluidtransit.data.routines.Routines(this) }
    val navigation by lazy { dev.antigravity.fluidtransit.data.nav.NavigationHolder() }
    val travelMode by lazy { dev.antigravity.fluidtransit.data.nav.TravelModeStore(this) }

    /**
     * RAPTOR vuole un solo thread (lo scratch e' riusato, per scelta): tutte
     * le query passano da questo dispatcher, e il motore si ricrea solo
     * quando cambia il bundle.
     */
    val routingDispatcher: kotlinx.coroutines.CoroutineDispatcher =
        kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)

    private var raptorCache: Pair<Long, dev.antigravity.fluidtransit.routing.Raptor>? = null

    fun raptorFor(reader: dev.antigravity.fluidtransit.routing.BundleReader): dev.antigravity.fluidtransit.routing.Raptor {
        raptorCache?.let { (id, r) -> if (id == reader.buildId) return r }
        val fresh = dev.antigravity.fluidtransit.routing.Raptor(reader)
        raptorCache = reader.buildId to fresh
        return fresh
    }

    override fun onCreate() {
        super.onCreate()
        // Le due trappole di MapLibre trovate in Fase 1, nell'ordine giusto:
        // getInstance a tre argomenti con chiave nulla, e il client OkHttp
        // registrato DOPO — invertendole si ottiene la stessa eccezione della
        // prima, che punta al posto sbagliato.
        org.maplibre.android.MapLibre.getInstance(
            this,
            null,
            org.maplibre.android.WellKnownTileServer.MapLibre,
        )
        org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(
            dev.antigravity.fluidtransit.ui.map.MapHttp.client(this),
        )

        bundleManager.start()
        placesManager.start()
        // Le routine: canale di notifica pronto e sveglie riarmate (dopo un
        // aggiornamento dell'app le sveglie vecchie non esistono piu').
        dev.antigravity.fluidtransit.data.routines.RoutineScheduler.ensureChannel(this)
        applicationScope.launch {
            dev.antigravity.fluidtransit.data.routines.RoutineScheduler.rescheduleAll(this@FluidTransitApp)
        }
        // La trappola nota dei widget: si ridisegnano quando cambiano i DATI,
        // non quando cambia l'ASPETTO. Il collegamento tema->updateAll e'
        // esplicito, o cambiare accento non si vede sulla home. E un
        // updateAll all'avvio rinfresca gli orari mostrati.
        applicationScope.launch {
            runCatching {
                dev.antigravity.fluidtransit.ui.widget.StopWidget().updateAll(this@FluidTransitApp)
                dev.antigravity.fluidtransit.ui.widget.RoutineWidget().updateAll(this@FluidTransitApp)
            }
            runCatching {
                dev.antigravity.fluidtransit.ui.widget.WidgetRefresher
                    .scheduleNext(this@FluidTransitApp)
            }
            settingsStore.settings.drop(1)
                .collect {
                    runCatching {
                        dev.antigravity.fluidtransit.ui.widget.StopWidget().updateAll(this@FluidTransitApp)
                        dev.antigravity.fluidtransit.ui.widget.RoutineWidget().updateAll(this@FluidTransitApp)
                    }
                }
        }
        // Ogni giro di trip-updates entra nel modello dei ritardi, da qui e
        // una volta sola, qualunque schermata sia aperta.
        applicationScope.launch {
            realtime.delays.collect { snapshot ->
                val ready = bundleManager.state.value as? BundleManager.BundleState.Ready
                val reader = ready?.reader ?: return@collect
                if (snapshot == null) return@collect
                val at = snapshot.feedTimestamp.takeIf { it > 0 }
                    ?: (System.currentTimeMillis() / 1000)
                for (d in snapshot.byTripHash.values) {
                    if (d.canceled || d.noData) continue
                    val trip = reader.findTripByIdHash(d.tripHash).takeIf { it >= 0 }
                        ?: reader.findTripByRouteAndDeparture(
                            d.routeHash,
                            d.direction,
                            d.startTimeSec,
                        ).takeIf { it >= 0 }
                        ?: continue
                    delayModel.observe(trip, d.delaySec, d.nextStopSeq, at)
                }
                // Le corse di cui non si sente parlare da mezz'ora sono
                // finite: la memoria non deve crescere per sempre.
                delayModel.forgetBefore(at - 30 * 60)
            }
        }
        // Il manifest remoto, se la copia in cache e' vecchia. Non blocca
        // niente: finche' non arriva l'app usa l'ultima risposta valida.
        applicationScope.launch { runCatching { remoteConfig.refreshIfStale() } }
    }

    companion object {
        const val MANIFEST_URL =
            "https://raw.githubusercontent.com/Casual76/Fluid-transit/main/manifest.json"
    }
}
