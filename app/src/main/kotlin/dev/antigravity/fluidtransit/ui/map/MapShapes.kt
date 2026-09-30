package dev.antigravity.fluidtransit.ui.map

/**
 * Le forme che la schermata chiede di disegnare, dette in un modo che non
 * nomina nessun motore di mappa.
 *
 * Servono perche' la geometria del viaggio nasceva gia' impacchettata come
 * `FeatureCollection` di MapLibre: chi la costruiva doveva conoscere il
 * motore, e cambiare motore voleva dire riscrivere anche chi la costruisce.
 * Qui si dice soltanto "questa linea, di questo colore, tratteggiata o no",
 * e la traduzione la fa la mappa.
 */

/** Una polilinea. Le coordinate stanno in due array paralleli: niente oggetti per vertice. */
class MapLine(
    val lat: DoubleArray,
    val lon: DoubleArray,
    /** Colore RGB della linea. Per le camminate non si usa: le disegna grigie. */
    val colorRgb: Int = 0,
    /** Le camminate sono tratteggiate, le tratte in bus piene. */
    val dashed: Boolean = false,
) {
    val size: Int get() = lat.size
}

/** Il viaggio da mostrare: le tratte in bus e le camminate, in ordine. */
class JourneyShape(val lines: List<MapLine>) {
    val isEmpty: Boolean get() = lines.isEmpty()

    companion object {
        val EMPTY = JourneyShape(emptyList())
    }
}

/**
 * Cosa accendere sulla mappa mentre si viaggia, detto senza nominare ne'
 * gli indici del bundle ne' il motore di mappa.
 *
 * La modalita' linea — quella che si ottiene toccando un bus — sa una cosa
 * sola: "questa linea intera". In navigazione non basta: andata e ritorno
 * della stessa linea sono la stessa linea, e vedersi accendere anche il
 * verso opposto mentre si sta andando da qualche parte e' esattamente il
 * rumore che si voleva togliere.
 */
class NavMapFocus(
    /** La linea che sto prendendo. */
    val routeHashHex: String,
    /** Le SOLE fermate fra dove salgo e dove scendo. */
    val stopHashes: Array<String>,
    /** Le linee che vanno bene lo stesso, la mia compresa. */
    val usefulRouteHashes: Array<String>,
    /**
     * Le altre linee si guardano mentre si aspetta, non mentre si e' a
     * bordo: una volta saliti non servono piu' a niente e tolgono spazio
     * alla sola cosa che conta, cioe' dove scendere.
     */
    val showUseful: Boolean,
    val colorRgb: Int,
)

/**
 * La tratta in corso, tagliata all'ascissa del mezzo.
 *
 * Tre vestiti diversi, non tre opacita': quello che il bus ha gia' fatto e'
 * grigio, quello che gli resta e' del colore della linea, e il tratto che
 * deve ancora fare per arrivare da me e' tratteggiato.
 */
class NavTrail(
    /** Da dove sta il bus fino a dove scendo. */
    val remaining: MapLine?,
    /** Da dove salgo fino a dove sta il bus. */
    val done: MapLine?,
    /** Da dove sta il bus fino a dove salgo, quando deve ancora arrivare. */
    val approach: MapLine?,
) {
    val isEmpty: Boolean get() = remaining == null && done == null && approach == null
}
