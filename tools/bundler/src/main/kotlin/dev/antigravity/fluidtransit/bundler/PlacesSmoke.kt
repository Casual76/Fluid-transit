package dev.antigravity.fluidtransit.bundler

import dev.antigravity.fluidtransit.routing.PlacesReader
import dev.antigravity.fluidtransit.routing.PlacesSearch
import java.io.File
import kotlin.system.exitProcess

/**
 * Il banco di prova del geocoding sul file vero:
 * `placesSmoke <luoghi.bin> [--near=lat,lon] <query...>`
 *
 * Il punto di riferimento non e' un dettaglio: senza, "via roma" restituisce
 * duecento vie toscane con lo stesso punteggio e la tua non c'e' mai. E'
 * esattamente il difetto che l'utente ha visto sul telefono, quindi qui si
 * prova con la posizione, come nell'app.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("uso: placesSmoke <luoghi.bin> [--near=lat,lon] <query...>")
        exitProcess(2)
    }
    var refLat = Double.NaN
    var refLon = Double.NaN
    val queries = ArrayList<String>()
    for (a in args.drop(1)) {
        if (a.startsWith("--near=")) {
            val parts = a.removePrefix("--near=").split(',')
            if (parts.size == 2) {
                refLat = parts[0].trim().toDouble()
                refLon = parts[1].trim().toDouble()
            }
        } else {
            queries.add(a)
        }
    }

    PlacesReader(File(args[0])).use { r ->
        val search = PlacesSearch(r)
        println("luoghi: ${r.fastCount} rapidi, ${r.streetCount} vie con civici, ${r.civiciCount} numeri")
        if (!refLat.isNaN()) println("riferimento: $refLat, $refLon")
        for (q in queries) {
            val t0 = System.nanoTime()
            val fast = search.fast(q, 8, refLat, refLon)
            val fastMs = (System.nanoTime() - t0) / 1_000_000
            val t1 = System.nanoTime()
            val civ = search.civici(q, 6, refLat, refLon)
            val civMs = (System.nanoTime() - t1) / 1_000_000
            println("\n\"$q\"  (rapida ${fastMs} ms, civici ${civMs} ms)")
            for (h in fast) {
                println("  [${h.kind}] ${h.score}  ${h.name} — ${h.context}  (${"%.5f".format(h.lat)}, ${"%.5f".format(h.lon)})")
            }
            for (h in civ) {
                println("  [civico] ${h.score}  ${h.name} — ${h.context}  (${"%.5f".format(h.lat)}, ${"%.5f".format(h.lon)})")
            }
        }
    }
}
