package dev.antigravity.fluidtransit.ui.map

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo storico delle ricerche.
 *
 * E' un elenco di posti dove uno e' stato o dove vorrebbe andare: e' la cosa
 * piu' personale che l'app tiene, e le due regole che conta che siano giuste
 * sono "non cresce per sempre" e "si puo' svuotare".
 */
class RecentSearchesTest {

    private val tmp = ArrayList<File>()

    private fun nuovoFile(): File =
        File.createTempFile("recenti", ".json").also { tmp.add(it); it.delete() }

    @After
    fun pulisci() {
        tmp.forEach { it.delete() }
    }

    private fun voce(key: String, title: String = "Titolo", kind: String = "stop") =
        RecentSearches.Entry(kind, key, title, "Fermata", 0, 43.77, 11.25)

    @Test
    fun `l'ultima cercata sta in cima`() {
        val f = nuovoFile()
        val s = RecentSearches(f)
        s.add(voce("a", "Prima"))
        s.add(voce("b", "Seconda"))
        assertEquals(listOf("Seconda", "Prima"), RecentSearches(f).load().map { it.title })
    }

    @Test
    fun `ricercare la stessa cosa non la duplica, la risale`() {
        val f = nuovoFile()
        val s = RecentSearches(f)
        s.add(voce("a", "Alfa"))
        s.add(voce("b", "Beta"))
        s.add(voce("a", "Alfa"))
        val titoli = RecentSearches(f).load().map { it.title }
        assertEquals(listOf("Alfa", "Beta"), titoli)
    }

    @Test
    fun `lo storico non cresce per sempre`() {
        val f = nuovoFile()
        val s = RecentSearches(f)
        // Il tetto e' una decisione dell'archivio: qui si controlla solo che
        // esista e sia piccolo, non il numero esatto.
        repeat(40) { s.add(voce("k$it")) }
        val quanti = RecentSearches(f).load().size
        assertTrue("lo storico e' cresciuto senza tetto: $quanti", quanti in 1..20)
    }

    @Test
    fun `svuotare lo storico lo svuota davvero`() {
        val f = nuovoFile()
        val s = RecentSearches(f)
        s.add(voce("a"))
        s.clear()
        assertTrue(RecentSearches(f).load().isEmpty())
    }

    @Test
    fun `un file illeggibile vale come storico vuoto`() {
        val f = File.createTempFile("recenti", ".json").also { tmp.add(it) }
        f.writeText("[[[")
        assertTrue(RecentSearches(f).load().isEmpty())
    }

    @Test
    fun `un civico si salva e si rilegge come civico`() {
        // E' il tipo che per mesi si e' salvato e non si e' mai rivisto,
        // perche' il filtro dei recenti nominava solo fermate e luoghi.
        val f = nuovoFile()
        RecentSearches(f).add(voce("43.77139,11.25417", "Via Pisana 5", kind = "civic"))
        val e = RecentSearches(f).load().single()
        assertEquals("civic", e.kind)
        assertEquals("Via Pisana 5", e.title)
    }
}
