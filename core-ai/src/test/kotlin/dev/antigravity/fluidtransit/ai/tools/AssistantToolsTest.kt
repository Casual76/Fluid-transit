package dev.antigravity.fluidtransit.ai.tools

import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.DelayModel
import dev.antigravity.fluidtransit.routing.PlacesSearch
import dev.antigravity.fluidtransit.routing.Raptor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gli strumenti dell'assistente, dal lato di chi li chiama: cosa chiedono al
 * ponte, cosa rispondono, cosa mandano alla scheda di conferma.
 *
 * Il ponte e' finto e non ha il bundle: le prove sono sulla logica che sta
 * FRA il modello e l'app (argomenti, giorni, frasi), che e' dove sono nati i
 * difetti — "qui" cercato come un nome, "domani alle 8:30" calcolato per
 * oggi, "feriali e sabato" letto come i soli feriali.
 */
class AssistantToolsTest {

    // 2026-09-30 07:10 a Roma (CEST, +02:00).
    private val now = Instant.parse("2026-09-30T05:10:00Z")
    private val rome = ZoneId.of("Europe/Rome")

    private class Sink : ActionSink {
        val actions = mutableListOf<AssistantAction>()
        var outcome = ActionOutcome.DONE
        override suspend fun perform(action: AssistantAction): ActionOutcome {
            actions += action
            return outcome
        }
    }

    private class Bridge(
        var here0: Pair<Double, Double>? = null,
        var saved: List<NamedPoint> = emptyList(),
        var stops: List<StopHit> = emptyList(),
        var starredStops0: List<StarredStop> = emptyList(),
        var starredRoutes0: List<StarredRoute> = emptyList(),
    ) : TransitBridge {
        override val reader: BundleReader? = null
        override val places: PlacesSearch? = null
        override val delays: DelayModel? = null
        override val here: Pair<Double, Double>? get() = here0
        override val looking: Pair<Double, Double>? = null

        class PlanCall(val departAt: Long?, val arriveBy: Long?)

        val planCalls = mutableListOf<PlanCall>()
        var liveAsked = 0

        override suspend fun plan(
            fromLat: Double,
            fromLon: Double,
            toLat: Double,
            toLon: Double,
            departAtEpoch: Long?,
            arriveByEpoch: Long?,
        ): List<Raptor.Journey> {
            planCalls += PlanCall(departAtEpoch, arriveByEpoch)
            return emptyList()
        }

        override suspend fun ensureLive(vehicles: Boolean) {
            liveAsked++
        }

        override fun findStops(query: String, limit: Int): List<StopHit> =
            stops.filter { it.name.equals(query, ignoreCase = true) }.take(limit)

        override fun findRoutes(query: String, limit: Int): List<RouteHit> = emptyList()
        override fun vehiclesOfRoute(routeIndex: Int): List<LiveVehicle>? = null
        override fun savedPlaces(): List<NamedPoint> = saved
        override fun favouriteStops(): List<NamedPoint> = emptyList()
        override fun favouriteRouteNames(): List<String> = emptyList()
        override suspend fun alerts(): List<String> = emptyList()
        override fun starredStops(): List<StarredStop> = starredStops0
        override fun starredRoutes(): List<StarredRoute> = starredRoutes0
    }

    private fun ctx(bridge: Bridge, sink: Sink) = ToolContext(
        transit = bridge,
        locale = Locale.ITALIAN,
        zone = rome,
        nowMillis = now.toEpochMilli(),
        actionsEnabled = true,
        actions = sink,
    )

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    private fun careggi() = StopHit("ab", 1, "Careggi", 43.80, 11.25)

    // ------------------------------------------------ la scheda di conferma

