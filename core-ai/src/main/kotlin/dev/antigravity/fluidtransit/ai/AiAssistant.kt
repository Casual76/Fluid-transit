package dev.antigravity.fluidtransit.ai

import android.content.Context
import dev.antigravity.fluidtransit.ai.keys.AiKeyStore
import dev.antigravity.fluidtransit.ai.keys.AiSettingsStore
import dev.antigravity.fluidtransit.ai.net.AiHttp
import dev.antigravity.fluidtransit.ai.orchestrator.ActionExecutor
import dev.antigravity.fluidtransit.ai.orchestrator.AiDiagnosticsLog
import dev.antigravity.fluidtransit.ai.orchestrator.AppStatusInfo
import dev.antigravity.fluidtransit.ai.orchestrator.AssistantOrchestrator
import dev.antigravity.fluidtransit.ai.orchestrator.AssistantSession
import dev.antigravity.fluidtransit.ai.provider.ProviderFactory
import dev.antigravity.fluidtransit.ai.speech.AndroidPcmSource
import dev.antigravity.fluidtransit.ai.speech.SpeechCapture
import dev.antigravity.fluidtransit.ai.speech.Transcriber
import dev.antigravity.fluidtransit.ai.tools.AllTools
import dev.antigravity.fluidtransit.ai.tools.TransitBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * L'assistente, montato: chiavi, provider, strumenti, sessione.
 *
 * Si costruisce una volta nell'Application e si tiene: la sessione vive nello
 * scope dell'app, non in un composable, perche' una domanda deve poter
 * sopravvivere a un cambio di scheda.
 *
 * [enabled] dice se ha senso mostrarlo: serve almeno una chiave verificata e
 * l'interruttore acceso. Senza, il microfono resta quello di sistema, che
 * trascrive e basta — come prima della Fase 8.
 */
class AiAssistant(
    context: Context,
    scope: CoroutineScope,
    private val transit: TransitBridge,
    status: () -> AppStatusInfo,
    executor: ActionExecutor,
    userAgent: String,
) {
    private val appContext = context.applicationContext

    val http = AiHttp(userAgent)
    val keys = AiKeyStore(appContext)
    val settings = AiSettingsStore(appContext)

    val providers = ProviderFactory(
        http = http,
        keys = keys,
        settings = settings,
        referer = "https://github.com/Casual76/Fluid-transit",
        appTitle = "Fluid Transit",
    )

    val catalogs = dev.antigravity.fluidtransit.ai.keys.ModelCatalogStore(
        java.io.File(appContext.filesDir, "ai/models"),
    )

    /** Prova una chiave e scarica il catalogo modelli: la usa la schermata delle chiavi. */
    val verifier = dev.antigravity.fluidtransit.ai.keys.AiKeyVerifier(
        keys = keys,
        settings = settings,
        providers = providers,
        catalogs = catalogs,
    )

    val diagnostics = AiDiagnosticsLog()

    private val registry = AllTools.registry()

    private val orchestrator = AssistantOrchestrator(
        registry = registry,
        diagnostics = diagnostics,
    )

    private val transcriber = Transcriber { providers.ordered(ProviderFactory.Kind.STT) }

    val session = AssistantSession(
        scope = scope,
        transit = transit,
        settingsStore = settings,
        providers = providers,
        orchestrator = orchestrator,
        transcriber = transcriber,
        speechFactory = { SpeechCapture(AndroidPcmSource()) },
        cacheDir = appContext.cacheDir,
        executor = executor,
        status = status,
    )

    /** Ha senso offrirlo? Serve una chiave verificata e l'interruttore acceso. */
    val enabled: Flow<Boolean> = combine(settings.settings, keys.anyVerified) { s, anyKey ->
        s.enabled && anyKey
    }
}
