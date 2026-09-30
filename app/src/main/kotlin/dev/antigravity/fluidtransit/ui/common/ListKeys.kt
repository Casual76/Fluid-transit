package dev.antigravity.fluidtransit.ui.common

/**
 * Chiavi per una lista pigra: stabili quando le righe scorrono, e uniche
 * sempre.
 *
 * Senza chiavi, quando la prima partenza di un tabellone se ne va tutte le
 * righe sotto cambiano posizione, e Compose le ridisegna tutte come righe
 * nuove — a ogni battito, su ogni scheda aperta. Con le chiavi le righe si
 * riconoscono. Ma una `LazyColumn` con due chiavi uguali si chiude con
 * un'eccezione, e una riga doppia in un tabellone e' gia' capitata una volta
 * (la stessa corsa due volte al RISTORANTE LA BIANCA): qui le chiavi ripetute
 * prendono un numero d'ordine, invece di portare giu' la scheda.
 */
fun uniqueKeys(base: List<String>): List<String> {
    val seen = HashMap<String, Int>(base.size * 2)
    return base.map { k ->
        val n = seen.getOrDefault(k, 0)
        seen[k] = n + 1
        if (n == 0) k else "$k#$n"
    }
}
