package dev.antigravity.fluidtransit.ftb

import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import kotlin.system.measureNanoTime

/**
 * Riapre il `.ftb` e lo mette contro il GTFS di partenza.
 *
 * Un lettore che non sbaglia mai da solo non dimostra niente: il valore del
 * controllo sta nel ricalcolare la stessa risposta per una strada
 * completamente diversa - leggendo i CSV - e pretendere che coincidano.
 *
 * `gradlew verify --args="<dir gtfs> <bundle.ftb> [PROVINCIA]"`
 */
fun main(args: Array<String>) {
    val gtfsDir = File(args.getOrElse(0) { "work/gtfs" })
    val bundle = File(args.getOrElse(1) { "work/toscana-SI.ftb" })
    require(bundle.isFile) { "bundle non trovato: ${bundle.absolutePath}" }

    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        println("  ${if (ok) "ok  " else "FAIL"}  $name${if (detail.isEmpty()) "" else "  -> $detail"}")
        if (!ok) failures++
    }

    val openNanos: Long
    FtbReader(bundle).use { r ->
        openNanos = measureNanoTime { r.stopCount }

        println("== Apertura ==")
        println("  buildId        ${java.lang.Long.toHexString(r.buildId)}")
        println("  validita'      ${r.feedStart} .. ${r.feedEnd} (${r.dayCount} giorni)")
        println("  fermate        ${r.stopCount}")
        println("  linee          ${r.routeCount}")
        println("  pattern        ${r.patternCount}")
        println("  corse          ${r.tripCount}")
        println("  profili        ${r.profileCount}")
        println("  servizi        ${r.serviceCount}")
        println("  prima lettura in ${openNanos / 1000} us")
        println()

        println("== Integrita' ==")
        val badSections: List<String>
        val crcNanos = measureNanoTime { badSections = r.verifyChecksums() }
        check("CRC32 di tutte le sezioni", badSections.isEmpty(), badSections.joinToString())
        println("       (verifica esaustiva in ${crcNanos / 1_000_000} ms; in produzione va fatta per sezione, alla prima lettura)")

        // Ogni indice deve puntare dentro il proprio dominio. Un indice fuori
        // scala non si manifesta come errore ma come fermata sbagliata.
        var badRefs = 0
        for (p in 0 until r.patternCount) {
            if (r.patternRoute(p) !in 0 until r.routeCount) badRefs++
            val n = r.patternStopCount(p)
            if (n < 2) badRefs++
            for (i in 0 until n) if (r.patternStop(p, i) !in 0 until r.stopCount) badRefs++
        }
        for (t in 0 until r.tripCount) {
            if (r.tripPattern(t) !in 0 until r.patternCount) badRefs++
            if (r.tripProfile(t) !in 0 until r.profileCount) badRefs++
            if (r.tripService(t) !in 0 until r.serviceCount) badRefs++
        }
        check("integrita' referenziale di pattern e corse", badRefs == 0, "$badRefs riferimenti fuori scala")

        // Le corse di un pattern devono essere contigue e ordinate: e' il
        // presupposto della ricerca binaria in nextDepartures.
        var outOfOrder = 0
        for (p in 0 until r.patternCount) {
            val first = r.patternFirstTrip(p)
            for (k in 1 until r.patternTripCount(p)) {
                if (r.tripDeparture0(first + k) < r.tripDeparture0(first + k - 1)) outOfOrder++
                if (r.tripPattern(first + k) != p) outOfOrder++
            }
        }
        check("corse contigue e ordinate per partenza dentro ogni pattern", outOfOrder == 0, "$outOfOrder anomalie")
        println()

        // --- il giorno di servizio ---------------------------------------
        println("== Giorno di servizio e ora legale ==")
        // La finestra di questo feed non contiene un cambio d'ora (30/08 -
        // 19/09), quindi il caso va costruito a mano. Non e' ipotetico: cade
        // dentro ogni feed pubblicato a fine ottobre, ogni anno.
        //
        // Il punto meno intuitivo, e il primo modo di sbagliarlo: il giorno
        // di servizio lungo 25 ore e' quello *precedente* al cambio d'ora.
        // L'ora ripetuta cade nella notte fra il 24 e il 25, che appartiene
        // ancora al giorno di servizio del 24 come sua estensione 24:00-27:00.
        // Il 25 stesso e' un giorno di servizio normale di 24 ore.
        val dstEndDate = lastSundayOfOctober(2026)   // 2026-10-25, si torna all'ora solare
        val dstStartDate = lastSundayOfMarch(2026)   // 2026-03-29, si passa all'ora legale
        val longDay = dstEndDate.minusDays(1)
        val shortDay = dstStartDate.minusDays(1)

        check(
            "il giorno di servizio $longDay dura 25 ore (contiene l'ora ripetuta)",
            Ftb.serviceDayLength(longDay) == 25 * 3600L,
            "${Ftb.serviceDayLength(longDay) / 3600.0} h",
        )
        check(
            "il giorno di servizio $shortDay dura 23 ore (contiene l'ora saltata)",
            Ftb.serviceDayLength(shortDay) == 23 * 3600L,
            "${Ftb.serviceDayLength(shortDay) / 3600.0} h",
        )
        check(
            "il giorno del cambio stesso ($dstEndDate) e' un giorno normale di 24 ore",
            Ftb.serviceDayLength(dstEndDate) == 24 * 3600L,
            "${Ftb.serviceDayLength(dstEndDate) / 3600.0} h",
        )
        check(
            "un giorno qualunque dura 24 ore",
            Ftb.serviceDayLength(LocalDate.of(2026, 9, 2)) == 24 * 3600L,
        )

        // La proprieta' che conta per chi guarda l'orario: l'ancoraggio a
        // mezzogiorno tiene corretti gli orari diurni anche nei giorni di
        // cambio. E' il motivo per cui GTFS non ancora a mezzanotte.
        for (day in listOf(longDay, dstEndDate, shortDay, dstStartDate)) {
            val at6 = Ftb.serviceDayStart(day).plusSeconds(6 * 3600).atZone(Ftb.ROME)
            check(
                "una corsa alle 06:00 del $day parte davvero alle 06:00 locali",
                at6.toLocalTime() == LocalTime.of(6, 0) && at6.toLocalDate() == day,
                at6.toString(),
            )
        }

        // L'ora ripetuta, vista dal giorno di servizio lungo: 26:00 e 27:00
        // sono due istanti distinti che l'orologio locale chiama entrambi
        // 02:00. Una app che convertisse in orario locale e poi confrontasse
        // stringhe qui fonderebbe due corse in una.
        val firstTwo = Ftb.serviceDayStart(longDay).plusSeconds(26 * 3600L).atZone(Ftb.ROME)
        val secondTwo = Ftb.serviceDayStart(longDay).plusSeconds(27 * 3600L).atZone(Ftb.ROME)
        check(
            "26:00 e 27:00 del $longDay sono due 02:00 locali distinti",
            firstTwo.toLocalTime() == LocalTime.of(2, 0) &&
                secondTwo.toLocalTime() == LocalTime.of(2, 0) &&
                firstTwo.offset != secondTwo.offset,
            "${firstTwo.offset} poi ${secondTwo.offset}",
        )

        // E il caso opposto: nel giorno corto le 02:30 non esistono.
        val skipped = Ftb.serviceDayStart(shortDay).plusSeconds(26 * 3600L + 1800).atZone(Ftb.ROME)
        check(
            "26:30 del $shortDay salta l'ora inesistente e cade alle 03:30",
            skipped.toLocalTime() == LocalTime.of(3, 30),
            skipped.toString(),
        )
        println()

        // --- aggancio al realtime -----------------------------------------
        println("== Indice dei trip_id ==")
        val rnd = Random(20260830)
        var resolved = 0
        val sampleTrips = List(500) { rnd.nextInt(r.tripCount) }
        val lookupNanos = measureNanoTime {
            for (t in sampleTrips) if (r.findTripByTripId(r.tripId(t)) == t) resolved++
        }
        check("500 trip_id reali risolti al loro indice", resolved == sampleTrips.size, "$resolved/500")
        check(
            "un trip_id inventato non risolve",
            r.findTripByTripId("questo-trip-id-non-esiste-42") == -1,
        )
        println("       (${lookupNanos / sampleTrips.size} ns per lookup)")
        println()

        // --- confronto con il CSV -----------------------------------------
        println("== Prossimi passaggi, contro il CSV ==")
        buildServiceMapping(gtfsDir, r)
        val sampledStops = pickBusiestStops(r, 12, rnd)
        val expected = departuresFromCsv(gtfsDir, r, sampledStops.keys)

        val probeDate = r.feedStart.plusDays((r.dayCount / 2).toLong())
        val probeTimes = listOf(
            LocalTime.of(7, 30), LocalTime.of(12, 0), LocalTime.of(18, 45),
            LocalTime.of(23, 50), LocalTime.of(0, 30),
        )
        var comparisons = 0
        var mismatches = 0
        val queryNanos = ArrayList<Long>()
        for ((stopIdx, stopId) in sampledStops) {
            for (time in probeTimes) {
                val now = ZonedDateTime.of(probeDate, time, Ftb.ROME).toInstant()
                val fromBundle: List<FtbReader.Departure>
                queryNanos.add(measureNanoTime {
                    fromBundle = r.nextDepartures(stopIdx, now, limit = 5, horizonSeconds = 3 * 3600)
                })
                val fromCsv = expectedDepartures(expected, stopId, r, now, 5, 3 * 3600)
                comparisons++
                val a = fromBundle.map { it.instant.epochSecond }
                if (a != fromCsv) {
                    mismatches++
                    if (mismatches <= 3) {
                        println("       divergenza a ${r.stopName(stopIdx)} ($stopId) alle $time:")
                        println("         bundle: $a")
                        println("         csv   : $fromCsv")
                    }
                }
            }
        }
        check("$comparisons query identiche a quelle ricalcolate dal CSV", mismatches == 0, "$mismatches divergenze")
        queryNanos.sort()
        println("       mediana ${queryNanos[queryNanos.size / 2] / 1000} us, massimo ${queryNanos.last() / 1000} us")
        println()

        // --- corse oltre la mezzanotte ------------------------------------
        println("== Corse oltre le 24:00 ==")
        var lateTrips = 0
        var latest = 0
        for (t in 0 until r.tripCount) {
            val p = r.tripPattern(t)
            val end = r.tripDeparture0(t) + r.profileOffset(r.tripProfile(t), r.patternStopCount(p) - 1)
            if (end >= 24 * 3600) lateTrips++
            latest = maxOf(latest, end)
        }
        println("  corse che finiscono dopo le 24:00: $lateTrips")
        println("  ultimo istante del giorno di servizio: ${latest / 3600}:${String.format("%02d", (latest % 3600) / 60)}")
        println("  tetto dichiarato nell'header: ${r.maxTripEndSeconds / 3600}:${String.format("%02d", (r.maxTripEndSeconds % 3600) / 60)}")
        check(
            "esistono corse oltre la mezzanotte (altrimenti il caso non e' coperto)",
            lateTrips > 0,
        )
        check(
            "l'header dichiara il tetto reale del giorno di servizio",
            r.maxTripEndSeconds == latest,
            "${r.maxTripEndSeconds} vs $latest",
        )

        // La corsa che parte piu' tardi di tutte, interrogata un minuto prima
        // che passi. E' il caso del rischio numero 2 del piano: chi guarda
        // l'app alle sei del mattino deve vedere la corsa del giorno di
        // servizio di *ieri*, non un elenco vuoto.
        var latestTrip = -1
        var latestDep = -1
        for (t in 0 until r.tripCount) {
            val dep = r.tripDeparture0(t)
            if (dep > latestDep) {
                latestDep = dep
                latestTrip = t
            }
        }
        val latePattern = r.tripPattern(latestTrip)
        val lateStop = r.patternStop(latePattern, 0)
        var lateDate: LocalDate? = null
        for (d in 0 until r.dayCount) {
            if (r.serviceActive(r.tripService(latestTrip), d)) {
                lateDate = r.feedStart.plusDays(d.toLong())
                break
            }
        }
        if (lateDate == null) {
            check("la corsa piu' notturna ha almeno un giorno attivo", false)
        } else {
            val departsAt = Ftb.serviceDayStart(lateDate).plusSeconds(latestDep.toLong())
            val askedAt = departsAt.minusSeconds(60)
            val local = askedAt.atZone(Ftb.ROME)
            val found = r.nextDepartures(lateStop, askedAt, limit = 20, horizonSeconds = 1800)
            check(
                "la corsa delle ${latestDep / 3600}:${String.format("%02d", (latestDep % 3600) / 60)} " +
                    "del $lateDate si trova interrogando alle ${local.toLocalTime()} del ${local.toLocalDate()}",
                found.any { it.tripIndex == latestTrip },
                "${found.size} partenze nella mezz'ora successiva",
            )
        }
        println()

        // --- ricerca spaziale ---------------------------------------------
        println("== Griglia spaziale ==")
        var gridOk = true
        var checkedRadii = 0
        for (i in 0 until 40) {
            val s = rnd.nextInt(r.stopCount)
            val lat = r.stopLat(s)
            val lon = r.stopLon(s)
            val near = r.stopsNear(lat, lon, 500.0).toSet()
            // Confronto contro la scansione completa: la griglia deve trovare
            // esattamente le stesse fermate, non "quasi".
            val brute = (0 until r.stopCount).filter {
                FtbReader.haversine(lat, lon, r.stopLat(it), r.stopLon(it)) <= 500.0
            }.toSet()
            if (near != brute) gridOk = false
            checkedRadii++
        }
        check("$checkedRadii ricerche a 500 m identiche alla scansione completa", gridOk)
        println()
    }

    println(if (failures == 0) "TUTTI I CONTROLLI PASSATI" else "$failures CONTROLLI FALLITI")
    if (failures > 0) kotlin.system.exitProcess(1)
}

