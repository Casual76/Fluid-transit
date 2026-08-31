package dev.antigravity.fluidtransit.bundler

import java.io.File
import kotlin.system.exitProcess

/**
 * CLI del bundler: `build <dir-gtfs> <out.ftb> [PROVINCIA|-]`.
 *
 * Il terzo argomento filtra su una provincia (`area_id` di area.txt: FI, SI,
 * AR, ...) o `-` per l'intera regione. Il filtro serve ai feed di prova nei
 * test e a nient'altro: il bundle di produzione e' sempre l'intera regione.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("uso: bundler <dir-gtfs> <out.ftb> [PROVINCIA|-]")
        exitProcess(2)
    }
    val gtfsDir = File(args[0])
    require(gtfsDir.isDirectory) { "non e' una cartella: $gtfsDir" }
    val out = File(args[1])
    val area = args.getOrNull(2)?.takeIf { it != "-" }

    val builder = BundleBuilder(gtfsDir, area)
    builder.build(out)
    val s = builder.stats

    fun fmt(n: Number): String = String.format("%,d", n.toLong()).replace(',', '.')

    println("bundle: $out (${fmt(out.length())} byte) in ${s.buildMillis} ms")
    println("  corse:    ${fmt(s.tripsKept)} tenute su ${fmt(s.tripsTotal)}")
    println("  fermate:  ${fmt(s.stopsKept)} su ${fmt(s.stopsTotal)} · linee ${fmt(s.routesKept)} · servizi ${fmt(s.servicesKept)}")
    println("  pattern:  ${fmt(s.patterns)} (${fmt(s.patternStopEntries)} voci) · profili ${fmt(s.profiles)} (${fmt(s.profileValues)} valori)")
    println("  soste:    ${fmt(s.dwellEntries)} · corsa piu' lunga ${s.maxTripSpanSeconds} s · fine piu' tarda ${s.maxTripEndSeconds} s")
    println("  transfer: ${fmt(s.transferEdges)} archi (${fmt(s.transferCapped)} fermate oltre il tetto di grado)")
    println("  scartate: ${s.droppedSingleStop} con una fermata sola, ${s.droppedNegativeDep0} senza orario di partenza, ${s.droppedOffsetOverflow} oltre le 18h12m")
    println("  sezioni:")
    for ((name, bytes) in s.sectionBytes) {
        println("    %-14s %12s byte".format(name, fmt(bytes)))
    }

    // Le perdite non fermano il build - un feed vero ne ha sempre qualcuna -
    // ma un'esplosione si': il gate volumetrico del workflow confronta questi
    // numeri col build precedente.
    val dropped = s.droppedSingleStop + s.droppedNegativeDep0 + s.droppedOffsetOverflow
    if (s.tripsKept > 0 && dropped * 100L / (s.tripsKept + dropped) > 5) {
        System.err.println("ERRORE: oltre il 5% delle corse scartate ($dropped): feed sospetto")
        exitProcess(1)
    }

    // Il frammento per index.json: i numeri che il workflow usa per i gate
    // (volumetria +-15%, corse per giorno della settimana +-20% contro lo
    // stesso giorno del build precedente) e i campi che l'app legge.
    val report = args.getOrNull(3)?.let { File(it) }
    if (report != null) {
        val buildId = java.lang.Long.toHexString(
            dev.antigravity.fluidtransit.routing.BundleReader(out).use { it.buildId },
        )
        report.parentFile?.mkdirs()
        report.writeText(
            buildString {
                appendLine("{")
                appendLine("  \"buildId\": \"$buildId\",")
                appendLine("  \"validFrom\": \"${s.feedStartDate}\",")
                appendLine("  \"validTo\": \"${s.feedEndDate}\",")
                appendLine("  \"stops\": ${s.stopsKept},")
                appendLine("  \"routes\": ${s.routesKept},")
                appendLine("  \"trips\": ${s.tripsKept},")
                appendLine("  \"patterns\": ${s.patterns},")
                appendLine("  \"dropped\": $dropped,")
                appendLine("  \"tripsActivePerDay\": [${s.tripsActivePerDay.joinToString(", ")}]")
                appendLine("}")
            },
        )
        println("  report:   $report")
    }
}
