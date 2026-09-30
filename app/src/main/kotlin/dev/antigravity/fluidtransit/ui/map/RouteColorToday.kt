package dev.antigravity.fluidtransit.ui.map

import dev.antigravity.fluidtransit.routing.BundleReader

/**
 * Il colore di una linea salvata, come e' OGGI.
 *
 * Preferiti e ricerche recenti si ricordano il colore del giorno in cui li hai
 * salvati, ma quel colore non e' della linea: e' dell'assegnazione di quella
 * notte, e il bundler lo sposta ancora quando una linea prende un incrocio
 * nuovo (prima del 16/09 ne spostava 98 su 946 a ogni bundle). Il risultato
 * era una linea con due tinte a seconda di dove la guardavi — la pastiglia in
 * "Le tue linee" o fra i recenti in un colore, e la stessa linea sulla mappa,
 * nel tabellone e nella scheda in un altro — cioe' proprio la divergenza che
 * la colorazione con `FT_BUNDLE_PRECEDENTE` doveva evitare.
 *
 * Quello che vale e' il colore del bundle, per HASH come tutto cio' che si
 * salva. Il colore salvato resta solo come ripiego: quando gli orari non sono
 * ancora pronti, o quando la linea negli orari di oggi non c'e' piu', e una
 * pastiglia con la tinta di ieri e' meglio di una senza.
 */
internal fun routeColorToday(reader: BundleReader?, idHashHex: String, saved: Int): Int {
    if (reader == null) return saved
    // L'hash si salva in esadecimale senza segno; qui torna il Long a 64 bit
    // con cui il bundle lo indicizza.
    val hash = idHashHex.toULongOrNull(16)?.toLong() ?: return saved
    val idx = reader.findRouteByIdHash(hash)
    if (idx < 0) return saved
    return reader.routeDisplayColor(idx) and 0xFFFFFF
}
