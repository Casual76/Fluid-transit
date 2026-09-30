package dev.antigravity.fluidtransit.ai.tools

import dev.antigravity.fluidtransit.ai.tools.Args.bool
import dev.antigravity.fluidtransit.ai.tools.Args.int
import dev.antigravity.fluidtransit.ai.tools.Args.str
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Ftb
import dev.antigravity.fluidtransit.routing.DepartureText
import dev.antigravity.fluidtransit.routing.Times
import dev.antigravity.fluidtransit.routing.WhenText
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
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
    val stop = Resolve.stopIndex(ctx, args.str("fermata")) ?: return Resolve.stopNotFound(ctx, args.str("fermata"), "non trovo la fermata")
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
    val route = ctx.transit.findRoutes(query, 1).firstOrNull() ?: return Resolve.notFound(ctx, "non trovo una linea che si chiami \"$query\"")
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
  override val description = "Gli orari di una fermata in un giorno, anche per una linea sola. Senza fascia oraria: tutto il giorno, dal primo all'ultimo mezzo, comprese le corse dopo mezzanotte. Per \"a che ora passa il primo bus domani?\", \"che bus passano stanotte fra le 23 e le 2?\"."
  override val parameters = Schema.obj(
    mapOf(
      "fermata" to Schema.str("il nome della fermata; vuoto per la piu' vicina"),
      "giorno" to Schema.str("oggi, domani, o una data aaaa-mm-gg"),
      "dalle" to Schema.str("ora di inizio, es. 07:00; vuoto = dall'inizio del giorno"),
      "alle" to Schema.str("ora di fine, es. 09:00; puo' cadere dopo mezzanotte (dalle 23:00 alle 02:00; anche 26:00 = le 02:00 di notte); vuoto = fino all'ultima corsa"),
      "linea" to Schema.str("solo una linea (facoltativo)"),
    ),
  )

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val reader = ctx.transit.reader ?: return NO_DATA
    val stop = Resolve.stopIndex(ctx, args.str("fermata")) ?: return Resolve.stopNotFound(ctx, args.str("fermata"), "non trovo la fermata")
    val today = Instant.ofEpochMilli(ctx.nowMillis).atZone(ctx.zone).toLocalDate()
    val date = when (val raw = args.str("giorno")?.lowercase()?.trim()) {
      null, "oggi" -> today
      "domani" -> today.plusDays(1)
      "dopodomani" -> today.plusDays(2)
      else -> runCatching { LocalDate.parse(raw) }.getOrNull() ?: return "errore: giorno non capito (oggi, domani, o aaaa-mm-gg)"
    }
    // Un giorno fuori dal bundle non e' un giorno senza corse: `nextDepartures` lo salta e
    // tornerebbe una lista vuota, cioe' "non passa niente" detto come un fatto del mondo mentre
    // e' un buco dei nostri dati.
    val dayIndex = ChronoUnit.DAYS.between(reader.feedStart, date)
    if (dayIndex < 0 || dayIndex >= reader.dayCount) {
      val ultimo = reader.feedStart.plusDays(reader.dayCount - 1L)
      return "errore: gli orari scaricati coprono dal ${Times.dateLabel(reader.feedStart, today, relative = false)} " +
        "al ${Times.dateLabel(ultimo, today, relative = false)}, per quel giorno non so cosa passa"
    }
    // Una fascia non capita si dice, non si sostituisce con un'altra: rispondere su un'ora diversa
    // da quella chiesta e' peggio che chiedere di riprovare.
    val window = DayWindow.parse(
      args.str("dalle"),
      args.str("alle"),
      DayWindow.serviceDayEndMinutes(reader.maxTripEndSeconds),
    ) ?: return "errore: fascia oraria non capita (es. dalle 07:00 alle 09:00, anche oltre mezzanotte: dalle 23:00 alle 02:00)"
    val dayStart = Ftb.serviceDayStart(date, ctx.zone)
    val start = dayStart.plusSeconds(window.fromMinutes * 60L)
    val lineFilter = args.str("linea")?.let { q -> ctx.transit.findRoutes(q, 1).firstOrNull()?.routeIndex }
    // Senza tetto: `corse` deve essere il totale vero, e "prima" e "ultima" quelle vere. Con
    // `limit = 60` il numero si fermava a sessanta e l'ultimo bus della giornata non c'era.
    val departures = reader.nextDepartures(stop, start, limit = Int.MAX_VALUE, horizonSeconds = window.horizonSeconds, zone = ctx.zone)
      .filter { lineFilter == null || it.routeIndex == lineFilter }
    // Il giorno con le parole di `Times.dateLabel`: al modello serve sapere che "domani" e' il 1
    // ottobre, ma a chi legge la risposta non va detto "2026-10-01".
    val giornoEsteso = Times.dateLabel(date, today, relative = false)
    val giornoRelativo = Times.dateLabel(date, today)
    if (departures.isEmpty()) {
      return "da ${reader.stopName(stop)} non passa niente il $giornoEsteso fra le ${label(window.fromMinutes)} e le ${label(window.toMinutes)}"
    }
    return ToolText.build {
      line("fermata", reader.stopName(stop))
      val giorno = if (giornoRelativo == giornoEsteso) giornoEsteso else "$giornoRelativo, $giornoEsteso"
      line("giorno", "$giorno, dalle ${label(window.fromMinutes)} alle ${label(window.toMinutes)}")
      line("corse", departures.size)
      // Prima e ultima stanno sopra l'elenco: e' l'elenco che il tetto di caratteri taglia in
      // fondo, e "a che ora finisce il servizio" e' proprio la riga che non deve mancare.
      line("prima", describe(reader, departures.first(), date, ctx.zone))
      if (departures.size > 1) line("ultima", describe(reader, departures.last(), date, ctx.zone))
      departures.take(SHOWN).forEach { d -> line(describe(reader, d, date, ctx.zone)) }
      if (departures.size > SHOWN) line("elenco", "prime $SHOWN di ${departures.size}")
      line("nota", "sono orari previsti dall'orario ufficiale, non dati dal vivo")
    }
  }

  /** Un passaggio: l'ora come sulla tabella del giorno ("01:13 di notte" dopo la mezzanotte), la linea, il capolinea. */
  private fun describe(reader: BundleReader, d: BundleReader.Departure, date: LocalDate, zone: ZoneId): String =
    "${WhenText.clockOnDay(d.instant.epochSecond, date, zone)} · ${reader.lineName(d.routeIndex)} → ${reader.patternDestination(d.patternIndex)}"

  // Le ore oltre le 24 sono fascia che attraversa la mezzanotte, e "fino alle 25" e' una cosa che
  // si dice: l'eco non deve rispondere "01:00" come se fosse stamattina.
  private fun label(minutes: Int): String = WhenText.serviceClock(minutes * 60)

  private companion object {
    /** Quante righe scrivere: oltre, il testo per il modello sfora il suo tetto di caratteri. */
    const val SHOWN = 40
  }
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
    val stop = Resolve.stopIndex(ctx, args.str("fermata")) ?: return Resolve.stopNotFound(ctx, args.str("fermata"), "non trovo la fermata di partenza")
    val wanted = args.str("verso")?.lowercase()?.trim() ?: return "errore: manca la destinazione"
    val target = ctx.transit.findStops(wanted, 1).firstOrNull()
    // Lo stesso tabellone delle schermate. Qui si diceva "orario previsto" e
    // "dal vivo": due terzi di un terzo vocabolario, su numeri calcolati a
    // parte che potevano non coincidere con quelli della scheda fermata.
    val board = ctx.transit.board(stop, 40, 3 * 3600) ?: return NO_DATA
    val matching = board.rows.filter { d ->
      d.destination.lowercase().contains(wanted) ||
        target != null && passesThrough(reader, d.patternIndex, d.positionInPattern, target.stopIndex)
    }
    if (matching.isEmpty()) {
      val destinations = board.rows.map { it.destination }.filter { it.isNotBlank() }.distinct()
      return "da ${reader.stopName(stop)} non parte niente verso \"$wanted\" nelle prossime tre ore" +
        (if (destinations.isEmpty()) "" else "; da qui si va verso: ${destinations.joinToString(", ")}")
    }
    return ToolText.build {
      line("da", board.stopName.ifEmpty { reader.stopName(stop) })
      line("verso", target?.name ?: wanted)
      matching.take(5).forEach { d ->
        val phrase = DepartureText.phrase(d, board.computedAtEpoch)
        line(
          "${d.line} → ${d.destination} · ${Times.hhmm(d.effectiveEpoch, ctx.zone)} " +
            "(${phrase.headline}) · ${phrase.support}",
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
    val to = args.str("a")?.let { Resolve.target(ctx, it)?.point } ?: return Resolve.notFound(ctx, "non trovo la destinazione")
    val from = args.str("da")?.let { Resolve.target(ctx, it)?.point }
      ?: ctx.reference?.let { NamedPoint("qui", "", it.first, it.second) }
      ?: return "errore: non so da dove parti"
    val arriveBy = Resolve.timeToday(ctx, args.str("entro")) ?: return "errore: ora di arrivo non capita (es. 08:30)"
    val journeys = ctx.transit.plan(from.lat, from.lon, to.lat, to.lon, departAtEpoch = null, arriveByEpoch = arriveBy)
    if (journeys.isEmpty()) {
      val giorno = WhenText.dayWord(arriveBy, ctx.nowEpoch, ctx.zone)?.let { " ($it)" }.orEmpty()
      return "nessun itinerario per arrivare a ${to.name} entro le ${Times.hhmm(arriveBy, ctx.zone)}$giorno"
    }
    val best = journeys.maxByOrNull { it.departure }!!
    return ToolText.build {
      line("da", from.name)
      line("a", to.name)
      // Ogni orario dice il suo giorno quando non e' oggi, e la partenza dice fra quanto con le
      // stesse parole delle altre superfici: alle 23:00 "parti alle 06:40 (461 min)" era la
      // partenza di domattina scritta come un numero da dividere per sessanta, senza il giorno.
      line("per arrivare entro", WhenText.clock(arriveBy, ctx.nowEpoch, ctx.zone))
      line("parti", WhenText.atClockWithWait(best.departure.epochSecond, ctx.nowEpoch, ctx.zone))
      line("arrivo previsto", WhenText.clock(best.arrival.epochSecond, ctx.nowEpoch, ctx.zone))
      // Le stesse parole delle schermate: la durata dalla funzione condivisa,
      // che sopra l'ora dice le ore — un viaggio notturno da quattro ore si
      // presentava come "250 minuti" — e il cambio al singolare quando e' uno
      // solo, che qui diceva "1 cambi".
      val cambi = when (best.transfers) {
        0 -> "nessun cambio"
        1 -> "1 cambio"
        else -> "${best.transfers} cambi"
      }
      line(
        "durata",
        "${Times.durationBetween(best.departure.epochSecond, best.arrival.epochSecond)} · $cambi",
      )
      if (journeys.size > 1) line("alternative", journeys.sortedByDescending { it.departure }.drop(1).take(2).joinToString("; ") { "parti ${WhenText.atClock(it.departure.epochSecond, ctx.nowEpoch, ctx.zone)}" })
    }
  }
}

fun scheduleExtraTools(): List<AiTool> = listOf(
  NearbyStopsTool(), StopLinesTool(), RoutePathTool(), StopDayScheduleTool(), NextBusForTool(), NetworkStatusTool(), WhenToLeaveTool(),
)
