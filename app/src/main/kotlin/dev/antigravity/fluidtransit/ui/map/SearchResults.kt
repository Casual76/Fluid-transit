package dev.antigravity.fluidtransit.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import dev.antigravity.fluidtransit.data.places.PlacesManager
import dev.antigravity.fluidtransit.routing.BundleReader
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
): List<Suggestion> {
    val placesReady = places as? PlacesManager.State.Ready
    // La posizione si legge quando serve, non quando si compone.
    val dove by rememberUpdatedState(reference)

    val rapidi by produceState(
        initialValue = emptyList<Suggestion>(),
        query, searchIndex, placesReady, reader,
    ) {
        if (query.length < MIN_QUERY) {
            value = emptyList()
            return@produceState
        }
        delay(FAST_DEBOUNCE_MS)
        val ref = dove()
        value = withContext(Dispatchers.Default) {
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
                            "Fermata · a " + dev.antigravity.fluidtransit.routing.Words.distance(
                                dev.antigravity.fluidtransit.routing.BundleReader
                                    .haversine(rLat, rLon, hit.lat, hit.lon),
                            )
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
            val luoghi = placesReady?.search?.fast(query, PLACE_LIMIT, rLat, rLon).orEmpty().map { h ->
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
    }

    val civici by produceState(initialValue = emptyList<Suggestion>(), query, placesReady) {
        value = emptyList()
        // Un civico ha un numero dentro, e una via da sola non e' un civico:
        // chiedere l'indice dei civici per "via roma" costa e non serve.
        if (placesReady == null || query.length < MIN_CIVIC_QUERY || query.none { it.isDigit() }) {
            return@produceState
        }
        delay(SLOW_DEBOUNCE_MS)
        val ref = dove()
        value = withContext(Dispatchers.Default) {
            placesReady.search.civici(
                query,
                CIVIC_LIMIT,
                ref?.first ?: Double.NaN,
                ref?.second ?: Double.NaN,
            ).map { h ->
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
    }

    return (rapidi + civici).sortedByDescending { it.score }
}

/** Sotto due lettere qualunque cosa somiglia a qualunque altra. */
private const val MIN_QUERY = 2

/** Scrivere e' un gesto continuo: si aspetta la fine della parola. */
private const val FAST_DEBOUNCE_MS = 160L

/** I civici costano: si aspetta di piu', e solo se sembra un indirizzo. */
private const val SLOW_DEBOUNCE_MS = 350L
private const val MIN_CIVIC_QUERY = 5

private const val TRANSIT_LIMIT = 25
private const val PLACE_LIMIT = 14
private const val CIVIC_LIMIT = 6