    @Test
    fun `nessuna azione ha una conferma nuda`() {
        val punto = NamedPoint("Stazione", "", 43.0, 11.0)
        val tutte = listOf(
            AssistantAction.ShowPlace(punto),
            AssistantAction.ShowStop("ab", "Careggi"),
            AssistantAction.ShowRoute(3, "6"),
            AssistantAction.ShowJourneys(null, punto, null, null),
            AssistantAction.StartNavigation(punto),
            AssistantAction.SavePlace("Casa", punto),
            AssistantAction.StarStop("ab", "Careggi"),
            AssistantAction.StarRoute(3, "6"),
            AssistantAction.CreateRoutine("Lavoro", null, punto, setOf(1, 2, 3, 4, 5), "arrive", 510),
            AssistantAction.UnstarStop("ab", "Careggi"),
            AssistantAction.UnstarRoute("cd", "6"),
            AssistantAction.RemoveSavedPlace(1L, "Casa"),
            AssistantAction.StopNavigation,
            AssistantAction.SetRoutineEnabled(1L, "Lavoro", true),
            AssistantAction.RemoveRoutine(1L, "Lavoro"),
            AssistantAction.RefreshData,
        )
        for (a in tutte) {
            val text = ActionText.confirm(a)
            assertNotEquals("Confermi?", text)
            assertTrue("troppo corta: $text", text.length > 12)
        }
    }

    @Test
    fun `la conferma dice cosa sta per sparire`() {
        assertEquals(
            "Tolgo la stella alla fermata Stazione?",
            ActionText.confirm(AssistantAction.UnstarStop("ab", "Stazione")),
        )
        assertEquals(
            "Tolgo la stella alla linea 6?",
            ActionText.confirm(AssistantAction.UnstarRoute("cd", "6")),
        )
        assertEquals(
            "Tolgo Casa dai posti salvati?",
            ActionText.confirm(AssistantAction.RemoveSavedPlace(1L, "Casa")),
        )
        assertEquals(
            "Elimino la routine Lavoro?",
            ActionText.confirm(AssistantAction.RemoveRoutine(1L, "Lavoro")),
        )
        assertEquals(
            "Spengo la routine Lavoro?",
            ActionText.confirm(AssistantAction.SetRoutineEnabled(1L, "Lavoro", false)),
        )
        assertEquals(
            "Accendo la routine Lavoro?",
            ActionText.confirm(AssistantAction.SetRoutineEnabled(1L, "Lavoro", true)),
        )
    }

    @Test
    fun `la conferma di una routine dice giorni, ora e partenza`() {
        val a = AssistantAction.CreateRoutine(
            "Careggi", null, NamedPoint("Careggi", "", 43.8, 11.2),
            setOf(1, 2, 3, 4, 5, 6), "arrive", 8 * 60 + 30,
        )
        val text = ActionText.confirm(a)
        assertTrue(text, text.contains("Careggi"))
        assertTrue(text, text.contains("arrivo alle 08:30"))
        assertTrue(text, text.contains("lun, mar, mer, gio, ven, sab"))
        // Partenza non detta: la routine parte da dove sei quando la crei, e la scheda lo dice.
        assertTrue(text, text.contains("da dove ti trovi"))
    }

    @Test
    fun `salvare un'etichetta esistente avverte che la sposta`() {
        val p = NamedPoint("la tua posizione", "", 43.0, 11.0)
        val nuova = ActionText.confirm(AssistantAction.SavePlace("Casa", p))
        val spostata = ActionText.confirm(AssistantAction.SavePlace("Casa", p, replaces = "Casa"))
        assertFalse(nuova.contains("Sostituisce"))
        assertTrue(spostata.contains("Sostituisce"))
    }

    @Test
    fun `ogni esito ha la sua ragione e solo uno parla delle impostazioni`() {
        val frasi = ActionOutcome.entries.associateWith { outcomeText(it, "fatto") }
        assertEquals("fatto", frasi.getValue(ActionOutcome.DONE))
        for ((esito, frase) in frasi) {
            if (esito != ActionOutcome.UNAVAILABLE) assertNotEquals(ACTIONS_OFF, frase)
        }
        // Le ragioni distinte: nessuna frase ne ripete un'altra.
        assertEquals(frasi.size, frasi.values.toSet().size)
        assertTrue(frasi.getValue(ActionOutcome.NO_ITINERARY).contains("itinerario"))
        assertTrue(frasi.getValue(ActionOutcome.NO_ORIGIN).contains("da dove"))
    }

    // ------------------------------------------------ salva_posto

