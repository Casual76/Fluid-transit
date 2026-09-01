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
            settingsStore.settings.drop(1)
                .collect {
                    runCatching {
                        dev.antigravity.fluidtransit.ui.widget.StopWidget().updateAll(this@FluidTransitApp)
                        dev.antigravity.fluidtransit.ui.widget.RoutineWidget().updateAll(this@FluidTransitApp)
                    }
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
