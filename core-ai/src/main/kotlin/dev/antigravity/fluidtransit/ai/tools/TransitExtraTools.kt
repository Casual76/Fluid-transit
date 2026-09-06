package dev.antigravity.fluidtransit.ai.tools

import dev.antigravity.fluidtransit.ai.tools.Args.bool
import dev.antigravity.fluidtransit.ai.tools.Args.int
import dev.antigravity.fluidtransit.ai.tools.Args.str
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Ftb
import dev.antigravity.fluidtransit.routing.Times
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject

private const val NO_DATA = "errore: gli orari non sono ancora scaricati"

/** Il nome di una linea come lo legge la gente: il numero, o il nome lungo se il numero manca. */
private fun BundleReader.lineName(routeIndex: Int): String =
  routeShortName(routeIndex).ifEmpty { routeLongName(routeIndex) }

/** I giorni della settimana come li scrive il modello: "lun", "lunedi", "1", "feriali". */
private fun parseDays(raw: List<String>): Set<Int> = raw.flatMap { item ->
  when (val t = item.lowercase().trim().trim('\'')) {
    "feriali", "lun-ven", "settimana" -> listOf(1, 2, 3, 4, 5)
    "weekend", "fine settimana" -> listOf(6, 7)
    "tutti", "ogni giorno", "sempre" -> listOf(1, 2, 3, 4, 5, 6, 7)
    else -> listOfNotNull(
      when {
        t.startsWith("lun") -> 1
        t.startsWith("mar") -> 2
        t.startsWith("mer") -> 3
        t.startsWith("gio") -> 4
        t.startsWith("ven") -> 5
        t.startsWith("sab") -> 6
        t.startsWith("dom") -> 7
        else -> t.toIntOrNull()?.takeIf { it in 1..7 }
      },
    )
  }
}.toSet()

private fun daysLabel(days: Set<Int>): String = when {
  days.isEmpty() -> "mai"
  days == setOf(1, 2, 3, 4, 5) -> "dal lunedi' al venerdi'"
  days.size == 7 -> "tutti i giorni"
  else -> days.sorted().joinToString(", ") { DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ITALIAN) }
}

/** Le fermate intorno a un punto: la domanda "da dove parto?" prima ancora degli orari. */
class NearbyStopsTool : AiTool {
  override val name = "fermate_vicine"
  override val group = ToolGroup.PLACES
  override val description = "Le fermate piu' vicine a dove si trova l'utente (o a un luogo che gli dai), con la distanza e le linee che ci passano."
  override val parameters = Schema.obj(
    mapOf(
      "luogo" to Schema.str("il posto attorno a cui cercare; vuoto per dove si trova l'utente"),
      "raggio_metri" to Schema.int("entro quanti metri (default 700)", 100, 3000),
      "quante" to Schema.int("quante fermate elencare (default 5)", 1, 10),
    ),
  )

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val reader = ctx.transit.reader ?: return NO_DATA
    val point = args.str("luogo")?.let { Resolve.target(ctx, it)?.point } ?: ctx.reference?.let { NamedPoint("qui", "", it.first, it.second) }
      ?: return "errore: non so dove ti trovi"
    val radius = (args.int("raggio_metri") ?: 700).toDouble()
    val limit = args.int("quante") ?: 5
    val near = reader.stopsNear(point.lat, point.lon, radius)
      .map { it to BundleReader.haversine(point.lat, point.lon, reader.stopLat(it), reader.stopLon(it)) }
      .sortedBy { it.second }
      .take(limit)
    if (near.isEmpty()) return "nessuna fermata entro ${radius.toInt()} metri da ${point.name}"
    return ToolText.build {
      line("intorno a", point.name)
      near.forEach { (stop, distance) ->
        val lines = reader.patternsAtStop(stop).map { reader.patternRoute(it) }.distinct().map { reader.lineName(it) }.filter { it.isNotBlank() }.distinct()
        line("${reader.stopName(stop)} · ${distance.toInt()} m" + (if (lines.isEmpty()) "" else " · linee ${lines.sorted().joinToString(", ")}"))
      }
    }
  }
}

