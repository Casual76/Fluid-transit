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

    // --- il grafo di sovrapposizione e la colorazione ----------------------
    // Peso dell'arco = quante fermate condividono: quando la palette non
    // basta, si riusa il colore del vicino con cui ci si sovrappone meno.
    val adjacency = HashMap<Long, Int>() // (min<<32|max) -> peso
    for (s in routesAtStop) {
        val list = s.toIntArray().also { it.sort() }
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                val key = (list[i].toLong() shl 32) or list[j].toLong()
                adjacency[key] = (adjacency[key] ?: 0) + 1
            }
        }
    }
    val neighbors = Array(routes.size) { HashMap<Int, Int>() }
    for ((key, weight) in adjacency) {
        val a = (key ushr 32).toInt()
        val b = (key and 0xffffffff).toInt()
        neighbors[a][b] = weight
        neighbors[b][a] = weight
    }

    val order = routes.indices.sortedWith(
        compareByDescending<Int> { neighbors[it].size }.thenBy { routes[it].id },
    )
    for (r in order) {
        val usedWeight = IntArray(PALETTE.size)
        var anyFree = false
        for ((n, w) in neighbors[r]) {
            val c = routes[n].color
            if (c >= 0) usedWeight[c] += w
        }
        for (c in PALETTE.indices) if (usedWeight[c] == 0) anyFree = true
        // Fra i liberi il primo; se la palette e' esaurita dai vicini, quello
        // col minor peso di sovrapposizione. Partenza ruotata sull'hash della
        // linea, cosi' linee lontane e mai adiacenti non escono tutte del
        // primo colore della palette.
        val start = ((Ftb.hash64(routes[r].id) % PALETTE.size + PALETTE.size) % PALETTE.size).toInt()
        var best = -1
        for (k in PALETTE.indices) {
            val c = (start + k) % PALETTE.size
            if (usedWeight[c] == 0) {
                best = c
                break
            }
        }
        if (best < 0) {
            best = 0
            for (c in PALETTE.indices) if (usedWeight[c] < usedWeight[best]) best = c
        }
        routes[r].color = best
        if (anyFree) Unit
    }

    // --- shapes, in streaming: geometrie per linea -------------------------
    // Raggruppate per shape_id come stop_times lo e' per corsa; l'ordine
    // dentro il gruppo e' shape_pt_sequence e non si assume.
    var featuresOut = 0
    var pointsIn = 0L
    var pointsOut = 0L
    val seenGeometry = HashSet<Long>()
    val linee = File(outDir, "linee.geojsonl").bufferedWriter(bufferSize = 1 shl 20)
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
            val simplified = simplify(la, lo, TOLERANCE_M)
            pointsOut += simplified.size
            // Geometrie identiche (andata e ritorno sullo stesso asse, o
            // shape duplicate) si emettono una volta sola per linea.
            var gh = Ftb.FNV_OFFSET xor route.toLong()
            for (i in simplified) {
                gh = (gh xor la[i].toRawBits()) * Ftb.FNV_PRIME
                gh = (gh xor lo[i].toRawBits()) * Ftb.FNV_PRIME
            }
            if (!seenGeometry.add(gh)) return

            val r = routes[route]
            val sb = StringBuilder(simplified.size * 24 + 128)
            sb.append("{\"type\":\"Feature\",\"properties\":{\"c\":\"")
                .append(PALETTE[r.color])
                .append("\",\"n\":").append(jsonString(r.shortName))
                .append(",\"cat\":\"").append(r.category)
                .append("\",\"rh\":\"").append(java.lang.Long.toHexString(Ftb.hash64(r.id)))
                .append("\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
            for ((k, i) in simplified.withIndex()) {
                if (k > 0) sb.append(',')
                sb.append('[').append(round6(lo[i])).append(',').append(round6(la[i])).append(']')
            }
            sb.append("]}}\n")
            linee.write(sb.toString())
            featuresOut++
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
    linee.close()

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

    val colorUse = IntArray(PALETTE.size)
    for (r in routes) if (r.color >= 0) colorUse[r.color]++
    // Quante coppie adiacenti hanno lo stesso colore: e' la misura di quanto
    // la promessa "linee sovrapposte, colori diversi" e' mantenuta.
    var conflicts = 0
    for ((key, _) in adjacency) {
        val a = (key ushr 32).toInt()
        val b = (key and 0xffffffff).toInt()
        if (routes[a].color == routes[b].color) conflicts++
    }

    println("overlay: ${System.currentTimeMillis() - t0} ms")
    println("  linee:    $featuresOut geometrie da ${shapeToRoute.size} shape ($pointsIn -> $pointsOut punti, DP ${TOLERANCE_M} m)")
    println("  fermate:  $stopsOut")
    println("  colori:   ${colorUse.joinToString()} per tinta")
    println("  conflitti: $conflicts coppie sovrapposte con lo stesso colore su ${adjacency.size}")
}

