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

    class SavePlace(
        val label: String,
        val point: NamedPoint,
        /**
         * Il nome con cui esiste gia' un posto con questa etichetta: salvarlo
         * di nuovo lo SPOSTA, e chi conferma deve saperlo prima, non scoprirlo
         * quando "Casa" e' finita altrove.
         */
        val replaces: String? = null,
    ) : AssistantAction {
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

    /** Toglie la stella a una fermata. */
    class UnstarStop(val idHashHex: String, val name: String) : AssistantAction {
        override val needsConfirmation = true
    }

    /** Toglie la stella a una linea. */
    class UnstarRoute(val idHashHex: String, val shortName: String) : AssistantAction {
        override val needsConfirmation = true
    }

    class RemoveSavedPlace(val id: Long, val label: String) : AssistantAction {
        override val needsConfirmation = true
    }

    /** Ferma la navigazione in corso: si annulla da soli, quindi basta un tocco. */
    data object StopNavigation : AssistantAction {
        override val needsConfirmation = false
    }

    /** Accende o spegne una routine. */
    class SetRoutineEnabled(val id: Long, val label: String, val enabled: Boolean) : AssistantAction {
        override val needsConfirmation = true
    }

    class RemoveRoutine(val id: Long, val label: String) : AssistantAction {
        override val needsConfirmation = true
    }

    /** Riscarica l'orario offline: consuma rete, quindi si chiede. */
    data object RefreshData : AssistantAction {
        override val needsConfirmation = true
    }
}

/**
 * Com'e' andata un'azione.
 *
 * Le ragioni di un fallimento sono distinte perche' il modello le riferisce
 * all'utente: con un solo "non disponibile" ogni guasto diventava "le azioni
 * sono disattivate nelle impostazioni", che e' falso quando la navigazione
 * non parte perche' di notte non ci sono bus, e fa cercare il rimedio nel
 * posto sbagliato.
 */
enum class ActionOutcome {
    DONE,
    REJECTED,
    TIMEOUT,

    /** Le azioni sono spente, o nessuno in grado di eseguirla: vedi [ACTIONS_OFF]. */
    UNAVAILABLE,

    /** La mappa non e' aperta (o non ha risposto): le azioni che la riguardano non hanno dove atterrare. */
    NO_MAP,

    /** Qualcosa si e' rotto mentre si eseguiva: l'azione non e' andata. */
    FAILED,

    /** Non c'e' un itinerario adesso, quindi la navigazione non parte. */
    NO_ITINERARY,

    /** Non si sa da dove parte la persona: niente posizione e nessuna partenza detta. */
    NO_ORIGIN,

    /** Gli orari non sono ancora scaricati. */
    NO_DATA,

    /** L'oggetto dell'azione non c'e' piu' (una routine tolta nel frattempo). */
    NOT_FOUND,
}

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
    ActionOutcome.NO_MAP ->
        "non fatto: la mappa non ha risposto (non e' aperta, o e' occupata); l'utente puo' farlo a mano"
    ActionOutcome.FAILED -> "non fatto: c'e' stato un errore nell'app; l'utente puo' farlo a mano"
    ActionOutcome.NO_ITINERARY ->
        "non fatto: adesso non c'e' nessun itinerario con i mezzi verso quel posto " +
            "(di notte capita), quindi la navigazione non parte"
    ActionOutcome.NO_ORIGIN ->
        "non fatto: non so da dove parti, la posizione non e' disponibile: " +
            "chiedi all'utente da dove parte o di accendere la posizione"
    ActionOutcome.NO_DATA -> "non fatto: gli orari non sono ancora scaricati"
    ActionOutcome.NOT_FOUND -> "non fatto: non c'e' piu', e' stato tolto nel frattempo"
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
