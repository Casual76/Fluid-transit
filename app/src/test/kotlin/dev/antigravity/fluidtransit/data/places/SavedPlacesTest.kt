package dev.antigravity.fluidtransit.data.places

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I posti salvati: casa, lavoro, quello che uno decide.
 *
 * Come le stelle, sono roba scritta dall'utente, e come le stelle non avevano
 * una riga che dicesse che tornano indietro com'erano.
 */
class SavedPlacesTest {

    private val tmp = ArrayList<File>()

    private fun nuovoFile(): File =
        File.createTempFile("posti", ".json").also { tmp.add(it); it.delete() }

    @After
    fun pulisci() {
        tmp.forEach { it.delete() }
    }

    @Test
    fun `senza file non ci sono posti`() {
        assertTrue(SavedPlaces(nuovoFile()).load().isEmpty())
    }

    @Test
    fun `un posto salvato torna indietro com'era`() {
        val f = nuovoFile()
        SavedPlaces(f).add("Casa", 43.771389, 11.254167)
        val e = SavedPlaces(f).load().single()
        assertEquals("Casa", e.label)
        assertEquals(43.771389, e.lat, 1e-9)
        assertEquals(11.254167, e.lon, 1e-9)
    }

    @Test
    fun `due posti convivono, e si tolgono uno alla volta`() {
        val f = nuovoFile()
        val s = SavedPlaces(f)
        s.add("Casa", 43.1, 11.1)
        s.add("Lavoro", 43.2, 11.2)
        val posti = SavedPlaces(f).load()
        assertEquals(2, posti.size)
        SavedPlaces(f).remove(posti.first().id)
        assertEquals(1, SavedPlaces(f).load().size)
    }

    @Test
    fun `un file illeggibile vale come nessun posto`() {
        val f = File.createTempFile("posti", ".json").also { tmp.add(it) }
        f.writeText("{}")
        assertTrue(SavedPlaces(f).load().isEmpty())
    }
}
