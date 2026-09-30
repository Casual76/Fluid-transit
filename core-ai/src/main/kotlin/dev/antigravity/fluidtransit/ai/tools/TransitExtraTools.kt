package dev.antigravity.fluidtransit.ai.tools

import dev.antigravity.fluidtransit.ai.tools.Args.bool
import dev.antigravity.fluidtransit.ai.tools.Args.int
import dev.antigravity.fluidtransit.ai.tools.Args.str
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Ftb
import dev.antigravity.fluidtransit.routing.DepartureText
import dev.antigravity.fluidtransit.routing.Times
import dev.antigravity.fluidtransit.routing.WhenText
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject

private const val NO_DATA = "errore: gli orari non sono ancora scaricati"

/** Il nome di una linea come lo legge la gente: il numero, o il nome lungo se il numero manca. */
private fun BundleReader.lineName(routeIndex: Int): String =
  routeShortName(routeIndex).ifEmpty { routeLongName(routeIndex) }

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
    val poles = reader.stopsNear(point.lat, point.lon, radius)
      .map { it to BundleReader.haversine(point.lat, point.lon, reader.stopLat(it), reader.stopLon(it)) }
      .sortedBy { it.second }
    // Una fermata vera e' un gruppo di pali (le due direzioni, le due
    // banchine): elencati uno per uno, tre luoghi con lo stesso nome si
    // prendevano i cinque posti e il resto spariva. Si tiene il palo piu'
    // vicino di ogni gruppo e si tagliano i GRUPPI, non i pali.
    val groups = LinkedHashMap<List<Int>, Pair<Int, Double>>()
    for ((stop, distance) in poles) {
      val key = ctx.transit.siblings(stop).sorted().ifEmpty { listOf(stop) }
      groups.putIfAbsent(key, stop to distance)
    }
    val near = groups.entries.take(limit)
    if (near.isEmpty()) return "nessuna fermata entro ${radius.toInt()} metri da ${point.name}"
    return ToolText.build {
      line("intorno a", point.name)
      near.forEach { (members, nearest) ->
        val (stop, distance) = nearest
        val lines = members.flatMap { reader.patternsAtStop(it).toList() }
          .map { reader.patternRoute(it) }.distinct().map { reader.lineName(it) }.filter { it.isNotBlank() }.distinct()
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
    // Tutte le banchine della fermata: il palo trovato dalla ricerca e' uno,
    // e da quello solo si vede una direzione (o nessuna linea, se e' un palo
    // dove si scende e basta).
    val patterns = ctx.transit.siblings(stop).flatMap { reader.patternsAtStop(it).toList() }.distinct()
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
    val today = Resolve.today(ctx)
    val date = Resolve.parseDay(today, args.str("giorno"))
      ?: return "errore: giorno non capito (oggi, domani, un giorno della settimana, o aaaa-mm-gg)"
    // Un giorno fuori dal bundle non e' un giorno senza corse: `nextDepartures` lo salta e
    // tornerebbe una lista vuota, cioe' "non passa niente" detto come un fatto del mondo mentre
    // e' un buco dei nostri dati.
    Resolve.coverageError(reader, date, today)?.let { return it }
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
    // Senza "alle" la finestra arriva alla fine del giorno di servizio (fino alle 30 passate),
    // e il lettore guarda anche il giorno dopo: senza questo filtro entravano le corse regolari
    // del mattino seguente, e "ultima" diventava il bus delle 06:05 di domani. Si tengono le corse
    // del giorno chiesto, comprese le sue notturne oltre le 24, e quelle della notte prima che
    // cadono dopo la mezzanotte di quel giorno. Con un "alle" esplicito oltre la mezzanotte, il
    // giorno dopo e' quello che si e' chiesto e resta.
    val mezzanotteDopo = date.plusDays(1).atStartOfDay(ctx.zone).toInstant()
    val soloIlGiorno = args.str("alle") == null
    // Tutte le banchine della fermata, come fa il tabellone: da un palo solo "il primo bus di
    // domani" e "l'ultimo" erano quelli di una direzione. La regola e' quella di
    // `Departures.oneRowPerBus`: di ogni corsa si tiene il palo preferito (il trovato per
    // primo) e da quello TUTTI i passaggi. Un dedup per (corsa, giorno) secco toglieva anche il
    // secondo passaggio di un anello dalla stessa fermata (215 pattern su 8.331), e se era il
    // piu' tardi "l'ultimo bus di stasera" era sbagliato.
    val poli = (listOf(stop) + ctx.transit.siblings(stop).filter { it != stop }).distinct()
    val daiPoli = poli.map { palo ->
      reader.nextDepartures(palo, start, limit = Int.MAX_VALUE, horizonSeconds = window.horizonSeconds, zone = ctx.zone)
    }
    val departures = onePolePerTrip(daiPoli)
      .sortedBy { it.instant }
      .filter { lineFilter == null || it.routeIndex == lineFilter }
      .filter { !soloIlGiorno || it.serviceDate == date || it.instant < mezzanotteDopo }
    // Il giorno con le parole di `Times.dateLabel`: al modello serve sapere che "domani" e' il 1
    // ottobre, ma a chi legge la risposta non va detto "2026-10-01".
    val giornoEsteso = Times.dateLabel(date, today, relative = false)
    val giornoRelativo = Times.dateLabel(date, today)
    // Senza "alle" la fine della finestra e' un numero tecnico (la fine del giorno di servizio):
    // scriverlo, "alle 06:10 di notte", sembrerebbe una fascia che nessuno ha chiesto.
    val fino = if (soloIlGiorno) "fino all'ultima corsa" else "alle ${label(window.toMinutes)}"
    if (departures.isEmpty()) {
      return "da ${reader.stopName(stop)} non passa niente il $giornoEsteso dalle ${label(window.fromMinutes)} $fino"
    }
    return ToolText.build {
      line("fermata", reader.stopName(stop))
      val giorno = if (giornoRelativo == giornoEsteso) giornoEsteso else "$giornoRelativo, $giornoEsteso"
      line("giorno", "$giorno, dalle ${label(window.fromMinutes)} $fino")
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

  internal companion object {
    /** Quante righe scrivere: oltre, il testo per il modello sfora il suo tetto di caratteri. */
    const val SHOWN = 40

    /**
     * I passaggi di piu' pali senza la stessa corsa due volte: [perPalo] e' in ordine di
     * preferenza, e di ogni (corsa, giorno di servizio) si tiene il primo palo che la vede, con
     * tutti i suoi passaggi (un anello che ripassa dalla stessa fermata ne ha due).
     */
    fun onePolePerTrip(perPalo: List<List<BundleReader.Departure>>): List<BundleReader.Departure> {
      val preferito = HashMap<Pair<Int, LocalDate>, Int>()
      perPalo.forEachIndexed { i, righe ->
        for (d in righe) preferito.putIfAbsent(d.tripIndex to d.serviceDate, i)
      }
      val out = ArrayList<BundleReader.Departure>()
      perPalo.forEachIndexed { i, righe ->
        for (d in righe) if (preferito[d.tripIndex to d.serviceDate] == i) out.add(d)
      }
      return out
    }
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
    ctx.transit.ensureLive(vehicles = false)
    val board = ctx.transit.board(stop, 40, 3 * 3600) ?: return NO_DATA
    // La destinazione e' una fermata con due direzioni: il bus che ci va la tocca al palo della
    // SUA direzione, che e' quello trovato dalla ricerca solo una volta su due. Si cercano tutte
    // le banchine, o "non parte niente verso Piazza Dalmazia" era detto con il bus li'.
    val targetStops = target?.let { ctx.transit.siblings(it.stopIndex).toSet().ifEmpty { setOf(it.stopIndex) } }.orEmpty()
    val matching = board.rows.filter { d ->
      d.destination.lowercase().contains(wanted) ||
        targetStops.isNotEmpty() && passesThrough(reader, d.patternIndex, d.positionInPattern, targetStops)
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

  /** Vero se il percorso, dopo la fermata di salita, tocca una delle banchine della fermata cercata. */
  private fun passesThrough(reader: BundleReader, pattern: Int, from: Int, stopIndexes: Set<Int>): Boolean =
    ((from + 1) until reader.patternStopCount(pattern)).any { reader.patternStop(pattern, it) in stopIndexes }
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
      "giorno" to Schema.str("il giorno dell'arrivo: oggi, domani, dopodomani, un giorno della settimana (sabato) o una data aaaa-mm-gg; vuoto = la prossima volta che quell'ora viene"),
    ),
    required = listOf("a", "entro"),
  )

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val to = args.str("a")?.let { Resolve.target(ctx, it)?.point } ?: return Resolve.notFound(ctx, "non trovo la destinazione")
    val from = args.str("da")?.let { Resolve.target(ctx, it)?.point }
      ?: ctx.reference?.let { NamedPoint("qui", "", it.first, it.second) }
      ?: return "errore: non so da dove parti"
    // Il giorno, come in come_arrivo: senza "giorno" l'ora e' la prossima volta che viene.
    val today = Resolve.today(ctx)
    val dayText = args.str("giorno")
    val day = if (dayText == null) null else Resolve.parseDay(today, dayText)
      ?: return "errore: giorno non capito (oggi, domani, un giorno della settimana, o aaaa-mm-gg)"
    val arriveBy = Resolve.timeOn(ctx, day, args.str("entro")) ?: return "errore: ora di arrivo non capita (es. 08:30)"
    if (Resolve.alreadyPassedToday(ctx, day, arriveBy)) return Resolve.PASSED_TODAY
    if (day != null) {
      ctx.transit.reader?.let { r -> Resolve.coverageError(r, day, today)?.let { return it } }
    }
    if (day == null || day == today) ctx.transit.ensureLive(vehicles = false)
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