/** Che linee passano da una fermata: la domanda che viene prima di "quando passa". */
class StopLinesTool : AiTool {
  override val name = "linee_fermata"
  override val group = ToolGroup.SCHEDULE
  override val description = "Le linee che passano da una fermata, con le destinazioni. Per \"che autobus passano da qui?\"."
  override val parameters = Schema.obj(mapOf("fermata" to Schema.str("il nome della fermata; vuoto per la piu' vicina")))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val reader = ctx.transit.reader ?: return NO_DATA
    val stop = Resolve.stopIndex(ctx, args.str("fermata")) ?: return "non trovo la fermata"
    val patterns = reader.patternsAtStop(stop)
    if (patterns.isEmpty()) return "da ${reader.stopName(stop)} non risulta passare nessuna linea"
    val byRoute = patterns.groupBy { reader.patternRoute(it) }
    return ToolText.build {
      line("fermata", reader.stopName(stop))
      line("linee", byRoute.size)
      byRoute.entries.sortedBy { reader.lineName(it.key) }.forEach { (route, ps) ->
        val destinations = ps.map { reader.patternDestination(it) }.filter { it.isNotBlank() }.distinct()
        line("${reader.lineName(route)} → ${destinations.joinToString(" / ").ifEmpty { "—" }}")
      }
    }
  }
}

/** Il percorso di una linea, fermata per fermata. */
class RoutePathTool : AiTool {
  override val name = "percorso_linea"
  override val group = ToolGroup.SCHEDULE
  override val description = "Le fermate di una linea in ordine, in una direzione. Per \"dove passa il 23?\", \"la linea arriva in centro?\"."
  override val parameters = Schema.obj(
    mapOf(
      "linea" to Schema.str("il numero o il nome della linea"),
      "verso" to Schema.str("la destinazione, se la linea ha due direzioni"),
    ),
    required = listOf("linea"),
  )

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val reader = ctx.transit.reader ?: return NO_DATA
    val query = args.str("linea") ?: return "errore: manca la linea"
    val route = ctx.transit.findRoutes(query, 1).firstOrNull() ?: return "non trovo una linea che si chiami \"$query\""
    val patterns = reader.patternsOfRoute(route.routeIndex)
    if (patterns.isEmpty()) return "la linea ${route.shortName} non ha percorsi nell'orario scaricato"
    val wanted = args.str("verso")?.lowercase()
    val pattern = patterns.filter { wanted == null || reader.patternDestination(it).lowercase().contains(wanted) }
      .maxByOrNull { reader.patternStopCount(it) } ?: patterns.first()
    val stops = (0 until reader.patternStopCount(pattern)).map { reader.stopName(reader.patternStop(pattern, it)) }
    return ToolText.build {
      line("linea", route.shortName)
      line("verso", reader.patternDestination(pattern).ifEmpty { "—" })
      line("fermate", stops.size)
      val other = patterns.map { reader.patternDestination(it) }.filter { it.isNotBlank() && it != reader.patternDestination(pattern) }.distinct()
      if (other.isNotEmpty()) line("altre direzioni", other.joinToString(", "))
      line(stops.joinToString(" → "))
    }
  }
}