    @Test
    fun `salva questo posto senza dove usa la posizione`() = runBlocking {
        val sink = Sink()
        val bridge = Bridge(here0 = 43.77 to 11.25)
        val out = SavePlaceTool().run(args("nome" to "Casa"), ctx(bridge, sink))
        assertEquals("salvato come \"Casa\"", out)
        val a = sink.actions.single() as AssistantAction.SavePlace
        assertEquals(43.77, a.point.lat, 0.0)
        // In minuscolo: sulla scheda sta in mezzo a una frase.
        assertEquals("la tua posizione", a.point.name)
    }

    @Test
    fun `qui non e' un nome da cercare`() = runBlocking {
        for (qui in listOf("qui", "Qui.", "questo posto", "la tua posizione")) {
            val sink = Sink()
            val bridge = Bridge(here0 = 43.77 to 11.25)
            val out = SavePlaceTool().run(args("nome" to "Lavoro", "dove" to qui), ctx(bridge, sink))
            assertFalse(out, out.startsWith("non trovo"))
            assertEquals(1, sink.actions.size)
        }
    }

    @Test
    fun `senza posizione non si salva un punto inventato`() = runBlocking {
        val sink = Sink()
        val out = SavePlaceTool().run(args("nome" to "Casa"), ctx(Bridge(here0 = null), sink))
        assertTrue(out, out.startsWith("errore:"))
        assertTrue(out, out.contains("non so dove ti trovi"))
        assertTrue(sink.actions.isEmpty())
    }

    @Test
    fun `salva_posto con un nome cerca quel nome e segnala la sostituzione`() = runBlocking {
        val sink = Sink()
        val bridge = Bridge(
            here0 = 43.0 to 11.0,
            stops = listOf(careggi()),
            saved = listOf(NamedPoint("Casa", "Il tuo posto", 43.1, 11.1)),
        )
        SavePlaceTool().run(args("nome" to "casa", "dove" to "Careggi"), ctx(bridge, sink))
        val a = sink.actions.single() as AssistantAction.SavePlace
        assertEquals("Careggi", a.point.name)
        assertEquals("Casa", a.replaces)
    }

    @Test
    fun `dove non e' obbligatorio nello schema`() {
        val required = SavePlaceTool().parameters["required"].toString()
        assertTrue(required, required.contains("nome"))
        assertFalse(required, required.contains("dove"))
    }

    // ------------------------------------------------ crea_routine

    @Test
    fun `i giorni di una routine si sommano`() {
        assertEquals(setOf(1, 2, 3, 4, 5, 6), parseRoutineDays("feriali e sabato"))
        assertEquals(setOf(1, 3, 5), parseRoutineDays("lun,mer,ven"))
        assertEquals(setOf(6, 7), parseRoutineDays("weekend"))
        assertEquals(setOf(6, 7), parseRoutineDays("sabato e domenica"))
        assertEquals((1..7).toSet(), parseRoutineDays("tutti i giorni"))
        assertEquals((1..7).toSet(), parseRoutineDays("ogni giorno"))
        assertEquals(setOf(1, 2, 3, 4, 5), parseRoutineDays("dal lunedi al venerdi"))
        assertEquals(setOf(1, 2, 3, 4, 5), parseRoutineDays("lun-ven"))
        assertEquals(setOf(2, 4), parseRoutineDays("martedì e giovedì"))
    }

    @Test
    fun `gli intervalli dei giorni si leggono in tutte le forme che si sentono`() {
        val feriali = setOf(1, 2, 3, 4, 5)
        // La frase che l'app stessa scrive (DaysText.label), apostrofi compresi.
        assertEquals(feriali, parseRoutineDays("dal lunedi' al venerdi'"))
        assertEquals(feriali, parseRoutineDays("dal lunedì al venerdì"))
        assertEquals(feriali, parseRoutineDays("da lunedi a venerdi"))
        assertEquals(feriali, parseRoutineDays("fra lunedi e venerdi"))
        assertEquals(feriali, parseRoutineDays("tra lunedi e venerdi"))
        // Al contrario gira intorno alla settimana, non tiene i soli estremi.
        assertEquals(setOf(5, 6, 7, 1), parseRoutineDays("da venerdi a lunedi"))
    }

