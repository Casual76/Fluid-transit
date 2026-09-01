package dev.antigravity.fluidtransit.bundler

import dev.antigravity.fluidtransit.routing.PlacesReader
import dev.antigravity.fluidtransit.routing.PlacesSearch
import java.io.File
import kotlin.system.exitProcess

/** Il banco di prova del geocoding sul file vero: `placesSmoke <luoghi.bin> <query>`. */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("uso: placesSmoke <luoghi.bin> <query...>")
        exitProcess(2)
    }
    PlacesReader(File(args[0])).use { r ->
        val search = PlacesSearch(r)
        println("luoghi: ${r.fastCount} rapidi, ${r.streetCount} vie con civici, ${r.civiciCount} numeri")
        for (q in args.drop(1)) {
            val t0 = System.nanoTime()
            val fast = search.fast(q)
            val fastMs = (System.nanoTime() - t0) / 1_000_000
            val t1 = System.nanoTime()
            val civ = search.civici(q)
            val civMs = (System.nanoTime() - t1) / 1_000_000
            println("\n\"$q\"  (rapida ${fastMs} ms, civici ${civMs} ms)")
            for (h in fast) println("  [${h.kind}] ${h.name} — ${h.context}  (${"%.5f".format(h.lat)}, ${"%.5f".format(h.lon)})")
            for (h in civ) println("  [civico] ${h.name} — ${h.context}  (${"%.5f".format(h.lat)}, ${"%.5f".format(h.lon)})")
        }
    }
}
