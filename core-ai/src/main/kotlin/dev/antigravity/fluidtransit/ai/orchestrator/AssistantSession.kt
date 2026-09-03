package dev.antigravity.fluidtransit.ai.orchestrator

import dev.antigravity.fluidtransit.ai.keys.AiSettingsStore
import dev.antigravity.fluidtransit.ai.net.AiError
import dev.antigravity.fluidtransit.ai.provider.ProviderFactory
import dev.antigravity.fluidtransit.ai.provider.ProviderId
import dev.antigravity.fluidtransit.ai.speech.SpeechCapture
import dev.antigravity.fluidtransit.ai.speech.Transcriber
import dev.antigravity.fluidtransit.ai.tools.ActionOutcome
import dev.antigravity.fluidtransit.ai.tools.ActionSink
import dev.antigravity.fluidtransit.ai.tools.AssistantAction
import dev.antigravity.fluidtransit.ai.tools.OpenTarget
import dev.antigravity.fluidtransit.ai.tools.ToolContext
import dev.antigravity.fluidtransit.ai.tools.TransitBridge
import java.io.File
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Un'azione che deve fare la UI (aprire una pagina): la sessione la chiede, la shell la esegue. */
sealed interface UiCommand {
    class Open(val target: OpenTarget) : UiCommand
}

/**
 * Chi esegue davvero le azioni: la mappa, i preferiti, le routine.
 *
 * La sessione si occupa della conferma e degli stati; toccare l'app e'
 * lavoro di chi l'app ce l'ha in mano. Ritorna true se l'azione e' andata.
 */
fun interface ActionExecutor {
    suspend fun execute(action: AssistantAction): Boolean
}

/** Lo stato dell'app che il prompt racconta al modello. */
class AppStatusInfo(
    val placeLabel: String?,
    val bundleReady: Boolean,
    val placesReady: Boolean,
    val realtimeState: String,
    val feedAgeSeconds: Int?,
)

/**
 * La sessione dell'assistente, una per processo, nello scope
 * dell'Application: la domanda gira qui e non nel composable, quindi
 * sopravvive a uno scroll, a un cambio di scheda e a un'uscita dall'app.
 *
 * La UI osserva [state], [conversation], [pendingAction] e [commands]; chiama
 * [askText], [askVoice], [stopListening], [cancel], [resolveAction], [reset].
 */
