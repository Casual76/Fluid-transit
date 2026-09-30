package dev.antigravity.fluidtransit.ai.tools

import dev.antigravity.fluidtransit.ai.tools.Args.int
import dev.antigravity.fluidtransit.ai.tools.Args.str
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.DepartureText
import dev.antigravity.fluidtransit.routing.Raptor
import dev.antigravity.fluidtransit.routing.ServiceDays
import dev.antigravity.fluidtransit.routing.Times
import dev.antigravity.fluidtransit.routing.WhenText
import dev.antigravity.fluidtransit.routing.Words
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
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

    /**
     * L'indice della fermata di cui parla il modello: quella nominata, o la piu' vicina a dove si
     * trova. E' la prima riga di meta' degli strumenti degli orari.
     */
    /** Da dire quando la ricerca per nome non c'e': vedi [TransitBridge.searchAvailable]. */
    const val SEARCH_UNAVAILABLE =
        "errore: la ricerca per nome non e' disponibile adesso (l'indice di fermate e linee " +
            "non si e' preparato), quindi non so se esiste; si puo' cercare dalla posizione"

    /**
     * "Non trovo", ma solo quando abbiamo davvero cercato.
     *
     * Senza l'indice ogni ricerca torna vuota, e il modello riferiva che la
     * fermata non esiste. Tutti gli strumenti passano da qui per dirlo.
     */
    fun notFound(ctx: ToolContext, message: String): String =
        if (ctx.transit.searchAvailable) message else SEARCH_UNAVAILABLE

    /** Come [notFound], per [stopIndex]: senza un nome la ricerca non c'entra. */
    fun stopNotFound(ctx: ToolContext, query: String?, message: String): String =
        if (query == null) message else notFound(ctx, message)

    fun stopIndex(ctx: ToolContext, query: String?): Int? {
        val reader = ctx.transit.reader ?: return null
        if (query != null) return ctx.transit.findStops(query, 1).firstOrNull()?.stopIndex
        val ref = ctx.reference ?: return null
        return reader.stopsNear(ref.first, ref.second, 700.0).firstOrNull()
    }

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

    /**
     * "8:30", "08:30", "20.15", "8" -> ore e minuti, o null.
     *
     * Tutto il testo deve essere un'ora. Prima "8:30 di sera" dava le 08:00
     * (i minuti non capiti diventavano zero in silenzio) e il viaggio veniva
     * calcolato su un'ora che nessuno aveva detto.
     */
    fun clockOf(text: String?): LocalTime? {
        val m = CLOCK.matchEntire(text?.trim().orEmpty()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (h !in 0..23 || min !in 0..59) return null
        return LocalTime.of(h, min)
    }

    private val CLOCK = Regex("""(\d{1,2})(?:[:.h](\d{1,2}))?(?::\d{2})?""")

    /** "8:30", "08:30", "20.15" -> l'epoch di oggi (o domani se e' gia' passata). */
    fun timeToday(ctx: ToolContext, text: String?): Long? = timeOn(ctx, null, text)

    /**
     * L'epoch di un'ora in un giorno.
     *
     * Con [date] nullo e' "oggi, o domani se l'ora e' gia' passata da piu' di
     * cinque minuti": e' quello che si intende dicendo "alle 8" senza altro.
     * Con un giorno detto e' quel giorno e basta: "domani alle 8:30" alle 07:10
     * si calcolava sulle 08:30 di OGGI, e la risposta non diceva il giorno
     * perche' per oggi non si dice.
     */
    fun timeOn(ctx: ToolContext, date: LocalDate?, text: String?): Long? {
        val clock = clockOf(text) ?: return null
        if (date != null) return date.atTime(clock).atZone(ctx.zone).toEpochSecond()
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ctx.nowMillis), ctx.zone)
        var at = now.with(clock)
        if (at.isBefore(now.minusMinutes(5))) at = at.plusDays(1)
        return at.toEpochSecond()
    }

    /** Il giorno di oggi nel fuso del contesto. */
    fun today(ctx: ToolContext): LocalDate =
        Instant.ofEpochMilli(ctx.nowMillis).atZone(ctx.zone).toLocalDate()

    /**
     * "oggi", "domani", "dopodomani", "sabato", "2026-10-05" -> la data; null
     * se il testo non e' un giorno. Senza testo e' oggi.
     *
     * Un nome di giorno della settimana e' la prima volta che cade da oggi
     * compreso: "sabato" detto di sabato e' oggi.
     */
    fun parseDay(today: LocalDate, raw: String?): LocalDate? {
        val t = raw?.lowercase()?.trim()?.replace('ì', 'i') ?: return today
        if (t.isEmpty()) return today
        val weekday = WEEKDAYS[t.removeSuffix("'")]
        return when {
            t == "oggi" -> today
            t == "domani" -> today.plusDays(1)
            t == "dopodomani" -> today.plusDays(2)
            weekday != null -> today.plusDays(((weekday - today.dayOfWeek.value + 7) % 7).toLong())
            else -> runCatching { LocalDate.parse(t) }.getOrNull()
        }
    }

    private val WEEKDAYS = mapOf(
        "lunedi" to 1, "martedi" to 2, "mercoledi" to 3, "giovedi" to 4,
        "venerdi" to 5, "sabato" to 6, "domenica" to 7,
    )

    /**
     * Un giorno fuori dal bundle non e' un giorno senza corse: e' un buco dei
     * nostri dati, e si dice. Null se il giorno e' coperto.
     */
    fun coverageError(reader: BundleReader, date: LocalDate, today: LocalDate): String? {
        val dayIndex = ChronoUnit.DAYS.between(reader.feedStart, date)
        if (dayIndex >= 0 && dayIndex < reader.dayCount) return null
        val ultimo = reader.feedStart.plusDays(reader.dayCount - 1L)
        return "errore: gli orari scaricati coprono dal ${Times.dateLabel(reader.feedStart, today, relative = false)} " +
            "al ${Times.dateLabel(ultimo, today, relative = false)}, per quel giorno non so cosa passa"
    }

    /**
     * "qui", "questo posto", "la tua posizione": il modello scrive queste
     * parole dove lo schema dice "vuoto per dove si trova adesso". Sono la
     * posizione, non un nome da cercare (cercato, "qui" risponde "non trovo").
     */
    fun isHere(text: String?): Boolean {
        val t = text?.lowercase()?.trim()?.trimEnd('.', '!', '?', ' ') ?: return true
        return t.isEmpty() || t in HERE_WORDS
    }

    private val HERE_WORDS = setOf(
        "qui", "qua", "qui dove sono", "dove sono", "questo posto", "questo luogo",
        "la mia posizione", "la tua posizione", "posizione attuale", "la posizione attuale",
        "dove mi trovo", "dove sono adesso", "qui adesso",
    )

    fun distanceLabel(ctx: ToolContext, lat: Double, lon: Double): String? {
        val ref = ctx.reference ?: return null
        return Words.distance(BundleReader.haversine(ref.first, ref.second, lat, lon))
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
                line(Resolve.notFound(ctx, "nessun risultato per \"$q\""))
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
            "che mancano. Dice anche se il minuto viene dal bus, e' una nostra stima o e' l'orario di tabella."
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
            reader.stopsNear(ref.first, ref.second, 600.0).firstOrNull()
                ?: return "nessuna fermata entro seicento metri"
        } else {
            ctx.transit.findStops(query, 1).firstOrNull()?.stopIndex
                ?: return Resolve.notFound(ctx, "non trovo una fermata che si chiami \"$query\"")
        }

        // Lo stesso tabellone delle schermate, non un calcolo parallelo:
        // altrimenti chiedere "quando passa il 6" e guardare la scheda della
        // stessa fermata poteva dare due minuti diversi.
        ctx.transit.ensureLive(vehicles = false)
        val board = ctx.transit.board(stopIndex, wanted, 3 * 3600)
            ?: return "errore: gli orari non sono ancora scaricati"
        if (board.rows.isEmpty()) {
            // "Non passa piu' niente" e' un'affermazione sul mondo: con gli
            // orari scaduti il guasto e' nostro, e il modello la riferirebbe a
            // chi domanda se c'e' ancora un bus come un fatto. Le parole sono
            // quelle del tabellone della fermata.
            if (board.outsideValidity) {
                val scaduti = DepartureText.empty(DepartureText.Trouble.ORARI_SCADUTI)
                return "${scaduti.title}. ${scaduti.detail}"
            }
            return "da ${reader.stopName(stopIndex)} non passa piu' niente nelle prossime tre ore"
        }

        return ToolText.build {
            line("fermata", board.stopName.ifEmpty { reader.stopName(stopIndex) })
            for (d in board.rows) {
                val phrase = DepartureText.phrase(d, board.computedAtEpoch)
                line(
                    "${d.line} verso ${d.destination}: " +
                        "${phrase.headline} (${Times.hhmm(d.effectiveEpoch)}, ${phrase.support})",
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
            ?: return Resolve.notFound(ctx, "non trovo una linea che si chiami \"$q\"")
        val now = Instant.ofEpochMilli(ctx.nowMillis)
        val today = now.atZone(ctx.zone).toLocalDate()

        // Ieri e oggi, non solo oggi: alle 00:30 la corsa che sta per passare
        // e' quasi sempre una "24:40" del giorno di servizio di ieri. Con la
        // sola data di oggi lo strumento rispondeva "prossima partenza 05:10"
        // e "ultima 00:40 di notte" — cioe' quella di domani — e il modello
        // riferiva che l'ultimo bus era fra dieci minuti, o che non ce n'erano.
        val trips = ServiceDays.tripsOfRoute(
            reader, hit.routeIndex, ServiceDays.at(reader, now, ctx.zone),
        )

        var first = Int.MAX_VALUE
        var last = Int.MIN_VALUE
        var count = 0
        var nextDep = Long.MAX_VALUE
        var nextHeadsign = ""
        for (t in trips) {
            if (t.day.isToday) {
                count++
                if (t.departureSeconds < first) first = t.departureSeconds
                if (t.departureSeconds > last) last = t.departureSeconds
            }
            val dep = t.departureEpoch
            if (dep >= ctx.nowEpoch && dep < nextDep) {
                nextDep = dep
                nextHeadsign = reader.patternDestination(t.patternIndex)
            }
        }
        val tail = ServiceDays.tail(trips, ctx.nowEpoch)
        if (count == 0 && tail == null) {
            // "Nessuna corsa" non e' una sola cosa: con gli orari scaduti non
            // e' la linea a non averne, siamo noi a non sapere quali siano. Il
            // modello riferirebbe "oggi non c'e' servizio" a chi domanda se
            // puo' contare su un bus.
            if (!ServiceDays.covers(reader, today)) {
                val scaduti = DepartureText.empty(DepartureText.Trouble.ORARI_SCADUTI)
                return "${scaduti.title}. ${scaduti.detail}"
            }
            return "la linea ${hit.shortName} oggi non ha corse"
        }
        // Non un modulo 24 in casa: l'ultima corsa di una linea urbana parte
        // spesso dopo la mezzanotte, e "01:13" senza altro fa credere che sia
        // passata stamattina.
        fun hm(sec: Int) = Times.serviceTime(sec)
        return ToolText.build {
            line("linea", hit.shortName)
            line("destinazione", hit.headsign)
            if (tail != null) line(Times.serviceTail(tail, ctx.zone))
            if (count > 0) {
                // Prima e ultima sono del giorno di servizio di oggi, e il
                // "di notte" dice che l'ultima e' dopo la mezzanotte.
                line("corse oggi", count)
                line("prima (oggi)", hm(first))
                line("ultima (oggi)", hm(last))
            } else if (!ServiceDays.covers(reader, today)) {
                // Rimane la coda di ieri, ma per oggi gli orari non ci sono:
                // non e' "nessuna corsa".
                val scaduti = DepartureText.empty(DepartureText.Trouble.ORARI_SCADUTI)
                line("oggi", "${scaduti.title}. ${scaduti.detail}")
            } else {
                line("corse oggi", "nessuna")
            }
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
            ?: return Resolve.notFound(ctx, "non trovo una linea che si chiami \"$q\"")
        ctx.transit.ensureLive(vehicles = true)
        // Null non e' "nessun mezzo": e' "non lo so". Con l'app appena
        // svegliata, o il feed fermo da ore, una lista vuota si riferiva come
        // "la linea non ha bus" e le posizioni vecchie come "sono qui".
        val buses = ctx.transit.vehiclesOfRoute(hit.routeIndex)
            ?: return "non so dove sono i mezzi della linea ${hit.shortName} adesso: " +
                "i dati dal vivo non sono disponibili o sono troppo vecchi (vedi stato_rete)"
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
                        (
                            if (b.fixAgeSeconds > 180) {
                                " (posizione di ${Words.age(b.fixAgeSeconds)} fa)"
                            } else {
                                ""
                            }
                            ),
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
            "giorno" to Schema.str(
                "il giorno dell'ora: oggi, domani, dopodomani, un giorno della settimana " +
                    "(sabato) o una data aaaa-mm-gg; vuoto = oggi",
            ),
        ),
        required = listOf("a"),
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val toText = args.str("a") ?: return "errore: manca la destinazione"
        val to = Resolve.target(ctx, toText)
            ?: return Resolve.notFound(ctx, "non trovo un posto che si chiami \"$toText\"")
        val fromArg = args.str("da")
        val from = Resolve.target(ctx, fromArg)
            ?: return if (fromArg == null) {
                // Senza un nome la partenza e' la posizione: qui la ricerca
                // non c'entra.
                "non trovo il punto di partenza \"\""
            } else {
                Resolve.notFound(ctx, "non trovo il punto di partenza \"$fromArg\"")
            }

        // Il giorno: senza, l'ora e' "oggi o la prossima volta che viene". Con un
        // giorno detto e' quel giorno, e se e' un altro da oggi la risposta lo
        // scrive (alle 07:10 "domani alle 8:30" si calcolava sulle 08:30 di
        // oggi, senza una parola che lo dicesse).
        val today = Resolve.today(ctx)
        val dayText = args.str("giorno")
        val day = if (dayText == null) null else Resolve.parseDay(today, dayText)
            ?: return "errore: giorno non capito (oggi, domani, un giorno della settimana, o aaaa-mm-gg)"
        val partiText = args.str("parti_alle")
        val arrivaText = args.str("arriva_entro")
        // Un'ora detta e non capita ("8 e mezza", "domani mattina") non e'
        // "parti adesso": si dice, come fa quando_uscire.
        val departAt = Resolve.timeOn(ctx, day, partiText)
        if (partiText != null && departAt == null) {
            return "errore: ora di partenza non capita (es. 08:30)"
        }
        val arriveBy = Resolve.timeOn(ctx, day, arrivaText)
        if (arrivaText != null && arriveBy == null) {
            return "errore: ora di arrivo non capita (es. 08:30)"
        }
        if (day != null && day != today && departAt == null && arriveBy == null) {
            return "errore: per un altro giorno serve anche l'ora (parti_alle o arriva_entro)"
        }
        if (day != null) {
            ctx.transit.reader?.let { r ->
                Resolve.coverageError(r, day, today)?.let { return it }
            }
        }

        // Il tempo reale conta solo per un viaggio di oggi.
        if (day == null || day == today) ctx.transit.ensureLive(vehicles = false)
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
            if (day != null && day != today) {
                line("per il giorno", Times.dateLabel(day, today))
            }
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
                // Il giorno compare quando non e' oggi: "parti_alle 7:30" detto alle 23:00
                // e' domattina (`Resolve.timeToday` sposta l'ora passata a domani), e
                // "07:30 → 08:10" senza altro si leggeva come un viaggio di stasera.
                line(
                    "${WhenText.clock(j.departure.epochSecond, ctx.nowEpoch, ctx.zone)} → " +
                        "${WhenText.clock(j.arrival.epochSecond, ctx.nowEpoch, ctx.zone)} " +
                        // Contata fra i due orari appena scritti: "10:26 →
                        // 10:51 (24 min)" e' una sottrazione che non torna,
                        // e letta ad alta voce e' peggio che scritta.
                        "(${Times.durationBetween(
                            j.departure.epochSecond, j.arrival.epochSecond,
                        )}, $cambi" +
                        (if (lines.isNotEmpty()) ", $lines" else ", a piedi") + ")",
                )
            }
        }
    }
}
