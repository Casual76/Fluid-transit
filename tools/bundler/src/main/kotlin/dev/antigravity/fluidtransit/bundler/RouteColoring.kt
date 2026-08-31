package dev.antigravity.fluidtransit.bundler

import dev.antigravity.fluidtransit.routing.Ftb

/**
 * L'assegnazione dei colori alle linee, condivisa fra il bundler (che la
 * scrive nel record ROUTES) e l'overlay (che la scrive nelle tile): stessa
 * funzione, stessi input, stessi colori ovunque.
 *
 * Il feed colora per categoria (~6 colori su 766 linee): inutilizzabile per
 * "ogni tratta col suo colore". La richiesta vera - decisa con l'utente -
 * non e' 766 colori distinti ma che 2-3 linee sovrapposte abbiano colori
 * nettamente diversi. E' una colorazione di grafo: due linee sono adiacenti
 * se condividono fermate, la palette ha 12 tinte tarate per la mappa, e
 * l'assegnazione e' deterministica - greedy per grado decrescente, pesata
 * sulle fermate condivise quando i vicini hanno gia' esaurito la palette.
 */
object RouteColoring {

    /**
     * Dodici tinte distinte, leggibili su basemap chiara e scura: sature ma
     * non fluorescenti, senza gialli pallidi (spariscono sul chiaro) e senza
     * blu notte (spariscono sullo scuro). Come interi 0xRRGGBB.
     */
    val PALETTE = intArrayOf(
        0xE5484D, // rosso
        0x2E90FA, // azzurro
        0x12A594, // verde acqua
        0xF76B15, // arancio
        0x8E4EC6, // viola
        0x5B9E31, // verde foglia
        0xE5006A, // magenta
        0x0B6BCB, // blu
        0xB8860B, // ocra
        0x00A2C7, // ciano
        0xD6409F, // rosa acceso
        0x7C66DC, // indaco
    )

    fun hex(paletteIndex: Int): String = "#%06X".format(PALETTE[paletteIndex])

    /**
     * Assegna un indice di palette a ogni linea.
     *
     * @param routeIds gli id GTFS, nell'ordine degli indici usati in [routesAtStop]
     * @param routesAtStop per ogni fermata, gli indici delle linee che la servono
     * @return per ogni linea, l'indice nella [PALETTE]
     */
    fun assign(routeIds: List<String>, routesAtStop: Iterable<Collection<Int>>): IntArray {
        // Il grafo di sovrapposizione, pesato: quante fermate condividono.
        val adjacency = HashMap<Long, Int>()
        for (s in routesAtStop) {
            val list = s.toIntArray().also { it.sort() }
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val key = (list[i].toLong() shl 32) or list[j].toLong()
                    adjacency[key] = (adjacency[key] ?: 0) + 1
                }
            }
        }
        val neighbors = Array(routeIds.size) { HashMap<Int, Int>() }
        for ((key, weight) in adjacency) {
            val a = (key ushr 32).toInt()
            val b = (key and 0xffffffff).toInt()
            neighbors[a][b] = weight
            neighbors[b][a] = weight
        }

        val colors = IntArray(routeIds.size) { -1 }
        val order = routeIds.indices.sortedWith(
            compareByDescending<Int> { neighbors[it].size }.thenBy { routeIds[it] },
        )
        for (r in order) {
            val usedWeight = IntArray(PALETTE.size)
            for ((n, w) in neighbors[r]) {
                val c = colors[n]
                if (c >= 0) usedWeight[c] += w
            }
            // Fra i colori liberi, il primo a partire da una posizione ruotata
            // sull'hash della linea: linee lontane e mai adiacenti non escono
            // cosi' tutte della prima tinta della palette.
            val start = ((Ftb.hash64(routeIds[r]) % PALETTE.size + PALETTE.size) % PALETTE.size).toInt()
            var best = -1
            for (k in PALETTE.indices) {
                val c = (start + k) % PALETTE.size
                if (usedWeight[c] == 0) {
                    best = c
                    break
                }
            }
            if (best < 0) {
                best = 0
                for (c in PALETTE.indices) if (usedWeight[c] < usedWeight[best]) best = c
            }
            colors[r] = best
        }
        return colors
    }

    /** Coppie adiacenti con lo stesso colore: la misura della promessa mantenuta. */
    fun conflicts(colors: IntArray, routesAtStop: Iterable<Collection<Int>>): Pair<Int, Int> {
        val seen = HashSet<Long>()
        var conflicts = 0
        for (s in routesAtStop) {
            val list = s.toIntArray().also { it.sort() }
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val key = (list[i].toLong() shl 32) or list[j].toLong()
                    if (seen.add(key) && colors[list[i]] == colors[list[j]]) conflicts++
                }
            }
        }
        return conflicts to seen.size
    }
}
