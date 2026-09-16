package dev.antigravity.fluidtransit.routing

import java.io.File
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Quante corse sono in viaggio adesso.
 *
 * Serve a dare un denominatore a "corse seguite adesso": milleseicento
 * seguite e' tutto o e' un terzo a seconda di quante ce ne sono, e finche'
 * il denominatore non c'era quel numero non diceva niente a nessuno.
 */
class CoverageTest {

    private val tmp = ArrayList<File>()

    @AfterTest
    fun pulisci() {
        tmp.forEach { it.delete() }
    }

    /** La rete di prova: corse alle 08:00 e alle 25:30, tre fermate in quattro minuti. */
    private fun bundle() = BundleReader(TestBundle.write(tmp))

    private fun istante(giorniDopoInizio: Long, secondiNelGiorno: Long): Instant =
        Ftb.serviceDayStart(TestBundle.feedStart.plusDays(giorniDopoInizio))
            .plusSeconds(secondiNelGiorno)

    @Test
    fun `a meta' corsa la corsa e' in viaggio`() {
        bundle().use { r ->
            // 08:02: la corsa delle 08:00 dura fino alle 08:04.
            assertEquals(1, Coverage.now(r, istante(2, 8 * 3600 + 120)).inViaggio)
        }
    }

    @Test
    fun `fuori dagli orari non viaggia nessuno`() {
        bundle().use { r ->
            assertEquals(0, Coverage.now(r, istante(2, 12 * 3600)).inViaggio)
        }
    }

    @Test
    fun `la notturna delle 25 e mezza appartiene al giorno prima`() {
        bundle().use { r ->
            // L'una e mezza del mattino: e' la corsa delle "25:30" del giorno
            // di servizio precedente. Cercarla nel giorno di oggi vuol dire
            // non trovarla, ed e' il modo in cui le app perdono l'ultimo bus.
            assertEquals(1, Coverage.now(r, istante(3, 25 * 3600 + 30 * 60 + 60)).inViaggio)
        }
    }

    @Test
    fun `un giorno fuori dal calendario non conta corse`() {
        bundle().use { r ->
            assertEquals(0, Coverage.now(r, istante(-40, 8 * 3600 + 120)).inViaggio)
        }
    }

    @Test
    fun `la quota seguita e' quella delle corse in strada, non di tutte`() {
        bundle().use { r ->
            // Il feed dice qualcosa di UNA corsa: quella delle 08:00 e'
            // l'unica in viaggio alle 08:02, e quindi la copertura e' piena.
            val tutto = object : LiveTimes {
                override fun at(tripIndex: Int, position: Int, stopCount: Int, nowEpoch: Long) = null
                override fun covers(tripIndex: Int) = true
            }
            val s = Coverage.now(r, istante(2, 8 * 3600 + 120), tutto)
            assertEquals(1, s.inViaggio)
            assertEquals(1, s.seguite)
            assertEquals(100, s.percento)
        }
    }

    @Test
    fun `senza niente in strada non si dichiara una percentuale`() {
        bundle().use { r ->
            // Zero su zero non e' "copertura zero": e' notte.
            assertEquals(null, Coverage.now(r, istante(2, 12 * 3600)).percento)
        }
    }
}
