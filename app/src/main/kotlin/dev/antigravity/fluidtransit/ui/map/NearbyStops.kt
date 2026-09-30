package dev.antigravity.fluidtransit.ui.map

import dev.antigravity.fluidtransit.routing.StopGroups

/**
 * Una fermata vera vicina: la banchina piu' vicina e la fermata che la
 * rappresenta.
 *
 * La distanza si misura dalla banchina piu' vicina ([nearest]), perche' e'
 * quella che si raggiunge; il nome, la chiave e le coordinate sono quelli del
 * rappresentante ([representative]), gli stessi che ha la stessa fermata
 * nella ricerca.
 */
internal class NearbyStop(val nearest: Int, val representative: Int)

/**
 * Le prime [limit] fermate vere fra le banchine [ordered], dalla piu' vicina.
 *
 * "Fermate vicine" nella barra di ricerca prendeva le prime cinque BANCHINE.
 * Il 53% delle fermate condivide il nome con un'altra, e l'87% di quelle sta
 * entro 100 m (misurato il 15/09/2026): uscivano "TORRE GALLI - a 40 m" e
 * "TORRE GALLI - a 60 m" una sotto l'altra, e le cinque righe erano spesso
 * due o tre fermate vere. Non solo: la chiave era l'hash della banchina
 * scelta, mentre la ricerca usa quella del rappresentante del gruppo, quindi
 * la stessa fermata scelta da qui e poi dalla ricerca finiva due volte fra i
 * "Recenti", che deduplicano per chiave.
 *
 * Senza gruppi (non sono ancora arrivati) ogni banchina vale per se'.
 */
internal fun nearbyStops(ordered: List<Int>, groups: StopGroups?, limit: Int): List<NearbyStop> {
    val seen = HashSet<Long>()
    val out = ArrayList<NearbyStop>(limit)
    for (s in ordered) {
        val g = groups?.groupOf(s) ?: -1
        // Il gruppo se c'e'; se no la banchina, in uno spazio di chiavi a parte.
        val key = if (g >= 0) g.toLong() else -1L - s
        if (!seen.add(key)) continue
        val rep = if (g >= 0) groups?.members(g)?.firstOrNull() ?: s else s
        out += NearbyStop(nearest = s, representative = rep)
        if (out.size == limit) break
    }
    return out
}
