package dev.antigravity.fluidtransit.ai.orchestrator

import dev.antigravity.fluidengine.ai.orchestrator.AiConfirmationGate
import dev.antigravity.fluidengine.ai.orchestrator.AiDiagnosticsLog
import dev.antigravity.fluidengine.ai.orchestrator.AiOrchestrator
import dev.antigravity.fluidengine.ai.orchestrator.AiRouter
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.ai.tools.ToolRegistry
import dev.antigravity.fluidtransit.ai.AiAssistant
import dev.antigravity.fluidtransit.ai.keys.KeyHelp
import dev.antigravity.fluidtransit.ai.tools.AllTools
import dev.antigravity.fluidtransit.ai.tools.ToolContext
import dev.antigravity.fluidtransit.ai.tools.ToolGroup
import dev.antigravity.fluidtransit.ai.tools.WRITE_TOOL_WAIT_MILLIS
import dev.antigravity.fluidtransit.ai.tools.TransitTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il tetto di uno strumento e la finestra di conferma, con la configurazione
 * vera dell'assistente ([AiAssistant.orchestratorConfig]) e l'orchestratore
 * dell'engine che la legge.
 *
 * Il difetto: un tetto unico di venti secondi per tutti tagliava a meta' i
 * sessanta secondi che l'utente ha per toccare "Conferma". La scheda spariva,
 * un tocco tardivo veniva ignorato, e il modello riceveva "lo strumento non
 * ha risposto in tempo" invece di "nessuna conferma". Dalla 3.0 i due tetti
 * li dichiarano i gruppi ([ToolGroup.toolTimeoutMillis],
 * [ToolGroup.awaitsConfirmation]) e l'orchestratore somma la finestra del
 * cancello: non ci sono piu' un `ToolLimits` e uno strumento "con il cronometro".
 */
class ToolTimeoutTest {

    private val config = AiAssistant.orchestratorConfig
    private val total = config.totalBudgetMillis

    private val orchestrator = AiOrchestrator(
        registry = AllTools.registry(),
        router = AiRouter(
            groups = ToolGroup.entries,
            actionGroup = ToolGroup.APP,
            domainHint = "prova",
            defaultGroups = listOf(ToolGroup.PLACES),
        ),
        diagnostics = AiDiagnosticsLog(),
        config = config,
        gate = AiConfirmationGate(config),
    )

    /** Uno strumento qualunque del gruppo: il tetto dipende dal gruppo, non dal nome. */
    private fun toolOf(group: ToolGroup): TransitTool = AllTools.tools().first { it.group == group }

    private val reads = listOf(ToolGroup.PLACES, ToolGroup.SCHEDULE, ToolGroup.LIVE, ToolGroup.JOURNEY)
    private val writes = listOf(ToolGroup.APP, ToolGroup.ROUTINE)

    @Test
    fun `i numeri del giro sono quelli di sempre`() {
        assertEquals(6, config.maxRounds)
        assertEquals(1_200, config.maxOutputTokens)
        assertEquals(90_000L, config.totalBudgetMillis)
        assertEquals(15_000L, config.finalReserveMillis)
        assertEquals(20_000L, config.toolTimeoutMillis)
        assertEquals(60_000L, config.confirmationTimeoutMillis)
        // Due livelli, router e chat: il profondo non c'e'.
        assertFalse(config.deepTierEnabled)
    }

    @Test
    fun `gli strumenti di lettura hanno il tetto di venti secondi`() {
        for (g in reads) {
            assertEquals("$g", 20_000L, orchestrator.toolTimeoutMillis(toolOf(g), total))
        }
    }

    @Test
    fun `gli strumenti che scrivono aspettano almeno la finestra di conferma`() {
        for (g in writes) {
            // Con un budget intero il tetto copre i sessanta secondi di conferma.
            val t = orchestrator.toolTimeoutMillis(toolOf(g), total)
            assertTrue("tetto $t", t > config.confirmationTimeoutMillis)
        }
    }

    @Test
    fun `senza il vincolo del budget uno strumento che scrive ha lavoro piu' una finestra`() {
        for (g in writes) {
            assertEquals(
                WRITE_TOOL_WAIT_MILLIS + config.confirmationTimeoutMillis,
                orchestrator.toolTimeoutMillis(toolOf(g), budgetRemainingMillis = 10 * total),
            )
        }
    }

