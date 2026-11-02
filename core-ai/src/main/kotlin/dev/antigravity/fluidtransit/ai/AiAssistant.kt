package dev.antigravity.fluidtransit.ai

import android.content.Context
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.keys.AiKeyVerifier
import dev.antigravity.fluidengine.ai.keys.AiSettingsStore
import dev.antigravity.fluidengine.ai.keys.KeystoreCipher
import dev.antigravity.fluidengine.ai.keys.ModelCatalogStore
import dev.antigravity.fluidengine.ai.net.AiHttp
import dev.antigravity.fluidengine.ai.orchestrator.AiConfirmationGate
import dev.antigravity.fluidengine.ai.orchestrator.AiDiagnosticsLog
import dev.antigravity.fluidengine.ai.orchestrator.AiOrchestrator
import dev.antigravity.fluidengine.ai.orchestrator.AiOrchestratorConfig
import dev.antigravity.fluidengine.ai.orchestrator.AiRouter
import dev.antigravity.fluidengine.ai.provider.ProviderFactory
import dev.antigravity.fluidengine.ai.speech.AndroidPcmSource
import dev.antigravity.fluidengine.ai.speech.SpeechCapture
import dev.antigravity.fluidengine.ai.speech.Transcriber
import dev.antigravity.fluidtransit.ai.keys.TransitAiPreferences
import dev.antigravity.fluidtransit.ai.orchestrator.ActionExecutor
import dev.antigravity.fluidtransit.ai.orchestrator.AppStatusInfo
import dev.antigravity.fluidtransit.ai.orchestrator.AssistantSession
import dev.antigravity.fluidtransit.ai.tools.AllTools
import dev.antigravity.fluidtransit.ai.tools.ToolGroup
import dev.antigravity.fluidtransit.ai.tools.ToolLimits
import dev.antigravity.fluidtransit.ai.tools.TransitBridge
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * L'assistente, montato: chiavi, provider, strumenti, sessione.
 *
 * Tutto quello che non sa di autobus e' di `engine-ai` (provider, chiavi
 * cifrate, stream, failover, router, orchestratore, voce, stati); da qui
 * passano solo le scelte di Fluid Transit: l'alias delle chiavi, il file delle
 * preferenze con le sue migrazioni, i numeri del giro, i gruppi e gli strumenti.
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
    private val scope: CoroutineScope,
    private val transit: TransitBridge,
    status: () -> AppStatusInfo,
    executor: ActionExecutor,
    userAgent: String,
) {
    private val appContext = context.applicationContext

    val http = AiHttp(userAgent)

    /**
     * Le chiavi, nel Keystore sotto l'alias di sempre: con quello dell'engine
     * (`fluidengine.ai`) le chiavi gia' salvate non si decifrerebbero piu', e
     * l'utente le vedrebbe "da reinserire".
     */
    val keys = AiKeyStore(appContext, KeystoreCipher(KEYSTORE_ALIAS))

    private val preferences = TransitAiPreferences(appContext)

    val settings: AiSettingsStore = preferences.settings

    val catalogs = ModelCatalogStore(File(appContext.filesDir, "ai/models"))

    val providers = ProviderFactory(
        http = http,
        keys = keys,
        settings = settings,
        referer = "https://github.com/Casual76/Fluid-transit",
        appTitle = "Fluid Transit",
        catalogs = catalogs,
    )

    /** Prova una chiave e scarica il catalogo modelli: la usa la schermata delle chiavi. */
    val verifier = AiKeyVerifier(
        keys = keys,
        settings = settings,
        providers = providers,
        catalogs = catalogs,
    )

    val diagnostics = AiDiagnosticsLog()

    private val router = AiRouter(
        groups = ToolGroup.entries,
        actionGroup = ToolGroup.APP,
        domainHint = "i mezzi pubblici in Toscana: fermate, linee, orari, bus in tempo reale, viaggi, " +
            "posti salvati, preferiti e routine dell'utente",
        // Quando lo stadio 1 non risponde non si butta via la domanda: si danno i
        // gruppi che coprono quasi tutto quello che si chiede a un'app di
        // trasporto — dove sta una cosa, quando passa, cosa succede adesso.
        defaultGroups = listOf(ToolGroup.PLACES, ToolGroup.SCHEDULE, ToolGroup.LIVE),
    )

    private val orchestrator = AiOrchestrator(
        registry = AllTools.registry(),
        router = router,
        diagnostics = diagnostics,
        // I numeri dell'assistente di prima: una card a schermo, sopra la mappa,
        // che vive novanta secondi. Il tetto dei tool e' il piu' largo (quello
        // di chi aspetta la conferma): i tetti per gruppo li mette ToolLimits.
        config = AiOrchestratorConfig(
            maxRounds = 6,
            maxMoreTools = 2,
            maxOutputTokens = 1_200,
            parallelTools = 4,
            toolTimeoutMillis = ToolLimits.ORCHESTRATOR_TIMEOUT_MILLIS,
            totalBudgetMillis = ToolLimits.TOTAL_BUDGET_MILLIS,
            finalReserveMillis = ToolLimits.FINAL_RESERVE_MILLIS,
        ),
    )

    private val transcriber = Transcriber { providers.ordered(ProviderFactory.Kind.STT) }

    private val gate = AiConfirmationGate(ToolLimits.CONFIRMATION_TIMEOUT_MILLIS)

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
        gate = gate,
        onModelUnavailable = { provider, model -> scope.launch { verifier.markUnavailable(provider, model) } },
    )

    /** Ha senso offrirlo? Serve una chiave verificata e l'interruttore acceso. */
    val enabled: Flow<Boolean> = combine(settings.settings, keys.anyVerified) { s, anyKey ->
        s.enabled && anyKey
    }

    /** Dopo una chiave verificata: accende l'assistente, se l'utente non ha mai scelto. */
    suspend fun enableIfNeverChosen() = preferences.enableIfNeverChosen()

    private companion object {
        /** L'alias del Keystore della copia di prima: e' quello sotto cui stanno le chiavi. */
        const val KEYSTORE_ALIAS = "fluidtransit.ai"
    }
}
