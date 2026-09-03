package dev.antigravity.fluidtransit.ai.tools

import dev.antigravity.fluidtransit.ai.tools.Args.int
import dev.antigravity.fluidtransit.ai.tools.Args.str
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Ftb
import dev.antigravity.fluidtransit.routing.Raptor
import dev.antigravity.fluidtransit.routing.Times
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlinx.serialization.json.JsonObject

/**
 * Da una parola a un punto sulla mappa.
 *
 * L'ordine non e' casuale: prima i posti che l'utente ha salvato (se dice
 * "casa" intende la sua), poi le fermate, poi i luoghi, poi le linee. E' lo
 * stesso ordine di probabilita' con cui una persona usa quelle parole.
 */
internal object Resolve {

    class Target(
        val point: NamedPoint,
        val stop: StopHit? = null,
        val route: RouteHit? = null,
    )

    fun target(ctx: ToolContext, text: String?): Target? {
        val q = text?.trim().orEmpty()
        if (q.isEmpty()) {
            val here = ctx.transit.here ?: ctx.transit.looking ?: return null
            return Target(NamedPoint("La tua posizione", "", here.first, here.second))
        }
        val bridge = ctx.transit

        bridge.savedPlaces().firstOrNull { it.name.equals(q, ignoreCase = true) }
            ?.let { return Target(it) }

        val ref = ctx.reference
        val stop = bridge.findStops(q, 1).firstOrNull()
        val place = bridge.places?.fast(
            q, 1,
            ref?.first ?: Double.NaN,
            ref?.second ?: Double.NaN,
        )?.firstOrNull()

        // Fra una fermata e un luogo con lo stesso nome vince il luogo se e'
        // un nome proprio pieno: "Uffizi" e' il museo, non la fermata omonima
        // — ma "Piazza Dalmazia" e' la fermata, che e' dove si sale.
        if (place != null && stop == null) {
            return Target(NamedPoint(place.name, place.context, place.lat, place.lon))
        }
        if (stop != null) {
            return Target(NamedPoint(stop.name, "Fermata", stop.lat, stop.lon), stop = stop)
        }
        val route = bridge.findRoutes(q, 1).firstOrNull()
        if (route != null) {
            val r = bridge.reader ?: return null
            val p = r.patternsOfRoute(route.routeIndex).firstOrNull() ?: return null
            val s = r.patternStop(p, 0)
            return Target(
                NamedPoint("Linea ${route.shortName}", route.headsign, r.stopLat(s), r.stopLon(s)),
                route = route,
            )
        }
        return null
    }

    /** "8:30", "08:30", "20.15" → l'epoch di oggi (o domani se e' gia' passata). */
    fun timeToday(ctx: ToolContext, text: String?): Long? {
        val t = text?.trim()?.replace('.', ':') ?: return null
        val parts = t.split(':')
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (h !in 0..23 || m !in 0..59) return null
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ctx.nowMillis), ctx.zone)
        var at = now.with(LocalTime.of(h, m))
        if (at.isBefore(now.minusMinutes(5))) at = at.plusDays(1)
        return at.toEpochSecond()
    }

    fun distanceLabel(ctx: ToolContext, lat: Double, lon: Double): String? {
        val ref = ctx.reference ?: return null
        val m = BundleReader.haversine(ref.first, ref.second, lat, lon)
        return if (m < 1000) "${m.toInt()} m" else "%.1f km".format(m / 1000)
    }
}

