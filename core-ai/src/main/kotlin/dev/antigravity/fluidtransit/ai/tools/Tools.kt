package dev.antigravity.fluidtransit.ai.tools

import dev.antigravity.fluidtransit.ai.provider.ToolSpec
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.DelayModel
import dev.antigravity.fluidtransit.routing.PlacesSearch
import dev.antigravity.fluidtransit.routing.Raptor
import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * I gruppi del catalogo a due stadi: uno stadio piccolo sceglie i gruppi, lo
 * stadio grande riceve solo gli strumenti di quei gruppi.
 *
 * Serve perche' i limiti dei provider gratuiti sono per modello e per minuto:
 * mandare tutti gli strumenti a ogni domanda costa token a vuoto. Gli id sono
 * le parole che il modello legge nello schema, quindi in italiano come il
 * resto; la frase di stato ("Guardo gli orari") la mette la UI dalla chiave.
 */
enum class ToolGroup(val id: String, val statusKey: String, val hint: String) {
    PLACES(
        "luogo", "places",
        "trovare fermate, linee, luoghi, indirizzi e i posti salvati dall'utente",
    ),
    SCHEDULE(
        "orari", "schedule",
        "quando passa un mezzo: prossimi passaggi di una fermata, corse di una linea",
    ),
    LIVE(
        "live", "live",
        "cosa sta succedendo adesso: dov'e' un bus, quanto ritardo ha, avvisi di servizio",
    ),
    JOURNEY(
        "viaggio", "journey",
        "come si va da un posto a un altro: itinerari, cambi, orari di partenza e arrivo",
    ),
    APP(
        "app", "app",
        "azioni nell'app: mostrare qualcosa sulla mappa, avviare la navigazione, " +
            "salvare un posto, mettere una stella, creare una routine",
    ),
    ;

    companion object {
        fun fromId(id: String?): ToolGroup? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}

/** Un punto con un nome: quello che gli strumenti si scambiano. */
class NamedPoint(
    val name: String,
    val context: String,
    val lat: Double,
    val lon: Double,
)

/** Una fermata trovata per nome. */
class StopHit(
    val idHashHex: String,
    val stopIndex: Int,
    val name: String,
    val lat: Double,
    val lon: Double,
)

/** Una linea trovata per nome o numero. */
class RouteHit(
    val routeIndex: Int,
    val shortName: String,
    val headsign: String,
)

/** Un mezzo vivo, come lo vede l'assistente. */
class LiveVehicle(
    val routeShortName: String,
    val headsign: String,
    val lat: Double,
    val lon: Double,
    val delaySeconds: Int?,
    val nextStopName: String?,
    val fixAgeSeconds: Int,
)

/**
 * Quello che gli strumenti devono chiedere all'app, perche' il bundle da solo
 * non lo sa: il realtime, i preferiti, i posti salvati, la posizione.
 *
 * E' un'interfaccia e non una dipendenza diretta perche' questo modulo non
 * conosce ne' Compose ne' MapLibre: l'app la implementa e la passa.
 */
interface TransitBridge {
    val reader: BundleReader?
    val places: PlacesSearch?
    val delays: DelayModel?

    /** Dove si trova l'utente adesso, se il telefono lo sa. */
    val here: Pair<Double, Double>?

    /** Il centro della mappa che sta guardando: il "qui" quando esplora altrove. */
    val looking: Pair<Double, Double>?

    /**
     * Un itinerario. RAPTOR e' single-thread per costruzione (lo scratch e'
     * riusato), quindi e' l'app a serializzare le chiamate sul suo
     * dispatcher: gli strumenti girano in parallelo, il motore no.
     */
    suspend fun plan(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        departAtEpoch: Long?,
        arriveByEpoch: Long?,
    ): List<Raptor.Journey>

    /**
     * Fermate e linee per nome, con lo stesso criterio della barra di
     * ricerca: l'assistente e la barra non devono mai dare risposte diverse
     * alla stessa parola.
     */
    fun findStops(query: String, limit: Int = 5): List<StopHit>

    fun findRoutes(query: String, limit: Int = 5): List<RouteHit>

    /** I mezzi vivi di una linea, gia' risolti contro il bundle. */
    fun vehiclesOfRoute(routeIndex: Int): List<LiveVehicle>

    fun savedPlaces(): List<NamedPoint>
    fun favouriteStops(): List<NamedPoint>
    fun favouriteRouteNames(): List<String>

