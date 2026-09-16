package dev.antigravity.fluidtransit.data.routines

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le routine: viaggi che l'utente ha descritto una volta e si aspetta che
 * l'app ricordi per sempre.
 *
 * Sono l'archivio piu' ricco dei tre — giorni della settimana, ancora,
 * coordinate, l'ultimo consiglio calcolato — e finora nessuna riga diceva che
 * torna indietro com'era andato dentro. Un campo perso qui vuol dire una
 * sveglia che non suona.
 */
class RoutinesTest {

    private val tmp = ArrayList<File>()

    private fun nuovoFile(): File =
        File.createTempFile("routines", ".json").also { tmp.add(it); it.delete() }

    @After
    fun pulisci() {
        tmp.forEach { it.delete() }
    }

    private fun esempio(id: Long = 7) = Routines.Routine(
        id = id,
        label = "Verso l'ospedale",
        fromLat = 43.8031,
        fromLon = 11.2469,
        toLat = 43.7704,
        toLon = 11.2392,
        toName = "Via Pisana 5",
        days = setOf(1, 2, 3, 4, 5),
        anchor = "arrive",
        anchorMinutes = 370,
        enabled = true,
    )

    @Test
    fun `senza file non ci sono routine`() {
        assertTrue(Routines(nuovoFile()).list().isEmpty())
    }

    @Test
    fun `una routine torna indietro con tutti i suoi campi`() {
        val f = nuovoFile()
        Routines(f).add(esempio())
        val r = Routines(f).list().single()
        assertEquals(7L, r.id)
        assertEquals("Verso l'ospedale", r.label)
        assertEquals("Via Pisana 5", r.toName)
        assertEquals(setOf(1, 2, 3, 4, 5), r.days)
        assertEquals("arrive", r.anchor)
        assertEquals(370, r.anchorMinutes)
        assertTrue(r.enabled)
        // Le coordinate al milionesimo: un arrotondamento qui sposta la
        // partenza di un isolato.
        assertEquals(43.8031, r.fromLat, 1e-9)
        assertEquals(11.2392, r.toLon, 1e-9)
    }

    @Test
    fun `salvare due volte la stessa routine non ne fa due`() {
        val f = nuovoFile()
        val s = Routines(f)
        s.add(esempio())
        s.add(
            esempio().let {
                Routines.Routine(
                    it.id, "Nuovo nome", it.fromLat, it.fromLon, it.toLat, it.toLon,
                    it.toName, it.days, it.anchor, it.anchorMinutes, it.enabled,
                )
            },
        )
        val liste = Routines(f).list()
        assertEquals(1, liste.size)
        assertEquals("Nuovo nome", liste.single().label)
    }

    @Test
    fun `una routine cancellata non torna`() {
        val f = nuovoFile()
        val s = Routines(f)
        s.add(esempio(1))
        s.add(esempio(2))
        s.remove(1)
        assertEquals(listOf(2L), Routines(f).list().map { it.id })
    }

    @Test
    fun `un file illeggibile non fa cadere le sveglie`() {
        val f = File.createTempFile("routines", ".json").also { tmp.add(it) }
        f.writeText("non sono un json")
        assertTrue(Routines(f).list().isEmpty())
    }
}
