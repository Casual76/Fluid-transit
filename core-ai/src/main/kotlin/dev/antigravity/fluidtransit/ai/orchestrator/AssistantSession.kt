package dev.antigravity.fluidtransit.ai.orchestrator

import dev.antigravity.fluidengine.ai.keys.AiSettingsStore
import dev.antigravity.fluidengine.ai.net.AiError
import dev.antigravity.fluidengine.ai.orchestrator.AiConfirmationGate
import dev.antigravity.fluidengine.ai.orchestrator.AiOrchestrator
import dev.antigravity.fluidengine.ai.orchestrator.AskInput
import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.antigravity.fluidengine.ai.orchestrator.AssistantFailure
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.orchestrator.ConfirmationOutcome
import dev.antigravity.fluidengine.ai.orchestrator.Conversation
import dev.antigravity.fluidengine.ai.orchestrator.FailureKind
import dev.antigravity.fluidengine.ai.orchestrator.MicLevel
import dev.antigravity.fluidengine.ai.orchestrator.PendingConfirmation
import dev.antigravity.fluidengine.ai.provider.ProviderFactory
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.ai.speech.SpeechCapture
import dev.antigravity.fluidengine.ai.speech.Transcriber
import dev.antigravity.fluidtransit.ai.tools.ActionOutcome
import dev.antigravity.fluidtransit.ai.tools.ActionSink
import dev.antigravity.fluidtransit.ai.tools.ActionText
import dev.antigravity.fluidtransit.ai.tools.AssistantAction
import dev.antigravity.fluidtransit.ai.tools.ToolContext
import dev.antigravity.fluidtransit.ai.tools.TransitBridge
import java.io.File
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Chi esegue davvero le azioni: la mappa, i preferiti, le routine.
 *
 * La sessione si occupa della conferma e degli stati; toccare l'app e'
 * lavoro di chi l'app ce l'ha in mano. Ritorna com'e' andata, con la ragione
 * quando non e' andata: un semplice true/false faceva dire all'assistente
 * "fatto" appena l'azione era stata messa in coda, anche se la mappa poi non
 * faceva niente (navigazione senza itinerario, routine senza partenza).
 */
