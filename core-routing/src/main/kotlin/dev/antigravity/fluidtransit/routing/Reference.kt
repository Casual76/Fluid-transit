package dev.antigravity.fluidtransit.routing

/**
 * Da dove si misura "vicino".
 *
 * Normalmente da dove sei. Ma se hai portato la mappa lontano da li', comanda
 * quello che stai guardando: cercare "via Roma" mentre si esplora Siena deve
 * dare le vie senesi, non quelle sotto casa.
 *
 * La regola era scritta tre volte con tre risposte diverse — i risultati
 * della ricerca la applicavano per intero, i recenti guardavano solo la
 * posizione, le fermate vicine solo la mappa — e i tre elenchi si vedono
 * nello stesso pannello, uno sotto l'altro. Tre modi di misurare in una
 * schermata sola vogliono dire che la stessa fermata compare a due distanze
 * diverse a due centimetri di distanza, che e' successo davvero.
 */
object Reference {

    /**
     * Oltre questo, la mappa vince sulla posizione.
     *
     * Venti chilometri: dentro quel raggio si e' ancora "dalle parti di
     * casa", e la mappa spostata di qualche isolato non deve cambiare da
     * dove si misura.
     */
    const val MAP_WINS_METERS = 20_000.0

    /**
     * @param here dove sei, secondo il GPS. Null se non lo sappiamo.
     * @param looking il centro della mappa. Null se non c'e' una mappa.
     */
    fun point(
        here: Pair<Double, Double>?,
        looking: Pair<Double, Double>?,
    ): Pair<Double, Double>? {
        if (here == null) return looking
        if (looking == null) return here
        val away = BundleReader.haversine(here.first, here.second, looking.first, looking.second)
        return if (away > MAP_WINS_METERS) looking else here
    }
}