    @Test
    fun `due conferme nello stesso giro hanno due finestre, una dopo l'altra`() {
        val unaSola = orchestrator.toolTimeoutMillis(toolOf(ToolGroup.APP), 10 * total, confirmationsInRound = 1)
        val due = orchestrator.toolTimeoutMillis(toolOf(ToolGroup.APP), 10 * total, confirmationsInRound = 2)
        assertEquals(config.confirmationTimeoutMillis, due - unaSola)
    }

    @Test
    fun `il tetto non supera mai quello che resta del budget meno la riserva`() {
        val restano = 50_000L
        assertEquals(
            restano - config.finalReserveMillis,
            orchestrator.toolTimeoutMillis(toolOf(ToolGroup.APP), restano),
        )
        // E non scende sotto i tre secondi nemmeno a budget finito.
        assertEquals(3_000L, orchestrator.toolTimeoutMillis(toolOf(ToolGroup.APP), 0L))
        assertEquals(3_000L, orchestrator.toolTimeoutMillis(toolOf(ToolGroup.PLACES), 0L))
    }

    @Test
    fun `chi legge lascia cinque secondi al budget per rispondere`() {
        assertEquals(15_000L, orchestrator.toolTimeoutMillis(toolOf(ToolGroup.LIVE), 20_000L))
    }

    @Test
    fun `la finestra dell'orchestratore e quella del cancello sono lo stesso numero`() {
        assertEquals(AiConfirmationGate(config).timeoutMillis, orchestrator.confirmationWindowMillis)
        assertEquals(60_000L, orchestrator.confirmationWindowMillis)
    }

    @Test
    fun `il catalogo dell'assistente ha tutti gli strumenti, col gruppo delle azioni giusto`() {
        val registry: ToolRegistry<ToolContext> = AllTools.registry()
        val plain = AllTools.tools()
        assertEquals(27, plain.size)
        assertEquals(plain.map { it.name }, registry.tools.map { it.name })
        assertEquals(plain.map { it.group }, registry.tools.map { it.group })
        assertEquals(ToolGroup.APP, registry.actionGroup)
        assertFalse(registry.visibleGroups(actionsEnabled = false).contains(ToolGroup.APP))
        assertTrue(registry.visibleGroups(actionsEnabled = false).contains(ToolGroup.ROUTINE))
    }

    // ------------------------------------------------ le parole della chiave

    @Test
    fun `la spiegazione dice cosa parte verso il servizio`() {
        for (p in ProviderId.entries) {
            val sent = KeyHelp.whatIsSent(p)
            assertTrue(sent, sent.contains(p.label))
            assertTrue(sent, sent.contains("voce"))
            assertTrue(sent, sent.contains("domande"))
            assertTrue(sent, sent.contains("posti salvati"))
            assertTrue(KeyHelp.keyStaysHere(p).contains("resta su questo telefono"))
            // La scheda della chiave le mostra insieme.
            assertEquals("$sent ${KeyHelp.keyStaysHere(p)}", KeyHelp.disclosure(p))
        }
    }

    @Test
    fun `il sottotitolo dell'interruttore dipende da chiave e interruttore`() {
        val acceso = KeyHelp.enableSubtitle(anyKeyVerified = true, enabled = true)
        val spento = KeyHelp.enableSubtitle(anyKeyVerified = true, enabled = false)
        val senza = KeyHelp.enableSubtitle(anyKeyVerified = false, enabled = false)
        assertTrue(acceso, acceso.startsWith("Chiedi a voce"))
        assertTrue(spento, spento.contains("accendi l'interruttore"))
        assertFalse(spento, spento.startsWith("Chiedi a voce"))
        assertTrue(senza, senza.contains("chiave"))
        assertEquals(3, setOf(acceso, spento, senza).size)
    }

    @Test
    fun `gli esempi sono domande`() {
        assertEquals(3, AssistantHints.EXAMPLES.size)
        assertTrue(AssistantHints.EXAMPLES.all { it.endsWith("?") })
    }
}
