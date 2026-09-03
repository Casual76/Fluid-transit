package dev.antigravity.fluidtransit.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.ai.keys.KeyState
import dev.antigravity.fluidtransit.ai.keys.VerifyResult
import dev.antigravity.fluidtransit.ai.provider.ProviderId
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
 * Una chiave non e' "messa" finche' non e' stata PROVATA: salvarla e basta
 * significherebbe scoprire che era sbagliata alla prima domanda, che e' il
 * momento peggiore.
 */
@Composable
fun AssistantSettingsGroup(app: FluidTransitApp) {
    val scope = rememberCoroutineScope()
    val assistant = remember { app.assistant }
    val states by assistant.keys.states.collectAsStateWithLifecycle(initialValue = emptyMap())
    val settings by assistant.settings.settings.collectAsStateWithLifecycle(
        initialValue = dev.antigravity.fluidtransit.ai.keys.AiSettings(),
    )

    var editing by remember { mutableStateOf<ProviderId?>(null) }
    var verifying by remember { mutableStateOf<ProviderId?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }

    FluidListGroup {
        FluidListRow(
            title = "Assistente",
            subtitle = if (states.values.any { it.verified }) {
                "Chiedi a voce o scrivendo: cerca, calcola viaggi, dice dove sono i bus"
            } else {
                "Serve la chiave di almeno un servizio, qui sotto"
            },
            badge = {
                FluidSwitch(
                    checked = settings.enabled,
                    onCheckedChange = { on -> scope.launch { assistant.settings.setEnabled(on) } },
                )
            },
        )

        for (provider in ProviderId.entries) {
            val state = states[provider] ?: KeyState(present = false, verifiedAtMillis = null)
            FluidListRow(
                title = provider.label,
                subtitle = when {
                    verifying == provider -> "Sto provando la chiave…"
                    state.verified -> "Chiave verificata: resta su questo telefono"
                    state.present -> "Chiave salvata ma non verificata: toccala per riprovare"
                    else -> hintFor(provider)
                },
                meta = when {
                    state.verified -> "ok"
                    state.present -> "da provare"
                    else -> "—"
                },
                onClick = { editing = provider },
            )
        }

        FluidListRow(
            title = "Azioni nell'app",
            subtitle = "Lascia che l'assistente apra schede, salvi posti e crei routine. " +
                "Le cose che scrivono chiedono comunque conferma",
            badge = {
                FluidSwitch(
                    checked = settings.actionsEnabled,
                    onCheckedChange = { on ->
                        scope.launch { assistant.settings.setActionsEnabled(on) }
                    },
                )
            },
        )
    }

    lastError?.let { message ->
        AlertDialog(
            onDismissRequest = { lastError = null },
            title = { Text("La chiave non ha funzionato") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { lastError = null }) { Text("Va bene") } },
        )
    }

    val target = editing
    if (target != null) {
        var draft by remember(target) { mutableStateOf("") }
        val present = states[target]?.present == true
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(target.label) },
            text = {
                Column {
                    Text(
                        "Incolla la tua chiave. Non lascia il telefono: viaggia solo verso " +
                            "${target.label}, e qui dentro sta cifrata.",
                    )
                    Spacer(Modifier.padding(top = 10.dp))
                    Text(hintFor(target))
                    Spacer(Modifier.padding(top = 10.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        placeholder = { Text(placeholderFor(target)) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val key = draft.trim()
                    editing = null
                    if (key.isEmpty()) return@TextButton
                    scope.launch {
                        verifying = target
                        assistant.keys.set(target, key)
                        // Provarla adesso: scoprire che e' sbagliata alla
                        // prima domanda sarebbe il momento peggiore.
                        when (val result = assistant.verifier.verify(target)) {
                            is VerifyResult.Ok -> Unit
                            is VerifyResult.Failed -> {
                                lastError = result.error?.message
                                    ?: "Il servizio non ha risposto. Riprova fra poco."
                            }

                            else -> {
                                assistant.keys.set(target, null)
                                lastError = "Il servizio l'ha rifiutata: controlla di averla copiata tutta."
                            }
                        }
                        verifying = null
                    }
                }) { Text("Salva e prova") }
            },
            dismissButton = {
                if (present) {
                    TextButton(onClick = {
                        editing = null
                        scope.launch { assistant.keys.set(target, null) }
                    }) { Text("Rimuovi") }
                } else {
                    TextButton(onClick = { editing = null }) { Text("Annulla") }
                }
            },
        )
    }
}

private fun hintFor(provider: ProviderId): String = when (provider) {
    ProviderId.GROQ -> "Gratis su console.groq.com — il piu' veloce dei tre"
    ProviderId.GEMINI -> "Gratis su aistudio.google.com"
    ProviderId.OPENROUTER -> "openrouter.ai: un unico accesso a molti modelli"
}

private fun placeholderFor(provider: ProviderId): String = when (provider) {
    ProviderId.GROQ -> "gsk_…"
    ProviderId.GEMINI -> "AIza…"
    ProviderId.OPENROUTER -> "sk-or-…"
}
