package dev.antigravity.fluidtransit.bundler

import dev.antigravity.fluidtransit.routing.BundleReader
import java.io.File

/**
 * Cosa sa il bundle delle fermate che si somigliano.
 *
 * Nasce da una cosa vista sul telefono: cercando "TORRE GALLI" escono due
 * righe identiche — stesso nome, stesso sottotitolo — e non c'e' modo di
 * sapere quale sia quale. Sono due banchine della stessa fermata, e il feed
 * lo sa: `stops.txt` ha `parent_station`. La domanda e' quanto lo sa.
 *
 * Questo strumento risponde prima di scrivere il codice che raggruppa, perche'
 * ci sono due mondi possibili: se i parent ci sono, raggruppare e' leggere un
 * campo; se non ci sono, va dedotto da nome e distanza, ed e' un altro lavoro.
 *
 * Uso: stopsSmoke &lt;file.ftb&gt;
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("uso: stopsSmoke <file.ftb>")
        return
    }
    BundleReader(File(args[0])).use { r ->
        val n = r.stopCount
        println("fermate: $n")

        var withParent = 0
        val parents = HashSet<Int>()
        for (i in 0 until n) {
            val p = r.stopParent(i)
            if (p >= 0) {
                withParent++
                parents.add(p)
            }
        }
        println("con parent_station: $withParent (${withParent * 100 / n}%), su ${parents.size} stazioni")

        // I gruppi per nome: quante fermate condividono esattamente lo stesso
        // nome, e quanto stanno lontane. E' la domanda che la ricerca pone
        // all'utente senza saperlo.
        val byName = HashMap<String, MutableList<Int>>()
        for (i in 0 until n) byName.getOrPut(r.stopName(i)) { ArrayList() }.add(i)
        val duplicated = byName.filterValues { it.size > 1 }
        val inDuplicates = duplicated.values.sumOf { it.size }
        println(
            "nomi ripetuti: ${duplicated.size} nomi, $inDuplicates fermate " +
                "(${inDuplicates * 100 / n}% del totale)",
        )

        val sizes = duplicated.values.map { it.size }.sorted()
        if (sizes.isNotEmpty()) {
            println(
                "fermate per nome ripetuto: mediana ${sizes[sizes.size / 2]}, " +
                    "massimo ${sizes.last()}",
            )
        }

        // Quanto sono vicine fra loro: due banchine della stessa fermata
        // stanno a decine di metri, due fermate omonime in due paesi diversi a
        // chilometri. Raggruppare le prime e' un servizio, raggruppare le
        // seconde e' un errore.
        val spreads = ArrayList<Double>()
        for (group in duplicated.values) {
            var max = 0.0
            for (a in group.indices) {
                for (b in a + 1 until group.size) {
                    val d = BundleReader.haversine(
                        r.stopLat(group[a]), r.stopLon(group[a]),
                        r.stopLat(group[b]), r.stopLon(group[b]),
                    )
                    if (d > max) max = d
                }
            }
            spreads.add(max)
        }
        spreads.sort()
        if (spreads.isNotEmpty()) {
            fun q(p: Double) = spreads[(spreads.size * p).toInt().coerceAtMost(spreads.size - 1)]
            println(
                "distanza massima dentro un nome ripetuto: " +
                    "mediana ${q(0.5).toInt()} m, p75 ${q(0.75).toInt()} m, " +
                    "p90 ${q(0.90).toInt()} m, p99 ${q(0.99).toInt()} m, " +
                    "max ${spreads.last().toInt()} m",
            )
            for (limit in listOf(100, 250, 500, 1_000)) {
                val below = spreads.count { it <= limit }
                println("  entro $limit m: $below su ${spreads.size} (${below * 100 / spreads.size}%)")
            }
        }

        // E come se la cava il raggruppamento vero.
        val t0 = System.currentTimeMillis()
        val groups = dev.antigravity.fluidtransit.routing.StopGroups.build(r)
        val ms = System.currentTimeMillis() - t0
        val sizesG = (0 until groups.size).map { groups.members(it).size }
        val riuniti = sizesG.filter { it > 1 }
        println()
        println("raggruppamento: ${groups.size} gruppi da $n fermate, in $ms ms")
        println(
            "  gruppi con piu' di una banchina: ${riuniti.size}, " +
                "che riuniscono ${riuniti.sum()} fermate",
        )
        println("  gruppo piu' grande: ${sizesG.max()} banchine")
        val diametri = (0 until groups.size).mapNotNull { g ->
            val m = groups.members(g)
            if (m.size < 2) return@mapNotNull null
            var max = 0.0
            for (a in m.indices) for (b in a + 1 until m.size) {
                val d = BundleReader.haversine(
                    r.stopLat(m[a]), r.stopLon(m[a]), r.stopLat(m[b]), r.stopLon(m[b]),
                )
                if (d > max) max = d
            }
            max
        }.sorted()
        if (diametri.isNotEmpty()) {
            println(
                "  diametro dei gruppi riuniti: mediana ${diametri[diametri.size / 2].toInt()} m, " +
                    "massimo ${diametri.last().toInt()} m",
            )
        }
        println()

        // Un nome preciso, quando serve guardare un caso visto sul telefono.
        if (args.size > 1) {
            // Il nome sta negli argomenti rimasti: i nomi delle fermate hanno
            // gli spazi dentro, e --args di Gradle li spezza comunque.
            val nome = args.drop(1).joinToString(" ")
            val wanted = dev.antigravity.fluidtransit.routing.Relevance.normalize(nome)
            val match = (0 until n).filter {
                dev.antigravity.fluidtransit.routing.Relevance.normalize(r.stopName(it)) == wanted
            }
            println("fermate chiamate esattamente \"$nome\": ${match.size}")
            for (a in match) {
                val vicina = match.filter { it != a }.minOfOrNull { b ->
                    BundleReader.haversine(
                        r.stopLat(a), r.stopLon(a), r.stopLat(b), r.stopLon(b),
                    ).toInt()
                }
                // L'hash e' l'indirizzo della fermata: e' quello che
                // finisce in un widget o in un deep link, e sopravvive al
                // cambio notturno di bundle mentre l'indice no.
                println(
                    "  [$a] gruppo ${groups.groupOf(a)} " +
                        "(${groups.siblings(a).size} banchine)  " +
                        "piu' vicina omonima: ${vicina ?: "-"} m  " +
                        "hash ${java.lang.Long.toHexString(r.stopIdHash(a))}",
                )
            }
            println()
        }

        // I dieci casi peggiori, per capire cosa sono davvero.
        println("i nomi piu' ripetuti:")
        duplicated.entries.sortedByDescending { it.value.size }.take(10).forEach { (name, group) ->
            var max = 0.0
            for (a in group.indices) {
                for (b in a + 1 until group.size) {
                    val d = BundleReader.haversine(
                        r.stopLat(group[a]), r.stopLon(group[a]),
                        r.stopLat(group[b]), r.stopLon(group[b]),
                    )
                    if (d > max) max = d
                }
            }
            val parented = group.count { r.stopParent(it) >= 0 }
            println(
                "  ${group.size}x  ${max.toInt().toString().padStart(6)} m  " +
                    "$parented col parent  $name",
            )
        }
    }
}
