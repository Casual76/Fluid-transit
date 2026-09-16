package dev.antigravity.fluidtransit.data.favorites

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le stelle: l'unica cosa in quest'app che l'utente ha creato a mano.
 *
 * Fino a stanotte non c'era una riga che dicesse che sopravvivono a un giro
 * di scrittura e rilettura. La scrittura e' appena diventata atomica per non
 * poterle perdere a meta'; qui si controlla l'altra meta', cioe' che quello
 * che si rilegge sia quello che si e' messo.
 */
class FavoritesTest {

    private val tmp = ArrayList<File>()

    /** Un archivio nuovo, su un file che ancora non esiste. */
    private fun nuovoFile(): File =
        File.createTempFile("favorites", ".json").also { tmp.add(it); it.delete() }

    @After
    fun pulisci() {
        tmp.forEach { it.delete() }
    }

    @Test
    fun `senza file non ci sono preferiti, e non e' un errore`() {
        val s = Favorites(nuovoFile())
        assertTrue(s.stops().isEmpty())
        assertTrue(s.routes().isEmpty())
        assertFalse(s.isStopFavorite("abc"))
    }

    @Test
    fun `una stella messa si rilegge`() {
        val s = Favorites(nuovoFile())
        s.toggleStop("e76ddd4605d9f3d5", "SODERINI TORRINO SANTA ROSA")
        assertEquals(1, s.stops().size)
        assertEquals("SODERINI TORRINO SANTA ROSA", s.stops().first().name)
        assertTrue(s.isStopFavorite("e76ddd4605d9f3d5"))
    }

    @Test
    fun `la stella si toglie toccandola di nuovo`() {
        val s = Favorites(nuovoFile())
        s.toggleStop("abc", "Fermata")
        s.toggleStop("abc", "Fermata")
        assertTrue(s.stops().isEmpty())
    }

    @Test
    fun `quello che c'e' sul disco lo legge anche un'altra istanza`() {
        // E' il caso vero: il widget e l'app sono due lettori diversi dello
        // stesso file, e la copia in memoria di uno non aiuta l'altro.
        val f = nuovoFile()
        Favorites(f).toggleStop("abc", "Piazza Alfa")
        val altro = Favorites(f)
        assertEquals(1, altro.stops().size)
        assertEquals("Piazza Alfa", altro.stops().first().name)
    }

    @Test
    fun `le linee stanno accanto alle fermate senza pestarsi`() {
        val f = nuovoFile()
        val s = Favorites(f)
        s.toggleStop("s1", "Fermata")
        s.toggleRoute("r1", "C4", 0x1E88E5)
        val riletto = Favorites(f)
        assertEquals(1, riletto.stops().size)
        assertEquals(1, riletto.routes().size)
        assertEquals("C4", riletto.routes().first().shortName)
        assertEquals(0x1E88E5, riletto.routes().first().colorRgb)
    }

    @Test
    fun `gli accenti e gli apostrofi dei nomi toscani sopravvivono`() {
        val f = nuovoFile()
        Favorites(f).toggleStop("h", "SANT'ONOFRIO · Città")
        assertEquals("SANT'ONOFRIO · Città", Favorites(f).stops().first().name)
    }

    @Test
    fun `un file illeggibile non fa cadere l'app`() {
        // Il caso che la scrittura atomica rende quasi impossibile, ma che
        // resta possibile per i file scritti prima: meglio nessuna stella che
        // un errore in faccia mentre si apre la scheda Oggi.
        val f = File.createTempFile("favorites", ".json").also { tmp.add(it) }
        f.writeText("{ meta' di un json")
        assertTrue(Favorites(f).stops().isEmpty())
    }

    @Test
    fun `togliere l'ultima stella lascia un archivio vuoto, non un file rotto`() {
        val f = nuovoFile()
        val s = Favorites(f)
        s.toggleStop("a", "Uno")
        s.toggleStop("a", "Uno")
        assertTrue("il file deve esserci", f.isFile)
        assertTrue(Favorites(f).stops().isEmpty())
    }

    @Test
    fun `la versione scatta a ogni modifica, cosi' le schermate si rifanno`() {
        val s = Favorites(nuovoFile())
        val prima = s.version.value
        s.toggleStop("a", "Uno")
        assertTrue("la versione non e' cambiata", s.version.value > prima)
    }
}
