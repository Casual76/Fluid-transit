package dev.antigravity.fluidtransit.ai.orchestrator

import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.ai.tools.Schema
import dev.antigravity.fluidengine.ai.tools.ToolRegistry
import dev.antigravity.fluidtransit.ai.keys.KeyHelp
import dev.antigravity.fluidtransit.ai.tools.ActionSink
import dev.antigravity.fluidtransit.ai.tools.AllTools
import dev.antigravity.fluidtransit.ai.tools.LiveVehicle
import dev.antigravity.fluidtransit.ai.tools.NamedPoint
import dev.antigravity.fluidtransit.ai.tools.RouteHit
import dev.antigravity.fluidtransit.ai.tools.StopHit
import dev.antigravity.fluidtransit.ai.tools.ToolContext
import dev.antigravity.fluidtransit.ai.tools.ToolGroup
import dev.antigravity.fluidtransit.ai.tools.ToolLimits
import dev.antigravity.fluidtransit.ai.tools.TransitBridge
import dev.antigravity.fluidtransit.ai.tools.TransitTool
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.DelayModel
import dev.antigravity.fluidtransit.routing.PlacesSearch
import dev.antigravity.fluidtransit.routing.Raptor
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
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
 * ha risposto in tempo" invece di "nessuna conferma". L'orchestratore
 * dell'engine ha un tetto solo: i due tetti li mette [ToolLimits.timed].
 */
class ToolTimeoutTest {

    private val total = ToolLimits.TOTAL_BUDGET_MILLIS

    @Test
    fun `gli strumenti di lettura hanno il tetto di venti secondi`() {
        for (g in listOf(ToolGroup.PLACES, ToolGroup.SCHEDULE, ToolGroup.LIVE, ToolGroup.JOURNEY)) {
            assertEquals(ToolLimits.READ_TIMEOUT_MILLIS, ToolLimits.timeoutMillis(g, total))
        }
    }

    @Test
    fun `gli strumenti che scrivono aspettano almeno la finestra di conferma`() {
        for (g in listOf(ToolGroup.APP, ToolGroup.ROUTINE)) {
            // Con un budget intero il tetto copre i sessanta secondi di conferma.
            val t = ToolLimits.timeoutMillis(g, total)
            assertTrue("tetto $t", t > ToolLimits.CONFIRMATION_TIMEOUT_MILLIS)
        }
    }

    @Test
    fun `il tetto non supera mai quello che resta del budget meno la riserva`() {
        val restano = 50_000L
        val t = ToolLimits.timeoutMillis(ToolGroup.APP, restano)
        assertEquals(restano - ToolLimits.FINAL_RESERVE_MILLIS, t)
        // E non scende sotto i tre secondi nemmeno a budget finito.
        assertEquals(3_000L, ToolLimits.timeoutMillis(ToolGroup.APP, 0L))
        assertEquals(3_000L, ToolLimits.timeoutMillis(ToolGroup.PLACES, 0L))
    }

    @Test
    fun `l'orchestratore dell'engine non taglia prima dei tetti di gruppo`() {
        for (g in ToolGroup.entries) {
            assertTrue(ToolLimits.timeoutMillis(g, total) <= ToolLimits.ORCHESTRATOR_TIMEOUT_MILLIS)
        }
    }

    // ------------------------------------------- il tetto, messo davvero

    private class Slow(override val group: ToolGroup, private val millis: Long) : TransitTool {
        override val name = "lento"
        override val description = "aspetta"
        override val parameters: JsonObject = Schema.obj(emptyMap())

        override suspend fun answer(args: JsonObject, ctx: ToolContext): String {
            delay(millis)
            return "fatto"
        }
    }

    private object NoBridge : TransitBridge {
        override val reader: BundleReader? = null
        override val places: PlacesSearch? = null
        override val delays: DelayModel? = null
        override val here: Pair<Double, Double>? = null
        override val looking: Pair<Double, Double>? = null

        override suspend fun plan(
            fromLat: Double,
            fromLon: Double,
            toLat: Double,
            toLon: Double,
            departAtEpoch: Long?,
            arriveByEpoch: Long?,
        ): List<Raptor.Journey> = emptyList()

        override fun findStops(query: String, limit: Int): List<StopHit> = emptyList()
        override fun findRoutes(query: String, limit: Int): List<RouteHit> = emptyList()
        override fun vehiclesOfRoute(routeIndex: Int): List<LiveVehicle>? = null
        override fun savedPlaces(): List<NamedPoint> = emptyList()
        override fun favouriteStops(): List<NamedPoint> = emptyList()
        override fun favouriteRouteNames(): List<String> = emptyList()
        override suspend fun alerts(): List<String> = emptyList()
    }

    private val start = 1_000_000L
    private val ctx = ToolContext(NoBridge, Locale.ITALIAN, ZoneId.of("Europe/Rome"), start, true, ActionSink.Disabled)

    @Test
    fun `uno strumento di lettura lento si ferma a venti secondi`() = runTest {
        val out = ToolLimits.timed(Slow(ToolGroup.SCHEDULE, 30_000L)) { start }.run(JsonObject(emptyMap()), ctx)
        assertEquals("errore: lo strumento non ha risposto in tempo", out.text)
    }

    @Test
    fun `uno strumento che scrive puo' aspettare la conferma oltre i venti secondi`() = runTest {
        // Cinquanta secondi: l'utente ha toccato Conferma tardi, ma dentro la sua finestra.
        val out = ToolLimits.timed(Slow(ToolGroup.APP, 50_000L)) { start }.run(JsonObject(emptyMap()), ctx)
        assertEquals("fatto", out.text)
    }

    @Test
    fun `il catalogo dell'assistente ha tutti gli strumenti, col gruppo delle azioni giusto`() {
        val registry: ToolRegistry<ToolContext> = AllTools.registry()
        val plain = AllTools.tools()
        assertEquals(27, plain.size)
        assertEquals(plain.map { it.name }, registry.tools.map { it.name })
        // Il tetto non cambia gruppo ne' schema: il modello vede gli stessi strumenti di prima.
        assertEquals(plain.map { it.group }, registry.tools.map { it.group })
        assertEquals(plain.map { it.spec }, registry.tools.map { it.spec })
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