    @Test
    fun `una routine senza partenza detta e senza posizione non arriva alla conferma`() = runBlocking {
        val sink = Sink()
        val bridge = Bridge(here0 = null, stops = listOf(careggi()))
        val out = CreateRoutineTool().run(
            args("a" to "Careggi", "giorni" to "feriali", "ora" to "8:30"),
            ctx(bridge, sink),
        )
        assertTrue(out, out.startsWith("errore: non so dove ti trovi"))
        assertTrue(sink.actions.isEmpty())
    }

    @Test
    fun `uno stesso autobus su due pali si conta una volta ma un anello ripassa`() {
        fun d(trip: Int, hour: Int) = BundleReader.Departure(
            tripIndex = trip,
            patternIndex = 0,
            routeIndex = 0,
            serviceDate = LocalDate.of(2026, 9, 30),
            instant = Instant.parse("2026-09-30T0${hour}:00:00Z"),
            positionInPattern = 0,
        )
        // Palo preferito: la corsa 1 passa due volte (anello), la 2 una. Sul palo di fronte la
        // corsa 1 e la 3 (che qui non ha passaggi sul preferito).
        val preferito = listOf(d(1, 5), d(1, 7), d(2, 6))
        val difronte = listOf(d(1, 5), d(3, 6))
        val out = StopDayScheduleTool.onePolePerTrip(listOf(preferito, difronte))
        assertEquals(listOf(1 to 5, 1 to 7, 2 to 6, 3 to 6), out.map { it.tripIndex to it.instant.atZone(java.time.ZoneOffset.UTC).hour })
    }

    @Test
    fun `un'ora di oggi gia' passata si dice, e adesso non e' un'ora`() = runBlocking {
        val sink = Sink()
        val bridge = Bridge(here0 = 43.0 to 11.0, stops = listOf(careggi()))
        // Sono le 07:10: le 06:00 di OGGI sono passate, e slittare a domani e' roba del campo vuoto.
        val passata = JourneyTool().run(
            args("a" to "Careggi", "arriva_entro" to "6:00", "giorno" to "oggi"),
            ctx(bridge, sink),
        )
        assertEquals(Resolve.PASSED_TODAY, passata)
        assertTrue(bridge.planCalls.isEmpty())
        val uscire = WhenToLeaveTool().run(
            args("a" to "Careggi", "entro" to "6:00", "giorno" to "oggi"),
            ctx(bridge, sink),
        )
        assertEquals(Resolve.PASSED_TODAY, uscire)
        // "adesso" dove lo schema dice "vuoto per adesso": si calcola per adesso.
        JourneyTool().run(
            args("a" to "Careggi", "parti_alle" to "adesso"),
            ctx(bridge, sink),
        )
        val call = bridge.planCalls.single()
        assertNull(call.departAt)
    }

    @Test
    fun `quello che non si capisce non crea una routine a meta`() {
        assertTrue(parseRoutineDays("domani").isEmpty())
        assertTrue(parseRoutineDays("feriali e boh").isEmpty())
        assertTrue(parseRoutineDays("").isEmpty())
        assertTrue(parseRoutineDays(null).isEmpty())
    }

    @Test
    fun `una partenza detta e non trovata non diventa dove ti trovi`() = runBlocking {
        val sink = Sink()
        val bridge = Bridge(here0 = 43.0 to 11.0, stops = listOf(careggi()))
        val out = CreateRoutineTool().run(
            args("a" to "Careggi", "da" to "Posto che non esiste", "giorni" to "feriali", "ora" to "8:30"),
            ctx(bridge, sink),
        )
        assertTrue(out, out.contains("non trovo"))
        assertTrue(sink.actions.isEmpty())
    }

    @Test
    fun `un'ora con parole in coda non diventa le otto`() = runBlocking {
        val sink = Sink()
        val bridge = Bridge(here0 = 43.0 to 11.0, stops = listOf(careggi()))
        val out = CreateRoutineTool().run(
            args("a" to "Careggi", "giorni" to "feriali", "ora" to "8:30 di sera"),
            ctx(bridge, sink),
        )
        assertTrue(out, out.startsWith("non ho capito l'ora"))
        assertTrue(sink.actions.isEmpty())
    }

