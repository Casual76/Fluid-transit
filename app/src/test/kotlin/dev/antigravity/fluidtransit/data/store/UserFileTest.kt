package dev.antigravity.fluidtransit.data.store

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le stelle dell'utente non si possono perdere a meta' di una scrittura.
 *
 * Il difetto che questo chiude non lascia tracce: `writeText` tronca il file
 * e poi ci scrive, e se il processo muore fra le due cose — un'app in secondo
 * piano che il sistema chiude, cioe' la normalita' — al riavvio il JSON non
 * si legge, il lettore risponde "nessun preferito" perche' e' scritto cosi',
 * e la prima stella toccata dopo riscrive il file sopra. Da fuori sembra solo
 * che l'app si sia dimenticata.
 *
 * Su file veri e non su finzioni: quello che si sta verificando e' proprio il
 * comportamento del filesystem.
 */
class UserFileTest {

    private val tmp = ArrayList<File>()

    private fun file(nome: String): File =
        File.createTempFile(nome, ".json").also { tmp.add(it) }

    @After
    fun pulisci() {
        tmp.forEach { it.delete() }
        tmp.forEach { File(it.parentFile, it.name + UserFile.PART_SUFFIX).delete() }
    }

    @Test
    fun `quello che si scrive si rilegge`() {
        val f = file("stelle")
        assertTrue(UserFile.writeAtomically(f, """{"stops":[]}"""))
        assertEquals("""{"stops":[]}""", f.readText())
    }

    @Test
    fun `riscrivere sostituisce, non aggiunge in coda`() {
        val f = file("stelle")
        UserFile.writeAtomically(f, "un contenuto piuttosto lungo, di sicuro piu' del prossimo")
        UserFile.writeAtomically(f, "corto")
        assertEquals("corto", f.readText())
    }

    @Test
    fun `gli accenti sopravvivono al giro`() {
        // I nomi delle fermate toscane ne sono pieni, e il file lo rilegge
        // `JSONObject`, che su byte non UTF-8 solleva invece di arrangiarsi.
        val f = file("stelle")
        val testo = """{"n":"Città, però — é è ì ò ù"}"""
        UserFile.writeAtomically(f, testo)
        assertEquals(testo, f.readText())
    }

    @Test
    fun `il file di lavoro non resta in giro`() {
        val f = file("stelle")
        UserFile.writeAtomically(f, "qualcosa")
        val part = File(f.parentFile, f.name + UserFile.PART_SUFFIX)
        assertFalse("il file di lavoro e' rimasto sul disco", part.exists())
    }

    @Test
    fun `una scrittura impossibile lascia intatto quello che c'era`() {
        // E' il cuore della cosa: se il salvataggio non riesce, i preferiti
        // di prima devono essere ancora li'. Il caso si costruisce puntando a
        // un percorso che non puo' esistere — un file dentro un file.
        val vero = file("stelle")
        UserFile.writeAtomically(vero, """{"stops":["a","b"]}""")

        val impossibile = File(vero, "dentro-un-file.json")
        assertFalse(UserFile.writeAtomically(impossibile, "niente"))

        assertEquals("""{"stops":["a","b"]}""", vero.readText())
    }

    @Test
    fun `un file di lavoro rimasto da un giro andato male non si legge per sbaglio`() {
        // Se un salvataggio precedente e' morto a meta', quello che resta sul
        // disco e' un `.part` incompleto. Non e' un salvataggio: il file vero
        // non deve averne notizia, e la scrittura successiva se lo riprende.
        val f = file("stelle")
        UserFile.writeAtomically(f, "buono")
        val part = File(f.parentFile, f.name + UserFile.PART_SUFFIX)
        part.writeText("{ meta' di un json")

        assertEquals("buono", f.readText())
        assertTrue(UserFile.writeAtomically(f, "nuovo"))
        assertEquals("nuovo", f.readText())
        assertFalse(part.exists())
    }

    @Test
    fun `un file che non c'era si crea`() {
        val f = file("stelle")
        assertTrue(f.delete())
        assertTrue(UserFile.writeAtomically(f, "primo"))
        assertEquals("primo", f.readText())
    }

    @Test
    fun `una cartella che non c'e' ancora si crea invece di far fallire il salvataggio`() {
        // `writeText` su un percorso senza cartella solleva, e il chiamante
        // lo inghiottiva: il primo salvataggio in una cartella nuova andava
        // perso in silenzio.
        val base = File.createTempFile("archivio", "").also { tmp.add(it) }
        assertTrue(base.delete())
        val f = File(File(base, "sotto"), "stelle.json")
        assertTrue(UserFile.writeAtomically(f, "primo"))
        assertEquals("primo", f.readText())
        f.delete()
        f.parentFile.delete()
        base.delete()
    }

    @Test
    fun `scrivere vuoto e' una scrittura, non un errore`() {
        // Togliere l'ultima stella salva una lista vuota: deve arrivare a
        // destinazione, altrimenti la stella tolta tornerebbe al riavvio.
        val f = file("stelle")
        UserFile.writeAtomically(f, """{"stops":["a"]}""")
        assertTrue(UserFile.writeAtomically(f, """{"stops":[]}"""))
        assertEquals("""{"stops":[]}""", f.readText())
    }
}
