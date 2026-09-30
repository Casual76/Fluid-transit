package dev.antigravity.fluidtransit.ui.map

import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Ftb
import dev.antigravity.fluidtransit.routing.TestBundle
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Il colore di una linea salvata (stella, ricerca recente) contro quello di oggi.
 *
 * Il colore che si salva accanto alla linea e' quello della notte in cui la si
 * e' cercata; il bundler lo sposta ancora quando una linea prende un incrocio
 * nuovo. Se la pastiglia in Preferiti o fra i recenti usasse quello salvato,
 * la stessa linea avrebbe due tinte a seconda della schermata.
 */
class RouteColorTodayTest {

    private val tmp = ArrayList<File>()

    @After
    fun pulisci() {
        tmp.forEach { it.delete() }
    }

    private fun bundle() = BundleReader(TestBundle.write(tmp))

    /** L'hash della linea di prova come lo salvano i preferiti: esadecimale senza segno. */
    private val hashLinea = java.lang.Long.toHexString(Ftb.hash64(TestBundle.routeId))

    @Test
    fun `vince il colore del bundle su quello salvato`() {
        // La rete di prova assegna alla sua linea 0x9B6DD6: chi l'ha salvata
        // quando era rossa vede la tinta di oggi, non quella di allora.
        bundle().use { r ->
            assertEquals(0x9B6DD6, routeColorToday(r, hashLinea, 0xFF0000))
        }
    }

    @Test
    fun `una linea sparita dagli orari di oggi tiene il colore salvato`() {
        bundle().use { r ->
            val sconosciuta = java.lang.Long.toHexString(Ftb.hash64("una-linea-che-non-c-e-piu"))
            assertEquals(0x123456, routeColorToday(r, sconosciuta, 0x123456))
        }
    }

    @Test
    fun `senza orari pronti resta il colore salvato`() {
        assertEquals(0x123456, routeColorToday(null, hashLinea, 0x123456))
    }

    @Test
    fun `un hash che non si legge non fa eccezione`() {
        // I recenti di una versione vecchia possono avere una chiave vuota
        // (la ricerca la scrive vuota quando il bundle non c'e').
        bundle().use { r ->
            assertEquals(0x123456, routeColorToday(r, "", 0x123456))
            assertEquals(0x123456, routeColorToday(r, "non-e-esadecimale", 0x123456))
        }
    }

    @Test
    fun `il colore torna senza canale alfa`() {
        // RoutePill compone `0xFF000000 or colore`: un bit alto in piu' lo
        // sporcherebbe. Il bundle da' gia' 0xRRGGBB, la maschera e' la cintura.
        bundle().use { r ->
            assertEquals(0, routeColorToday(r, hashLinea, 0) and 0xFF000000.toInt())
        }
    }
}
