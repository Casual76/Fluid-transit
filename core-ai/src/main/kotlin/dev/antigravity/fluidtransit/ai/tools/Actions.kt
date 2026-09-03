package dev.antigravity.fluidtransit.ai.tools

/**
 * Le azioni che l'assistente puo' fare nell'app.
 *
 * La regola decisa con l'utente e' "mostra subito, agisci con conferma":
 * portare la mappa da qualche parte o calcolare un itinerario sono gesti
 * reversibili e avvengono all'istante; scrivere qualcosa — un posto salvato,
 * una stella, una routine — o avviare la navigazione passa da un tocco.
 *
 * L'azione non la esegue lo strumento: la chiede a [ActionSink], che e' la
 * sessione, che a sua volta esegue o gira la domanda alla UI. Cosi' un tool
 * resta una funzione pura di dati e testo.
 */
sealed interface AssistantAction {
    val needsConfirmation: Boolean

    /** Porta la mappa su un punto e apri il suo pannello. */
    class ShowPlace(val point: NamedPoint) : AssistantAction {
        override val needsConfirmation = false
    }

    /** Apri la scheda di una fermata. */
    class ShowStop(val idHashHex: String, val name: String) : AssistantAction {
        override val needsConfirmation = false
    }

    /** Accendi una linea sulla mappa e aprine la scheda. */
    class ShowRoute(val routeIndex: Int, val shortName: String) : AssistantAction {
        override val needsConfirmation = false
    }

    /** Mostra gli itinerari fra due punti. */
    class ShowJourneys(
        val from: NamedPoint?,
        val to: NamedPoint,
        val departAtEpoch: Long?,
        val arriveByEpoch: Long?,
    ) : AssistantAction {
        override val needsConfirmation = false
    }

    /** Avvia la navigazione sul primo itinerario mostrato. */
    class StartNavigation(val to: NamedPoint) : AssistantAction {
        override val needsConfirmation = true
    }

    class SavePlace(val label: String, val point: NamedPoint) : AssistantAction {
        override val needsConfirmation = true
    }

    class StarStop(val idHashHex: String, val name: String) : AssistantAction {
        override val needsConfirmation = true
    }

    class StarRoute(val routeIndex: Int, val shortName: String) : AssistantAction {
        override val needsConfirmation = true
    }

    /**
     * Una routine: giorni della settimana e un'ora di ancoraggio, come nella
     * scheda del viaggio.
     */
    class CreateRoutine(
        val label: String,
        val from: NamedPoint?,
        val to: NamedPoint,
        /** Lunedi' = 1 … Domenica = 7, come java.time.DayOfWeek. */
        val days: Set<Int>,
        /** "arrive" oppure "depart". */
        val anchor: String,
        val anchorMinutes: Int,
    ) : AssistantAction {
        override val needsConfirmation = true
    }
}

enum class ActionOutcome { DONE, REJECTED, TIMEOUT, UNAVAILABLE }

interface ActionSink {
    suspend fun perform(action: AssistantAction): ActionOutcome

    /** Quando le azioni sono spente nelle impostazioni. */
    object Disabled : ActionSink {
        override suspend fun perform(action: AssistantAction) = ActionOutcome.UNAVAILABLE
    }
}

internal const val ACTIONS_OFF =
    "le azioni nell'app sono disattivate nelle impostazioni dell'assistente: l'utente puo' farlo a mano"

internal fun outcomeText(outcome: ActionOutcome, done: String): String = when (outcome) {
    ActionOutcome.DONE -> done
    ActionOutcome.REJECTED -> "l'utente ha annullato"
    ActionOutcome.TIMEOUT -> "nessuna conferma dall'utente: non fatto"
    ActionOutcome.UNAVAILABLE -> ACTIONS_OFF
}

/**
 * Le pagine dell'app che l'assistente puo' indicare a fine risposta con un
 * marcatore `[[...]]`: diventano chip toccabili sotto il testo.
 */
enum class OpenTarget(val id: String) {
    MAP("mappa"),
    TODAY("oggi"),
    FAVOURITES("preferiti"),
    SETTINGS("impostazioni"),
    ;

    companion object {
        fun fromId(id: String?): OpenTarget? =
            entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}