/** Gli orari di una fermata in un giorno qualsiasi, non solo adesso. */
class StopDayScheduleTool : AiTool {
  override val name = "orari_fermata_giorno"
  override val group = ToolGroup.SCHEDULE
  override val description = "Gli orari di una fermata in un giorno e in una fascia oraria (default oggi dalle 6 alle 22), anche per una linea sola. Per \"a che ora passa il primo bus domani?\"."
  override val parameters = Schema.obj(
    mapOf(
      "fermata" to Schema.str("il nome della fermata; vuoto per la piu' vicina"),
      "giorno" to Schema.str("oggi, domani, o una data aaaa-mm-gg"),
      "dalle" to Schema.str("ora di inizio, es. 07:00"),
      "alle" to Schema.str("ora di fine, es. 09:00"),
      "linea" to Schema.str("solo una linea (facoltativo)"),
    ),
  )

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val reader = ctx.transit.reader ?: return NO_DATA
    val stop = Resolve.stopIndex(ctx, args.str("fermata")) ?: return "non trovo la fermata"
    val today = Instant.ofEpochMilli(ctx.nowMillis).atZone(ctx.zone).toLocalDate()
    val date = when (val raw = args.str("giorno")?.lowercase()?.trim()) {
      null, "oggi" -> today
      "domani" -> today.plusDays(1)
      "dopodomani" -> today.plusDays(2)
      else -> runCatching { LocalDate.parse(raw) }.getOrNull() ?: return "errore: giorno non capito (oggi, domani, o aaaa-mm-gg)"
    }
    val from = parseMinutes(args.str("dalle")) ?: 6 * 60
    val to = parseMinutes(args.str("alle")) ?: 22 * 60
    val dayStart = Ftb.serviceDayStart(date, ctx.zone)
    val start = dayStart.plusSeconds(from * 60L)
    val horizon = ((to - from) * 60).coerceAtLeast(60)
    val lineFilter = args.str("linea")?.let { q -> ctx.transit.findRoutes(q, 1).firstOrNull()?.routeIndex }
    val departures = reader.nextDepartures(stop, start, limit = 60, horizonSeconds = horizon, zone = ctx.zone)
      .filter { lineFilter == null || it.routeIndex == lineFilter }
    if (departures.isEmpty()) return "da ${reader.stopName(stop)} non passa niente il $date fra le ${label(from)} e le ${label(to)}"
    return ToolText.build {
      line("fermata", reader.stopName(stop))
      line("giorno", "$date, dalle ${label(from)} alle ${label(to)}")
      line("corse", departures.size)
      departures.take(40).forEach { d ->
        line("${Times.hhmm(d.instant.epochSecond, ctx.zone)} · ${reader.lineName(d.routeIndex)} → ${reader.patternDestination(d.patternIndex)}")
      }
      if (departures.size > 40) line("altre", departures.size - 40)
      line("nota", "sono orari previsti dall'orario ufficiale, non dati dal vivo")
    }
  }

  private fun parseMinutes(raw: String?): Int? {
    val m = Regex("(\\d{1,2})[:.]?(\\d{2})?").find(raw?.trim().orEmpty()) ?: return null
    val h = m.groupValues[1].toIntOrNull()?.takeIf { it in 0..29 } ?: return null
    return h * 60 + (m.groupValues[2].toIntOrNull() ?: 0)
  }

  private fun label(minutes: Int): String = "%02d:%02d".format(minutes / 60 % 24, minutes % 60)
}

/** "Quando passa il prossimo per il centro": la linea giusta scelta dalla destinazione. */
class NextBusForTool : AiTool {
  override val name = "prossimo_bus_per"
  override val group = ToolGroup.SCHEDULE
  override val description = "Il prossimo mezzo da una fermata che va verso un posto (per destinazione del percorso). Per \"quando passa il prossimo per la stazione?\"."
  override val parameters = Schema.obj(
    mapOf(
      "verso" to Schema.str("dove si vuole andare (una destinazione, un quartiere, una fermata)"),
      "fermata" to Schema.str("da quale fermata; vuoto per la piu' vicina"),
    ),
    required = listOf("verso"),
  )

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val reader = ctx.transit.reader ?: return NO_DATA
    val stop = Resolve.stopIndex(ctx, args.str("fermata")) ?: return "non trovo la fermata di partenza"
    val wanted = args.str("verso")?.lowercase()?.trim() ?: return "errore: manca la destinazione"
    val target = ctx.transit.findStops(wanted, 1).firstOrNull()
    val departures = reader.nextDepartures(stop, Instant.ofEpochMilli(ctx.nowMillis), limit = 40, horizonSeconds = 3 * 3600, zone = ctx.zone)
    val matching = departures.filter { d ->
      val destination = reader.patternDestination(d.patternIndex).lowercase()
      destination.contains(wanted) || target != null && passesThrough(reader, d.patternIndex, d.positionInPattern, target.stopIndex)
    }
    if (matching.isEmpty()) {
      val destinations = departures.map { reader.patternDestination(it.patternIndex) }.filter { it.isNotBlank() }.distinct()
      return "da ${reader.stopName(stop)} non parte niente verso \"$wanted\" nelle prossime tre ore" +
        (if (destinations.isEmpty()) "" else "; da qui si va verso: ${destinations.joinToString(", ")}")
    }
    return ToolText.build {
      line("da", reader.stopName(stop))
      line("verso", target?.name ?: wanted)
      matching.take(5).forEach { d ->
        val live = ctx.transit.delays?.at(d.tripIndex, d.positionInPattern, reader.patternStopCount(d.patternIndex))
        val effective = d.instant.epochSecond + (live?.delaySeconds ?: 0)
        line(
          "${reader.lineName(d.routeIndex)} → ${reader.patternDestination(d.patternIndex)} · ${Times.hhmm(effective, ctx.zone)} " +
            "(${Times.minutesLabel(ctx.nowEpoch, effective)}) · " + (if (live == null) "orario previsto" else "dal vivo, ${Times.delayLabel(live.delaySeconds)}"),
        )
      }
    }
  }

  /** Vero se il percorso, dopo la fermata di salita, tocca la fermata cercata. */
  private fun passesThrough(reader: BundleReader, pattern: Int, from: Int, stopIndex: Int): Boolean =
    ((from + 1) until reader.patternStopCount(pattern)).any { reader.patternStop(pattern, it) == stopIndex }
}