private fun lastSundayOfOctober(year: Int): LocalDate {
    var d = LocalDate.of(year, 10, 31)
    while (d.dayOfWeek != java.time.DayOfWeek.SUNDAY) d = d.minusDays(1)
    return d
}

private fun lastSundayOfMarch(year: Int): LocalDate {
    var d = LocalDate.of(year, 3, 31)
    while (d.dayOfWeek != java.time.DayOfWeek.SUNDAY) d = d.minusDays(1)
    return d
}

/** Le fermate con piu' pattern: sono quelle dove un errore si vede. */
private fun pickBusiestStops(r: FtbReader, count: Int, rnd: Random): Map<Int, String> {
    val ranked = (0 until r.stopCount)
        .sortedByDescending { r.patternsAtStop(it).size }
        .take(count * 3)
    val chosen = ranked.shuffled(rnd).take(count)
    // Il `stop_id` originale non e' nel bundle: si ritrova per coordinate e
    // nome quando si rilegge il CSV.
    return chosen.associateWith { "${r.stopName(it)}|${r.stopCode(it)}" }
}

private class CsvDeparture(val serviceId: String, val seconds: Int, val isLast: Boolean)

/**
 * Rilegge `stop_times.txt` e raccoglie, per le sole fermate campionate, tutte
 * le partenze con il proprio `service_id`. E' la strada lunga: nessun
 * pattern, nessun profilo, nessuna deduplica.
 */