fun interface ActionExecutor {
    suspend fun execute(action: AssistantAction): ActionOutcome
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
 * Il trasporto, l'orchestratore, la voce e gli stati sono quelli
 * dell'engine (`engine-ai`); qui resta quello che e' di Fluid Transit: il
 * prompt e il suo contesto, il vocabolario della voce, le azioni con la loro
 * frase di conferma, i chip che l'app capisce.
 *
 * La UI osserva [state], [micLevel], [pendingConfirmation]; chiama
 * [askText], [askVoice], [stopListening], [cancel], [resolveConfirmation],
 * [dismiss], [reset].
 */
class AssistantSession(
    private val scope: CoroutineScope,
    private val transit: TransitBridge,
    private val settingsStore: AiSettingsStore,
    private val providers: ProviderFactory,
    private val orchestrator: AiOrchestrator<ToolContext>,
    private val transcriber: Transcriber,
    private val speechFactory: () -> SpeechCapture,
    private val cacheDir: File,
    private val executor: ActionExecutor,
    private val status: () -> AppStatusInfo,
    private val gate: AiConfirmationGate,
    /** Un provider ha detto che un modello non c'e' piu': l'app riallinea le scelte salvate. */
    private val onModelUnavailable: (ProviderId, String) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) : ActionSink {

    private val stateFlow = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val state: StateFlow<AssistantState> = stateFlow

    private val conversationFlow = MutableStateFlow(Conversation(0L, clock()))
    val conversation: StateFlow<Conversation> = conversationFlow

    /**
     * L'azione che aspetta il si', con le parole per dirla: la tiene il
     * cancello dell'engine. La UI guarda questa e non lo stato, perche' lo
     * stato lo scrivono in sei punti diversi l'orchestratore e la sessione.
     */
    val pendingConfirmation: StateFlow<PendingConfirmation?> = gate.current

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
        gate.cancel()
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

    fun resolveConfirmation(id: Long, confirmed: Boolean) = gate.resolve(id, confirmed)

    private fun start(block: suspend () -> Unit) {
        job?.cancel()
        gate.cancel()
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                // Lo stato Cancelled lo scrive cancel(): qui non c'e' altro da dire.
            } catch (e: AssistantFailure) {
                stateFlow.value = AssistantState.Failed(
                    question = stateFlow.value.questionOrNull(),
                    kind = e.kind,
                    error = e.error,
                    retryAfterSec = e.retryAfterSec,
                    partial = (stateFlow.value as? AssistantState.Answering)?.partial,
                    provider = e.provider,
                    reason = e.reason,
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
                        // Un tetto anche qui, come la domanda scritta ha i suoi 90
                        // secondi: su un collegamento morto ma "connesso" la
                        // trascrizione restava sotto "Un attimo..." fino a dieci
                        // secondi di connessione piu' sessanta di lettura, per ogni
                        // provider. Il timeout delle coroutine da solo non basta,
                        // perche' la socket non si lascia interrompere: a fermare
                        // davvero la richiesta ci pensa AiHttp, che alla
                        // cancellazione chiude la connessione.
                        val heard = try {
                            withTimeoutOrNull(TRANSCRIPTION_LIMIT_MILLIS) {
                                transcriber.transcribe(
                                    event.file,
                                    LANGUAGE,
                                    Transcriber.hint(TRANSCRIPTION_DOMAIN, vocabulary),
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            throw transcriptionFailure(e)
                        }
                        val transcription = heard ?: throw AssistantFailure(FailureKind.TIMEOUT, null)
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

    /**
     * Il guasto di una trascrizione, con la sua vera ragione.
     *
     * Prima ogni errore diventava "Non sono riuscito a capire l'audio", anche quando l'audio era
     * perfetto e mancava la rete: su un treno senza campo l'utente alzava la voce e ripeteva, e non
     * serviva a niente. Solo quello che davvero non si capisce (una richiesta rifiutata, una risposta
     * illeggibile) resta una trascrizione fallita; il resto dice quello che la domanda scritta dice
     * gia': niente rete, ci ha messo troppo, il servizio e' a limite.
     */
    private fun transcriptionFailure(e: Throwable): AssistantFailure = AssistantFailure(
        kind = FailureKinds.of(e, other = FailureKind.TRANSCRIPTION),
        error = e as? AiError,
        retryAfterSec = (e as? AiError.RateLimited)?.retryAfterSec?.let { ceil(it).toInt() },
    )

    private suspend fun run(question: String, mode: AskMode) {
        val now = clock()
        var conversation = conversationFlow.value
        if (conversation.isExpired(now)) {
            conversation = Conversation(ids.getAndIncrement(), now)
            conversationFlow.value = conversation
        }
        conversation.lastActivityMillis = now
        val settings = settingsStore.current()
        // Due livelli, come l'assistente ha sempre avuto: il router e la chat.
        // Il profondo dell'engine (che la verifica della chiave sceglie dal
        // catalogo) qui si spegne, cosi' nessuna domanda passa a un modello
        // piu' lento o piu' caro di quello che l'utente conosce.
        val ordered = providers.ordered(ProviderFactory.Kind.CHAT).map { it.copy(deepModel = null) }
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
            actionsEnabled = settings.actionsEnabled,
            forceFinalPrompt = PromptBuilder.forceFinal(),
            chipFilter = TransitChips::accepts,
            onModelUnavailable = onModelUnavailable,
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
            tierReached = result.tierReached,
            switches = result.log.switches,
        )
    }

    // ------------------------------------------------------------- ActionSink

    /**
     * Una scheda di conferma per volta.
     *
     * Il prompt invita a chiamare piu' strumenti insieme, e "togli la stella a X e alla linea 6"
     * produce due azioni nello stesso giro. Il cancello dell'engine ne tiene una sola: una seconda
     * richiesta fa cadere la prima come rifiutata, e la prima stella restava li' senza che
     * l'utente avesse detto no. Con il lucchetto le azioni si chiedono in fila, ognuna con la sua
     * scheda e la sua finestra di sessanta secondi.
     */
    private val confirmation = Mutex()

    override suspend fun perform(action: AssistantAction): ActionOutcome {
        if (!action.needsConfirmation) return execute(action)
        return when (confirmation.withLock { askConfirmation(action) }) {
            ConfirmationOutcome.CONFIRMED -> execute(action)
            ConfirmationOutcome.REJECTED -> ActionOutcome.REJECTED
            ConfirmationOutcome.TIMEOUT -> ActionOutcome.TIMEOUT
        }
    }

    /**
     * La domanda al cancello, con lo stato che la dice: "Confermi?" in testa
     * finche' l'utente non sceglie, poi lo stato di prima. La frase e' di
     * [ActionText], esaustiva, cosi' nessuna azione ricade in un "Confermi?"
     * che non dice cosa sta per succedere.
     */
    private suspend fun askConfirmation(action: AssistantAction): ConfirmationOutcome = coroutineScope {
        val previous = stateFlow.value
        var own: AssistantState? = null
        // Il cancello da' un id alla richiesta solo quando la apre: lo stato la
        // segue da li'.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            val request = gate.current.filterNotNull().first()
            val awaiting = AssistantState.AwaitingConfirmation(
                previous.questionOrNull().orEmpty(),
                request,
                (previous as? AssistantState.Working)?.provider ?: ProviderId.GROQ,
            )
            own = awaiting
            stateFlow.value = awaiting
        }
        try {
            gate.ask(ActionText.confirm(action), null)
        } finally {
            watcher.cancel()
            // Lo stato si rimette solo se e' ancora il MIO: se nel frattempo e' arrivato altro
            // (una cancellazione, un errore) non lo si calpesta con uno vecchio.
            if (own != null && stateFlow.value === own) stateFlow.value = previous
        }
    }

    private suspend fun execute(action: AssistantAction): ActionOutcome = executor.execute(action)

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
        /**
         * Quanto aspettare la trascrizione di una domanda parlata (al piu' trenta secondi di WAV).
         * Abbastanza per caricare mezzo mega su un collegamento lento, poco per non lasciare un
         * cerchio che gira per minuti.
         */
        const val TRANSCRIPTION_LIMIT_MILLIS = 40_000L

        /** L'app e' in italiano, e i dati pure: la lingua non e' una variabile. */
        const val LANGUAGE = "it"

        /**
         * La frase con cui Whisper sa di che cosa si parla, prima dei nomi
         * dell'utente. Quella di prima parlava di meteo ("pioggia, neve,
         * radar"): era rimasta dalla copia di FluidWeather.
         */
        const val TRANSCRIPTION_DOMAIN =
            "Domande sui mezzi pubblici in Toscana: autobus, tramvia, fermate, linee, orari, " +
                "ritardi, partenze, arrivi, itinerari, posti salvati."
    }
}
