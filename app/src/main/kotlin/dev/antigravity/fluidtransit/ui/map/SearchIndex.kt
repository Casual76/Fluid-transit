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
    /** L'indice della banchina che rappresenta il gruppo. */
    private val stopIndexes: IntArray,
    private val routeNames: Array<String>,
    private val routeNorm: Array<String>,
    private val routeNameEnd: IntArray,
    private val routeDest: Array<String>,
    private val routeColor: IntArray,
    /**
     * Dove portare la mappa quando si tocca una linea.
     *
     * Erano DUE array e un indice: il numero della prima fermata della
     * linea, con cui si andava a leggere `stopLat`. Ma `stopLat` e'
     * indicizzato per GRUPPO di fermate omonime, non per fermata: col
     * raggruppamento acceso — cioe' sempre, nell'app — quel numero
     * pescava le coordinate di un gruppo qualunque, e quando superava il
     * numero dei gruppi cadeva sul ripiego zero, che e' il Golfo di
     * Guinea. Effetto doppio: la mappa si apriva sul posto sbagliato, e
     * il premio per la vicinanza premiava la linea sbagliata — cercando
     * "6" da Firenze veniva su per prima quella di Empoli.
     *
     * Adesso sono coordinate vere, lette dal bundle una volta sola.
     */
    private val routeLat: DoubleArray,
    private val routeLon: DoubleArray,
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
        if (tokens.isEmpty()) return emptyList()
        val hasRef = !refLat.isNaN() && !refLon.isNaN()

        /**
         * Un carattere solo: si guardano SOLO le linee, e per la sigla.
         *
         * Prima un carattere solo non cercava niente, e la guardia aveva la
         * sua ragione — una lettera sola su 34.000 nomi di fermata pesa
         * l'indice intero per restituire mezza Toscana. Ma le linee a una
         * cifra esistono, sono fra le piu' usate di Firenze, e "6" e'
         * esattamente come una persona le chiama: chi scriveva 6 non trovava
         * niente e restava li' a chiedersi cosa avesse sbagliato.
         *
         * Le linee sono 946 e la sigla e' corta: cercarci dentro un
         * carattere costa niente, e il risultato e' preciso invece che
         * vago.
         */
        val soloSigla = tokens.sumOf { it.length } < 2

        // Linee e fermate nella STESSA sessione: cosi' la rarita' di una
        // parola si misura sull'intero insieme e i due punteggi sono
        // confrontabili fra loro e con quelli dei luoghi.
        fun sessione(fuzzy: Boolean): Relevance.Session {
            val s = Relevance.Session(tokens, fuzzy)
            for (i in routeNorm.indices) {
                // Con un carattere solo si guarda la sigla e non il
                // capolinea: "6" non deve tirare fuori ogni linea che passa
                // da "via 6 agosto".
                if (soloSigla) s.observe(i, routeShortOf(i), routeNameEnd[i])
                else s.observe(i, routeNorm[i], routeNameEnd[i])
            }
            if (soloSigla) return s
            for (i in stopNorm.indices) {
                s.observe(routeNorm.size + i, stopNorm[i], stopNorm[i].length)
            }
            return s
        }

        // Prima esatta. Se non trova NIENTE, si riprova tollerando un refuso
        // per parola: e' il momento in cui un refuso e' la spiegazione piu'
        // probabile, ed e' l'unico in cui vale la pena pagare una seconda
        // passata sull'indice. Allargare sempre farebbe uscire "Ponte" a chi
        // scrive "Fonte".
        val session = sessione(fuzzy = false).let {
            if (it.candidateCount > 0) it else sessione(fuzzy = true)
        }

        val keep = Relevance.TopK(limit)
        for (k in 0 until session.candidateCount) {
            val id = session.candidateId(k)
            val score = if (id < routeNorm.size) {
                session.score(
                    k, ROUTE_BONUS,
                    distance(hasRef, refLat, refLon, routeLat[id], routeLon[id]),
                )
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
                        lat = routeLat[id],
                        lon = routeLon[id],
                    ),
                )
            } else {
                val i = id - routeNorm.size
                out.add(Hit.Stop(stopNames[i], score, stopIndexes[i], stopLat[i], stopLon[i]))
            }
        }
        return out
    }

    /** La sola sigla della linea, senza il capolinea che le sta attaccato. */
    private fun routeShortOf(i: Int): String = routeNorm[i].substring(0, routeNameEnd[i])

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

        /**
         * L'indice si costruisce sui GRUPPI, non sulle banchine.
         *
         * Cercando "TORRE GALLI" uscivano due righe identiche — stesso nome,
         * stesso sottotitolo "Fermata" — e l'unico modo di scegliere era
         * provarne una. Sulla rete vera le fermate omonime sono il 53% del
         * totale, quindi non era un caso raro: era meta' delle ricerche.
         *
         * Una riga per gruppo, e toccandola si vede il tabellone di tutte le
         * sue banchine.
         */
        fun build(r: BundleReader, groups: dev.antigravity.fluidtransit.routing.StopGroups?): SearchIndex {
            val representatives = if (groups == null) {
                IntArray(r.stopCount) { it }
            } else {
                IntArray(groups.size) { groups.members(it).first() }
            }
            val nStops = representatives.size
            val stopNames = Array(nStops) { r.stopName(representatives[it]) }
            val stopNorm = Array(nStops) { Relevance.normalize(stopNames[it]) }
            val stopLat = DoubleArray(nStops) { r.stopLat(representatives[it]) }
            val stopLon = DoubleArray(nStops) { r.stopLon(representatives[it]) }

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
            // Un punto qualsiasi della linea per centrarci la mappa: la
            // prima fermata del suo primo pattern, letta dal bundle come
            // coordinate e non come numero di fermata. Il numero da solo non
            // bastava: piu' sotto si andava a cercarlo in un array
            // indicizzato per gruppo di omonime, che e' un'altra cosa.
            val routeLat = DoubleArray(nRoutes)
            val routeLon = DoubleArray(nRoutes)
            for (i in 0 until nRoutes) {
                val stop = r.patternsOfRoute(i).firstOrNull()?.let { p -> r.patternStop(p, 0) }
                if (stop != null && stop >= 0) {
                    routeLat[i] = r.stopLat(stop)
                    routeLon[i] = r.stopLon(stop)
                } else {
                    routeLat[i] = Double.NaN
                    routeLon[i] = Double.NaN
                }
            }
            return SearchIndex(
                stopNames, stopNorm, stopLat, stopLon, representatives,
                routeNames, routeNorm, routeNameEnd, routeDest, routeColor, routeLat, routeLon,
            )
        }
    }
}
