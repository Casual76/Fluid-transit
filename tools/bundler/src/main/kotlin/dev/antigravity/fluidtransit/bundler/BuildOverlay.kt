package dev.antigravity.fluidtransit.bundler

import dev.antigravity.fluidtransit.routing.Ftb
import java.io.File
import kotlin.system.exitProcess

/**
 * L'overlay della rete per la mappa: da GTFS a due GeoJSONL pronte per
 * tippecanoe (`linee` e `fermate`), che il workflow impacchetta in un unico
 * PMTiles pubblicato accanto al bundle.
 *
 * `overlay <dir-gtfs> <out-dir>`
 *
 * ## I colori delle linee
 *
 * Il feed colora per categoria (390 linee blu, 341 verdi...): inutilizzabile
 * per "ogni tratta col suo colore". La richiesta vera - decisa con l'utente -
 * non e' 766 colori distinti ma che **2-3 linee sovrapposte abbiano colori
 * nettamente diversi**. E' un problema di colorazione di grafo: due linee
 * sono adiacenti se condividono almeno una fermata, e si colora con una
 * palette di 12 tinte scelte per leggibilita' sulla mappa. L'assegnazione e'
 * deterministica (stesso feed -> stessi colori) e greedy per grado
 * decrescente: dove la rete e' fitta i colori si decidono prima, con piu'
 * palette libera.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("uso: overlay <dir-gtfs> <out-dir>")
        exitProcess(2)
    }
    val gtfsDir = File(args[0])
    require(gtfsDir.isDirectory) { "non e' una cartella: $gtfsDir" }
    val outDir = File(args[1]).apply { mkdirs() }

    val t0 = System.currentTimeMillis()

    // --- routes: nome, agency, categoria -----------------------------------
    class Route(val id: String, val shortName: String, val agency: String) {
        val category: String get() = if (agency.startsWith("EX")) "e" else "u"
        var color: Int = -1 // indice nella palette
    }

    val routes = ArrayList<Route>()
    val routeIdToIdx = HashMap<String, Int>()
    CsvCursor.open(File(gtfsDir, "routes.txt")) { csv ->
        val cId = csv.requireColumn("route_id")
        val cShort = csv.column("route_short_name")
        val cAgency = csv.column("agency_id")
        while (csv.nextRow()) {
            val id = csv.string(cId)
            routeIdToIdx[id] = routes.size
            routes.add(Route(id, csv.string(cShort), csv.string(cAgency)))
        }
    }

    // --- trips: corsa -> linea, e shape -> linea ---------------------------
    val tripToRoute = HashMap<String, Int>()
    val shapeToRoute = HashMap<String, Int>()
    CsvCursor.open(File(gtfsDir, "trips.txt")) { csv ->
        val cTrip = csv.requireColumn("trip_id")
        val cRoute = csv.requireColumn("route_id")
        val cShape = csv.column("shape_id")
        while (csv.nextRow()) {
            val r = routeIdToIdx[csv.string(cRoute)] ?: continue
            tripToRoute[csv.string(cTrip)] = r
            val shape = csv.string(cShape)
            if (shape.isNotEmpty()) shapeToRoute.putIfAbsent(shape, r)
        }
    }

    // --- stops: indice e coordinate ----------------------------------------
    val stopIds = ArrayList<String>()
    val stopNames = ArrayList<String>()
    val stopLat = ArrayList<Double>()
    val stopLon = ArrayList<Double>()
    val stopIdToIdx = HashMap<String, Int>()
    CsvCursor.open(File(gtfsDir, "stops.txt")) { csv ->
        val cId = csv.requireColumn("stop_id")
        val cName = csv.requireColumn("stop_name")
        val cLat = csv.requireColumn("stop_lat")
        val cLon = csv.requireColumn("stop_lon")
        while (csv.nextRow()) {
            val lat = csv.double(cLat)
            val lon = csv.double(cLon)
            if (lat.isNaN() || lon.isNaN()) continue
            stopIdToIdx[csv.string(cId)] = stopIds.size
            stopIds.add(csv.string(cId))
            stopNames.add(csv.string(cName))
            stopLat.add(lat)
            stopLon.add(lon)
        }
    }

    // --- stop_times, in streaming: linee per fermata -----------------------
    // Serve due volte: le categorie di ogni fermata (per i filtri) e il grafo
    // di sovrapposizione fra linee (per i colori).
    val routesAtStop = Array(stopIds.size) { HashSet<Int>() }
    CsvCursor.open(File(gtfsDir, "stop_times.txt")) { csv ->
        val cTrip = csv.requireColumn("trip_id")
        val cStop = csv.requireColumn("stop_id")
        var tripBytes = ByteArray(0)
        var currentRoute = -1
        while (csv.nextRow()) {
            if (!csv.fieldEquals(cTrip, tripBytes)) {
                tripBytes = csv.bytes(cTrip)
                currentRoute = tripToRoute[csv.string(cTrip)] ?: -1
            }
            if (currentRoute < 0) continue
            val s = stopIdToIdx[csv.string(cStop)] ?: continue
            routesAtStop[s].add(currentRoute)
        }
    }

    // --- la colorazione, condivisa col bundler -----------------------------
    // Stessa funzione, stessi input deterministici: il colore che finisce
    // nelle tile e' lo stesso scritto nel record ROUTES del bundle.
    val paletteIdx = RouteColoring.assign(
        routes.map { it.id },
        routesAtStop.map { it as Collection<Int> },
    )
    for (r in routes.indices) routes[r].color = paletteIdx[r]

    // --- shapes: geometrie per linea, in due fasi --------------------------
    // Fase 1, streaming sul CSV: si raccoglie ogni shape ordinata e pulita.
    // Fase 2, in parallelo: (eventuale) map matching sulla strada OSM via
    // Valhalla, poi semplificazione. L'emissione resta nell'ordine del CSV,
    // cosi' il file e' byte-deterministico anche col pool di thread.
    var pointsIn = 0L
    class ShapeWork(val routeIdx: Int, val la: DoubleArray, val lo: DoubleArray)

    val works = ArrayList<ShapeWork>(8192)
    CsvCursor.open(File(gtfsDir, "shapes.txt")) { csv ->
        val cShape = csv.requireColumn("shape_id")
        val cLat = csv.requireColumn("shape_pt_lat")
        val cLon = csv.requireColumn("shape_pt_lon")
        val cSeq = csv.requireColumn("shape_pt_sequence")
        var shapeBytes = ByteArray(0)
        var shapeId = ""
        val seq = ArrayList<Int>()
        val lats = ArrayList<Double>()
        val lons = ArrayList<Double>()
        val seenShapes = HashSet<String>()

        fun flush() {
            if (shapeId.isEmpty() || lats.isEmpty()) return
            val route = shapeToRoute[shapeId] ?: return
            pointsIn += lats.size
            val order2 = (0 until lats.size).sortedBy { seq[it] }
            var la = DoubleArray(order2.size) { lats[order2[it]] }
            var lo = DoubleArray(order2.size) { lons[order2[it]] }
            // Punti duplicati o quasi (sotto 1 m) prima della semplificazione:
            // Douglas-Peucker su input sporco produce auto-intersezioni.
            val keep = ArrayList<Int>(la.size)
            for (i in la.indices) {
                if (keep.isEmpty() || metersApart(la[keep.last()], lo[keep.last()], la[i], lo[i]) >= 1.0) {
                    keep.add(i)
                }
            }
            la = DoubleArray(keep.size) { la[keep[it]] }
            lo = DoubleArray(keep.size) { lo[keep[it]] }
            if (la.size < 2) return
            works.add(ShapeWork(route, la, lo))
        }

        while (csv.nextRow()) {
            if (!csv.fieldEquals(cShape, shapeBytes)) {
                flush()
                seq.clear(); lats.clear(); lons.clear()
                shapeBytes = csv.bytes(cShape)
                shapeId = csv.string(cShape)
                require(seenShapes.add(shapeId)) {
                    "shapes.txt non e' raggruppato per shape_id: '$shapeId' ricompare"
                }
            }
            val lat = csv.double(cLat)
            val lon = csv.double(cLon)
            if (lat.isNaN() || lon.isNaN()) continue
            seq.add(csv.int(cSeq, seq.size))
            lats.add(lat)
            lons.add(lon)
        }
        flush()
    }

    // Fase 2: matching (se VALHALLA_URL e' nell'ambiente) e semplificazione.
    // Una tratta aderente alla strada merita una tolleranza piu' fine: i 12 m
    // che mascheravano il rumore GPS smusserebbero le curve vere.
    val matcher = System.getenv("VALHALLA_URL")
        ?.takeIf { it.isNotBlank() }
        ?.let { MapMatcher(it) }
    val matchedOk = java.util.concurrent.atomic.AtomicInteger()
    val matchedFallback = java.util.concurrent.atomic.AtomicInteger()

    class Emit(val json: String, val hash: Long, val points: Int)

    val emits = arrayOfNulls<Emit>(works.size)
    val pool = java.util.concurrent.Executors.newFixedThreadPool(if (matcher != null) 8 else 4)
    for (w in works.indices) {
        pool.execute {
            val work = works[w]
            val matched = matcher?.match(work.la, work.lo)
            if (matcher != null) {
                if (matched != null) matchedOk.incrementAndGet() else matchedFallback.incrementAndGet()
            }
            val la = matched?.lat ?: work.la
            val lo = matched?.lon ?: work.lo
            val tolerance = if (matched != null) TOLERANCE_MATCHED_M else TOLERANCE_M
            val simplified = simplify(la, lo, tolerance)
            // Geometrie identiche (andata e ritorno sullo stesso asse, o
            // shape duplicate) si emettono una volta sola per linea.
            var gh = Ftb.FNV_OFFSET xor work.routeIdx.toLong()
            for (i in simplified) {
                gh = (gh xor la[i].toRawBits()) * Ftb.FNV_PRIME
                gh = (gh xor lo[i].toRawBits()) * Ftb.FNV_PRIME
            }
            val r = routes[work.routeIdx]
            val sb = StringBuilder(simplified.size * 24 + 128)
            sb.append("{\"type\":\"Feature\",\"properties\":{\"c\":\"")
                .append(RouteColoring.hex(r.color))
                .append("\",\"n\":").append(jsonString(r.shortName))
                .append(",\"cat\":\"").append(r.category)
                .append("\",\"rh\":\"").append(java.lang.Long.toHexString(Ftb.hash64(r.id)))
                .append("\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
            for ((k, i) in simplified.withIndex()) {
                if (k > 0) sb.append(',')
                sb.append('[').append(round6(lo[i])).append(',').append(round6(la[i])).append(']')
            }
            sb.append("]}}\n")
            emits[w] = Emit(sb.toString(), gh, simplified.size)
        }
    }
    pool.shutdown()
    check(pool.awaitTermination(45, java.util.concurrent.TimeUnit.MINUTES)) {
        "il map matching non e' finito in 45 minuti"
    }

    var featuresOut = 0
    var pointsOut = 0L
    val seenGeometry = HashSet<Long>()
    File(outDir, "linee.geojsonl").bufferedWriter(bufferSize = 1 shl 20).use { linee ->
        for (e in emits) {
            if (e == null) continue
            if (!seenGeometry.add(e.hash)) continue
            linee.write(e.json)
            featuresOut++
            pointsOut += e.points
        }
    }

    // --- fermate ------------------------------------------------------------
    var stopsOut = 0
    File(outDir, "fermate.geojsonl").bufferedWriter(bufferSize = 1 shl 20).use { w ->
        for (s in stopIds.indices) {
            if (routesAtStop[s].isEmpty()) continue // mai servita: non si disegna
            var hasU = false
            var hasE = false
            for (r in routesAtStop[s]) {
                if (routes[r].category == "u") hasU = true else hasE = true
            }
            val cat = if (hasU && hasE) "ue" else if (hasU) "u" else "e"
            w.write(
                "{\"type\":\"Feature\",\"properties\":{\"n\":${jsonString(stopNames[s])}," +
                    "\"h\":\"${java.lang.Long.toHexString(Ftb.hash64(stopIds[s]))}\"," +
                    "\"cat\":\"$cat\"}," +
                    "\"geometry\":{\"type\":\"Point\",\"coordinates\":[${round6(stopLon[s])},${round6(stopLat[s])}]}}\n",
            )
            stopsOut++
        }
    }

    val colorUse = IntArray(RouteColoring.PALETTE.size)
    for (r in routes) if (r.color >= 0) colorUse[r.color]++
    val (conflicts, pairs) = RouteColoring.conflicts(
        paletteIdx,
        routesAtStop.map { it as Collection<Int> },
    )

    println("overlay: ${System.currentTimeMillis() - t0} ms")
    println("  linee:    $featuresOut geometrie da ${shapeToRoute.size} shape ($pointsIn -> $pointsOut punti, DP ${TOLERANCE_M} m)")
    if (matcher != null) {
        println("  matching: ${matchedOk.get()} tracce aderite alla strada, ${matchedFallback.get()} rimaste GPS (errore o guardia di lunghezza)")
    } else {
        println("  matching: spento (VALHALLA_URL assente)")
    }
    println("  fermate:  $stopsOut")
    println("  colori:   ${colorUse.joinToString()} per tinta")
    println("  conflitti: $conflicts coppie sovrapposte con lo stesso colore su $pairs")
}

/** Tolleranza della semplificazione: sotto i 12 m una tratta urbana resta riconoscibile a ogni zoom della mappa. */
private const val TOLERANCE_M = 12.0

/** Per le tracce gia' aderenti alla strada: 12 m smusserebbero le curve vere. */
private const val TOLERANCE_MATCHED_M = 3.0

private fun round6(v: Double): String = String.format(java.util.Locale.ROOT, "%.6f", v)

private fun jsonString(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
    }
    sb.append('"')
    return sb.toString()
}
