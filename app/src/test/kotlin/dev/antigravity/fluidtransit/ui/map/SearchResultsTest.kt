package dev.antigravity.fluidtransit.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Sta ancora cercando" contro "non c'e' niente": la barra dice
 * "Niente con questo nome" solo nel secondo caso.
 */
class SearchResultsTest {

    @Test
    fun `appena scrivi la prima lettera cerca ancora`() {
        // Il primo frame ha la query piena e i risultati della query di prima
        // (vuota): e' il momento in cui si leggeva "niente con questo nome".
        assertTrue(isSearching("a", rapidiFor = "", civiciFor = "", civiciApply = false))
    }

    @Test
    fun `una query che arriva intera cerca finche' i rapidi non la raggiungono`() {
        assertTrue(isSearching("farmacia comunale", rapidiFor = "farm", civiciFor = "", civiciApply = false))
        assertFalse(isSearching("farmacia comunale", rapidiFor = "farmacia comunale", civiciFor = "", civiciApply = false))
    }

    @Test
    fun `un indirizzo aspetta anche i civici`() {
        assertTrue(
            isSearching("via roma 12", rapidiFor = "via roma 12", civiciFor = "", civiciApply = true),
        )
        assertFalse(
            isSearching("via roma 12", rapidiFor = "via roma 12", civiciFor = "via roma 12", civiciApply = true),
        )
    }

    @Test
    fun `senza un indirizzo i civici non si aspettano`() {
        assertFalse(isSearching("duomo", rapidiFor = "duomo", civiciFor = "", civiciApply = false))
    }

    @Test
    fun `una barra vuota non cerca`() {
        assertFalse(isSearching("", rapidiFor = "", civiciFor = "", civiciApply = false))
    }

    @Test
    fun `i civici servono a un indirizzo e non a una via sola`() {
        assertTrue(civicSearchApplies("via roma 12", placesReady = true))
        assertFalse(civicSearchApplies("via roma", placesReady = true))
        assertFalse(civicSearchApplies("12", placesReady = true)) // troppo corta
        assertFalse(civicSearchApplies("via roma 12", placesReady = false))
    }

    @Test
    fun `i luoghi si cercano anche con la parola-tipo dentro il nome`() {
        // "linea 23": una sigla, i luoghi sarebbero rumore.
        assertEquals(emptyList<String>(), placeQueriesOf("linea 23"))
        // "linea gotica" e "via linea gotica 5": nomi veri, la forma intera
        // deve arrivare ai luoghi e ai civici.
        assertTrue("linea gotica" in placeQueriesOf("linea gotica"))
        assertTrue("via linea gotica 5" in placeQueriesOf("via linea gotica 5"))
        assertTrue("via gotica 5" in placeQueriesOf("via linea gotica 5"))
        // "fermata careggi": la forma spogliata va per prima.
        assertEquals("careggi", placeQueriesOf("fermata careggi").first())
        // Senza parole-tipo, la query com'e'.
        assertEquals(listOf("via roma 12"), placeQueriesOf("via roma 12"))
    }
}
