package dev.antigravity.fluidtransit.bundler

import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Ftb
import java.io.File
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
 * E' il golden gate del job notturno.
 *
 * Rispetto allo spike, il formato 2 rende la riconciliazione onesta: le
 * fermate si ritrovano per hash dello `stop_id`, non piu' per nome+codice.
 * I casi di calendario e ora legale non sono piu' qui: sono test JUnit in
 * `:core-routing`, che girano a ogni build e non solo a ogni bundle.
 *
 * `gradlew :bundler:verify --args="<dir gtfs> <bundle.ftb>"`
 */
fun main(args: Array<String>) {
    val gtfsDir = File(args.getOrElse(0) { "work/gtfs" })
    val bundle = File(args.getOrElse(1) { "work/toscana.ftb" })
    require(bundle.isFile) { "bundle non trovato: ${bundle.absolutePath}" }

    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        println("  ${if (ok) "ok  " else "FAIL"}  $name${if (detail.isEmpty()) "" else "  -> $detail"}")
        if (!ok) failures++
    }

    BundleReader(bundle, verifyCrcOnFirstUse = false).use { r ->
        val openNanos = measureNanoTime { r.stopCount }

        println("== Apertura ==")
        println("  buildId        ${java.lang.Long.toHexString(r.buildId)}")
        println("  validita'      ${r.feedStart} .. ${r.feedEnd} (${r.dayCount} giorni)")
        println("  fermate        ${r.stopCount}  linee ${r.routeCount}  pattern ${r.patternCount}")
        println("  corse          ${r.tripCount}  profili ${r.profileCount}  servizi ${r.serviceCount}")
        println("  prima lettura in ${openNanos / 1000} us")
        println()

        println("== Integrita' ==")
        val badSections: List<String>
        val crcNanos = measureNanoTime { badSections = r.verifyChecksums() }
        check("CRC32 di tutte le sezioni", badSections.isEmpty(), badSections.joinToString())
        println("       (esaustiva in ${crcNanos / 1_000_000} ms; il lettore la fa per sezione, alla prima lettura)")

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

        // --- identita': fermate, linee, corse ------------------------------
        println("== Identita' dal feed ==")

        // Ogni stop_id del CSV con coordinate valide deve risolvere via hash
        // alla fermata con le stesse coordinate. E' il contratto dei preferiti.
        var stopsChecked = 0
        var stopsBad = 0
        CsvCursor.open(File(gtfsDir, "stops.txt")) { csv ->
            val cId = csv.requireColumn("stop_id")
            val cLat = csv.requireColumn("stop_lat")
            val cLon = csv.requireColumn("stop_lon")
            while (csv.nextRow()) {
                val lat = csv.double(cLat)
                if (lat.isNaN()) continue
                val idx = r.findStopByIdHash(csv.hashField(cId))
                if (idx < 0) continue // fermata non selezionata dal filtro area
                stopsChecked++
                if (Math.abs(r.stopLat(idx) - lat) > 1e-5) stopsBad++
            }
        }
        check("$stopsChecked stop_id risolti alle coordinate giuste", stopsBad == 0, "$stopsBad sbagliati")
        check("tutte le fermate del bundle risolte", stopsChecked == r.stopCount, "$stopsChecked vs ${r.stopCount}")

        var routesResolved = 0
        CsvCursor.open(File(gtfsDir, "routes.txt")) { csv ->
            val cId = csv.requireColumn("route_id")
            while (csv.nextRow()) if (r.findRouteByIdHash(csv.hashField(cId)) >= 0) routesResolved++
        }
        check("route_id risolti quanto le linee del bundle", routesResolved == r.routeCount, "$routesResolved vs ${r.routeCount}")

        // Ogni trip_id del CSV che sta nel bundle deve risolvere a un indice
        // distinto, e gli indici distinti devono coprire tutte le corse.
        val tripIdOf = HashMap<Long, String>(r.tripCount * 2)
        val serviceOf = HashMap<Long, String>(r.tripCount * 2)
        val resolvedIdx = java.util.BitSet(r.tripCount)
        var unresolvedCsvTrips = 0
        CsvCursor.open(File(gtfsDir, "trips.txt")) { csv ->
            val cTrip = csv.requireColumn("trip_id")
            val cService = csv.requireColumn("service_id")
            while (csv.nextRow()) {
                val h = csv.hashField(cTrip)
                val idx = r.findTripByIdHash(h)
                if (idx < 0) {
                    unresolvedCsvTrips++
                    continue
                }
                resolvedIdx.set(idx)
                tripIdOf[h] = csv.string(cTrip)
                serviceOf[h] = csv.string(cService)
            }
        }
        check(
            "ogni corsa del bundle raggiunta da un trip_id del CSV",
            resolvedIdx.cardinality() == r.tripCount,
            "${resolvedIdx.cardinality()} vs ${r.tripCount}",
        )
        println("       (trip_id del CSV fuori dal bundle: $unresolvedCsvTrips - filtro area o corse scartate)")
        check("un trip_id inventato non risolve", r.findTripByTripId("questo-trip-id-non-esiste-42") == -1)

        // Il matcher secondario: per un campione di corse, (route, direzione,
        // dep0) deve trovare una corsa con la stessa terna. Non per forza la
        // stessa - due corse gemelle sono indistinguibili anche per GTFS-RT.
        val rnd = Random(20260830)
        var matcherOk = 0
        val sample = List(300) { rnd.nextInt(r.tripCount) }
        for (t in sample) {
            val route = r.patternRoute(r.tripPattern(t))
            val found = r.findTripByRouteAndDeparture(r.routeIdHash(route), r.tripDirection(t), r.tripDeparture0(t))
            if (found >= 0 &&
                r.patternRoute(r.tripPattern(found)) == route &&
                r.tripDirection(found) == r.tripDirection(t) &&
                r.tripDeparture0(found) == r.tripDeparture0(t)
            ) matcherOk++
        }
        check("matcher secondario su ${sample.size} corse campione", matcherOk == sample.size, "$matcherOk/${sample.size}")
        println()

        // --- transfer -------------------------------------------------------
        println("== Trasferimenti a piedi ==")
        var asym = 0
        var selfEdges = 0
        var badSeconds = 0
        var edges = 0
        for (s in 0 until r.stopCount) {
            for (tr in r.transfersFrom(s)) {
                edges++
                if (tr.targetStop == s) selfEdges++
                if (tr.seconds < BundleBuilder.MIN_TRANSFER_S) badSeconds++
                if (r.transfersFrom(tr.targetStop).none { it.targetStop == s }) asym++
            }
        }
        check("nessun arco verso se stessi", selfEdges == 0, "$selfEdges")
        check("simmetria: ogni arco esiste nei due versi", asym == 0, "$asym a senso unico")
        check("nessun tempo sotto il minimo", badSeconds == 0, "$badSeconds")
        // La garanzia del piano: chi ha una vicina entro il raggio conserva
        // almeno la piu' vicina.
        var isolated = 0
        for (i in 0 until 500) {
            val s = rnd.nextInt(r.stopCount)
            if (r.transfersFrom(s).isNotEmpty()) continue
            val near = r.stopsNear(r.stopLat(s), r.stopLon(s), BundleBuilder.WALK_RADIUS_M)
            if (near.any { it != s }) isolated++
        }
        check("500 campioni: nessuna fermata isolata con vicine nel raggio", isolated == 0, "$isolated isolate")
        println("       ($edges archi totali)")
        println()

        // --- confronto con il CSV -------------------------------------------
        println("== Prossimi passaggi, contro il CSV ==")
        val serviceIdToIndex = HashMap<String, Int>()
        for ((h, sid) in serviceOf) {
            val idx = r.findTripByIdHash(h)
            if (idx >= 0) serviceIdToIndex.putIfAbsent(sid, r.tripService(idx))
        }

        val busiest = (0 until r.stopCount)
            .sortedByDescending { r.patternsAtStop(it).size }
            .take(36)
            .shuffled(rnd)
            .take(12)
        val stopHashOf = busiest.associateWith { r.stopIdHash(it) }
        val expected = departuresFromCsv(gtfsDir, r, stopHashOf)

        val probeDate = r.feedStart.plusDays((r.dayCount / 2).toLong())
        val probeTimes = listOf(
            LocalTime.of(7, 30), LocalTime.of(12, 0), LocalTime.of(18, 45),
            LocalTime.of(23, 50), LocalTime.of(0, 30),
        )
        var comparisons = 0
        var mismatches = 0
        val queryNanos = ArrayList<Long>()
        for (stopIdx in busiest) {
            for (time in probeTimes) {
                val now = ZonedDateTime.of(probeDate, time, Ftb.ROME).toInstant()
                val fromBundle: List<BundleReader.Departure>
                queryNanos.add(measureNanoTime {
                    fromBundle = r.nextDepartures(stopIdx, now, limit = 5, horizonSeconds = 3 * 3600)
                })
                val fromCsv = expectedDepartures(
                    expected, stopHashOf.getValue(stopIdx), r, serviceIdToIndex, now, 5, 3 * 3600,
                )
                comparisons++
                val a = fromBundle.map { it.instant.epochSecond }
                if (a != fromCsv) {
                    mismatches++
                    if (mismatches <= 3) {
                        println("       divergenza a ${r.stopName(stopIdx)} alle $time:")
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

        // --- corse oltre la mezzanotte --------------------------------------
        println("== Corse oltre le 24:00 ==")
        var lateTrips = 0
        var latest = 0
        var latestTrip = -1
        var latestDep = -1
        for (t in 0 until r.tripCount) {
            val p = r.tripPattern(t)
            val end = r.tripDeparture0(t) + r.profileOffset(r.tripProfile(t), r.patternStopCount(p) - 1)
            if (end >= 24 * 3600) lateTrips++
            latest = maxOf(latest, end)
            if (r.tripDeparture0(t) > latestDep) {
                latestDep = r.tripDeparture0(t)
                latestTrip = t
            }
        }
        println("  corse che finiscono dopo le 24:00: $lateTrips")
        check("esistono corse oltre la mezzanotte (altrimenti il caso non e' coperto)", lateTrips > 0)
        check("l'header dichiara il tetto reale del giorno di servizio", r.maxTripEndSeconds == latest, "${r.maxTripEndSeconds} vs $latest")

        // La corsa piu' notturna, interrogata un minuto prima che passi: chi
        // guarda l'app alle sei del mattino deve vedere la corsa del giorno
        // di servizio di ieri, non un elenco vuoto.
        val latePattern = r.tripPattern(latestTrip)
        val lateStop = r.patternStop(latePattern, 0)
        var lateDate: java.time.LocalDate? = null
        for (d in 0 until r.dayCount) {
            if (r.serviceActive(r.tripService(latestTrip), d)) {
                lateDate = r.feedStart.plusDays(d.toLong())
                break
            }
        }
        if (lateDate == null) {
            check("la corsa piu' notturna ha almeno un giorno attivo", false)
        } else {
            val askedAt = Ftb.serviceDayStart(lateDate).plusSeconds(latestDep.toLong()).minusSeconds(60)
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

        // --- ricerca spaziale -----------------------------------------------
        println("== Griglia spaziale ==")
        var gridOk = true
        for (i in 0 until 40) {
            val s = rnd.nextInt(r.stopCount)
            val lat = r.stopLat(s)
            val lon = r.stopLon(s)
            val near = r.stopsNear(lat, lon, 500.0).toSet()
            val brute = (0 until r.stopCount).filter {
                BundleReader.haversine(lat, lon, r.stopLat(it), r.stopLon(it)) <= 500.0
            }.toSet()
            if (near != brute) gridOk = false
        }
        check("40 ricerche a 500 m identiche alla scansione completa", gridOk)
        println()
    }

    println(if (failures == 0) "TUTTI I CONTROLLI PASSATI" else "$failures CONTROLLI FALLITI")
    if (failures > 0) kotlin.system.exitProcess(1)
}

private class CsvDeparture(val serviceId: String, val seconds: Int, val isLast: Boolean)

/**
 * Rilegge `stop_times.txt` e raccoglie, per le sole fermate campionate, tutte
 * le partenze con il proprio `service_id`. E' la strada lunga: nessun
 * pattern, nessun profilo, nessuna deduplica. La chiave di riconciliazione
 * e' l'hash dello `stop_id`, che nel formato 2 e' nel bundle.
 */
private fun departuresFromCsv(
    gtfsDir: File,
    r: BundleReader,
    wantedStops: Map<Int, Long>, // stopIdx -> stopIdHash
): Map<Long, List<CsvDeparture>> {
    val wantedHashes = wantedStops.values.toHashSet()

    val tripService = HashMap<String, String>()
    CsvCursor.open(File(gtfsDir, "trips.txt")) { csv ->
        val cTrip = csv.requireColumn("trip_id")
        val cService = csv.requireColumn("service_id")
        while (csv.nextRow()) tripService[csv.string(cTrip)] = csv.string(cService)
    }

    val out = HashMap<Long, MutableList<CsvDeparture>>()
    CsvCursor.open(File(gtfsDir, "stop_times.txt")) { csv ->
        val cTrip = csv.requireColumn("trip_id")
        val cStop = csv.requireColumn("stop_id")
        val cSeq = csv.requireColumn("stop_sequence")
        val cDep = csv.requireColumn("departure_time")
        val cArr = csv.requireColumn("arrival_time")

        var trip = ""
        var keep = false
        val hits = ArrayList<Triple<Long, Int, Int>>() // hash, seq, secondi
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
                // Le corse del bundle: il CSV ne puo' contenere di piu'.
                keep = r.findTripByIdHash(Ftb.hash64(t)) >= 0
            }
            if (!keep) continue
            val seq = csv.int(cSeq, 0)
            maxSeq = maxOf(maxSeq, seq)
            val stopHash = csv.hashField(cStop)
            if (stopHash !in wantedHashes) continue
            val dep = csv.gtfsTime(cDep)
            val secs = if (dep >= 0) dep else csv.gtfsTime(cArr)
            if (secs >= 0) hits.add(Triple(stopHash, seq, secs))
        }
        flush()
    }
    return out
}

/** La stessa domanda di nextDepartures, risposta partendo dai CSV. */
private fun expectedDepartures(
    fromCsv: Map<Long, List<CsvDeparture>>,
    stopHash: Long,
    r: BundleReader,
    serviceIdToIndex: Map<String, Int>,
    now: java.time.Instant,
    limit: Int,
    horizonSeconds: Int,
): List<Long> {
    val all = fromCsv[stopHash] ?: return emptyList()
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
            val idx = serviceIdToIndex[d.serviceId] ?: continue
            if (!r.serviceActive(idx, dayIndex)) continue
            result.add(dayStart.epochSecond + d.seconds)
        }
    }
    result.sort()
    return if (result.size > limit) result.subList(0, limit) else result
}
