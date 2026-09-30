package dev.antigravity.fluidtransit.ui.map

/**
 * Dove porta un suggerimento della ricerca.
 *
 * Il tipo di un suggerimento e' una stringa — "stop", "route", "place",
 * "civic", "saved" — e la scelta di cosa aprire era scritta come "se e' un
 * luogo o un posto salvato…", "se e' una fermata…", "altrimenti e' una
 * linea". I civici non sono nessuno dei primi tre, quindi finivano nel ramo
 * delle linee; la chiave di un civico sono le sue coordinate, e leggere
 * "43.77139,11.25417" come un hash esadecimale da' null. Risultato: si
 * cercava "via Pisana 5", si toccava l'indirizzo giusto, e non succedeva
 * niente. La ricerca dei civici arrivava fino a un passo dalla fine e si
 * fermava li'.
 *
 * Qui i casi si nominano tutti, e il ramo di riserva e' il luogo: a un luogo
 * servono un nome e due coordinate, e quelle ci sono su ogni suggerimento.
 * Un tipo nuovo che nessuno ha ancora pensato si apre come un punto sulla
 * mappa, che e' sempre meglio di niente.
 */
internal enum class SuggestionTarget { STOP, ROUTE, PLACE }

internal fun targetOf(kind: String): SuggestionTarget = when (kind) {
    "stop" -> SuggestionTarget.STOP
    "route" -> SuggestionTarget.ROUTE
    else -> SuggestionTarget.PLACE
}

/**
 * Un suggerimento che puo' diventare un PUNTO del pianificatore.
 *
 * Nel pianificatore la ricerca compila "Da" o "A": servono posti. Una linea
 * non e' un posto, ma l'elenco la offriva in testa (scrivendo "23", o fra le
 * "Linee recenti"), e toccarla faceva `PlaceRef(titolo, sottotitolo, lat,
 * lon)` con le coordinate del primo capolinea del primo pattern: "Dove vai?
 * -> 23", e il viaggio calcolato verso un capolinea a caso, o verso NaN per
 * una linea senza pattern. Vale anche per un punto senza coordinate vere.
 */
internal fun Suggestion.isPlannerPoint(): Boolean =
    kind != "route" && lat.isFinite() && lon.isFinite()