private fun departuresFromCsv(
    gtfsDir: File,
    r: FtbReader,
    stops: Set<Int>,
): Map<String, List<CsvDeparture>> {
    // Chiave di riconciliazione fermata: nome + codice, come in pickBusiestStops.
    val wanted = HashMap<String, String>() // stop_id GTFS -> chiave
    CsvCursor.open(File(gtfsDir, "stops.txt")) { csv ->
        val cId = csv.requireColumn("stop_id")
        val cName = csv.requireColumn("stop_name")
        val cCode = csv.column("stop_code")
        val keys = stops.map { "${r.stopName(it)}|${r.stopCode(it)}" }.toHashSet()
        while (csv.nextRow()) {
            val key = "${csv.string(cName)}|${csv.string(cCode)}"
            if (key in keys) wanted[csv.string(cId)] = key
        }
    }

    val tripService = HashMap<String, String>()
    CsvCursor.open(File(gtfsDir, "trips.txt")) { csv ->
        val cTrip = csv.requireColumn("trip_id")
        val cService = csv.requireColumn("service_id")
        while (csv.nextRow()) tripService[csv.string(cTrip)] = csv.string(cService)
    }

    // Le corse presenti nel bundle: il CSV ne contiene molte di piu' (tutta
    // la Toscana) e confrontare insiemi diversi non direbbe niente.
    val inBundle = HashSet<String>(r.tripCount * 2)
    for (t in 0 until r.tripCount) inBundle.add(r.tripId(t))

    val out = HashMap<String, MutableList<CsvDeparture>>()
    // Per sapere se una fermata e' l'ultima della corsa serve la lunghezza
    // della corsa: si accumula per corsa e si decide alla fine.
    CsvCursor.open(File(gtfsDir, "stop_times.txt")) { csv ->
        val cTrip = csv.requireColumn("trip_id")
        val cStop = csv.requireColumn("stop_id")
        val cSeq = csv.requireColumn("stop_sequence")
        val cDep = csv.requireColumn("departure_time")
        val cArr = csv.requireColumn("arrival_time")

        var trip = ""
        var keep = false
        val hits = ArrayList<Triple<String, Int, Int>>() // chiave, seq, secondi
        var maxSeq = -1

        fun flush() {
            if (!keep) return
            for ((key, seq, secs) in hits) {
                out.getOrPut(key) { ArrayList() }
                    .add(CsvDeparture(tripService[trip] ?: "", secs, seq == maxSeq))
            }
        }

        while (csv.nextRow()) {
            val t = csv.string(cTrip)
            if (t != trip) {
                flush()
                hits.clear()
                maxSeq = -1
                trip = t
                keep = t in inBundle
            }
            if (!keep) continue
            val seq = csv.int(cSeq, 0)
            maxSeq = maxOf(maxSeq, seq)
            val key = wanted[csv.string(cStop)] ?: continue
            val dep = csv.gtfsTime(cDep)
            val secs = if (dep >= 0) dep else csv.gtfsTime(cArr)
            if (secs >= 0) hits.add(Triple(key, seq, secs))
        }
        flush()
    }
    return out
}