class AssistantSession(
    private val scope: CoroutineScope,
    private val transit: TransitBridge,
    private val settingsStore: AiSettingsStore,
    private val providers: ProviderFactory,
    private val orchestrator: AssistantOrchestrator,
    private val transcriber: Transcriber,
    private val speechFactory: () -> SpeechCapture,
    private val cacheDir: File,
    private val executor: ActionExecutor,
    private val status: () -> AppStatusInfo,
    private val clock: () -> Long = System::currentTimeMillis,
) : ActionSink {

    private val stateFlow = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val state: StateFlow<AssistantState> = stateFlow

    private val conversationFlow = MutableStateFlow(Conversation(0L, clock()))
    val conversation: StateFlow<Conversation> = conversationFlow

    private val pending = MutableStateFlow<PendingAction?>(null)
    val pendingAction: StateFlow<PendingAction?> = pending

    private val commandFlow = MutableSharedFlow<UiCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<UiCommand> = commandFlow

    /** L'ultima modalita' usata: la card sa come presentarsi. */
    val lastMode = MutableStateFlow(AskMode.TEXT)

    private var job: Job? = null

    /** Scritto dal worker e letto dal thread della UI quando si tocca "ferma": deve attraversare. */
    @Volatile
    private var speech: SpeechCapture? = null
    private val ids = AtomicLong(1)

    /**
     * Il livello del microfono, 0..1, e se in questo istante e' parlato.
     * Fuori dallo stato apposta: lo legge solo l'aureola, cinquanta volte al
     * secondo, senza ricomporre nient'altro.
     */
    private val micLevelFlow = MutableStateFlow(MicLevel())
    val micLevel: StateFlow<MicLevel> = micLevelFlow

    class PendingAction(
        val id: Long,
        val action: AssistantAction,
        internal val answer: CompletableDeferred<Boolean>,
    )

    val isBusy: Boolean get() = stateFlow.value.isBusy

    fun askText(question: String) {
        val text = question.trim()
        if (text.isEmpty()) return
        lastMode.value = AskMode.TEXT
        start { run(text, AskMode.TEXT) }
    }

    fun askVoice() {
        // Un ascolto per volta. Senza questa riga due chiamate ravvicinate
        // aprivano due AudioRecord sullo stesso microfono, e il secondo
        // consegnava silenzio.
        if (stateFlow.value is AssistantState.Listening) return
        lastMode.value = AskMode.VOICE
        start {
            val question = listen() ?: return@start
            run(question, AskMode.VOICE)
        }
    }

    fun stopListening() {
        speech?.stopNow()
    }

    fun cancel() {
        val current = stateFlow.value
        job?.cancel(CancellationException("annullato dall'utente"))
        job = null
        speech = null
        pending.value?.answer?.complete(false)
        pending.value = null
        stateFlow.value = AssistantState.Cancelled(
            question = current.questionOrNull(),
            partial = (current as? AssistantState.Answering)?.partial,
        )
    }

    /** La card dopo la lettura: torna a riposo senza perdere la conversazione. */
    fun dismiss() {
        if (!stateFlow.value.isBusy) stateFlow.value = AssistantState.Idle
    }

    fun reset() {
        cancel()
        conversationFlow.value = Conversation(ids.getAndIncrement(), clock())
        stateFlow.value = AssistantState.Idle
    }

    fun resolveAction(id: Long, confirmed: Boolean) {
        val current = pending.value ?: return
        if (current.id != id) return
        current.answer.complete(confirmed)
    }

    private fun start(block: suspend () -> Unit) {
        job?.cancel()
        pending.value = null
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                // Lo stato Cancelled lo scrive cancel(): qui non c'e' altro da dire.
            } catch (e: AssistantFailure) {
                stateFlow.value = AssistantState.Failed(
                    stateFlow.value.questionOrNull(), e.kind, e.error, e.retryAfterSec,
                    (stateFlow.value as? AssistantState.Answering)?.partial,
                )
            } catch (e: Throwable) {
                stateFlow.value = AssistantState.Failed(
                    stateFlow.value.questionOrNull(), FailureKind.UNKNOWN, e as? AiError, null, null,
                )
            }
        }
    }

    private suspend fun listen(): String? {
        val capture = speechFactory()
        speech = capture
        // Un file per ascolto: col nome fisso, due catture sovrapposte si
        // sovrascrivevano e la seconda cancellava il WAV della prima mentre
        // lo si stava trascrivendo.
        val file = File(cacheDir, "ai/ask-${ids.get()}-${clock()}.wav")
        var result: String? = null
        try {
            stateFlow.value = AssistantState.Listening(0L)
            micLevelFlow.value = MicLevel()
            capture.record(file).collect { event ->
                when (event) {
                    is SpeechCapture.Event.Level -> {
                        micLevelFlow.value = MicLevel(event.level, event.speaking)
                        val elapsed = event.elapsedMillis / 1000 * 1000
                        val shown = stateFlow.value
                        // Lo stato cambia una volta al secondo, non cinquanta:
                        // il livello viaggia per conto suo.
                        if (shown !is AssistantState.Listening || shown.elapsedMillis != elapsed) {
                            stateFlow.value = AssistantState.Listening(elapsed)
                        }
                    }

                    SpeechCapture.Event.SpeechStarted -> Unit

                    is SpeechCapture.Event.Empty -> {
                        stateFlow.value = AssistantState.HeardNothing
                    }

                    // Il microfono che non parte non e' una trascrizione
                    // fallita: e' un'altra cosa, con un altro rimedio.
                    is SpeechCapture.Event.Failed -> throw AssistantFailure(FailureKind.MICROPHONE, null)

                    is SpeechCapture.Event.Finished -> {
                        stateFlow.value = AssistantState.Transcribing
                        // Il vocabolario aiuta Whisper sui nomi che sbaglia
                        // sempre: fermate toscane, posti salvati, linee.
                        val vocabulary = transit.savedPlaces().map { it.name } +
                            transit.favouriteStops().map { it.name } +
                            transit.favouriteRouteNames()
                        val transcription = try {
                            transcriber.transcribe(
                                event.file,
                                LANGUAGE,
                                Transcriber.hint(LANGUAGE, vocabulary),
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: AiError.Unauthorized) {
                            throw AssistantFailure(FailureKind.UNAUTHORIZED, e)
                        } catch (e: Throwable) {
                            throw AssistantFailure(FailureKind.TRANSCRIPTION, e as? AiError)
                        }
                        result = transcription.text.takeIf { it.isNotBlank() }
                        if (result == null) stateFlow.value = AssistantState.HeardNothing
                    }
                }
            }
        } finally {
            speech = null
            micLevelFlow.value = MicLevel()
            runCatching { file.delete() }
        }
        return result
    }

    private suspend fun run(question: String, mode: AskMode) {
        val now = clock()
        var conversation = conversationFlow.value
        if (conversation.isExpired(now)) {
            conversation = Conversation(ids.getAndIncrement(), now)
            conversationFlow.value = conversation
        }
        conversation.lastActivityMillis = now
        val settings = settingsStore.current()
        val ordered = providers.ordered(ProviderFactory.Kind.CHAT)
        if (ordered.isEmpty()) throw AssistantFailure(FailureKind.NO_KEYS, null)

        val toolContext = ToolContext(
            transit = transit,
            locale = Locale.ITALIAN,
            zone = ZoneId.systemDefault(),
            nowMillis = now,
            actionsEnabled = settings.actionsEnabled,
            actions = if (settings.actionsEnabled) this else ActionSink.Disabled,
        )
        val info = status()
        val prompt = PromptContext(
            placeLabel = info.placeLabel,
            savedPlaces = transit.savedPlaces().map { it.name },
            favouriteRoutes = transit.favouriteRouteNames(),
            bundleReady = info.bundleReady,
            placesReady = info.placesReady,
            realtimeState = info.realtimeState,
            feedAgeSeconds = info.feedAgeSeconds,
            actionsEnabled = settings.actionsEnabled,
            mode = mode,
        )
        val input = AskInput(
            question = question,
            mode = mode,
            language = LANGUAGE,
            settings = settings,
            providers = ordered,
            toolContext = toolContext,
            systemPrompt = PromptBuilder.build(toolContext, prompt),
            conversation = conversation,
        )
        val result = orchestrator.ask(input, stateFlow)
        // La conversazione e' mutata dentro: la UI la rilegge dallo stesso oggetto.
        conversationFlow.value = conversation
        stateFlow.value = AssistantState.Done(
            question = question,
            answer = result.answer,
            chips = result.chips,
            provider = result.provider,
            mode = mode,
            usage = result.usage,
            toolsUsed = result.toolsUsed,
            durationMillis = result.log.durationMillis,
        )
    }

    // ------------------------------------------------------------- ActionSink

    override suspend fun perform(action: AssistantAction): ActionOutcome {
        if (!action.needsConfirmation) return execute(action)
        val request = PendingAction(ids.getAndIncrement(), action, CompletableDeferred())
        val previous = stateFlow.value
        pending.value = request
        stateFlow.value = AssistantState.AwaitingConfirmation(
            previous.questionOrNull().orEmpty(),
            action,
            (previous as? AssistantState.Working)?.provider ?: ProviderId.GROQ,
        )
        val confirmed = try {
            withTimeoutOrNull(CONFIRMATION_TIMEOUT_MILLIS) { request.answer.await() }
        } finally {
            if (pending.value?.id == request.id) pending.value = null
            if (stateFlow.value is AssistantState.AwaitingConfirmation) stateFlow.value = previous
        }
        return when (confirmed) {
            null -> ActionOutcome.TIMEOUT
            false -> ActionOutcome.REJECTED
            true -> execute(action)
        }
    }

    private suspend fun execute(action: AssistantAction): ActionOutcome =
        if (executor.execute(action)) ActionOutcome.DONE else ActionOutcome.UNAVAILABLE

    private fun AssistantState.questionOrNull(): String? = when (this) {
        is AssistantState.Classifying -> question
        is AssistantState.Working -> question
        is AssistantState.WaitingRateLimit -> question
        is AssistantState.SwitchingProvider -> question
        is AssistantState.Answering -> question
        is AssistantState.AwaitingConfirmation -> question
        is AssistantState.Done -> question
        is AssistantState.Failed -> question
        is AssistantState.Cancelled -> question
        else -> null
    }

    companion object {
        const val CONFIRMATION_TIMEOUT_MILLIS = 60_000L

        /** L'app e' in italiano, e i dati pure: la lingua non e' una variabile. */
        const val LANGUAGE = "it"
    }
}
