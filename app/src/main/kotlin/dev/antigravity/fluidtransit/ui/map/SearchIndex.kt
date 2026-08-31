package dev.antigravity.fluidtransit.ui.map

import dev.antigravity.fluidtransit.routing.BundleReader
import java.text.Normalizer

/**
 * La ricerca offline su fermate e linee, dal bundle.
 *
 * In memoria e senza sezione SEARCH: 29k nomi normalizzati stanno in un paio
 * di MB e si costruiscono in qualche decina di millisecondi una volta per
 * bundle. La sezione binaria arrivera' con la Fase 5, quando la ricerca
 * imparera' anche luoghi e indirizzi; l'interfaccia di questa classe non
 * dovra' cambiare.
 */
class SearchIndex private constructor(
    private val stopNames: Array<String>,
    private val stopNorm: Array<String>,
    private val stopLat: DoubleArray,
    private val stopLon: DoubleArray,
    private val routeNames: Array<String>,
    private val routeNorm: Array<String>,
    private val routeDest: Array<String>,
    private val routeColor: IntArray,
    private val routeFirstStop: IntArray,
) {

    sealed interface Hit {
        val title: String

        class Stop(
            override val title: String,
            val stopIndex: Int,
            val lat: Double,
            val lon: Double,
        ) : Hit

        class Route(
            override val title: String,
            val routeIndex: Int,
            val destination: String,
            val colorRgb: Int,
            val lat: Double,
            val lon: Double,
        ) : Hit
    }

    fun search(query: String, limit: Int = 25): List<Hit> {
        val q = normalize(query)
        if (q.length < 2) return emptyList()
        val prefix = ArrayList<Hit>()
        val contains = ArrayList<Hit>()

        // Prima le linee: sono poche e chi digita "23" vuole la linea 23.
        for (i in routeNorm.indices) {
            val at = routeNorm[i].indexOf(q)
            if (at < 0) continue
            val hit = Hit.Route(
                title = routeNames[i],
                routeIndex = i,
                destination = routeDest[i],
                colorRgb = routeColor[i],
                lat = stopLat.getOrElse(routeFirstStop[i]) { 0.0 },
                lon = stopLon.getOrElse(routeFirstStop[i]) { 0.0 },
            )
            if (at == 0) prefix.add(hit) else contains.add(hit)
            if (prefix.size + contains.size > limit * 2) break
        }
        for (i in stopNorm.indices) {
            if (prefix.size >= limit) break
            val at = stopNorm[i].indexOf(q)
            if (at < 0) continue
            val hit = Hit.Stop(stopNames[i], i, stopLat[i], stopLon[i])
            if (at == 0) prefix.add(hit) else contains.add(hit)
        }
        return (prefix + contains).take(limit)
    }

    companion object {
        fun build(r: BundleReader): SearchIndex {
            val nStops = r.stopCount
            val stopNames = Array(nStops) { r.stopName(it) }
            val stopNorm = Array(nStops) { normalize(stopNames[it]) }
            val stopLat = DoubleArray(nStops) { r.stopLat(it) }
            val stopLon = DoubleArray(nStops) { r.stopLon(it) }

            val nRoutes = r.routeCount
            val routeNames = Array(nRoutes) { i ->
                val short = r.routeShortName(i)
                if (short.isNotEmpty()) short else r.routeLongName(i)
            }
            val routeNorm = Array(nRoutes) { i ->
                normalize("${r.routeShortName(i)} ${r.routeLongName(i)}")
            }
            val routeDest = Array(nRoutes) { i ->
                r.routeLongName(i).ifEmpty { r.routeAgency(i) }
            }
            val routeColor = IntArray(nRoutes) { r.routeDisplayColor(it) }
            // Un punto qualsiasi della linea per centrarci la mappa: la prima
            // fermata del suo primo pattern.
            val routeFirstStop = IntArray(nRoutes) { i ->
                r.patternsOfRoute(i).firstOrNull()?.let { p -> r.patternStop(p, 0) } ?: 0
            }
            return SearchIndex(
                stopNames, stopNorm, stopLat, stopLon,
                routeNames, routeNorm, routeDest, routeColor, routeFirstStop,
            )
        }

        private fun normalize(s: String): String {
            val lower = s.lowercase().trim()
            val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
            return buildString(decomposed.length) {
                for (ch in decomposed) {
                    if (ch.category != CharCategory.NON_SPACING_MARK) append(ch)
                }
            }
        }
    }
}
