package dev.antigravity.fluidtransit.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ai.keys.AiSettings
import dev.antigravity.fluidengine.ai.keys.VerifyResult
import dev.antigravity.fluidengine.ai.provider.OpenRouterDataPolicy
import dev.antigravity.fluidengine.aiui.settings.FluidAiCatalogueRefresh
import dev.antigravity.fluidengine.aiui.settings.FluidAiSettingsActions
import dev.antigravity.fluidengine.aiui.settings.FluidAiSettingsOptions
import dev.antigravity.fluidengine.aiui.settings.FluidAiSettingsState
import dev.antigravity.fluidengine.aiui.strings.FluidAiSettingsStrings
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.ai.keys.KeyHelp
import kotlinx.coroutines.launch

/**
 * Le chiavi dell'assistente.
 *
 * Sono dell'utente e restano sul telefono, cifrate col Keystore: non passano
 * da nessun nostro server, e non stanno nel repo — che e' pubblico. Tre
 * provider perche' i piani gratuiti hanno limiti stretti e a orario di punta
 * si tocca il tetto: quando uno e' a limite si passa al successivo invece di
 * dire "riprova".
 *
 * La sezione vera e' dell'engine (`fluidAiSettingsSection`, la stessa di
 * ClasseViva e PampAI): l'interruttore, la scheda di ogni chiave con la guida a
 * passi, il campo segreto e la verifica, i modelli, il microfono, le azioni. Qui
 * c'e' solo il collegamento con l'assistente di Fluid Transit e le sue parole.
 *
 * Una chiave non e' "messa" finche' non e' stata PROVATA: salvarla e basta
 * significherebbe scoprire che era sbagliata alla prima domanda, che e' il
 * momento peggiore.
 */
@Composable
fun rememberAssistantSettingsSection(app: FluidTransitApp): AssistantSettingsSection {
    val scope = rememberCoroutineScope()
    val assistant = remember { app.assistant }
    val settings by assistant.settings.settings.collectAsStateWithLifecycle(
        // Quello che c'e' finche' il file non e' letto: le scelte di partenza dell'app, cosi'
        // l'interruttore delle azioni non lampeggia da spento ad acceso.
        initialValue = AiSettings(actionsEnabled = true, openRouterDataPolicy = OpenRouterDataPolicy.DENY),
    )
    val keys by assistant.keys.states.collectAsStateWithLifecycle(initialValue = emptyMap())
    val catalogues by assistant.catalogs.catalogues.collectAsStateWithLifecycle()
    val keyInfo by assistant.verifier.keyInfo.collectAsStateWithLifecycle()

    // I cataloghi si leggono dal disco all'apertura e si rinfrescano se vecchi di un giorno.
    FluidAiCatalogueRefresh(verified = keys.filterValues { it.verified }.keys) { provider ->
        assistant.verifier.refreshIfStale(provider)
    }

    val state = FluidAiSettingsState(
        settings = settings,
        keys = keys,
        catalogues = catalogues,
        keyInfo = keyInfo,
    )
    val actions = remember(assistant, scope) {
        FluidAiSettingsActions(
            saveAndVerify = { provider, key ->
                if (key != null) assistant.keys.set(provider, key)
                // Provarla adesso: scoprire che e' sbagliata alla prima domanda sarebbe il momento peggiore.
                val result = assistant.verifier.verify(provider)
                // Una chiave che il servizio rifiuta non resta li' "da provare": si toglie, e la
                // scheda dice di incollarla di nuovo. Un guasto della rete, invece, la lascia.
                if (result is VerifyResult.Invalid) assistant.keys.set(provider, null)
                result
            },
            removeKey = { provider -> scope.launch { assistant.keys.set(provider, null) } },
            setEnabled = { on -> scope.launch { assistant.settings.setEnabled(on) } },
            setChatOrder = { order -> scope.launch { assistant.settings.setChatOrder(order) } },
            setSttOrder = { order -> scope.launch { assistant.settings.setSttOrder(order) } },
            setModel = { provider, tier, model -> scope.launch { assistant.settings.setModel(provider, tier, model) } },
            setSttModel = { provider, model -> scope.launch { assistant.settings.setSttModel(provider, model) } },
            setOpenRouterFallbacks = { models -> scope.launch { assistant.settings.setOpenRouterFallbacks(models) } },
            setOpenRouterDataPolicy = { policy -> scope.launch { assistant.settings.setOpenRouterDataPolicy(policy) } },
            refreshCatalogue = { provider -> scope.launch { assistant.verifier.refreshIfStale(provider, force = true) } },
            setActionsEnabled = { on -> scope.launch { assistant.settings.setActionsEnabled(on) } },
            // La chiave e' buona: si accende l'assistente, se l'utente non ha mai scelto. Senza,
            // restava spento con "verificata" accanto alla chiave.
            onVerified = { _, _ -> scope.launch { assistant.enableIfNeverChosen() } },
        )
    }
    return AssistantSettingsSection(state, actions)
}

/** Quello che la sezione dell'assistente legge e quello che fa quando si tocca. */
class AssistantSettingsSection(
    val state: FluidAiSettingsState,
    val actions: FluidAiSettingsActions,
)

/**
 * Cosa mostra la sezione: quello che l'assistente di Fluid Transit ha davvero.
 * Niente ragionamento (il modello pensa come l'app gli dice di pensare), niente
 * lettura ad alta voce (l'app non parla), i soli due livelli di sempre (router e
 * chat: il profondo e' spento), niente cronologia delle richieste.
 */
val AssistantSettingsOptions = FluidAiSettingsOptions(
    tiers = false,
    transcription = false,
    thinking = false,
    speakReplies = false,
    recentRequests = false,
    // La spiegazione di cosa parte verso il servizio e' dentro ogni chiave (KeyHelp.disclosure):
    // una nota generica in piu' direbbe una cosa meno precisa.
    privacyNote = false,
)

/** Le parole di Fluid Transit nelle impostazioni dell'assistente. */
val AssistantSettingsStrings = FluidAiSettingsStrings(
    enabledTitle = "Attiva l'assistente",
    enabledNeedsKey = KeyHelp.enableSubtitle(anyKeyVerified = false, enabled = false),
    enabledOn = KeyHelp.enableSubtitle(anyKeyVerified = true, enabled = true),
    enabledOff = KeyHelp.enableSubtitle(anyKeyVerified = true, enabled = false),
    // Cosa parte verso il servizio, detto chiaro e a parte dalla chiave: la garanzia sulla chiave
    // si leggeva come "non esce niente", e non e' vero.
    keyDisclosure = { provider -> KeyHelp.disclosure(provider) },
    // Qui il ripiego e' sempre acceso: se un servizio e' al limite si passa al successivo.
    orderDetail = "Il primo risponde; gli altri fanno da riserva se è al limite o non risponde.",
    actionsTitle = "Azioni nell'app",
    actionsDetail = "Lascia che l'assistente apra schede, salvi posti e crei routine. " +
        "Le cose che scrivono chiedono comunque conferma",
)
