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
