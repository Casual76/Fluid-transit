package dev.antigravity.fluidtransit.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import dev.antigravity.fluidtransit.data.places.PlacesManager
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Relevance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Cosa esce scrivendo nella barra.
 *
 * Stava dentro MapScreen, che e' il file piu' toccato del repo; qui sta da
 * solo perche' la sua interfaccia e' piccola davvero — quello che scrivi,
 * l'indice, i luoghi, e da dove si pesa la vicinanza — e perche' e' l'unica
 * parte della schermata che si puo' leggere senza avere in testa il resto.
 *
 * Due stadi, e sono deliberati:
 *
 * **Il rapido.** Fermate e linee (indice in memoria) piu' i luoghi. Aspetta
 * 160 ms perche' scrivere e' un gesto continuo: riscandire l'indice a ogni
 * lettera e' lavoro buttato per tutte le lettere tranne l'ultima.
 *
 * **Il lento.** I civici, che costano di piu' e servono solo quando si sta
 * scrivendo un indirizzo. Parte dopo, e i suoi risultati si AGGIUNGONO a
 * quelli gia' a schermo invece di sostituirli: chi ha gia' visto la sua
 * fermata non se la vede sparire sotto il dito.
 */
@Composable
internal fun rememberSearchResults(
    query: String,
    searchIndex: SearchIndex?,
    places: PlacesManager.State?,
    reader: BundleReader?,
    reference: () -> Pair<Double, Double>?,
): SearchOutcome {
    val placesReady = places as? PlacesManager.State.Ready
    // La posizione si legge quando serve, non quando si compone.
    val dove by rememberUpdatedState(reference)

    val rapidi by produceState(
        initialValue = Computed("", emptyList()),
        query, searchIndex, placesReady, reader,
    ) {
        // Un carattere basta, ed e' il caso piu' comune che non funzionava.
        //
        // Il commento qui sotto diceva gia' "se scrivi 6 viene su la linea",
        // ma questa guardia fermava tutto prima: chi scriveva 6 — che e'
        // esattamente come si chiamano le linee a una cifra, fra le piu'
        // usate di Firenze — vedeva i recenti e le fermate vicine, cioe' la
        // risposta a una domanda che non aveva fatto. Il minimo resta per i
        // LUOGHI, che sono mezzo milione e su un carattere non direbbero
        // niente di utile.
        if (query.isEmpty()) {
            value = Computed(query, emptyList())
            return@produceState
        }
        // Se cambia un ingresso che non e' la query (l'indice, i luoghi che
        // diventano pronti, il bundle di ieri/oggi) il risultato in mano e'
        // della query di adesso ma non piu' di questi ingressi: senza, la
        // ricerca risultava finita mentre ricalcolava, e con la lista vuota
        // la barra diceva "Niente con questo nome" prima del risultato. Le
        // righe restano a schermo; si marca solo che non e' ancora valido.
        if (value.forQuery == query) value = Computed("", value.items)
        delay(FAST_DEBOUNCE_MS)
        val ref = dove()
        val placeQueries = placeQueriesOf(query)
        val items = withContext(Dispatchers.Default) {
            val rLat = ref?.first ?: Double.NaN
            val rLon = ref?.second ?: Double.NaN
            val transit = searchIndex?.search(query, TRANSIT_LIMIT, rLat, rLon).orEmpty().map { hit ->
                when (hit) {
                    is SearchIndex.Hit.Stop -> Suggestion(
                        kind = "stop",
                        key = reader?.let { java.lang.Long.toHexString(it.stopIdHash(hit.stopIndex)) }
                            ?: "",
                        title = hit.title,
                        // Quanto e' lontana, quando c'e' da dove misurare.
                        //
                        // Cercando "San Marco" uscivano due righe identiche,
                        // "SAN MARCO · Fermata" e "SAN MARCO · Fermata": sono
                        // due paesi diversi a settanta chilometri l'uno
                        // dall'altro, e l'unico modo di sceglierne una era
                        // provarla. I luoghi il loro contesto ce l'hanno gia'
                        // ("Firenze"); le fermate no, e la distanza e' il
                        // contesto che l'app puo' dare senza inventarsi
                        // niente.
                        subtitle = if (ref != null) {
                            dev.antigravity.fluidtransit.routing.Words.distanceNear(
                                dev.antigravity.fluidtransit.routing.BundleReader
                                    .haversine(rLat, rLon, hit.lat, hit.lon),
                            )?.let { "Fermata · a $it" } ?: "Fermata"
                        } else {
                            "Fermata"
                        },
                        colorRgb = 0,
                        lat = hit.lat,
                        lon = hit.lon,
                        score = hit.score,
                    )

                    is SearchIndex.Hit.Route -> Suggestion(
                        kind = "route",
                        key = reader?.let { java.lang.Long.toHexString(it.routeIdHash(hit.routeIndex)) }
                            ?: "",
                        title = hit.title,
                        subtitle = hit.destination,
                        colorRgb = hit.colorRgb,
                        lat = hit.lat,
                        lon = hit.lon,
                        score = hit.score,
                    )
                }
            }
            val luoghi = placeQueries
                .filter { it.length >= MIN_PLACE_QUERY }
                .flatMap { placesReady?.search?.fast(it, PLACE_LIMIT, rLat, rLon).orEmpty() }
                .distinctBy { "${it.kind}|${it.name}|${it.context}" }
                .sortedByDescending { it.score }
                .take(PLACE_LIMIT)
                .map { h ->
                Suggestion(
                    kind = "place",
                    key = "%.5f,%.5f".format(h.lat, h.lon),
                    title = h.name,
                    subtitle = h.context.ifEmpty { "Luogo" },
                    colorRgb = 0,
                    lat = h.lat,
                    lon = h.lon,
                    score = h.score,
                )
            }
            // Una lista sola, ordinata per quanto c'entra: se scrivi
            // "esselunga" viene su il supermercato, se scrivi "6" viene su la
            // linea. L'icona di ogni riga dice cos'e'.
            transit + luoghi
        }
        // Il risultato porta con se' per QUALE query e' stato calcolato: e'
        // l'unico modo di distinguere "non c'e' niente" da "non ho ancora
        // finito" (vedi SearchOutcome).
        value = Computed(query, items)
    }

    val civiciApply = placeQueriesOf(query).any { civicSearchApplies(it, placesReady != null) }
    val civici by produceState(initialValue = Computed("", emptyList()), query, placesReady) {
        value = Computed("", emptyList())
        // Un civico ha un numero dentro, e una via da sola non e' un civico:
        // chiedere l'indice dei civici per "via roma" costa e non serve.
        val civicQueries = placeQueriesOf(query).filter { civicSearchApplies(it, true) }
        if (placesReady == null || civicQueries.isEmpty()) {
            value = Computed(query, emptyList())
            return@produceState
        }
        delay(SLOW_DEBOUNCE_MS)
        val ref = dove()
        val items = withContext(Dispatchers.Default) {
            civicQueries.flatMap {
                placesReady.search.civici(
                    it,
                    CIVIC_LIMIT,
                    ref?.first ?: Double.NaN,
                    ref?.second ?: Double.NaN,
                )
            }.distinctBy { "${it.name}|${it.context}" }
                .sortedByDescending { it.score }
                .take(CIVIC_LIMIT)
                .map { h ->
                Suggestion(
                    kind = "civic",
                    key = "%.5f,%.5f".format(h.lat, h.lon),
                    title = h.name,
                    subtitle = h.context.ifEmpty { "Indirizzo" },
                    colorRgb = 0,
                    lat = h.lat,
                    lon = h.lon,
                    score = h.score,
                )
            }
        }
        value = Computed(query, items)
    }

    return SearchOutcome(
        items = (rapidi.items + civici.items).sortedByDescending { it.score },
        searching = isSearching(query, rapidi.forQuery, civici.forQuery, civiciApply),
    )
}

