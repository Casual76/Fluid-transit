package dev.antigravity.fluidtransit.ui.map

/**
 * Una richiesta alla mappa da un'altra scheda (Preferiti, Oggi): "aprimi
 * questa cosa". La shell cambia scheda e la mappa la consuma appena il
 * bundle e' pronto.
 */
sealed interface MapIntent {
    class Stop(val idHashHex: String, val name: String) : MapIntent
    class Route(val idHashHex: String) : MapIntent
    class Place(val name: String, val lat: Double, val lon: Double, val savedId: Long?) : MapIntent
}