    // ------------------------------------------------ togli_stella

    @Test
    fun `togli stella preferisce il nome esatto`() = runBlocking {
        val sink = Sink()
        val bridge = Bridge(
            starredStops0 = listOf(StarredStop("a", "Stazione"), StarredStop("b", "Stazione Nord")),
        )
        UnstarTool().run(args("cosa" to "stazione"), ctx(bridge, sink))
        val a = sink.actions.single() as AssistantAction.UnstarStop
        assertEquals("a", a.idHashHex)
    }

    @Test
    fun `togli stella chiede quale quando ne trova piu' di una`() = runBlocking {
        val sink = Sink()
        val bridge = Bridge(
            starredStops0 = listOf(StarredStop("a", "Stazione Sud"), StarredStop("b", "Stazione Nord")),
        )
        val out = UnstarTool().run(args("cosa" to "stazione"), ctx(bridge, sink))
        assertTrue(out, out.contains("Stazione Sud"))
        assertTrue(out, out.contains("Stazione Nord"))
        assertTrue(out, out.contains("Chiedi all'utente quale"))
        assertTrue(sink.actions.isEmpty())
    }

    // ------------------------------------------------ giorni e ore

    @Test
    fun `un'ora e' un'ora e basta`() {
        assertEquals(8 * 60 + 30, Resolve.clockOf("8:30")!!.let { it.hour * 60 + it.minute })
        assertEquals(20 * 60 + 15, Resolve.clockOf("20.15")!!.let { it.hour * 60 + it.minute })
        assertEquals(8 * 60, Resolve.clockOf("8")!!.let { it.hour * 60 + it.minute })
        assertNull(Resolve.clockOf("8:30 di sera"))
        assertNull(Resolve.clockOf("8 e mezza"))
        assertNull(Resolve.clockOf("domani mattina"))
        assertNull(Resolve.clockOf("7:75"))
        assertNull(Resolve.clockOf("25:00"))
        assertNull(Resolve.clockOf(""))
    }

    @Test
    fun `i giorni si dicono in parole`() {
        val oggi = LocalDate.of(2026, 9, 30) // mercoledi'
        assertEquals(oggi, Resolve.parseDay(oggi, null))
        assertEquals(oggi, Resolve.parseDay(oggi, "oggi"))
        assertEquals(LocalDate.of(2026, 10, 1), Resolve.parseDay(oggi, "domani"))
        assertEquals(LocalDate.of(2026, 10, 2), Resolve.parseDay(oggi, "dopodomani"))
        assertEquals(LocalDate.of(2026, 10, 3), Resolve.parseDay(oggi, "Sabato"))
        // Il giorno della settimana di oggi e' oggi, non fra sette giorni.
        assertEquals(oggi, Resolve.parseDay(oggi, "mercoledì"))
        assertEquals(LocalDate.of(2026, 10, 5), Resolve.parseDay(oggi, "2026-10-05"))
        assertNull(Resolve.parseDay(oggi, "fra un po'"))
    }

    @Test
    fun `domani alle 8 e 30 si calcola per domani`() {
        val c = ctx(Bridge(), Sink())
        val domani = Resolve.timeOn(c, LocalDate.of(2026, 10, 1), "8:30")!!
        assertEquals(
            LocalDate.of(2026, 10, 1).atTime(8, 30).atZone(rome).toEpochSecond(),
            domani,
        )
        // Senza giorno e' la prossima volta che viene: alle 07:10 "8:30" e' oggi.
        val oggi = Resolve.timeOn(c, null, "8:30")!!
        assertEquals(LocalDate.of(2026, 9, 30).atTime(8, 30).atZone(rome).toEpochSecond(), oggi)
    }