/**
 * Cosa e' uscito dalla ricerca, e se sta ancora lavorando.
 *
 * Senza `searching` una lista vuota voleva dire due cose: "non c'e' niente"
 * e "non ho ancora finito". Scrivendo la prima lettera il primo frame ha
 * la query piena e i risultati vuoti, e la barra diceva subito "Niente con
 * questo nome" — per tutta la raffica di digitazione (il debounce riparte a
 * ogni tasto) e di nuovo quando la query arriva intera dal microfono o da
 * un chip dell'assistente, finche' non finiva la scansione dei luoghi e la
 * seconda passata tollerante ai refusi. E' la lista vuota letta come
 * buona notizia, col ritardo che e' nostro.
 */
internal class SearchOutcome(
    val items: List<Suggestion>,
    /** I risultati sono di un'altra query, o quelli dei civici non sono ancora arrivati. */
    val searching: Boolean,
)

private class Computed(val forQuery: String, val items: List<Suggestion>)

/**
 * Le query che vanno ai luoghi e ai civici: senza "linea", "fermata", "bus",
 * E con la forma intera.
 *
 * "fermata careggi" arrivava ai luoghi con una parola che non sta in nessun
 * nome, e "bus 23" diventava una ricerca di vie che si chiamano "bus". Ma
 * "linea gotica", "terminal bus" e "via della corsa" hanno la parola-tipo
 * DENTRO il nome: spogliarla e basta (e non cercare niente quando c'era una
 * parola di linea) rispondeva "Niente con questo nome" per un luogo o una via
 * che esistono. Quindi si cercano tutte e due le forme e i risultati si
 * uniscono; l'unico caso in cui i luoghi non si cercano e' "linea 23", dove
 * il resto e' una sigla e un luogo con un 23 dentro sarebbe rumore.
 */
internal fun placeQueriesOf(query: String): List<String> {
    val tokens = Relevance.tokens(query)
    val hints = Relevance.kindHints(tokens)
    if (hints.rest.size == tokens.size) return listOf(query)
    if (hints.routesOnly && hints.restIsRouteCode) return emptyList()
    return listOf(hints.text, query).distinct()
}

/** Il passo dei civici si fa solo per qualcosa che somiglia a un indirizzo. */
internal fun civicSearchApplies(query: String, placesReady: Boolean): Boolean =
    placesReady && query.length >= MIN_CIVIC_QUERY && query.any { it.isDigit() }

/**
 * Sta ancora cercando? Vero finche' il risultato dei rapidi non e' della
 * query di adesso, o quello dei civici — se si applicano — non lo e'.
 */
internal fun isSearching(
    query: String,
    rapidiFor: String,
    civiciFor: String,
    civiciApply: Boolean,
): Boolean = query.isNotEmpty() && (rapidiFor != query || (civiciApply && civiciFor != query))

/** Sotto due lettere qualunque cosa somiglia a qualunque altra. */
/** Sotto due caratteri i luoghi non dicono niente: sono mezzo milione. */
private const val MIN_PLACE_QUERY = 2

/** Scrivere e' un gesto continuo: si aspetta la fine della parola. */
private const val FAST_DEBOUNCE_MS = 160L

/** I civici costano: si aspetta di piu', e solo se sembra un indirizzo. */
private const val SLOW_DEBOUNCE_MS = 350L
private const val MIN_CIVIC_QUERY = 5

private const val TRANSIT_LIMIT = 25
private const val PLACE_LIMIT = 14
private const val CIVIC_LIMIT = 6
