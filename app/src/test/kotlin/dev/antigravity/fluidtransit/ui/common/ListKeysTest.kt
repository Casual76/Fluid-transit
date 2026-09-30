package dev.antigravity.fluidtransit.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class ListKeysTest {

    @Test
    fun `le chiavi diverse restano quelle`() {
        assertEquals(listOf("a", "b", "c"), uniqueKeys(listOf("a", "b", "c")))
    }

    @Test
    fun `una riga doppia non porta giu' la lista`() {
        // Due chiavi uguali in una LazyColumn sono un'eccezione, non una
        // riga in piu': la seconda prende un numero d'ordine.
        val keys = uniqueKeys(listOf("12-3", "12-3", "7-1", "12-3"))
        assertEquals(listOf("12-3", "12-3#1", "7-1", "12-3#2"), keys)
        assertEquals(keys.size, keys.toSet().size)
    }
}
