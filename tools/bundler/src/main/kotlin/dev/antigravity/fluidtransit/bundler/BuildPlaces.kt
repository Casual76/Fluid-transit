package dev.antigravity.fluidtransit.bundler

import dev.antigravity.fluidtransit.routing.PlaceEntry
import dev.antigravity.fluidtransit.routing.Places
import dev.antigravity.fluidtransit.routing.PlacesWriter
import dev.antigravity.fluidtransit.routing.StreetEntry
import java.io.File
import kotlin.system.exitProcess

/**
 * Il geocoding offline: da quattro estratti OSM (geojsonseq di osmium) al
 * file `luoghi.bin` che l'app scarica accanto al bundle.
 *
 * `places <dir> <out.bin>` — nella dir: localita.geojsonl, poi.geojsonl,
 * vie.geojsonl, civici.geojsonl (ognuno puo' mancare).
 *
 * Il "comune" accanto a ogni risultato ("scuola agnoletti SESTO FIORENTINO")
 * non e' un campo OSM: e' la localita' piu' vicina pesata per importanza —
 * una city conta fino a 15 km, un paese fino a 3. Non e' perfetto ai
 * confini, ma per una ricerca e' cio' che serve: un nome che orienta.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("uso: places <dir-geojsonl> <out.bin>")
        exitProcess(2)
    }
    val dir = File(args[0])
    val out = File(args[1])
    val t0 = System.currentTimeMillis()

    // --- le localita': voci di ricerca E etichette di contesto -------------
    class Locality(val name: String, val lat: Double, val lon: Double, val radiusM: Double)

    val localities = ArrayList<Locality>()
    forEachFeature(File(dir, "localita.geojsonl")) { props, lat, lon ->
        val name = props["name"] as? String ?: return@forEachFeature
        val radius = when (props["place"]) {
            "city" -> 15_000.0
            "town" -> 8_000.0
            "village" -> 3_000.0
            "suburb" -> 2_500.0
            "quarter" -> 2_000.0
            "hamlet" -> 1_500.0
            "neighbourhood" -> 1_200.0
            else -> return@forEachFeature
        }
        localities.add(Locality(name, lat, lon, radius))
    }

    // Griglia 0,05 gradi per il "qual e' la localita' qui intorno".
    val cell = 0.05
    val locGrid = HashMap<Long, ArrayList<Int>>()
    for (i in localities.indices) {
        val key = (Math.floor(localities[i].lat / cell).toLong() shl 32) or
            (Math.floor(localities[i].lon / cell).toLong() and 0xffffffffL)
        locGrid.getOrPut(key) { ArrayList() }.add(i)
    }

    fun contextOf(lat: Double, lon: Double): String {
        var bestScore = Double.MAX_VALUE
        var best = ""
        val cellsAround = 4 // 0,2 gradi: copre i 15 km delle city
        val la0 = Math.floor(lat / cell).toLong()
        val lo0 = Math.floor(lon / cell).toLong()
        for (dla in -cellsAround..cellsAround) {
            for (dlo in -cellsAround..cellsAround) {
                val bucket = locGrid[((la0 + dla) shl 32) or ((lo0 + dlo) and 0xffffffffL)]
                    ?: continue
                for (i in bucket) {
                    val l = localities[i]
                    val d = metersApart(lat, lon, l.lat, l.lon)
                    if (d > l.radiusM) continue
                    val score = d / l.radiusM // normalizzata sull'importanza
                    if (score < bestScore) {
                        bestScore = score
                        best = l.name
                    }
                }
            }
        }
        return best
    }

    val fast = ArrayList<PlaceEntry>(200_000)
    for (l in localities) {
        fast.add(PlaceEntry(Places.KIND_LOCALITY, l.name, contextOf(l.lat, l.lon).takeIf { it != l.name } ?: "", l.lat, l.lon))
    }

    // --- i POI --------------------------------------------------------------
    var poiIn = 0
    var poiKept = 0
    val poiSeen = HashSet<String>()
    forEachFeature(File(dir, "poi.geojsonl")) { props, lat, lon ->
        poiIn++
        val name = (props["name"] as? String)?.trim() ?: return@forEachFeature
        if (name.length < 2) return@forEachFeature
        val ctx = contextOf(lat, lon)
        // Doppioni (nodo+area dello stesso posto): stesso nome nella stessa
        // cella di ~250 m, si tiene il primo.
        val key = Places.normalize(name) + "|" + ctx + "|" +
            Math.round(lat * 400) + "|" + Math.round(lon * 400)
        if (!poiSeen.add(key)) return@forEachFeature
        fast.add(PlaceEntry(Places.KIND_POI, name, ctx, lat, lon))
        poiKept++
    }

    // --- le vie: una voce per (nome, localita') -----------------------------
    class StreetAgg(val name: String, val context: String) {
        var latSum = 0.0
        var lonSum = 0.0
        var n = 0
    }

    val streetAgg = HashMap<String, StreetAgg>()
    var wayIn = 0
    forEachFeature(File(dir, "vie.geojsonl")) { props, lat, lon ->
        wayIn++
        val name = (props["name"] as? String)?.trim() ?: return@forEachFeature
        if (name.length < 2) return@forEachFeature
        val ctx = contextOf(lat, lon)
        val agg = streetAgg.getOrPut(Places.normalize(name) + "|" + ctx) { StreetAgg(name, ctx) }
        agg.latSum += lat
        agg.lonSum += lon
        agg.n++
    }
    for (a in streetAgg.values) {
        fast.add(PlaceEntry(Places.KIND_STREET, a.name, a.context, a.latSum / a.n, a.lonSum / a.n))
    }

    // --- i civici: raggruppati per via, ordinati per numero -----------------
    class CivAgg(val name: String, val context: String) {
        val nums = ArrayList<Triple<String, Double, Double>>()
    }

    val civAgg = HashMap<String, CivAgg>()
    var civIn = 0
    forEachFeature(File(dir, "civici.geojsonl")) { props, lat, lon ->
        val num = (props["addr:housenumber"] as? String)?.trim() ?: return@forEachFeature
        val street = ((props["addr:street"] ?: props["addr:place"]) as? String)?.trim()
            ?: return@forEachFeature
        if (num.isEmpty() || street.length < 2 || num.length > 8) return@forEachFeature
        civIn++
        val ctx = contextOf(lat, lon)
        val agg = civAgg.getOrPut(Places.normalize(street) + "|" + ctx) { CivAgg(street, ctx) }
        agg.nums.add(Triple(num, lat, lon))
    }

    fun numOrder(n: String): Pair<Int, String> {
        val digits = n.takeWhile { it.isDigit() }
        return (digits.toIntOrNull() ?: Int.MAX_VALUE) to n.lowercase()
    }

    val streets = civAgg.values.map { agg ->
        val sorted = agg.nums
            .distinctBy { Places.normalize(it.first) }
            .sortedWith(compareBy({ numOrder(it.first).first }, { numOrder(it.first).second }))
        StreetEntry(
            name = agg.name,
            context = agg.context,
            lat = sorted.sumOf { it.second } / sorted.size,
            lon = sorted.sumOf { it.third } / sorted.size,
            numbers = sorted,
        )
    }

    PlacesWriter.write(out, fast, streets)

    println("luoghi: ${System.currentTimeMillis() - t0} ms")
    println("  localita': ${localities.size}")
    println("  poi:       $poiKept tenuti su $poiIn")
    println("  vie:       ${streetAgg.size} voci da $wayIn segmenti")
    println("  civici:    $civIn numeri su ${streets.size} vie")
    println("  file:      ${out.length()} byte")
}

/**
 * Scorre un geojsonseq riga per riga ed estrae proprieta' + UN punto
 * rappresentativo della geometria: il punto stesso, il vertice di mezzo di
 * una linea, la media dell'anello di un poligono.
 */
