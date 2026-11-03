package dev.antigravity.fluidtransit.ui.assistant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.aiui.overlay.FluidAssistantActions
import dev.antigravity.fluidengine.aiui.overlay.FluidAssistantDock
import dev.antigravity.fluidengine.aiui.strings.FluidAssistantStrings
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.ai.orchestrator.AssistantHints
import dev.antigravity.fluidtransit.ai.orchestrator.TransitChips

/**
 * Le parole dell'assistente che sono di Fluid Transit: le frasi dei sei gruppi
 * di strumenti ("Cerco il posto...") e quella della conferma. Il resto — i
 * fallimenti, il limite di richieste, il cambio di servizio — e' del pannello
 * dell'engine, uguale per tutte le app.
 */
val TransitAssistantStrings = FluidAssistantStrings(
    // Sul pannello a conferma ferma, e' una domanda a cui si risponde con i due tasti.
    needsConfirmation = "Confermi?",
    // Il ripiego sul servizio successivo qui e' sempre acceso, non e' un interruttore: la frase
    // dell'engine che consiglia di accenderlo non direbbe il vero.
    failRateLimitedIn = "%1\$s è al limite: riprova fra %2\$d s.",
    failRateLimited = "%1\$s è al limite di richieste: riprova fra poco.",
    statusFor = { key, _ ->
        when (key) {
            "places" -> "Cerco il posto…"
            "schedule" -> "Guardo gli orari…"
            "live" -> "Guardo dove sono i mezzi…"
            "journey" -> "Calcolo il viaggio…"
            "app" -> "Preparo…"
            else -> null
        }
    },
)

/**
 * L'assistente sopra la mappa.
 *
 * Non e' una schermata e non e' un dialogo: e' il pannello ancorato in basso
 * dell'engine (`FluidAssistantDock`), che lascia la mappa viva sopra e sotto —
 * cosi' mentre l'assistente cerca una linea si vedono i bus muoversi, che e'
 * esattamente il momento in cui uno vuole guardarli. Il disegno, la conferma,
 * i chip, la riga d'ingresso e quello che serve a TalkBack sono dell'engine;
 * qui restano la sessione di Fluid Transit, gli esempi e i posti.
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
    val pending by session.pendingConfirmation.collectAsStateWithLifecycle()

    // Una domanda per apertura: senza questa guardia il ricomporre che segue
    // il primo stato rilanciava la stessa domanda.
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(startMode, initialQuestion) {
        if (started) return@LaunchedEffect
        started = true
        if (startMode == AskMode.VOICE) {
            session.askVoice()
        } else if (initialQuestion.isNotBlank()) {
            session.askText(initialQuestion)
        }
    }

    val actions = remember(session, onPlace) {
        FluidAssistantActions(
            onAskText = session::askText,
            onAskVoice = session::askVoice,
            onStopListening = session::stopListening,
            onCancel = session::cancel,
            onDismiss = session::dismiss,
            onConfirm = session::resolveConfirmation,
            onChip = { chip -> TransitChips.place(chip)?.let(onPlace) },
        )
    }

    FluidAssistantDock(
        state = state.withPlaceChipsOnly(),
        micLevel = mic,
        pending = pending,
        backdrop = backdrop,
        actions = actions,
        strings = TransitAssistantStrings,
        chipLabel = { TransitChips.place(it).orEmpty() },
        suggestions = AssistantHints.EXAMPLES,
        onClose = onClose,
    )
}

/**
 * Sotto la risposta compaiono i posti che l'assistente ha nominato, da aprire
 * sulla mappa. L'altro tipo di chip che l'app accetta (una pagina: "mappa",
 * "preferiti") non e' mai stato un tasto di questo pannello, e il pannello
 * dell'engine mostrerebbe ogni chip che trova: qui si tolgono prima.
 */
private fun AssistantState.withPlaceChipsOnly(): AssistantState =
    if (this is AssistantState.Done) {
        // "luogo:Careggi" e "place:Careggi" sono lo stesso posto: un chip solo.
        copy(chips = chips.filter { TransitChips.place(it) != null }.distinctBy { TransitChips.place(it) })
    } else {
        this
    }