    @Test
    fun `come_arrivo con un giorno calcola su quel giorno`() = runBlocking {
        val sink = Sink()
        val bridge = Bridge(here0 = 43.0 to 11.0, stops = listOf(careggi()))
        JourneyTool().run(
            args("a" to "Careggi", "arriva_entro" to "8:30", "giorno" to "domani"),
            ctx(bridge, sink),
        )
        val call = bridge.planCalls.single()
        assertEquals(
            LocalDate.of(2026, 10, 1).atTime(8, 30).atZone(rome).toEpochSecond(),
            call.arriveBy,
        )
    }

    @Test
    fun `come_arrivo non trasforma un'ora non capita in adesso`() = runBlocking {
        val sink = Sink()
        val bridge = Bridge(here0 = 43.0 to 11.0, stops = listOf(careggi()))
        val out = JourneyTool().run(
            args("a" to "Careggi", "parti_alle" to "8 e mezza"),
            ctx(bridge, sink),
        )
        assertEquals("errore: ora di partenza non capita (es. 08:30)", out)
        assertTrue(bridge.planCalls.isEmpty())
    }

    @Test
    fun `un altro giorno senza l'ora e' un errore, non un calcolo per adesso`() = runBlocking {
        val sink = Sink()
        val bridge = Bridge(here0 = 43.0 to 11.0, stops = listOf(careggi()))
        val out = JourneyTool().run(
            args("a" to "Careggi", "giorno" to "sabato"),
            ctx(bridge, sink),
        )
        assertTrue(out, out.startsWith("errore:"))
        assertTrue(bridge.planCalls.isEmpty())
    }

    @Test
    fun `un giorno non capito si dice`() = runBlocking {
        val sink = Sink()
        val bridge = Bridge(here0 = 43.0 to 11.0, stops = listOf(careggi()))
        val out = JourneyTool().run(
            args("a" to "Careggi", "parti_alle" to "8:30", "giorno" to "fra un po'"),
            ctx(bridge, sink),
        )
        assertTrue(out, out.startsWith("errore: giorno non capito"))
    }

    @Test
    fun `gli itinerari di oggi chiedono il tempo reale, quelli di un altro giorno no`() = runBlocking {
        val sink = Sink()
        val bridge = Bridge(here0 = 43.0 to 11.0, stops = listOf(careggi()))
        JourneyTool().run(args("a" to "Careggi"), ctx(bridge, sink))
        assertEquals(1, bridge.liveAsked)
        JourneyTool().run(
            args("a" to "Careggi", "parti_alle" to "8:30", "giorno" to "domani"),
            ctx(bridge, sink),
        )
        assertEquals(1, bridge.liveAsked)
    }

    @Test
    fun `quando_uscire legge il giorno come come_arrivo`() = runBlocking {
        val sink = Sink()
        val bridge = Bridge(here0 = 43.0 to 11.0, stops = listOf(careggi()))
        WhenToLeaveTool().run(
            args("a" to "Careggi", "entro" to "9", "giorno" to "domani"),
            ctx(bridge, sink),
        )
        assertEquals(
            LocalDate.of(2026, 10, 1).atTime(9, 0).atZone(rome).toEpochSecond(),
            bridge.planCalls.single().arriveBy,
        )
        val rotto = WhenToLeaveTool().run(
            args("a" to "Careggi", "entro" to "9 di sera"),
            ctx(bridge, sink),
        )
        assertTrue(rotto, rotto.startsWith("errore: ora di arrivo non capita"))
    }

    // ------------------------------------------------ dove_sono_i_bus

    @Test
    fun `non sapere dove sono i bus non e' non averne`() = runBlocking {
        // `findRoutes` qui e' vuota: si prova con una linea che c'e'.
        val bridge = object : TransitBridge by Bridge() {
            override fun findRoutes(query: String, limit: Int) = listOf(RouteHit(1, "6", "Careggi"))
            override fun vehiclesOfRoute(routeIndex: Int): List<LiveVehicle>? = null
        }
        val c = ToolContext(bridge, Locale.ITALIAN, rome, now.toEpochMilli(), true, Sink())
        val out = LiveBusesTool().run(args("linea" to "6"), c)
        assertFalse(out, out.contains("nessun mezzo"))
        assertTrue(out, out.startsWith("non so dove sono"))
        assertNotNull(out)
    }
}