private fun forEachFeature(
    file: File,
    onFeature: (props: Map<*, *>, lat: Double, lon: Double) -> Unit,
) {
    if (!file.exists()) return
    file.bufferedReader(bufferSize = 1 shl 20).useLines { lines ->
        for (raw in lines) {
            // osmium premette il separatore RS (0x1E) a ogni riga.
            val line = raw.trim(30.toChar(), ' ', '	')
            if (line.isEmpty() || !line.startsWith("{")) continue
            val f = runCatching { Json.parse(line) as? Map<*, *> }.getOrNull() ?: continue
            val props = f["properties"] as? Map<*, *> ?: continue
            val geometry = f["geometry"] as? Map<*, *> ?: continue
            val coords = geometry["coordinates"] ?: continue
            val point = representativePoint(geometry["type"] as? String, coords) ?: continue
            onFeature(props, point.second, point.first)
        }
    }
}

/** Ritorna (lon, lat). */
@Suppress("UNCHECKED_CAST")
private fun representativePoint(type: String?, coords: Any): Pair<Double, Double>? = when (type) {
    "Point" -> {
        val c = coords as List<Double>
        c[0] to c[1]
    }

    "LineString" -> {
        val c = coords as List<List<Double>>
        if (c.isEmpty()) null else c[c.size / 2].let { it[0] to it[1] }
    }

    "MultiLineString" -> {
        val c = coords as List<List<List<Double>>>
        c.firstOrNull()?.let { line -> line[line.size / 2].let { it[0] to it[1] } }
    }

    "Polygon" -> {
        val ring = (coords as List<List<List<Double>>>).firstOrNull()
        ring?.let { r -> r.map { it[0] }.average() to r.map { it[1] }.average() }
    }

    "MultiPolygon" -> {
        val ring = (coords as List<List<List<List<Double>>>>).firstOrNull()?.firstOrNull()
        ring?.let { r -> r.map { it[0] }.average() to r.map { it[1] }.average() }
    }

    else -> null
}
