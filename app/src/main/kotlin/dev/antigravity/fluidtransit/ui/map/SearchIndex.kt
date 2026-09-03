package dev.antigravity.fluidtransit.ui.map

import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Relevance

/**
 * La ricerca offline su fermate e linee, dal bundle.
 *
 * In memoria e senza sezione SEARCH: 29k nomi normalizzati stanno in un paio
 * di MB e si costruiscono in qualche decina di millisecondi una volta per
 * bundle.
 *
 * Dalla Fase 8 il punteggio e' quello condiviso di [Relevance], lo stesso
 * che pesa i luoghi: serve perche' i risultati finiscono in UNA lista sola,
 * ordinata per pertinenza, e prima invece fermate e luoghi venivano
 * incollati uno dopo l'altro — con i luoghi sistematicamente fuori schermo.
 * Prima la query veniva anche cercata come sottostringa INTERA, per cui
 * "stazione santa maria novella" non trovava la fermata che si chiama
 * "Santa Maria Novella Stazione".
 */
class SearchIndex private constructor(
    private val stopNames: Array<String>,
    private val stopNorm: Array<String>,
    private val stopLat: DoubleArray,
    private val stopLon: DoubleArray,
    private val routeNames: Array<String>,
    private val routeNorm: Array<String>,
    private val routeNameEnd: IntArray,
    private val routeDest: Array<String>,
    private val routeColor: IntArray,
    private val routeFirstStop: IntArray,
) {

    sealed interface Hit {
        val title: String
        val score: Int

        class Stop(
            override val title: String,
            override val score: Int,
            val stopIndex: Int,
            val lat: Double,
            val lon: Double,
        ) : Hit

        class Route(
            override val title: String,
            override val score: Int,
            val routeIndex: Int,
            val destination: String,
            val colorRgb: Int,
            val lat: Double,
            val lon: Double,
        ) : Hit
    }

    /**
     * [refLat]/[refLon] sono il punto da cui pesare la vicinanza: la tua
     * posizione, o il centro della mappa se l'hai spostata lontano.
     */
    fun search(
        query: String,
        limit: Int = 25,
        refLat: Double = Double.NaN,
        refLon: Double = Double.NaN,
    ): List<Hit> {
        val tokens = Relevance.tokens(query)
        if (tokens.isEmpty() || tokens.sumOf { it.length } < 2) return emptyList()
        val hasRef = !refLat.isNaN() && !refLon.isNaN()

        // Linee e fermate nella STESSA sessione: cosi' la rarita' di una
        // parola si misura sull'intero insieme e i due punteggi sono
        // confrontabili fra loro e con quelli dei luoghi.
        val session = Relevance.Session(tokens)
        for (i in routeNorm.indices) session.observe(i, routeNorm[i], routeNameEnd[i])
        for (i in stopNorm.indices) {
            session.observe(routeNorm.size + i, stopNorm[i], stopNorm[i].length)
        }

        val keep = Relevance.TopK(limit)
        for (k in 0 until session.candidateCount) {
            val id = session.candidateId(k)
            val score = if (id < routeNorm.size) {
                val lat = stopLat.getOrElse(routeFirstStop[id]) { 0.0 }
                val lon = stopLon.getOrElse(routeFirstStop[id]) { 0.0 }
                session.score(k, ROUTE_BONUS, distance(hasRef, refLat, refLon, lat, lon))
            } else {
                val i = id - routeNorm.size
                session.score(k, STOP_BONUS, distance(hasRef, refLat, refLon, stopLat[i], stopLon[i]))
            }
            keep.offer(id, score)
        }

        val out = ArrayList<Hit>(keep.size)
        keep.forEachByScore { id, score ->
            if (id < routeNorm.size) {
                out.add(
                    Hit.Route(
                        title = routeNames[id],
                        score = score,
                        routeIndex = id,
                        destination = routeDest[id],
                        colorRgb = routeColor[id],
                        lat = stopLat.getOrElse(routeFirstStop[id]) { 0.0 },
                        lon = stopLon.getOrElse(routeFirstStop[id]) { 0.0 },
                    ),
                )
            } else {
                val i = id - routeNorm.size
                out.add(Hit.Stop(stopNames[i], score, i, stopLat[i], stopLon[i]))
            }
        }
        return out
    }

    private fun distance(
        hasRef: Boolean,
        refLat: Double,
        refLon: Double,
        lat: Double,
        lon: Double,
    ): Double = if (hasRef) BundleReader.haversine(refLat, refLon, lat, lon) else -1.0

    companion object {
        /** Chi digita "23" vuole la linea 23 prima della fermata "via 23". */
        private const val ROUTE_BONUS = 6
        private const val STOP_BONUS = 5

        fun build(r: BundleReader): SearchIndex {
            val nStops = r.stopCount
            val stopNames = Array(nStops) { r.stopName(it) }
            val stopNorm = Array(nStops) { Relevance.normalize(stopNames[it]) }
            val stopLat = DoubleArray(nStops) { r.stopLat(it) }
            val stopLon = DoubleArray(nStops) { r.stopLon(it) }

            val nRoutes = r.routeCount
            val routeShort = Array(nRoutes) { Relevance.normalize(r.routeShortName(it)) }
            val routeNames = Array(nRoutes) { i ->
                val short = r.routeShortName(i)
                if (short.isNotEmpty()) short else r.routeLongName(i)
            }
            // Il nome della linea e' la sigla; il capolinea e' contorno, e
            // pesa meno — cosi' "6" batte "linea 12 per via del 6 agosto".
            val routeNorm = Array(nRoutes) { i ->
                Relevance.haystack(routeShort[i], Relevance.normalize(r.routeLongName(i)))
            }
            val routeNameEnd = IntArray(nRoutes) { routeShort[it].length }
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
                routeNames, routeNorm, routeNameEnd, routeDest, routeColor, routeFirstStop,
            )
        }
    }
}
