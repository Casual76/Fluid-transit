package dev.antigravity.fluidtransit.ftb

import java.io.File

/**
 * `gradlew run --args="<dir gtfs> <out.ftb> [PROVINCIA]"`
 *
 * Senza provincia costruisce l'intera Toscana: utile per misurare i numeri
 * veri del bundle, molto piu' lento del giocattolo.
 */
fun main(args: Array<String>) {
    val gtfsDir = File(args.getOrElse(0) { "work/gtfs" })
    val out = File(args.getOrElse(1) { "work/toscana-SI.ftb" })
    val area = args.getOrNull(2)?.takeIf { it.isNotBlank() && it != "-" }

    require(gtfsDir.isDirectory) { "cartella GTFS non trovata: ${gtfsDir.absolutePath}" }

    println("Sorgente : ${gtfsDir.absolutePath}")
    println("Provincia: ${area ?: "tutte (Toscana intera)"}")
    println()

    val builder = ToyBundleBuilder(gtfsDir, area)
    builder.build(out)
    val s = builder.stats

    fun pct(part: Number, total: Number): String {
        val t = total.toDouble()
        return if (t == 0.0) "-" else String.format("%.1f%%", 100.0 * part.toDouble() / t)
    }

    println("== Selezione ==")
    println("  fermate        ${s.stopsKept} / ${s.stopsTotal} (${pct(s.stopsKept, s.stopsTotal)})")
    println("  linee          ${s.routesKept}")
    println("  servizi        ${s.servicesKept}")
    println("  corse          ${s.tripsKept} / ${s.tripsTotal} (${pct(s.tripsKept, s.tripsTotal)})")
    println("  righe orario   ${s.stopTimeRowsKept}")
    println()

    println("== Collasso in pattern ==")
    println("  pattern              ${s.patterns}")
    println("  corse per pattern    ${String.format("%.1f", s.tripsKept.toDouble() / maxOf(1, s.patterns))}")
    println("  voci pattern-fermata ${s.patternStopEntries}  (da ${s.stopTimeRowsKept} righe orario)")
    println("  riduzione            ${String.format("%.1fx", s.stopTimeRowsKept.toDouble() / maxOf(1, s.patternStopEntries))}")
    println()

    println("== Deduplica dei profili ==")
    println("  profili distinti     ${s.profiles}")
    println("  fattore              ${String.format("%.1fx", s.tripsKept.toDouble() / maxOf(1, s.profiles))}")
    println("  valori u16 scritti   ${s.profileValues}")
    println("  soste non nulle      ${s.dwellEntries} su ${s.profileValues} (${pct(s.dwellEntries, s.profileValues)})")
    println("  durata max di corsa  ${s.maxTripSpanSeconds} s (limite u16: 65535)")
    println()

    println("== Sezioni ==")
    var total = 0
    for ((name, size) in s.sectionBytes) {
        println(String.format("  %-16s %10d B  %6.1f KB", name, size, size / 1024.0))
        total += size
    }
    println(String.format("  %-16s %10d B  %6.2f MB", "totale sezioni", total, total / 1048576.0))
    println(String.format("  %-16s %10d B  %6.2f MB", "file su disco", out.length(), out.length() / 1048576.0))
    println("  di cui trip_id in chiaro: ${String.format("%.2f MB", s.tripIdBytes / 1048576.0)}")
    println()

    println("collisioni di hash sui trip_id: ${s.hashCollisions}")
    println("build in ${s.buildMillis} ms")
    println("scritto: ${out.absolutePath}")
}
