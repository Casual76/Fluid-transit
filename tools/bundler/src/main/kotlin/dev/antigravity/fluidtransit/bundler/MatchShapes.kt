package dev.antigravity.fluidtransit.bundler

import java.io.File

/**
 * Riproietta le shape del feed sulla strada vera di OSM, UNA VOLTA, e scrive
 * il risultato come un `shapes.txt` sostitutivo.
 *
 * Prima della Fase 8 il matching viveva dentro il costruttore dell'overlay:
 * le tratte disegnate seguivano l'asfalto, ma la geometria dentro il bundle
 * restava la traccia GPS grezza semplificata a 20 m. Non era un problema
 * finche' serviva solo all'anteprima di un itinerario; da quando i bus ci
 * corrono sopra lo e' diventato, perche' un mezzo che avanza su una traccia
 * GPS taglia gli isolati e attraversa i palazzi.
 *
 * Il file uscito da qui e' un `shapes-matched.txt` nello stesso formato del
 * GTFS, quindi lo leggono senza saperlo entrambi i consumatori: il bundle e
 * l'overlay. Se manca — Valhalla non pronto, notte sfortunata — tutti
 * ricadono su `shapes.txt` e non si perde niente di ieri.
 *
 * Uso: matchshapes <gtfsDir> <outFile>
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("uso: matchshapes <gtfsDir> <outFile>")
        return
    }
    val gtfsDir = File(args[0])
    val out = File(args[1])
    val shapes = File(gtfsDir, "shapes.txt")
    if (!shapes.isFile) {
        System.err.println("shapes.txt assente in $gtfsDir: niente da riproiettare")
        return
    }
    val url = System.getenv("VALHALLA_URL")?.takeIf { it.isNotBlank() }
    if (url == null) {
        println("VALHALLA_URL non impostata: nessun matching, i consumatori useranno shapes.txt")
        return
    }
    val matcher = MapMatcher(url)

    // Una traccia per volta finirebbe in un'ora di attesa sulla rete; tutte
    // insieme finirebbero in memoria. A lotti: parallelismo pieno, memoria
    // limitata, e l'ordine di `shapes.txt` conservato (i lettori a valle
    // pretendono che le shape siano raggruppate).
    val batch = ArrayList<Shape>(BATCH)
    var matched = 0
    var kept = 0
    var points = 0L

    val writer = out.bufferedWriter(bufferSize = 1 shl 20)
    writer.write("shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n")

    fun flushBatch() {
        if (batch.isEmpty()) return
        val results = arrayOfNulls<MapMatcher.Matched>(batch.size)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(THREADS)
        for (i in batch.indices) {
            pool.execute { results[i] = matcher.match(batch[i].lat, batch[i].lon) }
        }
        pool.shutdown()
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES)
        for (i in batch.indices) {
            val s = batch[i]
            val m = results[i]
            if (m != null) matched++ else kept++
            val la = m?.lat ?: s.lat
            val lo = m?.lon ?: s.lon
            // Si scrive a 2 m: chi legge scegliera' la sua tolleranza (il
            // bundle 8 m, l'overlay 3), ma piu' fine di cosi' sarebbe solo
            // peso senza informazione.
            val idx = simplify(la, lo, WRITE_TOLERANCE_M)
            var seq = 0
            for (k in idx) {
                writer.write(s.id)
                writer.write(",")
                writer.write(fmt(la[k]))
                writer.write(",")
                writer.write(fmt(lo[k]))
                writer.write(",")
                writer.write(seq.toString())
                writer.write("\n")
                seq++
            }
            points += idx.size
        }
        batch.clear()
    }

    CsvCursor.open(shapes) { csv ->
        val cShape = csv.requireColumn("shape_id")
        val cLat = csv.requireColumn("shape_pt_lat")
        val cLon = csv.requireColumn("shape_pt_lon")
        val cSeq = csv.requireColumn("shape_pt_sequence")
        var shapeBytes = ByteArray(0)
        var shapeId = ""
        val seq = ArrayList<Int>()
        val lats = ArrayList<Double>()
        val lons = ArrayList<Double>()

        fun collect() {
            if (shapeId.isEmpty() || lats.size < 2) return
            val order = (0 until lats.size).sortedBy { seq[it] }
            val la = ArrayList<Double>(order.size)
            val lo = ArrayList<Double>(order.size)
            for (i in order) {
                if (la.isEmpty() || metersApart(la.last(), lo.last(), lats[i], lons[i]) >= 1.0) {
                    la.add(lats[i])
                    lo.add(lons[i])
                }
            }
            if (la.size < 2) return
            batch.add(Shape(shapeId, la.toDoubleArray(), lo.toDoubleArray()))
            if (batch.size >= BATCH) flushBatch()
        }

        while (csv.nextRow()) {
            if (!csv.fieldEquals(cShape, shapeBytes)) {
                collect()
                seq.clear(); lats.clear(); lons.clear()
                shapeBytes = csv.bytes(cShape)
                shapeId = csv.string(cShape)
            }
            val lat = csv.double(cLat)
            val lon = csv.double(cLon)
            if (lat.isNaN() || lon.isNaN()) continue
            seq.add(csv.int(cSeq, seq.size))
            lats.add(lat)
            lons.add(lon)
        }
        collect()
    }
    flushBatch()
    writer.flush()
    writer.close()

    val total = matched + kept
    println("matching: $matched tracce aderite alla strada, $kept rimaste GPS, su $total")
    println("scritto ${out.name}: $points punti, ${out.length() / 1024} KB")
    if (total > 0 && matched * 100 / total < MIN_MATCHED_PERCENT) {
        // Sotto questa soglia il grafo e' probabilmente incompleto: meglio
        // niente sidecar che una geometria peggiore di quella di ieri.
        System.err.println(
            "::warning::solo ${matched * 100 / total}% di tracce aderite: sidecar scartato",
        )
        out.delete()
    }
}

private class Shape(val id: String, val lat: DoubleArray, val lon: DoubleArray)

private const val BATCH = 64
private const val THREADS = 8
private const val WRITE_TOLERANCE_M = 2.0

/** Sotto questa percentuale di successo il risultato non si pubblica. */
private const val MIN_MATCHED_PERCENT = 80

private fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.6f", v)