/** Cerca qualsiasi cosa: fermate, linee, luoghi, indirizzi, posti salvati. */
class SearchTool : AiTool {
    override val name = "cerca"
    override val group = ToolGroup.PLACES
    override val description =
        "Cerca fermate, linee, luoghi, indirizzi e posti salvati. Usalo quando ti serve " +
            "sapere se una cosa esiste e dove sta, prima di rispondere o di mostrarla."
    override val parameters = Schema.obj(
        mapOf("cosa" to Schema.str("cosa cercare, come lo direbbe una persona")),
        required = listOf("cosa"),
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val q = args.str("cosa") ?: return "errore: manca cosa cercare"
        val bridge = ctx.transit
        val ref = ctx.reference
        return ToolText.build {
            val saved = bridge.savedPlaces().filter { it.name.contains(q, ignoreCase = true) }
            for (p in saved) line("posto salvato: ${p.name}")
            for (s in bridge.findStops(q, 4)) {
                val d = Resolve.distanceLabel(ctx, s.lat, s.lon)
                line("fermata: ${s.name}${if (d != null) " ($d)" else ""}")
            }
            for (r in bridge.findRoutes(q, 3)) {
                line("linea: ${r.shortName} verso ${r.headsign}")
            }
            val places = bridge.places?.fast(
                q, 5,
                ref?.first ?: Double.NaN,
                ref?.second ?: Double.NaN,
            ).orEmpty()
            for (p in places) {
                val d = Resolve.distanceLabel(ctx, p.lat, p.lon)
                line("luogo: ${p.name}${if (p.context.isNotEmpty()) " — ${p.context}" else ""}" + (d?.let { " ($it)" } ?: ""))
            }
            if (saved.isEmpty() && places.isEmpty() &&
                bridge.findStops(q, 1).isEmpty() && bridge.findRoutes(q, 1).isEmpty()
            ) {
                line("nessun risultato per \"$q\"")
            }
        }
    }
}

/** I posti che l'utente ha salvato. */
class SavedPlacesTool : AiTool {
    override val name = "posti_salvati"
    override val group = ToolGroup.PLACES
    override val description = "Elenca i posti salvati dall'utente (casa, lavoro, scuola, altri)."
    override val parameters = Schema.obj(emptyMap())

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val places = ctx.transit.savedPlaces()
        if (places.isEmpty()) return "l'utente non ha salvato nessun posto"
        return ToolText.build { for (p in places) line(p.name, Resolve.distanceLabel(ctx, p.lat, p.lon)) }
    }
}

/** I prossimi passaggi di una fermata. */
class NextDeparturesTool : AiTool {
    override val name = "prossimi_passaggi"
    override val group = ToolGroup.SCHEDULE
    override val description =
        "Quando passano i prossimi mezzi da una fermata, con linea, destinazione e minuti " +
            "che mancano. Dice anche se il minuto e' un dato live dal bus o un orario previsto."
    override val parameters = Schema.obj(
        mapOf(
            "fermata" to Schema.str("nome della fermata; vuoto per la piu' vicina a dove si trova"),
            "quanti" to Schema.int("quante corse elencare", 1, 8),
        ),
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val reader = ctx.transit.reader ?: return "errore: gli orari non sono ancora scaricati"
        val wanted = args.int("quanti")?.coerceIn(1, 8) ?: 5
        val query = args.str("fermata")
        val stopIndex = if (query == null) {
            val ref = ctx.reference ?: return "errore: non so dove ti trovi"
            reader.stopsNear(ref.first, ref.second, 600.0).minByOrNull {
                BundleReader.haversine(ref.first, ref.second, reader.stopLat(it), reader.stopLon(it))
            } ?: return "nessuna fermata entro seicento metri"
        } else {
            ctx.transit.findStops(query, 1).firstOrNull()?.stopIndex
                ?: return "non trovo una fermata che si chiami \"$query\""
        }

        val now = Instant.ofEpochMilli(ctx.nowMillis)
        val departures = reader.nextDepartures(stopIndex, now, limit = wanted, horizonSeconds = 3 * 3600)
        if (departures.isEmpty()) return "da ${reader.stopName(stopIndex)} non passa piu' niente nelle prossime tre ore"

        return ToolText.build {
            line("fermata", reader.stopName(stopIndex))
            for (d in departures) {
                val live = ctx.transit.delays?.at(
                    d.tripIndex,
                    d.positionInPattern,
                    reader.patternStopCount(d.patternIndex),
                )
                val eff = d.instant.epochSecond + (live?.delaySeconds ?: 0)
                val linea = reader.routeShortName(d.routeIndex)
                    .ifEmpty { reader.routeLongName(d.routeIndex) }
                val stato = when {
                    live == null -> "orario previsto"
                    live.delaySeconds == 0 -> "live, in orario"
                    else -> "live, ${Times.delayLabel(live.delaySeconds)}"
                }
                line(
                    "$linea verso ${reader.patternDestination(d.patternIndex)}: " +
                        "${Times.minutesLabel(ctx.nowEpoch, eff)} (${Times.hhmm(eff)}, $stato)",
                )
            }
        }
    }
}

