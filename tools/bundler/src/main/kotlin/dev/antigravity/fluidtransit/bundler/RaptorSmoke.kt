package dev.antigravity.fluidtransit.bundler

import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Raptor
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import dev.antigravity.fluidtransit.routing.Ftb
import kotlin.system.exitProcess

/**
 * Il banco di prova del motore sul bundle VERO: query da riga di comando,
 * viaggi stampati tappa per tappa, tempi cronometrati. E' la verifica
 * "senza device" della Fase 5 — e resta il modo piu' rapido di indagare
 * un itinerario strano segnalato dall'utente.
 *
 * `raptorSmoke <bundle.ftb> <fromLat> <fromLon> <toLat> <toLon> [HH:MM]`
 */
fun main(args: Array<String>) {
    if (args.size < 5) {
        System.err.println("uso: raptorSmoke <bundle.ftb> <fromLat> <fromLon> <toLat> <toLon> [HH:MM]")
        exitProcess(2)
    }
    val reader = BundleReader(File(args[0]))
    val from = Raptor.Place(args[1].toDouble(), args[2].toDouble())
    val to = Raptor.Place(args[3].toDouble(), args[4].toDouble())
    val departAt = if (args.size >= 6) {
        val (h, m) = args[5].split(':').map { it.toInt() }
        ZonedDateTime.now(Ftb.ROME).withHour(h).withMinute(m).withSecond(0).toInstant()
    } else {
        Instant.now()
    }

    val raptor = Raptor(reader)

    // Riscaldamento + misura: la prima query paga le pagine fredde del mmap.
    val t0 = System.nanoTime()
    val journeys = raptor.plan(from, to, departAt)
    val coldMs = (System.nanoTime() - t0) / 1_000_000
    val t1 = System.nanoTime()
    raptor.plan(from, to, departAt)
    val warmMs = (System.nanoTime() - t1) / 1_000_000

    fun hm(i: Instant): String = ZonedDateTime.ofInstant(i, Ftb.ROME)
        .let { "%02d:%02d".format(it.hour, it.minute) }

    println("bundle: ${reader.stopCount} fermate, ${reader.routeCount} linee, ${reader.tripCount} corse")
    println("query: fredda ${coldMs} ms, calda ${warmMs} ms — ${journeys.size} soluzioni\n")
    for ((n, j) in journeys.withIndex()) {
        println(
            "#${n + 1}  ${hm(j.departure)} -> ${hm(j.arrival)}  " +
                "(${j.durationSeconds / 60} min, ${j.transfers} cambi, " +
                "${j.walkSeconds / 60} min a piedi)",
        )
        for (leg in j.legs) {
            when (leg) {
                is Raptor.Leg.Walk -> {
                    val fromName = if (leg.fromStop >= 0) reader.stopName(leg.fromStop) else "partenza"
                    val toName = if (leg.toStop >= 0) reader.stopName(leg.toStop) else "arrivo"
                    println("    ${hm(leg.departure)}  a piedi ${leg.seconds / 60} min: $fromName -> $toName")
                }

                is Raptor.Leg.Ride -> {
                    val line = reader.routeShortName(leg.route).ifEmpty { reader.routeLongName(leg.route) }
                    println(
                        "    ${hm(leg.departure)}  linea $line: " +
                            "${reader.stopName(leg.boardStop)} -> ${reader.stopName(leg.alightStop)} " +
                            "(arrivo ${hm(leg.arrival)})",
                    )
                }
            }
        }
        println()
    }
    reader.close()
}
