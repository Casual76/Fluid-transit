package dev.antigravity.fluidtransit.ai.orchestrator

import dev.antigravity.fluidtransit.ai.keys.KeyHelp
import dev.antigravity.fluidtransit.ai.provider.ProviderId
import dev.antigravity.fluidtransit.ai.tools.ToolGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il tetto di uno strumento e la finestra di conferma.
 *
 * Il difetto: un tetto unico di venti secondi per tutti tagliava a meta' i
 * sessanta secondi che l'utente ha per toccare "Conferma". La scheda spariva,
 * un tocco tardivo veniva ignorato, e il modello riceveva "lo strumento non
 * ha risposto in tempo" invece di "nessuna conferma".
 */
class ToolTimeoutTest {

    private val total = TimeBudget.TOTAL_MILLIS

    @Test
    fun `gli strumenti di lettura hanno il tetto di venti secondi`() {
        for (g in listOf(ToolGroup.PLACES, ToolGroup.SCHEDULE, ToolGroup.LIVE, ToolGroup.JOURNEY)) {
            assertEquals(AssistantOrchestrator.TOOL_TIMEOUT_MILLIS, AssistantOrchestrator.toolTimeoutMillis(g, total))
        }
    }

    @Test
    fun `gli strumenti che scrivono aspettano almeno la finestra di conferma`() {
        for (g in listOf(ToolGroup.APP, ToolGroup.ROUTINE)) {
            // Con un budget intero il tetto copre i sessanta secondi di conferma.
            val t = AssistantOrchestrator.toolTimeoutMillis(g, total)
            assertTrue("tetto $t", t > AssistantSession.CONFIRMATION_TIMEOUT_MILLIS)
        }
    }

    @Test
    fun `il tetto non supera mai quello che resta del budget meno la riserva`() {
        val restano = 50_000L
        val t = AssistantOrchestrator.toolTimeoutMillis(ToolGroup.APP, restano)
        assertEquals(restano - TimeBudget.FINAL_RESERVE_MILLIS, t)
        // E non scende sotto i tre secondi nemmeno a budget finito.
        assertEquals(3_000L, AssistantOrchestrator.toolTimeoutMillis(ToolGroup.APP, 0L))
        assertEquals(3_000L, AssistantOrchestrator.toolTimeoutMillis(ToolGroup.PLACES, 0L))
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
        }
    }

    @Test
    fun `gli indirizzi sono solo i siti dei servizi`() {
        for (p in ProviderId.entries) {
            val url = KeyHelp.url(p)
            assertTrue(url, url.startsWith("https://"))
            // Nessun percorso: un sito di cui non si controlla il percorso lo cambia senza avvisare.
            assertFalse(url, url.removePrefix("https://").contains('/'))
            assertEquals(url.removePrefix("https://"), KeyHelp.host(p))
        }
    }

    @Test
    fun `un solo servizio e' consigliato`() {
        assertEquals(listOf(ProviderId.GROQ), ProviderId.entries.filter { KeyHelp.recommended(it) })
        assertTrue(KeyHelp.rowHint(ProviderId.GROQ).startsWith("Consigliato per cominciare"))
        assertFalse(KeyHelp.rowHint(ProviderId.GEMINI).contains("Consigliato"))
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