/** Prima e ultima corsa, frequenza, prossima partenza di una linea. */
class RouteScheduleTool : AiTool {
    override val name = "orari_linea"
    override val group = ToolGroup.SCHEDULE
    override val description =
        "Come funziona oggi una linea: prima e ultima corsa, ogni quanto passa, dove va."
    override val parameters = Schema.obj(
        mapOf("linea" to Schema.str("numero o nome della linea")),
        required = listOf("linea"),
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val reader = ctx.transit.reader ?: return "errore: gli orari non sono ancora scaricati"
        val q = args.str("linea") ?: return "errore: manca la linea"
        val hit = ctx.transit.findRoutes(q, 1).firstOrNull()
            ?: return "non trovo una linea che si chiami \"$q\""
        val now = Instant.ofEpochMilli(ctx.nowMillis)
        val today = now.atZone(ctx.zone).toLocalDate()
        val dayIndex = java.time.temporal.ChronoUnit.DAYS.between(reader.feedStart, today).toInt()
        val dayStart = Ftb.serviceDayStart(today).epochSecond

        var first = Int.MAX_VALUE
        var last = Int.MIN_VALUE
        var count = 0
        var nextDep = Long.MAX_VALUE
        var nextHeadsign = ""
        for (p in reader.patternsOfRoute(hit.routeIndex)) {
            val firstTrip = reader.patternFirstTrip(p)
            for (k in 0 until reader.patternTripCount(p)) {
                val t = firstTrip + k
                if (dayIndex < 0 || dayIndex >= reader.dayCount) continue
                if (!reader.serviceActive(reader.tripService(t), dayIndex)) continue
                count++
                val dep0 = reader.tripDeparture0(t)
                if (dep0 < first) first = dep0
                if (dep0 > last) last = dep0
                val dep = dayStart + dep0
                if (dep >= ctx.nowEpoch && dep < nextDep) {
                    nextDep = dep
                    nextHeadsign = reader.patternDestination(p)
                }
            }
        }
        if (count == 0) return "la linea ${hit.shortName} oggi non ha corse"
        fun hm(sec: Int) = "%02d:%02d".format((sec / 3600) % 24, (sec % 3600) / 60)
        return ToolText.build {
            line("linea", hit.shortName)
            line("destinazione", hit.headsign)
            line("corse oggi", count)
            line("prima", hm(first))
            line("ultima", hm(last))
            if (nextDep != Long.MAX_VALUE) {
                line("prossima partenza", "${Times.hhmm(nextDep)} verso $nextHeadsign")
            } else {
                line("prossima partenza", "nessuna: per oggi ha finito")
            }
        }
    }
}

/** Dove sono adesso i mezzi di una linea. */
class LiveBusesTool : AiTool {
    override val name = "dove_sono_i_bus"
    override val group = ToolGroup.LIVE
    override val description =
        "Dove si trovano adesso i mezzi di una linea, con il ritardo e la prossima fermata. " +
            "I dati arrivano dal feed della Regione e si rinnovano ogni paio di minuti."
    override val parameters = Schema.obj(
        mapOf("linea" to Schema.str("numero o nome della linea")),
        required = listOf("linea"),
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val q = args.str("linea") ?: return "errore: manca la linea"
        val hit = ctx.transit.findRoutes(q, 1).firstOrNull()
            ?: return "non trovo una linea che si chiami \"$q\""
        val buses = ctx.transit.vehiclesOfRoute(hit.routeIndex)
        if (buses.isEmpty()) {
            // Mai dire "cancellata": l'assenza dal feed non e' prova
            // dell'assenza del bus, la copertura AVL non e' uniforme.
            return "nessun mezzo della linea ${hit.shortName} risulta in viaggio adesso: " +
                "puo' voler dire che non ce ne sono, o che non stanno trasmettendo"
        }
        return ToolText.build {
            line("linea", hit.shortName)
            for (b in buses.take(6)) {
                val d = Resolve.distanceLabel(ctx, b.lat, b.lon)
                line(
                    "verso ${b.headsign}${if (d != null) ", a $d da te" else ""}" +
                        (b.nextStopName?.let { ", prossima fermata $it" } ?: "") +
                        ", ${Times.delayLabel(b.delaySeconds)}" +
                        (if (b.fixAgeSeconds > 180) " (posizione di ${b.fixAgeSeconds / 60} min fa)" else ""),
                )
            }
        }
    }
}

