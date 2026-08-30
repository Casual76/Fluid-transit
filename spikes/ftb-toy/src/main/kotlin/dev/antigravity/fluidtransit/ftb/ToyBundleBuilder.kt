package dev.antigravity.fluidtransit.ftb

import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Costruisce un `.ftb` giocattolo da un feed GTFS, filtrando su una provincia.
 *
 * Non e' il bundler di produzione: quello girera' in CI su DuckDB e sull'intera
 * Toscana. Questo serve a rispondere alle domande che il piano da' per
 * assodate ma che nessuno ha ancora misurato su questo feed:
 *
 *  - quante corse collassano davvero in pattern? (atteso 8.000-15.000 pattern
 *    per 213.584 corse in tutta la regione)
 *  - la deduplica dei profili di percorrenza rende davvero un fattore 4-5x?
 *  - `arrival == departure` e' davvero "quasi ovunque", cosi' che DWELL possa
 *    restare sparsa?
 *  - il container si riapre, i CRC tornano, e le query danno lo stesso
 *    risultato del CSV di partenza?
 *
 * Se una di queste risposte fosse molto diversa dall'attesa, cambierebbe il
 * dimensionamento del bundle e quindi il download iniziale dell'app.
 */
class ToyBundleBuilder(
    private val gtfsDir: File,
    private val areaFilter: String?,
) {

    // --- risultati misurati, riportati a fine build -----------------------
    class Stats {
        var stopsTotal = 0
        var stopsKept = 0
        var routesKept = 0
        var tripsTotal = 0
        var tripsKept = 0
        var stopTimeRowsTotal = 0L
        var stopTimeRowsKept = 0L
        var patterns = 0
        var patternStopEntries = 0
        var profiles = 0
        var profileValues = 0
        var dwellEntries = 0L
        var maxTripSpanSeconds = 0
        /** dep0 + ultimo scostamento: quanto tardi arriva la corsa piu' notturna. */
        var maxTripEndSeconds = 0
        var servicesKept = 0
        var hashCollisions = 0
        var tripIdBytes = 0L
        val sectionBytes = LinkedHashMap<String, Int>()
        var buildMillis = 0L
    }

    val stats = Stats()

    // --- chiavi per la deduplica -----------------------------------------

    /** IntArray usabile come chiave di HashMap, con hash memorizzato. */
    private class IntKey(val a: IntArray) {
        private val h = a.contentHashCode()
        override fun hashCode() = h
        override fun equals(other: Any?) = other is IntKey && a.contentEquals(other.a)
    }

    /**
     * Profilo di percorrenza: gli scostamenti dalla partenza della corsa piu'
     * le soste. Due corse dello stesso pattern in fasce orarie diverse hanno
     * quasi sempre gli stessi tempi relativi e differiscono solo per l'orario
     * di partenza: e' questa la ragione per cui la deduplica rende.
     */
    private class ProfileKey(val offsets: IntArray, val dwell: IntArray) {
        private val h = 31 * offsets.contentHashCode() + dwell.contentHashCode()
        override fun hashCode() = h
        override fun equals(other: Any?) =
            other is ProfileKey && offsets.contentEquals(other.offsets) && dwell.contentEquals(other.dwell)
    }

    fun build(out: File) {
        val t0 = System.currentTimeMillis()

        val feed = readFeedInfo()
        val stops = readStops()
        val routes = readRoutes()
        val services = readServices(feed.start, feed.end)
        val trips = readTrips(routes.idToIndex, services.idToIndex)

        val collected = collectPatterns(stops, trips)

        writeBundle(out, feed, stops, routes, services, trips, collected)

        stats.buildMillis = System.currentTimeMillis() - t0
    }

    // ------------------------------------------------------------------ feed

    private class Feed(val start: LocalDate, val end: LocalDate, val dayCount: Int)

    private fun readFeedInfo(): Feed {
        val file = File(gtfsDir, "feed_info.txt")
        var start: LocalDate? = null
        var end: LocalDate? = null
        if (file.exists()) {
            CsvCursor.open(file) { csv ->
                val cs = csv.column("feed_start_date")
                val ce = csv.column("feed_end_date")
                if (csv.nextRow()) {
                    if (!csv.isEmpty(cs)) start = Ftb.parseGtfsDate(csv.int(cs))
                    if (!csv.isEmpty(ce)) end = Ftb.parseGtfsDate(csv.int(ce))
                }
            }
        }
        // Il feed dichiara la propria finestra; se non lo facesse, la si
        // ricaverebbe dai calendari. Meglio derivarla che assumerla: una
        // finestra sbagliata sposta ogni bitmap di servizio.
        val s = start ?: error("feed_info.txt senza feed_start_date: finestra di validita' ignota")
        val e = end ?: error("feed_info.txt senza feed_end_date: finestra di validita' ignota")
        val days = ChronoUnit.DAYS.between(s, e).toInt() + 1
        require(days in 1..400) { "finestra di validita' implausibile: $days giorni" }
        return Feed(s, e, days)
    }

    // ----------------------------------------------------------------- stops

    class Stops {
        val idToIndex = HashMap<String, Int>()
        val ids = ArrayList<String>()
        val lat = ArrayList<Int>()
        val lon = ArrayList<Int>()
        val name = ArrayList<String>()
        val code = ArrayList<String>()
        val parent = ArrayList<String>()
        val area = ArrayList<String>()
        val size: Int get() = ids.size
    }

    private fun readStops(): Stops {
        val s = Stops()
        CsvCursor.open(File(gtfsDir, "stops.txt")) { csv ->
            val cId = csv.requireColumn("stop_id")
            val cName = csv.requireColumn("stop_name")
            val cLat = csv.requireColumn("stop_lat")
            val cLon = csv.requireColumn("stop_lon")
            val cCode = csv.column("stop_code")
            val cParent = csv.column("parent_station")
            val cArea = csv.column("area_id")
            while (csv.nextRow()) {
                val lat = csv.double(cLat)
                val lon = csv.double(cLon)
                if (lat.isNaN() || lon.isNaN()) continue
                val id = csv.string(cId)
                s.idToIndex[id] = s.ids.size
                s.ids.add(id)
                s.lat.add(Math.round(lat * Ftb.COORD_SCALE).toInt())
                s.lon.add(Math.round(lon * Ftb.COORD_SCALE).toInt())
                s.name.add(csv.string(cName))
                s.code.add(csv.string(cCode))
                s.parent.add(csv.string(cParent))
                s.area.add(csv.string(cArea))
            }
        }
        stats.stopsTotal = s.size
        return s
    }

    // ---------------------------------------------------------------- routes

    class Routes {
        val idToIndex = HashMap<String, Int>()
        val shortName = ArrayList<String>()
        val longName = ArrayList<String>()
        val agency = ArrayList<String>()
        val type = ArrayList<Int>()
        val color = ArrayList<Int>()
        val size: Int get() = shortName.size
    }

    private fun readRoutes(): Routes {
        val r = Routes()
        CsvCursor.open(File(gtfsDir, "routes.txt")) { csv ->
            val cId = csv.requireColumn("route_id")
            val cShort = csv.column("route_short_name")
            val cLong = csv.column("route_long_name")
            val cAgency = csv.column("agency_id")
            val cType = csv.column("route_type")
            val cColor = csv.column("route_color")
            while (csv.nextRow()) {
                r.idToIndex[csv.string(cId)] = r.size
                r.shortName.add(csv.string(cShort))
                r.longName.add(csv.string(cLong))
                r.agency.add(csv.string(cAgency))
                r.type.add(csv.int(cType, 3))
                r.color.add(csv.string(cColor).toIntOrNull(16) ?: 0)
            }
        }
        return r
    }

    // -------------------------------------------------------------- services

    class Services(val dayCount: Int) {
        val idToIndex = HashMap<String, Int>()
        val ids = ArrayList<String>()
        /** Un bit per giorno della finestra di validita'. */
        val bitmaps = ArrayList<ByteArray>()

        fun indexOf(id: String): Int = idToIndex.getOrPut(id) {
            ids.add(id)
            bitmaps.add(ByteArray((dayCount + 7) / 8))
            ids.size - 1
        }

        fun set(index: Int, day: Int, active: Boolean) {
            if (day < 0 || day >= dayCount) return
            val b = bitmaps[index]
            val mask = (1 shl (day % 8))
            if (active) b[day / 8] = (b[day / 8].toInt() or mask).toByte()
            else b[day / 8] = (b[day / 8].toInt() and mask.inv()).toByte()
        }

        fun isActive(index: Int, day: Int): Boolean {
            if (day < 0 || day >= dayCount) return false
            return (bitmaps[index][day / 8].toInt() shr (day % 8)) and 1 == 1
        }
    }

    private fun readServices(start: LocalDate, end: LocalDate): Services {
        val dayCount = ChronoUnit.DAYS.between(start, end).toInt() + 1
        val s = Services(dayCount)

        // calendar.txt: la regola settimanale, valida in un intervallo.
        val calendar = File(gtfsDir, "calendar.txt")
        if (calendar.exists()) {
            CsvCursor.open(calendar) { csv ->
                val cId = csv.requireColumn("service_id")
                val cDays = intArrayOf(
                    csv.requireColumn("monday"), csv.requireColumn("tuesday"),
                    csv.requireColumn("wednesday"), csv.requireColumn("thursday"),
                    csv.requireColumn("friday"), csv.requireColumn("saturday"),
                    csv.requireColumn("sunday"),
                )
                val cStart = csv.requireColumn("start_date")
                val cEnd = csv.requireColumn("end_date")
                while (csv.nextRow()) {
                    val idx = s.indexOf(csv.string(cId))
                    val from = Ftb.parseGtfsDate(csv.int(cStart))
                    val to = Ftb.parseGtfsDate(csv.int(cEnd))
                    val weekly = BooleanArray(7) { csv.int(cDays[it], 0) == 1 }
                    var d = if (from.isBefore(start)) start else from
                    val last = if (to.isAfter(end)) end else to
                    while (!d.isAfter(last)) {
                        // DayOfWeek.value: lunedi' = 1. L'array parte da lunedi'.
                        if (weekly[d.dayOfWeek.value - 1]) {
                            s.set(idx, ChronoUnit.DAYS.between(start, d).toInt(), true)
                        }
                        d = d.plusDays(1)
                    }
                }
            }
        }

        // calendar_dates.txt: le eccezioni, che vincono sempre sulla regola.
        val dates = File(gtfsDir, "calendar_dates.txt")
        if (dates.exists()) {
            CsvCursor.open(dates) { csv ->
                val cId = csv.requireColumn("service_id")
                val cDate = csv.requireColumn("date")
                val cType = csv.requireColumn("exception_type")
                while (csv.nextRow()) {
                    val idx = s.indexOf(csv.string(cId))
                    val date = Ftb.parseGtfsDate(csv.int(cDate))
                    if (date.isBefore(start) || date.isAfter(end)) continue
                    val day = ChronoUnit.DAYS.between(start, date).toInt()
                    s.set(idx, day, csv.int(cType) == 1)
                }
            }
        }
        return s
    }

    // ------------------------------------------------------------------ trips

    class Trips {
        val idToIndex = HashMap<String, Int>()
        val ids = ArrayList<String>()
        val route = ArrayList<Int>()
        val service = ArrayList<Int>()
        val direction = ArrayList<Int>()
        val headsign = ArrayList<String>()
        val size: Int get() = ids.size
    }

    private fun readTrips(routeIndex: Map<String, Int>, serviceIndex: Map<String, Int>): Trips {
        val t = Trips()
        CsvCursor.open(File(gtfsDir, "trips.txt")) { csv ->
            val cTrip = csv.requireColumn("trip_id")
            val cRoute = csv.requireColumn("route_id")
            val cService = csv.requireColumn("service_id")
            val cDir = csv.column("direction_id")
            val cHead = csv.column("trip_headsign")
            while (csv.nextRow()) {
                val routeId = csv.string(cRoute)
                val serviceId = csv.string(cService)
                val r = routeIndex[routeId] ?: continue
                val sv = serviceIndex[serviceId] ?: continue
                t.idToIndex[csv.string(cTrip)] = t.size
                t.ids.add(csv.string(cTrip))
                t.route.add(r)
                t.service.add(sv)
                t.direction.add(csv.int(cDir, 0).coerceIn(0, 1))
                t.headsign.add(csv.string(cHead))
            }
        }
        stats.tripsTotal = t.size
        return t
    }

    // ------------------------------------------------------- pattern collapse

    class Collected {
        /** Per pattern: sequenza di indici di fermata (indici globali di Stops). */
        val patternStops = ArrayList<IntArray>()
        val patternRoute = ArrayList<Int>()
        val patternDirection = ArrayList<Int>()

        /** Per profilo: scostamenti in secondi dalla partenza, e soste sparse. */
        val profileOffsets = ArrayList<IntArray>()
        val profileDwell = ArrayList<IntArray>()

        /** Per corsa selezionata. */
        val tripIndex = ArrayList<Int>()
        val tripPattern = ArrayList<Int>()
        val tripProfile = ArrayList<Int>()
        val tripDep0 = ArrayList<Int>()

        val usedStops = HashSet<Int>()
        val usedServices = HashSet<Int>()
        val usedRoutes = HashSet<Int>()
    }

    /**
     * Unico passaggio su `stop_times.txt`.
     *
     * Il file e' raggruppato per corsa: si accumulano le righe della corsa in
     * corso e si decide al cambio. Cosi' la memoria resta quella di una corsa
     * sola - una trentina di righe - invece dei 282 MB del file. Se il feed
     * smettesse di essere raggruppato, il controllo sotto lo direbbe subito
     * invece di produrre in silenzio un bundle con corse spezzate.
     */
    private fun collectPatterns(stops: Stops, trips: Trips): Collected {
        val c = Collected()
        val patternIndex = HashMap<IntKey, Int>()
        val profileIndex = HashMap<ProfileKey, Int>()
        val seenTrips = HashSet<String>()

        var currentTrip = ""
        var currentTripIdx = -1
        val seq = ArrayList<Int>()      // stop_sequence
        val stopIdx = ArrayList<Int>()
        val arrival = ArrayList<Int>()
        val departure = ArrayList<Int>()

        fun flush() {
            if (currentTripIdx < 0 || stopIdx.isEmpty()) return
            stats.stopTimeRowsTotal += stopIdx.size

            // Le righe vanno ordinate per stop_sequence: il feed le ha gia'
            // in ordine, ma un bundle costruito su un'assunzione non
            // verificata e' un bug che si manifesta a Firenze alle 8 del
            // mattino, non in CI.
            val order = (0 until stopIdx.size).sortedBy { seq[it] }

            val keep = areaFilter == null || order.any { stops.area[stopIdx[it]] == areaFilter }
            if (!keep) return

            val n = order.size
            if (n < 2) return // una corsa con una sola fermata non e' percorribile

            val stopsArr = IntArray(n) { stopIdx[order[it]] }
            val depArr = IntArray(n) { departure[order[it]] }
            val arrArr = IntArray(n) { arrival[order[it]] }

            // La prima partenza e' l'origine dei tempi della corsa; tutto il
            // resto e' uno scostamento u16 da questa.
            val dep0 = depArr[0]
            if (dep0 < 0) return
            val offsets = IntArray(n) { depArr[it] - dep0 }
            if (offsets.any { it < 0 || it > 65535 }) return
            stats.maxTripSpanSeconds = maxOf(stats.maxTripSpanSeconds, offsets[n - 1])
            stats.maxTripEndSeconds = maxOf(stats.maxTripEndSeconds, dep0 + offsets[n - 1])

            // Le soste: solo dove arrivo e partenza differiscono davvero.
            val dwell = ArrayList<Int>()
            for (i in 0 until n) {
                val d = depArr[i] - arrArr[i]
                if (arrArr[i] >= 0 && d > 0) {
                    dwell.add(i)
                    dwell.add(minOf(d, 65535))
                }
            }
            val dwellArr = dwell.toIntArray()

            val routeIdx = trips.route[currentTripIdx]
            val dir = trips.direction[currentTripIdx]
            // La chiave del pattern e' linea + verso + sequenza esatta delle
            // fermate: e' la definizione che rende i pattern riusabili come
            // rappresentazione route-oriented per RAPTOR.
            val patternKey = IntKey(intArrayOf(routeIdx, dir, *stopsArr))
            val patternIdx = patternIndex.getOrPut(patternKey) {
                c.patternStops.add(stopsArr)
                c.patternRoute.add(routeIdx)
                c.patternDirection.add(dir)
                c.patternStops.size - 1
            }

            val profileIdx = profileIndex.getOrPut(ProfileKey(offsets, dwellArr)) {
                c.profileOffsets.add(offsets)
                c.profileDwell.add(dwellArr)
                c.profileOffsets.size - 1
            }

            c.tripIndex.add(currentTripIdx)
            c.tripPattern.add(patternIdx)
            c.tripProfile.add(profileIdx)
            c.tripDep0.add(dep0)
            c.usedStops.addAll(stopsArr.toList())
            c.usedServices.add(trips.service[currentTripIdx])
            c.usedRoutes.add(routeIdx)
            stats.stopTimeRowsKept += n
        }

        CsvCursor.open(File(gtfsDir, "stop_times.txt")) { csv ->
            val cTrip = csv.requireColumn("trip_id")
            val cStop = csv.requireColumn("stop_id")
            val cSeq = csv.requireColumn("stop_sequence")
            val cArr = csv.requireColumn("arrival_time")
            val cDep = csv.requireColumn("departure_time")
            var tripBytes = ByteArray(0)

            while (csv.nextRow()) {
                if (!csv.fieldEquals(cTrip, tripBytes)) {
                    flush()
                    seq.clear(); stopIdx.clear(); arrival.clear(); departure.clear()
                    tripBytes = csv.bytes(cTrip)
                    currentTrip = csv.string(cTrip)
                    require(seenTrips.add(currentTrip)) {
                        "stop_times.txt non e' raggruppato per corsa: '$currentTrip' ricompare"
                    }
                    currentTripIdx = trips.idToIndex[currentTrip] ?: -1
                }
                if (currentTripIdx < 0) continue
                val si = stops.idToIndex[csv.string(cStop)] ?: continue
                seq.add(csv.int(cSeq, 0))
                stopIdx.add(si)
                val dep = csv.gtfsTime(cDep)
                val arr = csv.gtfsTime(cArr, dep)
                arrival.add(arr)
                departure.add(if (dep >= 0) dep else arr)
            }
            flush()
        }

        stats.tripsKept = c.tripIndex.size
        stats.patterns = c.patternStops.size
        stats.profiles = c.profileOffsets.size
        stats.patternStopEntries = c.patternStops.sumOf { it.size }
        stats.profileValues = c.profileOffsets.sumOf { it.size }
        stats.dwellEntries = c.profileDwell.sumOf { (it.size / 2).toLong() }
        stats.stopsKept = c.usedStops.size
        stats.routesKept = c.usedRoutes.size
        stats.servicesKept = c.usedServices.size
        return c
    }

    // ---------------------------------------------------------------- writing

    private fun writeBundle(
        out: File,
        feed: Feed,
        stops: Stops,
        routes: Routes,
        services: Services,
        trips: Trips,
        c: Collected,
    ) {
        val strings = StringTable()
        val writer = FtbWriter()

        // --- STOPS, riordinate lungo la curva di Hilbert -------------------
        val kept = c.usedStops.toIntArray()
        // Ordine deterministico prima dell'ordinamento spaziale: due build
        // devono produrre lo stesso file, e l'iterazione di un HashSet no.
        kept.sort()
        val minLat = kept.minOf { stops.lat[it] }
        val minLon = kept.minOf { stops.lon[it] }
        val maxLat = kept.maxOf { stops.lat[it] }
        val maxLon = kept.maxOf { stops.lon[it] }
        val spanLat = maxOf(1, maxLat - minLat)
        val spanLon = maxOf(1, maxLon - minLon)
        val hilbert = LongArray(kept.size) { i ->
            val s = kept[i]
            val x = (((stops.lon[s] - minLon).toLong() * 65535) / spanLon).toInt()
            val y = (((stops.lat[s] - minLat).toLong() * 65535) / spanLat).toInt()
            Ftb.hilbertD(x, y)
        }
        val order = (kept.indices).sortedWith(compareBy({ hilbert[it] }, { kept[it] }))
        val newIndexOf = HashMap<Int, Int>(kept.size * 2)
        order.forEachIndexed { newIdx, oldPos -> newIndexOf[kept[oldPos]] = newIdx }

        val stopsBuf = ByteBuf(kept.size * Ftb.STOP_RECORD + 8)
        stopsBuf.i32(kept.size)
        stopsBuf.i32(0)
        for (oldPos in order) {
            val s = kept[oldPos]
            stopsBuf.i32(stops.lat[s])
            stopsBuf.i32(stops.lon[s])
            stopsBuf.i32(strings.intern(stops.name[s]))
            stopsBuf.i32(strings.intern(stops.code[s]))
            // Il parent puo' essere una fermata non selezionata: in quel caso
            // -1, mai un indice che punta fuori.
            val parentNew = stops.idToIndex[stops.parent[s]]?.let { newIndexOf[it] } ?: -1
            stopsBuf.i32(parentNew)
        }

        // --- STOP_GRID: celle da 0,01 gradi, CSR ---------------------------
        val cellOf = LongArray(kept.size)
        for (newIdx in kept.indices) {
            val s = kept[order[newIdx]]
            val latCell = Math.floorDiv(stops.lat[s], (Ftb.GRID_DEGREES * Ftb.COORD_SCALE).toInt())
            val lonCell = Math.floorDiv(stops.lon[s], (Ftb.GRID_DEGREES * Ftb.COORD_SCALE).toInt())
            cellOf[newIdx] = (latCell.toLong() shl 32) or (lonCell.toLong() and 0xffffffffL)
        }
        val byCell = kept.indices.sortedWith(compareBy({ cellOf[it] }, { it }))
        val gridBuf = ByteBuf()
        val cellKeys = ArrayList<Long>()
        val cellStart = ArrayList<Int>()
        var prev = Long.MIN_VALUE
        byCell.forEachIndexed { pos, newIdx ->
            if (cellOf[newIdx] != prev) {
                cellKeys.add(cellOf[newIdx])
                cellStart.add(pos)
                prev = cellOf[newIdx]
            }
        }
        gridBuf.i32(cellKeys.size)
        gridBuf.i32(byCell.size)
        for (k in cellKeys) gridBuf.i64(k)
        for (o in cellStart) gridBuf.i32(o)
        gridBuf.i32(byCell.size) // sentinella: fine dell'ultima cella
        for (i in byCell) gridBuf.i32(i)

        // --- ROUTES --------------------------------------------------------
        val routeRemap = HashMap<Int, Int>()
        val keptRoutes = c.usedRoutes.toIntArray().also { it.sort() }
        val routesBuf = ByteBuf(keptRoutes.size * Ftb.ROUTE_RECORD + 8)
        routesBuf.i32(keptRoutes.size)
        routesBuf.i32(0)
        keptRoutes.forEachIndexed { newIdx, r ->
            routeRemap[r] = newIdx
            routesBuf.i32(strings.intern(routes.shortName[r]))
            routesBuf.i32(strings.intern(routes.longName[r]))
            routesBuf.i32(strings.intern(routes.agency[r]))
            routesBuf.i32(routes.type[r])
            routesBuf.i32(routes.color[r])
        }

        // --- SERVICES ------------------------------------------------------
        val serviceRemap = HashMap<Int, Int>()
        val keptServices = c.usedServices.toIntArray().also { it.sort() }
        val bitmapBytes = (feed.dayCount + 7) / 8
        val servicesBuf = ByteBuf(keptServices.size * bitmapBytes + 12)
        servicesBuf.i32(keptServices.size)
        servicesBuf.i32(feed.dayCount)
        servicesBuf.i32(bitmapBytes)
        keptServices.forEachIndexed { newIdx, s ->
            serviceRemap[s] = newIdx
            servicesBuf.bytes(services.bitmaps[s])
        }

        // --- corse ordinate per (pattern, partenza) ------------------------
        // E' l'ordine che RAPTOR richiede: le corse di un pattern contigue e
        // crescenti nel tempo, cosi' che "la prima corsa utile" sia una
        // ricerca binaria dentro un intervallo.
        val tripOrder = c.tripIndex.indices.sortedWith(
            compareBy({ c.tripPattern[it] }, { c.tripDep0[it] }, { trips.ids[c.tripIndex[it]] })
        )
        val patternFirstTrip = IntArray(c.patternStops.size) { -1 }
        val patternTripCount = IntArray(c.patternStops.size)
        tripOrder.forEachIndexed { pos, t ->
            val p = c.tripPattern[t]
            if (patternFirstTrip[p] < 0) patternFirstTrip[p] = pos
            patternTripCount[p]++
        }

        // --- PATTERNS + PATTERN_STOPS --------------------------------------
        val patternStopsBuf = ByteBuf(stats.patternStopEntries * 4 + 8)
        patternStopsBuf.i32(stats.patternStopEntries)
        patternStopsBuf.i32(0)
        val patternFirstStop = IntArray(c.patternStops.size)
        var cursor = 0
        for (p in c.patternStops.indices) {
            patternFirstStop[p] = cursor
            for (s in c.patternStops[p]) {
                patternStopsBuf.i32(newIndexOf.getValue(s))
                cursor++
            }
        }
        val patternsBuf = ByteBuf(c.patternStops.size * Ftb.PATTERN_RECORD + 8)
        patternsBuf.i32(c.patternStops.size)
        patternsBuf.i32(0)
        for (p in c.patternStops.indices) {
            patternsBuf.i32(routeRemap.getValue(c.patternRoute[p]))
            patternsBuf.i32(patternFirstStop[p])
            patternsBuf.u16(c.patternStops[p].size)
            patternsBuf.u8(c.patternDirection[p])
            patternsBuf.u8(0)
            patternsBuf.i32(patternFirstTrip[p])
            patternsBuf.i32(patternTripCount[p])
        }

        // --- PROFILES + DWELL ----------------------------------------------
        val profilesBuf = ByteBuf(stats.profileValues * 2 + c.profileOffsets.size * 4 + 8)
        profilesBuf.i32(c.profileOffsets.size)
        profilesBuf.i32(stats.profileValues)
        var off = 0
        for (p in c.profileOffsets) {
            profilesBuf.i32(off)
            off += p.size
        }
        profilesBuf.i32(off)
        for (p in c.profileOffsets) for (v in p) profilesBuf.u16(v)

        val dwellBuf = ByteBuf()
        dwellBuf.i32(c.profileDwell.size)
        dwellBuf.i32(stats.dwellEntries.toInt())
        var dOff = 0
        for (d in c.profileDwell) {
            dwellBuf.i32(dOff)
            dOff += d.size / 2
        }
        dwellBuf.i32(dOff)
        for (d in c.profileDwell) {
            var i = 0
            while (i < d.size) {
                dwellBuf.u16(d[i])
                dwellBuf.u16(d[i + 1])
                i += 2
            }
        }

        // --- TRIPS ----------------------------------------------------------
        val tripsBuf = ByteBuf(tripOrder.size * Ftb.TRIP_RECORD + 8)
        tripsBuf.i32(tripOrder.size)
        tripsBuf.i32(0)
        for (t in tripOrder) {
            val gt = c.tripIndex[t]
            tripsBuf.i32(c.tripPattern[t])
            tripsBuf.u16(serviceRemap.getValue(trips.service[gt]))
            tripsBuf.u16(0)
            tripsBuf.i32(c.tripDep0[t])
            tripsBuf.i32(c.tripProfile[t])
            tripsBuf.i32(strings.intern(trips.ids[gt]))
            stats.tripIdBytes += trips.ids[gt].toByteArray(Charsets.UTF_8).size
        }

        // --- TRIP_ID_INDEX: l'aggancio al realtime --------------------------
        // Il `trip_id` piu' il giorno di servizio e' l'unica chiave condivisa
        // fra bundle, RAPTOR e feed realtime. Qui diventa un hash a 64 bit
        // ordinato: la ricerca e' binaria e non tocca la tabella stringhe.
        val hashes = LongArray(tripOrder.size)
        tripOrder.forEachIndexed { pos, t -> hashes[pos] = Ftb.hash64(trips.ids[c.tripIndex[t]]) }
        val hashOrder = hashes.indices.sortedWith(compareBy({ hashes[it] }, { it }))
        var collisions = 0
        for (i in 1 until hashOrder.size) {
            if (hashes[hashOrder[i]] == hashes[hashOrder[i - 1]]) collisions++
        }
        stats.hashCollisions = collisions
        val indexBuf = ByteBuf(tripOrder.size * 12 + 8)
        indexBuf.i32(tripOrder.size)
        indexBuf.i32(0)
        for (i in hashOrder) indexBuf.i64(hashes[i])
        for (i in hashOrder) indexBuf.i32(i)

        // --- STOP_PATTERNS: CSR fermata -> pattern che la servono -----------
        val degree = IntArray(kept.size)
        for (p in c.patternStops.indices) {
            for (s in c.patternStops[p]) degree[newIndexOf.getValue(s)]++
        }
        val spStart = IntArray(kept.size + 1)
        for (i in kept.indices) spStart[i + 1] = spStart[i] + degree[i]
        val fill = spStart.copyOf()
        val spValues = IntArray(spStart[kept.size])
        for (p in c.patternStops.indices) {
            for (s in c.patternStops[p]) {
                val ns = newIndexOf.getValue(s)
                spValues[fill[ns]++] = p
            }
        }
        val stopPatternsBuf = ByteBuf(spValues.size * 4 + kept.size * 4 + 8)
        stopPatternsBuf.i32(kept.size)
        stopPatternsBuf.i32(spValues.size)
        for (v in spStart) stopPatternsBuf.i32(v)
        for (v in spValues) stopPatternsBuf.i32(v)

        // --- STRINGS va per ultima: la riempiono tutte le altre ------------
        val stringsBuf = strings.build()

        writer.section(Ftb.S_STRINGS, stringsBuf)
        writer.section(Ftb.S_STOPS, stopsBuf)
        writer.section(Ftb.S_STOP_GRID, gridBuf)
        writer.section(Ftb.S_ROUTES, routesBuf)
        writer.section(Ftb.S_PATTERNS, patternsBuf)
        writer.section(Ftb.S_PATTERN_STOPS, patternStopsBuf)
        writer.section(Ftb.S_TRIPS, tripsBuf)
        writer.section(Ftb.S_PROFILES, profilesBuf)
        writer.section(Ftb.S_DWELL, dwellBuf)
        writer.section(Ftb.S_TRIP_ID_INDEX, indexBuf)
        writer.section(Ftb.S_STOP_PATTERNS, stopPatternsBuf)
        writer.section(Ftb.S_SERVICES, servicesBuf)

        for (id in Ftb.SECTION_NAMES.keys.sorted()) {
            val size = writer.sizeOf(id)
            if (size > 0) stats.sectionBytes[Ftb.SECTION_NAMES.getValue(id)] = size
        }

        writer.write(out, feed.start, feed.end, feed.dayCount, stats.maxTripEndSeconds)
    }
}
