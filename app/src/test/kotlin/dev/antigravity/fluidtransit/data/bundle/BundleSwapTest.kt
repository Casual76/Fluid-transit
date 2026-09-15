package dev.antigravity.fluidtransit.data.bundle

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Lo scambio del bundle notturno.
 *
 * Il ramo che conta e' quello che fallisce, ed e' anche l'unico che non si
 * vede mai girare: un utente lo incontra una volta ogni mai, e quando lo
 * incontra si ritrova l'app senza orari e cinquanta megabyte da riscaricare.
 */
class BundleSwapTest {

    private lateinit var dir: File
    private lateinit var active: File
    private lateinit var previous: File
    private lateinit var part: File

    @Before
    fun setUp() {
        dir = File.createTempFile("swap", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        active = File(dir, "active.ftb")
        previous = File(dir, "active.previous.ftb")
        part = File(dir, "incoming.ftb.part")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `il nuovo prende il posto del vecchio, e il vecchio resta da parte`() {
        active.writeText("ieri")
        part.writeText("oggi")

        BundleSwap.promote(part, active, previous)

        assertEquals("oggi", active.readText())
        assertEquals("ieri", previous.readText())
        assertFalse("il file scaricato e' ancora li'", part.exists())
    }

    @Test
    fun `il primo bundle non ha un vecchio da mettere da parte`() {
        part.writeText("il primo")

        BundleSwap.promote(part, active, previous)

        assertEquals("il primo", active.readText())
        assertFalse(previous.exists())
    }

    @Test
    fun `un vecchio rimasto da uno scambio precedente non blocca il nuovo`() {
        previous.writeText("l'altro ieri")
        active.writeText("ieri")
        part.writeText("oggi")

        BundleSwap.promote(part, active, previous)

        assertEquals("oggi", active.readText())
        assertEquals("ieri", previous.readText())
    }

    @Test
    fun `se la promozione fallisce, gli orari di ieri sono ancora al loro posto`() {
        // Il caso vero: il file scaricato non c'e' piu' (spazio finito,
        // pulizia del sistema, un'altra copia dell'app). Prima di questo, qui
        // l'app restava senza NESSUN bundle.
        active.writeText("ieri")
        assertFalse(part.exists())

        var lanciato = false
        try {
            BundleSwap.promote(part, active, previous)
        } catch (_: Exception) {
            lanciato = true
        }

        assertTrue("un fallimento silenzioso e' peggio di un errore", lanciato)
        assertTrue("il bundle attivo e' sparito", active.isFile)
        assertEquals("ieri", active.readText())
    }

    @Test
    fun `un processo morto durante lo scambio si recupera all'avvio`() {
        // Lo stato intermedio: il vecchio di lato, il nuovo non ancora
        // arrivato. E' l'unico orario rimasto.
        previous.writeText("ieri")
        assertFalse(active.exists())

        assertTrue(BundleSwap.recover(active, previous))

        assertEquals("ieri", active.readText())
        assertFalse(previous.exists())
    }

    @Test
    fun `con un bundle attivo non si recupera niente`() {
        // Il caso normale dopo uno scambio riuscito: il vecchio e' ancora
        // sul disco in attesa di essere buttato, e non deve tornare davanti.
        active.writeText("oggi")
        previous.writeText("ieri")

        assertFalse(BundleSwap.recover(active, previous))
        assertEquals("oggi", active.readText())
    }

    @Test
    fun `senza niente da recuperare non succede niente`() {
        assertFalse(BundleSwap.recover(active, previous))
        assertFalse(active.exists())
    }
}
