package dev.antigravity.fluidtransit.ai.tools

import dev.antigravity.fluidengine.ai.orchestrator.AiConfirmationGate
import dev.antigravity.fluidengine.ai.tools.AiTool
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * Quanto puo' durare uno strumento dell'assistente di casa, per gruppo.
 *
 * L'engine ha un tetto solo per tutti (`AiOrchestratorConfig.toolTimeoutMillis`)
 * e qui ne servono due. Quelli che leggono stanno in venti secondi: oltre, un
 * feed che non risponde lasciava l'utente a guardare "Guardo gli orari..."
 * per mezzo minuto. Quelli che scrivono (gruppi APP e ROUTINE) aspettano
 * dentro `perform()` il tocco dell'utente, che ha i suoi sessanta secondi:
 * con il tetto di venti la scheda Conferma/Annulla spariva a meta', un tocco
 * tardivo veniva ignorato e il modello riceveva "lo strumento non ha risposto
 * in tempo" invece di "nessuna conferma". `stato_dati aggiorna` e' il caso
 * peggiore: conferma piu' i venti secondi d'attesa dell'aggiornamento.
 *
 * Per questo all'orchestratore si da' il tetto piu' largo
 * ([ORCHESTRATOR_TIMEOUT_MILLIS]) e ogni strumento passa da [timed], che mette
 * il suo. Gli strumenti chiamati da PampAI attraverso il ponte non ci passano:
 * li' il tetto e' quello del ponte, come prima.
 */
object ToolLimits {
    /** Il tetto di uno strumento che legge. */
    const val READ_TIMEOUT_MILLIS = 20_000L

    /** Quanto l'utente ha per toccare Conferma: la finestra del cancello dell'engine. */
    const val CONFIRMATION_TIMEOUT_MILLIS = AiConfirmationGate.DEFAULT_TIMEOUT_MILLIS

    /** L'attesa che uno strumento che scrive si prende DOPO la conferma (l'aggiornamento dei dati: 20 s). */
    const val WRITE_TOOL_WAIT_MILLIS = 25_000L

    /** Il budget di una domanda: una card a schermo vive con novanta secondi. */
    const val TOTAL_BUDGET_MILLIS = 90_000L

    /** Il tempo tenuto da parte per scrivere la risposta. */
    const val FINAL_RESERVE_MILLIS = 15_000L

    /** Il tetto che vede l'orchestratore: il piu' largo, cosi' decidono quelli di [timeoutMillis]. */
    const val ORCHESTRATOR_TIMEOUT_MILLIS = CONFIRMATION_TIMEOUT_MILLIS + WRITE_TOOL_WAIT_MILLIS

    /** I gruppi i cui strumenti aspettano il tocco dell'utente. */
    fun writes(group: ToolGroup?): Boolean = group == ToolGroup.APP || group == ToolGroup.ROUTINE

    /**
     * Il tetto per uno strumento di [group], con [budgetRemainingMillis] ancora
     * da spendere nella domanda: mai oltre quello che resta (meno la riserva
     * per chi scrive), mai sotto i tre secondi.
     */
    fun timeoutMillis(group: ToolGroup?, budgetRemainingMillis: Long): Long =
        if (writes(group)) {
            minOf(ORCHESTRATOR_TIMEOUT_MILLIS, (budgetRemainingMillis - FINAL_RESERVE_MILLIS).coerceAtLeast(3_000))
        } else {
            minOf(READ_TIMEOUT_MILLIS, (budgetRemainingMillis - 5_000).coerceAtLeast(3_000))
        }

    /**
     * Lo stesso strumento, col tetto del suo gruppo. Il budget si conta da
     * `ToolContext.nowMillis`, che la sessione fissa all'inizio della domanda
     * un istante prima dell'orchestratore.
     */
    fun timed(tool: AiTool<ToolContext>, clock: () -> Long = System::currentTimeMillis): AiTool<ToolContext> =
        TimedTool(tool, clock)

    private class TimedTool(
        private val inner: AiTool<ToolContext>,
        private val clock: () -> Long,
    ) : AiTool<ToolContext> by inner {
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
            val remaining = TOTAL_BUDGET_MILLIS - (clock() - ctx.nowMillis)
            val limit = timeoutMillis(inner.group as? ToolGroup, remaining)
            return withTimeoutOrNull(limit) { inner.run(args, ctx) }
                ?: ToolOutput.error("lo strumento non ha risposto in tempo")
        }
    }
}
