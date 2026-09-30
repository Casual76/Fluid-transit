package dev.antigravity.fluidtransit.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/**
 * Un titolo di sezione che TalkBack riconosce come titolo.
 *
 * Con la navigazione per titoli non si poteva saltare da una sezione
 * all'altra: eyebrow e titolo di `FluidSectionTitle` erano due testi
 * qualunque, letti uno dopo l'altro, e per arrivare ad "Avvisi" nella scheda
 * Oggi bisognava scorrere ogni riga delle partenze, ognuna spezzata in piu'
 * punti di lettura; nella schermata degli avvisi, ogni scheda che precede
 * "Nei prossimi giorni" — in un giorno di sciopero sono un centinaio.
 *
 * `FluidSectionTitle` e' dell'engine e non si tocca da qui, quindi il segno lo
 * mette chi lo usa: `mergeDescendants` fa dei due testi un solo nodo, cosi' si
 * sente "Avvisi In Toscana, titolo" e non due frasi da sole. Sta qui, in un
 * posto solo, e non copiata in ogni schermata: quando l'engine lo fara' da se'
 * si toglie questa funzione e basta.
 */
fun Modifier.titoloDiSezione(): Modifier =
    semantics(mergeDescendants = true) { heading() }
