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
import kotlinx.coroutines.launch

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
        // Il manifest remoto, se la copia in cache e' vecchia. Non blocca
        // niente: finche' non arriva l'app usa l'ultima risposta valida.
        applicationScope.launch { runCatching { remoteConfig.refreshIfStale() } }
    }

    companion object {
        const val MANIFEST_URL =
            "https://raw.githubusercontent.com/Casual76/Fluid-transit/main/manifest.json"
    }
}