/** La stessa domanda di nextDepartures, risposta partendo dai CSV. */
private fun expectedDepartures(
    fromCsv: Map<String, List<CsvDeparture>>,
    stopKey: String,
    r: FtbReader,
    now: java.time.Instant,
    limit: Int,
    horizonSeconds: Int,
): List<Long> {
    val all = fromCsv[stopKey] ?: return emptyList()
    val today = now.atZone(Ftb.ROME).toLocalDate()
    val result = ArrayList<Long>()
    for (offsetDays in -1..1) {
        val date = today.plusDays(offsetDays.toLong())
        val dayIndex = ChronoUnit.DAYS.between(r.feedStart, date).toInt()
        if (dayIndex < 0 || dayIndex >= r.dayCount) continue
        val dayStart = Ftb.serviceDayStart(date)
        val target = now.epochSecond - dayStart.epochSecond
        for (d in all) {
            if (d.isLast) continue
            if (d.seconds < target || d.seconds > target + horizonSeconds) continue
            if (!serviceActiveByName(r, d.serviceId, dayIndex)) continue
            result.add(dayStart.epochSecond + d.seconds)
        }
    }
    result.sort()
    return if (result.size > limit) result.subList(0, limit) else result
}

/**
 * I `service_id` non sono nel bundle come stringhe: gli indici sono
 * rimappati. Si ricostruisce la corrispondenza usando le corse, che sono
 * nell'uno e nell'altro.
 */
private val serviceIdToIndex = HashMap<String, Int>()

private fun serviceActiveByName(r: FtbReader, serviceId: String, dayIndex: Int): Boolean {
    val idx = serviceIdToIndex[serviceId] ?: return false
    return r.serviceActive(idx, dayIndex)
}

/** Popolata da [buildServiceMapping] prima dei confronti. */
fun buildServiceMapping(gtfsDir: File, r: FtbReader) {
    val tripService = HashMap<String, String>()
    CsvCursor.open(File(gtfsDir, "trips.txt")) { csv ->
        val cTrip = csv.requireColumn("trip_id")
        val cService = csv.requireColumn("service_id")
        while (csv.nextRow()) tripService[csv.string(cTrip)] = csv.string(cService)
    }
    for (t in 0 until r.tripCount) {
        val sid = tripService[r.tripId(t)] ?: continue
        serviceIdToIndex.putIfAbsent(sid, r.tripService(t))
    }
}


