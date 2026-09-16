package dev.antigravity.fluidtransit.ui.map

/**
 * Una richiesta alla mappa da un'altra scheda (Preferiti, Oggi) o da fuori
 * dall'app (un widget, una notifica, un deep link): "aprimi questa cosa".
 * La shell cambia scheda e la mappa la consuma appena il bundle e' pronto.
 */
sealed interface MapIntent {
    /**
     * Portami sulla mappa, senza aprire niente.
     *
     * Serve alle schede vuote: "Oggi si costruisce dai preferiti" e "Niente
     * di salvato" spiegavano il gesto — stella una fermata — ma lasciavano a
     * chi legge il compito di trovare da solo dove si fa. Una scheda vuota
     * che nomina un gesto e non ci porta e' meta' di una risposta.
     */
    data object Home : MapIntent

    class Stop(val idHashHex: String, val name: String) : MapIntent
    class Route(val idHashHex: String) : MapIntent
    class Place(val name: String, val lat: Double, val lon: Double, val savedId: Long?) : MapIntent

    /**
     * Un viaggio gia' deciso: la routine che ti ha appena avvisato, aperta
     * sul pianificatore con la sua partenza e il suo arrivo.
     *
     * `fromLat`/`fromLon` nulli vogliono dire "da dove sei adesso", che e'
     * la stessa convenzione di `originRef` dentro la mappa.
     */
    class Journey(
        val fromLat: Double?,
        val fromLon: Double?,
        val toLat: Double,
        val toLon: Double,
        val toName: String,
    ) : MapIntent
}
