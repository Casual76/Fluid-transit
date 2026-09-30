package dev.antigravity.fluidtransit.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.ai.keys.KeyHelp
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
        // Il titolo si dice due volte: sulla riga e sul tasto. L'interruttore
        // e' un nodo a se' per TalkBack, e senza nome leggeva solo
        // "Interruttore, attivo" (vedi "Colori dal telefono" in SettingsTab).
        val titoloAttiva = "Attiva l'assistente"
        FluidListRow(
            title = titoloAttiva,
            // Dipende da chiave E interruttore: con la chiave verificata e l'interruttore
            // spento il sottotitolo prometteva l'assistente, e il microfono restava quello
            // di sistema senza che nessuno dicesse perche'.
            subtitle = KeyHelp.enableSubtitle(
                anyKeyVerified = states.values.any { it.verified },
                enabled = settings.enabled,
            ),
            badge = {
                FluidSwitch(
                    checked = settings.enabled,
                    onCheckedChange = { on -> scope.launch { assistant.settings.setEnabled(on) } },
                    modifier = Modifier.semantics { contentDescription = titoloAttiva },
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
                    // Non "—": in queste righe il trattino restava sospeso
                    // sotto il testo senza dire cosa fare, mentre le altre
                    // due parole lo dicono. Qui la riga e' un'azione, e il
                    // suo stato e' "non l'hai ancora messa".
                    else -> "da impostare"
                },
                onClick = { editing = provider },
            )
        }

        val titoloAzioni = "Azioni nell'app"
        FluidListRow(
            title = titoloAzioni,
            subtitle = "Lascia che l'assistente apra schede, salvi posti e crei routine. " +
                "Le cose che scrivono chiedono comunque conferma",
            badge = {
                FluidSwitch(
                    checked = settings.actionsEnabled,
                    onCheckedChange = { on ->
                        scope.launch { assistant.settings.setActionsEnabled(on) }
                    },
                    modifier = Modifier.semantics { contentDescription = titoloAzioni },
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
                // Scorre: con le spiegazioni il dialogo non sta piu' in uno schermo basso
                // o a carattere grande, e il campo e i tasti devono restare raggiungibili.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(KeyHelp.WHAT_IS_A_KEY)
                    Spacer(Modifier.padding(top = 8.dp))
                    Text(KeyHelp.STEPS)
                    // Il sito del servizio: si apre con un tocco, cosi' non serve ricopiarlo.
                    val uriHandler = LocalUriHandler.current
                    TextButton(onClick = {
                        runCatching { uriHandler.openUri(KeyHelp.url(target)) }
                    }) { Text("Apri ${KeyHelp.host(target)}") }
                    if (KeyHelp.recommended(target)) {
                        Text("Consigliato per cominciare: e' il piu' veloce dei tre.")
                        Spacer(Modifier.padding(top = 8.dp))
                    }
                    // Cosa parte verso il servizio, detto chiaro e a parte dalla chiave: la
                    // garanzia sulla chiave si leggeva come "non esce niente", e non e' vero.
                    Text(KeyHelp.whatIsSent(target))
                    Spacer(Modifier.padding(top = 8.dp))
                    Text(KeyHelp.keyStaysHere(target))
                    Spacer(Modifier.padding(top = 10.dp))
                    // Una chiave e' una password: non si mostra, e la tastiera
                    // non la impara.
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        placeholder = { Text(placeholderFor(target)) },
                        visualTransformation =
                        androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                            autoCorrectEnabled = false,
                        ),
                    )
                    // Togliere la chiave e' un'azione sua, lontana dai due
                    // pulsanti in basso. Prima prendeva il posto di "Annulla":
                    // chi apriva il dialogo solo per guardare, e voleva
                    // chiuderlo, la cancellava.
                    if (present) {
                        Spacer(Modifier.padding(top = 6.dp))
                        TextButton(onClick = {
                            editing = null
                            scope.launch { assistant.keys.set(target, null) }
                        }) { Text("Rimuovi la chiave salvata") }
                    }
                }
            },
            // Col campo vuoto e una chiave gia' salvata il tasto la RIPROVA:
            // la riga dice "toccala per riprovare", e prima il tocco apriva
            // il dialogo e "Salva e prova" lo chiudeva senza fare niente —
            // la chiave andava incollata di nuovo, in un campo che adesso
            // non si vede. Col campo vuoto e nessuna chiave, il tasto e'
            // spento: non c'e' niente da salvare.
            confirmButton = {
                val riprova = present && draft.isBlank()
                TextButton(enabled = draft.isNotBlank() || present, onClick = {
                    val key = draft.trim().ifEmpty { null }
                    editing = null
                    scope.launch {
                        verifying = target
                        if (key != null) assistant.keys.set(target, key)
                        // Provarla adesso: scoprire che e' sbagliata alla
                        // prima domanda sarebbe il momento peggiore.
                        when (val result = assistant.verifier.verify(target)) {
                            // La chiave funziona: si accende l'assistente, se l'utente non ha
                            // mai scelto. Senza, restava spento con "ok" accanto alla chiave.
                            is VerifyResult.Ok -> assistant.settings.enableIfNeverChosen()
                            is VerifyResult.Failed -> {
                                lastError = result.error?.message
                                    ?: "Il servizio non ha risposto. Riprova fra poco."
                            }

                            else -> {
                                assistant.keys.set(target, null)
                                lastError = if (key == null) {
                                    "Il servizio ha rifiutato la chiave salvata: incollala di nuovo."
                                } else {
                                    "Il servizio l'ha rifiutata: controlla di averla copiata tutta."
                                }
                            }
                        }
                        verifying = null
                    }
                }) { Text(if (riprova) "Riprova" else "Salva e prova") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Annulla") }
            },
        )
    }
}

private fun hintFor(provider: ProviderId): String = KeyHelp.rowHint(provider)

private fun placeholderFor(provider: ProviderId): String = when (provider) {
    ProviderId.GROQ -> "gsk_…"
    ProviderId.GEMINI -> "AIza…"
    ProviderId.OPENROUTER -> "sk-or-…"
}