/** Gli avvisi di servizio. */
class AlertsTool : AiTool {
    override val name = "avvisi"
    override val group = ToolGroup.LIVE
    override val description =
        "Gli avvisi di servizio pubblicati dall'azienda: deviazioni, scioperi, lavori."
    override val parameters = Schema.obj(emptyMap())

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val alerts = ctx.transit.alerts()
        if (alerts.isEmpty()) return "nessun avviso di servizio in corso"
        return ToolText.build { for (a in alerts.take(6)) line("- $a") }
    }
}

/** Come si va da un posto a un altro. */
class JourneyTool : AiTool {
    override val name = "come_arrivo"
    override val group = ToolGroup.JOURNEY
    override val description =
        "Calcola come andare da un posto a un altro con i mezzi: orari, linee, cambi e " +
            "durata. Mostra anche le soluzioni sulla mappa."
    override val parameters = Schema.obj(
        mapOf(
            "a" to Schema.place,
            "da" to Schema.str("da dove si parte; vuoto per dove si trova adesso"),
            "parti_alle" to Schema.str("ora di partenza, formato 8:30; vuoto per adesso"),
            "arriva_entro" to Schema.str("ora entro cui arrivare, formato 8:30"),
        ),
        required = listOf("a"),
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val toText = args.str("a") ?: return "errore: manca la destinazione"
        val to = Resolve.target(ctx, toText)
            ?: return "non trovo un posto che si chiami \"$toText\""
        val fromArg = args.str("da")
        val from = Resolve.target(ctx, fromArg)
            ?: return "non trovo il punto di partenza \"${fromArg ?: ""}\""

        val departAt = Resolve.timeToday(ctx, args.str("parti_alle"))
        val arriveBy = Resolve.timeToday(ctx, args.str("arriva_entro"))

        val journeys = ctx.transit.plan(
            from.point.lat, from.point.lon,
            to.point.lat, to.point.lon,
            departAt, arriveBy,
        )
        if (journeys.isEmpty()) {
            return "nessun collegamento con i mezzi da ${from.point.name} a ${to.point.name}" +
                (if (arriveBy != null || departAt != null) " a quell'ora" else " adesso")
        }

        // Mostrare non chiede conferma: e' un gesto reversibile.
        ctx.actions.perform(
            AssistantAction.ShowJourneys(
                from = if (fromArg == null) null else from.point,
                to = to.point,
                departAtEpoch = departAt,
                arriveByEpoch = arriveBy,
            ),
        )

        return ToolText.build {
            line("da", from.point.name)
            line("a", to.point.name)
            for (j in journeys.take(3)) {
                val rides = j.legs.filterIsInstance<Raptor.Leg.Ride>()
                val lines = rides.joinToString(" poi ") { r ->
                    ctx.transit.reader?.let { rd ->
                        rd.routeShortName(r.route).ifEmpty { rd.routeLongName(r.route) }
                    } ?: "?"
                }
                val cambi = when (j.transfers) {
                    0 -> "diretto"
                    1 -> "1 cambio"
                    else -> "${j.transfers} cambi"
                }
                line(
                    "${Times.hhmm(j.departure.epochSecond)} → ${Times.hhmm(j.arrival.epochSecond)} " +
                        "(${j.durationSeconds / 60} min, $cambi" +
                        (if (lines.isNotEmpty()) ", $lines" else ", a piedi") + ")",
                )
            }
        }
    }
}