    /** Gli avvisi di servizio delle linee che interessano all'utente. */
    suspend fun alerts(): List<String>
}

/** Cosa un tool puo' toccare mentre gira. */
class ToolContext(
    val transit: TransitBridge,
    val locale: Locale,
    val zone: ZoneId,
    val nowMillis: Long,
    val actionsEnabled: Boolean,
    val actions: ActionSink,
) {
    val nowEpoch: Long get() = nowMillis / 1000

    /**
     * Il punto da cui misurare "vicino": dove sei, ma se hai portato la mappa
     * lontano vince quello che stai guardando — la stessa regola della
     * ricerca, cosi' l'assistente e la barra non si contraddicono.
     */
    val reference: Pair<Double, Double>?
        get() {
            val h = transit.here ?: return transit.looking
            val l = transit.looking ?: return h
            val away = BundleReader.haversine(h.first, h.second, l.first, l.second)
            return if (away > 20_000.0) l else h
        }
}

interface AiTool {
    val name: String
    val group: ToolGroup
    val description: String
    val parameters: JsonObject

    /** Il risultato e' testo compatto per il modello: righe `chiave: valore`, mai JSON verboso. */
    suspend fun run(args: JsonObject, ctx: ToolContext): String

    val spec: ToolSpec get() = ToolSpec(name, description, parameters)
}

/**
 * Il catalogo: tutti i tool, quelli di un insieme di gruppi, e il
 * tool-scappatoia con cui il modello chiede un gruppo che lo stadio 1 non gli
 * ha dato.
 */
class ToolRegistry(val tools: List<AiTool>) {

    init {
        val duplicates = tools.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "tool duplicati: $duplicates" }
    }

    fun specsFor(groups: Set<ToolGroup>): List<ToolSpec> =
        tools.filter { it.group in groups }.map { it.spec }

    fun allSpecs(): List<ToolSpec> = tools.map { it.spec }

    fun find(name: String): AiTool? = tools.firstOrNull { it.name == name }

    val moreTools: ToolSpec = ToolSpec(
        name = MORE_TOOLS,
        description = "Chiede altri strumenti di un gruppo non ancora disponibile. Gruppi: " +
            ToolGroup.entries.joinToString("; ") { "${it.id} = ${it.hint}" },
        parameters = Schema.obj(
            mapOf(
                "gruppo" to Schema.str(
                    "il gruppo di strumenti che serve",
                    ToolGroup.entries.map { it.id },
                ),
            ),
            required = listOf("gruppo"),
        ),
    )

    companion object {
        const val MORE_TOOLS = "altri_tool"
    }
}

/** Gli schemi JSON dei parametri, brevi: ogni parola nello schema costa token a ogni giro. */
object Schema {
    fun obj(properties: Map<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { properties.forEach { (name, schema) -> put(name, schema) } })
            if (required.isNotEmpty()) {
                put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            }
        }

    fun str(description: String, enum: List<String>? = null): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
        enum?.let { values -> put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }) }
    }

    fun int(description: String, minimum: Int? = null, maximum: Int? = null): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
        minimum?.let { put("minimum", it) }
        maximum?.let { put("maximum", it) }
    }

    fun bool(description: String): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    /** Il parametro che quasi ogni strumento ha: dove. */
    val place: JsonObject = str(
        "un posto: nome di fermata, luogo, indirizzo, un posto salvato dall'utente " +
            "(\"casa\", \"lavoro\"), oppure vuoto per dove si trova adesso",
    )
}

/** Lettura tollerante degli argomenti: il modello scrive numeri come stringhe e viceversa. */
object Args {
    fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let {
        it.doubleOrNull?.toInt() ?: it.contentOrNull?.trim()?.toDoubleOrNull()?.toInt()
    }

    fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.let {
        it.doubleOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull()
    }

    fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let {
        it.booleanOrNull ?: it.contentOrNull?.trim()?.toBooleanStrictOrNull()
    }

    fun JsonObject.list(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
}

/**
 * Il testo che torna al modello: righe brevi, un budget di caratteri (~600
 * token) oltre il quale si tronca dicendo quante righe mancano. Gli
 * strumenti tagliano prima le loro liste; questo e' l'ultimo argine, e serve
 * soprattutto sui modelli con finestre piccole.
 */
object ToolText {
    const val MAX_CHARS = 2400

    fun limit(text: String, maxChars: Int = MAX_CHARS): String {
        if (text.length <= maxChars) return text
        val lines = text.lines()
        val kept = StringBuilder()
        var count = 0
        for (line in lines) {
            if (kept.length + line.length + 1 > maxChars - 40) break
            kept.append(line).append('\n')
            count++
        }
        val missing = lines.size - count
        return kept.toString().trimEnd() + "\n… (altre $missing righe omesse)"
    }

    class Builder {
        private val lines = mutableListOf<String>()
        fun line(text: String) { lines += text }
        fun line(key: String, value: Any?) { lines += "$key: ${value ?: "—"}" }
        fun blank() { lines += "" }
        fun build(): String = limit(lines.joinToString("\n").trim())
    }

    fun build(block: Builder.() -> Unit): String = Builder().apply(block).build()
}
