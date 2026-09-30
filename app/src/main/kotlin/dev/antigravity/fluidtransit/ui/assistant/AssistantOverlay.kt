package dev.antigravity.fluidtransit.ui.assistant

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidGlassIconButton
import dev.antigravity.fluidengine.ui.fluid.FluidHairline
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassEdge
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.ai.orchestrator.AskMode
import dev.antigravity.fluidtransit.ai.orchestrator.AssistantState
import dev.antigravity.fluidtransit.ui.map.GlassActionButton

/**
 * L'assistente sopra la mappa.
 *
 * Non e' una schermata e non e' un dialogo: e' un pannello di vetro appoggiato
 * in basso, che lascia la mappa viva sopra e sotto — cosi' mentre l'assistente
 * cerca una linea si vedono i bus muoversi, che e' esattamente il momento in
 * cui uno vuole guardarli.
 *
 * Il microfono apre la modalita' vocale; scrivendo nella barra di ricerca si
 * arriva qui in modalita' testo. E' la scelta dell'utente: "se premiamo il
 * microfono l'assistente va in modalita' vocale, se scriviamo nella barra il
 * tasto del microfono si trasforma in un tasto per chiedere all'IA".
 */
@Composable
fun AssistantOverlay(
    app: FluidTransitApp,
    backdrop: GlassBackdropState,
    startMode: AskMode,
    initialQuestion: String = "",
    /** Il tocco su un chip: la mappa apre quel posto. */
    onPlace: (String) -> Unit,
    onClose: () -> Unit,
) {
    val session = remember { app.assistant.session }
    val state by session.state.collectAsStateWithLifecycle()
    val mic by session.micLevel.collectAsStateWithLifecycle()
    val pending by session.pendingAction.collectAsStateWithLifecycle()

    var draft by remember { mutableStateOf("") }

    // Una domanda per apertura: senza questa guardia il ricomporre che segue
    // il primo stato rilanciava la stessa domanda.
    var started by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(startMode, initialQuestion) {
        if (started) return@LaunchedEffect
        started = true
        if (startMode == AskMode.VOICE) {
            session.askVoice()
        } else if (initialQuestion.isNotBlank()) {
            session.askText(initialQuestion)
        }
    }

    // Per chi usa un lettore di schermo: il riquadro si annuncia quando si
    // apre, lo stato ("Sto ascoltando", "Confermi?") quando cambia, e la
    // risposta una volta, finita. Prima nessuna delle tre cose si sentiva:
    // si parlava all'assistente e non si sapeva ne' che ascoltava, ne' cosa
    // avesse risposto, ne' che chiedeva una conferma.
    val vista = androidx.compose.ui.platform.LocalView.current
    val risposta = answerText(state)
    LaunchedEffect(state is AssistantState.Done, risposta) {
        if (state is AssistantState.Done && risposta.isNotBlank()) {
            @Suppress("DEPRECATION")
            vista.announceForAccessibility(risposta.take(600))
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .semantics { paneTitle = "Assistente" }
            .glassSurface(
                state = backdrop,
                tint = GlassDefaults.floatingTint(),
                shape = ContinuousCornerShape(FluidRadius.Sheet),
                edge = GlassEdge.None,
                role = GlassRole.Floating,
            ),
    ) {
        // --- testata: cosa sta facendo, e come si chiude --------------------
        //
        // Senza un'altezza fissa. La frase di stato o d'errore ("Serve una
        // chiave: mettila in Impostazioni -> Assistente") col carattere grande
        // va su tre o quattro righe, e con 46 dp fissi se ne vedeva una e
        // mezza, tagliata senza puntini: proprio la parte che dice cosa fare.
        // Adesso la riga cresce col testo (il minimo e' quello di prima) e, se
        // un carattere enorme non bastasse lo stesso, la frase finisce in "...".
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp)
                .heightIn(min = 46.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Halo(level = mic.level, speaking = mic.speaking, busy = state.isBusy)
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (pending != null) "Confermi?" else statusText(state),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp)
                    .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
            )
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Chiudi l'assistente",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(38.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = {
                            session.cancel()
                            session.dismiss()
                            onClose()
                        },
                    )
                    .padding(9.dp),
            )
        }

        val body = answerText(state)
        if (body.isNotBlank()) {
            FluidHairline(modifier = Modifier.padding(horizontal = 20.dp))
            // Il corpo prende quello che resta, non uno spazio fisso. Con la
            // tastiera aperta e una risposta lunga a carattere grande il tetto
            // di 280 dp veniva misurato per primo, e la riga per scrivere in
            // fondo restava con gli avanzi: campo e tasto tagliati a meta', o
            // spariti del tutto se c'erano anche i chip. Con weight(1f, fill =
            // false) la Column misura prima testata, chip, conferma e riga di
            // ingresso, e la risposta scorre nello spazio che avanza (al piu'
            // 280 dp, come prima). L'imePadding in cima le da' un'altezza
            // massima, che e' quello che serve al weight.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MarkdownLite(body)
            }
        }

        // --- a riposo: tre domande da cui cominciare -------------------------
        //
        // "Chiedimi qualcosa" a vuoto non dice cosa l'assistente sa fare, e chi
        // ha appena messo la chiave non lo scopre: tre esempi, toccabili.
        if (state is AssistantState.Idle && draft.isBlank()) {
            FluidHairline(modifier = Modifier.padding(horizontal = 20.dp))
            // Come il corpo della risposta, prende quello che avanza e scorre: a riposo la
            // tastiera e' aperta proprio perche' si e' appena toccato il campo, e con
            // intestazione e tre righe da 48 dp (circa 190 dp) fissi, un telefono piccolo o in
            // orizzontale col carattere grande tagliava il campo di scrittura e il microfono.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "Per esempio",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(bottom = 2.dp)
                        .semantics { heading() },
                )
                for (esempio in dev.antigravity.fluidtransit.ai.orchestrator.AssistantHints.EXAMPLES) {
                    Text(
                        text = esempio,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = { session.askText(esempio) },
                            )
                            .padding(vertical = 6.dp),
                    )
                }
            }
        }

        // --- i posti che ha nominato, da aprire con un tocco ----------------
        val chips = (state as? AssistantState.Done)?.chips.orEmpty()
            .filterIsInstance<dev.antigravity.fluidtransit.ai.orchestrator.AnswerChip.Place>()
        if (chips.isNotEmpty()) {
            // I chip vanno a capo invece di stare tutti su una riga. Coi nomi
            // veri delle fermate ("STAZIONE SANTA MARIA NOVELLA", "SODERINI
            // TORRINO SANTA ROSA") il primo si prendeva quasi tutta la
            // larghezza e gli altri ricevevano quaranta dp, con il testo a capo
            // ogni due lettere: il pannello diventava alto come lo schermo e la
            // riga per scrivere restava senza spazio. Nel FlowRow ogni chip ha
            // al piu' la larghezza intera, e se non ci sta va sotto.
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (chip in chips.take(3)) {
                    GlassActionButton(
                        text = chip.name,
                        icon = null,
                        backdrop = backdrop,
                        maxLines = 2,
                        onClick = { onPlace(chip.name) },
                    )
                }
            }
        }

        // --- la conferma di un'azione che scrive ----------------------------
        //
        // Si guarda l'azione IN ATTESA, non lo stato: lo stato lo scrivono in
        // sei punti diversi l'orchestratore e la sessione, e basta che uno
        // arrivi dopo perche' il tasto non compaia mai — che e' esattamente
        // quello che succedeva.
        val request = pending
        if (request != null) {
            Text(
                text = actionLabel(request.action),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassActionButton(
                    text = "Conferma",
                    icon = null,
                    backdrop = backdrop,
                    onClick = { session.resolveAction(request.id, true) },
                    emphasized = true,
                    modifier = Modifier.weight(1f),
                )
                GlassActionButton(
                    text = "Annulla",
                    icon = null,
                    backdrop = backdrop,
                    onClick = { session.resolveAction(request.id, false) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // --- la riga in fondo: parlare o scrivere ---------------------------
        FluidHairline(modifier = Modifier.padding(horizontal = 20.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                // Il tasto Invio della tastiera diceva "Invia" e non faceva
                // niente: si doveva cercare il pulsante accanto al campo.
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = {
                        if (draft.isNotBlank() && state !is AssistantState.Listening) {
                            session.askText(draft)
                            draft = ""
                        }
                    },
                ),
                decorationBox = { inner ->
                    if (draft.isEmpty()) {
                        Text(
                            text = "Chiedi qualcosa…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                },
                modifier = Modifier.weight(1f),
            )
            if (state is AssistantState.Listening) {
                FluidGlassIconButton(onClick = { session.stopListening() }, backdrop = backdrop) {
                    Icon(
                        imageVector = Icons.Rounded.Stop,
                        contentDescription = "Ho finito di parlare",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            } else if (draft.isBlank()) {
                FluidGlassIconButton(onClick = { session.askVoice() }, backdrop = backdrop) {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = "Parla",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            } else {
                FluidGlassIconButton(
                    onClick = {
                        session.askText(draft)
                        draft = ""
                    },
                    backdrop = backdrop,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Send,
                        contentDescription = "Chiedi",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * L'aureola: un cerchio che respira col microfono mentre ascolta e pulsa
 * piano mentre lavora. Non e' decorazione — e' l'unico segno che dice se il
 * telefono ti sta davvero sentendo, che e' la prima domanda di chi parla a
 * una macchina.
 */
@Composable
private fun Halo(level: Float, speaking: Boolean, busy: Boolean) {
    val target = when {
        speaking -> 1f + level.coerceIn(0f, 1f) * 0.5f
        busy -> 1.15f
        else -> 1f
    }
    val scale by animateFloatAsState(targetValue = target, label = "halo")
    Box(
        modifier = Modifier.size(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size((12 * scale).dp)
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                ),
        )
    }
}

/** Cosa sta facendo, in parole d'uso. */
private fun statusText(state: AssistantState): String = when (state) {
    AssistantState.Idle -> "Chiedimi qualcosa"
    is AssistantState.Listening -> "Ti ascolto…"
    AssistantState.Transcribing -> "Un attimo…"
    AssistantState.HeardNothing -> "Non ho sentito niente"
    is AssistantState.Classifying -> "Capisco cosa serve…"
    is AssistantState.Working -> when (state.statusKey) {
        "places" -> "Cerco il posto…"
        "schedule" -> "Guardo gli orari…"
        "live" -> "Guardo dove sono i mezzi…"
        "journey" -> "Calcolo il viaggio…"
        "app" -> "Preparo…"
        "more_tools" -> "Mi serve dell'altro…"
        else -> "Ci penso…"
    }

    is AssistantState.WaitingRateLimit -> "Il servizio e' a limite: riprovo fra ${state.secondsLeft} s"
    is AssistantState.SwitchingProvider -> "Cambio servizio…"
    is AssistantState.Answering -> "Rispondo…"
    is AssistantState.AwaitingConfirmation -> "Confermi?"
    is AssistantState.Done -> "Fatto"
    is AssistantState.Failed -> failureText(state)
    is AssistantState.Cancelled -> "Annullato"
}

private fun failureText(state: AssistantState.Failed): String = when (state.kind) {
    dev.antigravity.fluidtransit.ai.orchestrator.FailureKind.NO_KEYS ->
        "Serve una chiave: mettila in Impostazioni → Assistente"
    dev.antigravity.fluidtransit.ai.orchestrator.FailureKind.UNAUTHORIZED ->
        "La chiave non e' valida: controllala in Impostazioni"
    dev.antigravity.fluidtransit.ai.orchestrator.FailureKind.RATE_LIMITED ->
        "Il servizio e' a limite: riprova fra poco"
    dev.antigravity.fluidtransit.ai.orchestrator.FailureKind.NETWORK ->
        "Niente rete"
    dev.antigravity.fluidtransit.ai.orchestrator.FailureKind.TIMEOUT ->
        "Ci ha messo troppo"
    dev.antigravity.fluidtransit.ai.orchestrator.FailureKind.MICROPHONE ->
        "Il microfono non parte: forse lo sta usando un'altra app"
    dev.antigravity.fluidtransit.ai.orchestrator.FailureKind.TRANSCRIPTION ->
        "Non sono riuscito a capire l'audio"
    else -> "Non ha funzionato"
}

/** Il testo da mostrare: la risposta, o quello che c'e' arrivato finora. */
private fun answerText(state: AssistantState): String = when (state) {
    is AssistantState.Answering -> state.partial
    is AssistantState.Done -> state.answer
    is AssistantState.Failed -> state.partial.orEmpty()
    is AssistantState.Cancelled -> state.partial.orEmpty()
    else -> ""
}

/**
 * L'azione, detta all'utente prima di chiedergli di confermarla: la frase e'
 * di `:core-ai` ([ActionText]), esaustiva, cosi' nessuna azione ricade in un
 * "Confermi?" che non dice cosa sta per succedere.
 */
private fun actionLabel(
    action: dev.antigravity.fluidtransit.ai.tools.AssistantAction,
): String = dev.antigravity.fluidtransit.ai.tools.ActionText.confirm(action)
