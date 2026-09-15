package dev.antigravity.fluidtransit.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Dove porta un suggerimento della ricerca.
 *
 * Il difetto che questo inchioda non dava errori: si cercava "via Pisana 5",
 * l'app mostrava l'indirizzo giusto, lo si toccava, e non succedeva niente.
 * Il tipo e' una stringa, la scelta era scritta con un `else` che voleva dire
 * "linea", e i civici — che non sono ne' fermate ne' luoghi ne' posti salvati
 * — ci finivano dentro: la loro chiave sono due coordinate, letta come hash
 * esadecimale da' null, e il ramo non faceva niente e non lo diceva.
 */
class SuggestionTargetTest {

    @Test
    fun `i tre tipi che si aprono in modo diverso`() {
        assertEquals(SuggestionTarget.STOP, targetOf("stop"))
        assertEquals(SuggestionTarget.ROUTE, targetOf("route"))
        assertEquals(SuggestionTarget.PLACE, targetOf("place"))
    }

    @Test
    fun `un civico e' un posto sulla mappa, non una linea`() {
        assertEquals(SuggestionTarget.PLACE, targetOf("civic"))
    }

    @Test
    fun `un posto salvato e' un posto`() {
        assertEquals(SuggestionTarget.PLACE, targetOf("saved"))
    }

    @Test
    fun `un tipo che non esiste ancora si apre come un punto sulla mappa`() {
        // E' la ragione per cui la riserva e' il luogo: a un luogo servono un
        // nome e due coordinate, e quelle ci sono su ogni suggerimento. Un
        // vicolo cieco silenzioso, invece, non si vede finche' qualcuno non
        // prova proprio quel tipo.
        assertEquals(SuggestionTarget.PLACE, targetOf("quartiere"))
        assertEquals(SuggestionTarget.PLACE, targetOf(""))
    }
}