/** Se i dati dal vivo stanno arrivando: la differenza fra "il bus è in ritardo" e "non lo so". */
class NetworkStatusTool : AiTool {
  override val name = "stato_rete"
  override val group = ToolGroup.LIVE
  override val description = "Se i dati dal vivo (posizioni e ritardi dei mezzi) stanno arrivando, da dove e quanto sono freschi. Da usare quando i minuti sembrano sbagliati."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: ToolContext): String = ToolText.build {
    line("dati dal vivo", ctx.transit.realtimeStatus())
    line("orario offline", ctx.transit.dataStatus())
  }
}

/** "A che ora devo uscire": l'orario di partenza per arrivare in tempo. */
class WhenToLeaveTool : AiTool {
  override val name = "quando_uscire"
  override val group = ToolGroup.JOURNEY
  override val description = "A che ora conviene uscire per arrivare in un posto entro un'ora: calcola l'itinerario a ritroso e dice l'orario di partenza e la prima corsa da prendere."
  override val parameters = Schema.obj(
    mapOf(
      "a" to Schema.place,
      "entro" to Schema.str("l'ora di arrivo, es. 08:30"),
      "da" to Schema.place,
    ),
    required = listOf("a", "entro"),
  )

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val to = args.str("a")?.let { Resolve.target(ctx, it)?.point } ?: return "non trovo la destinazione"
    val from = args.str("da")?.let { Resolve.target(ctx, it)?.point }
      ?: ctx.reference?.let { NamedPoint("qui", "", it.first, it.second) }
      ?: return "errore: non so da dove parti"
    val arriveBy = Resolve.timeToday(ctx, args.str("entro")) ?: return "errore: ora di arrivo non capita (es. 08:30)"
    val journeys = ctx.transit.plan(from.lat, from.lon, to.lat, to.lon, departAtEpoch = null, arriveByEpoch = arriveBy)
    if (journeys.isEmpty()) return "nessun itinerario per arrivare a ${to.name} entro le ${Times.hhmm(arriveBy, ctx.zone)}"
    val best = journeys.maxByOrNull { it.departure }!!
    return ToolText.build {
      line("da", from.name)
      line("a", to.name)
      line("per arrivare entro", Times.hhmm(arriveBy, ctx.zone))
      line("parti alle", "${Times.hhmm(best.departure.epochSecond, ctx.zone)} (${Times.minutesLabel(ctx.nowEpoch, best.departure.epochSecond)})")
      line("arrivo previsto", Times.hhmm(best.arrival.epochSecond, ctx.zone))
      line("durata", "${best.durationSeconds / 60} minuti" + (if (best.transfers > 0) " · ${best.transfers} cambi" else " · nessun cambio"))
      if (journeys.size > 1) line("alternative", journeys.sortedByDescending { it.departure }.drop(1).take(2).joinToString("; ") { "parti alle ${Times.hhmm(it.departure.epochSecond, ctx.zone)}" })
    }
  }
}

fun scheduleExtraTools(): List<AiTool> = listOf(
  NearbyStopsTool(), StopLinesTool(), RoutePathTool(), StopDayScheduleTool(), NextBusForTool(), NetworkStatusTool(), WhenToLeaveTool(),
)