/** Tolleranza della semplificazione: sotto i 12 m una tratta urbana resta riconoscibile a ogni zoom della mappa. */
private const val TOLERANCE_M = 12.0

/**
 * Dodici tinte distinte, tarate per leggibilita' su basemap chiara e scura:
 * sature ma non fluorescenti, spaziate sul cerchio cromatico, senza gialli
 * pallidi (spariscono sul chiaro) e senza blu notte (spariscono sullo scuro).
 */
private val PALETTE = arrayOf(
    "#E5484D", // rosso
    "#2E90FA", // azzurro
    "#12A594", // verde acqua
    "#F76B15", // arancio
    "#8E4EC6", // viola
    "#5B9E31", // verde foglia
    "#E5006A", // magenta
    "#0B6BCB", // blu
    "#B8860B", // ocra
    "#00A2C7", // ciano
    "#D6409F", // rosa acceso
    "#7C66DC", // indaco
)

private fun metersApart(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val kx = 111_320.0 * Math.cos(Math.toRadians(lat1))
    val ky = 110_574.0
    val dx = (lon2 - lon1) * kx
    val dy = (lat2 - lat1) * ky
    return Math.sqrt(dx * dx + dy * dy)
}

/**
 * Douglas-Peucker iterativo sugli indici. La distanza e' punto-segmento in
 * un piano localmente proiettato: a scala urbana l'errore della proiezione
 * e' millimetrico, e la semplicita' vale piu' della geodesia.
 */
private fun simplify(lat: DoubleArray, lon: DoubleArray, toleranceMeters: Double): IntArray {
    val n = lat.size
    if (n <= 2) return IntArray(n) { it }
    val keep = BooleanArray(n)
    keep[0] = true
    keep[n - 1] = true
    val stack = ArrayDeque<IntArray>()
    stack.addLast(intArrayOf(0, n - 1))
    while (stack.isNotEmpty()) {
        val (from, to) = stack.removeLast()
        if (to - from < 2) continue
        var worst = -1
        var worstDist = 0.0
        val kx = 111_320.0 * Math.cos(Math.toRadians(lat[from]))
        val ky = 110_574.0
        val ax = lon[from] * kx
        val ay = lat[from] * ky
        val bx = lon[to] * kx
        val by = lat[to] * ky
        val abx = bx - ax
        val aby = by - ay
        val ab2 = abx * abx + aby * aby
        for (i in from + 1 until to) {
            val px = lon[i] * kx
            val py = lat[i] * ky
            val t = if (ab2 == 0.0) 0.0 else ((px - ax) * abx + (py - ay) * aby) / ab2
            val tc = t.coerceIn(0.0, 1.0)
            val dx = px - (ax + abx * tc)
            val dy = py - (ay + aby * tc)
            val d = Math.sqrt(dx * dx + dy * dy)
            if (d > worstDist) {
                worstDist = d
                worst = i
            }
        }
        if (worstDist > toleranceMeters && worst > 0) {
            keep[worst] = true
            stack.addLast(intArrayOf(from, worst))
            stack.addLast(intArrayOf(worst, to))
        }
    }
    val out = ArrayList<Int>(n / 4)
    for (i in 0 until n) if (keep[i]) out.add(i)
    return out.toIntArray()
}

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
