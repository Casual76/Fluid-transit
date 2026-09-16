package dev.antigravity.fluidtransit.ui.map

import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Ftb
import dev.antigravity.fluidtransit.routing.TestBundle
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Le fermate che il mezzo si e' gia' lasciato indietro, nella scheda linea.
 *
 * Sul telefono, linea 12: 15:13, 15:15, 15:16, 15:18, e poi 15:15. Non era
 * un errore di calcolo — la corsa viaggiava tre minuti in anticipo, e le
 * fermate gia' passate mostrano l'orario di TABELLA, perche' un ritardo
 * riferito a dove si trova il mezzo adesso non dice niente su una fermata
 * che ha alle spalle. Ma un elenco di orari che torna indietro senza dire
 * perche' fa pensare che l'app sbagli i conti, ed e' esattamente il genere
 * di cosa per cui poi non ci si fida di nessuno dei numeri.
 *
 * Adesso il tratto gia' percorso si spegne. Questo test tiene il confine
 * dove deve stare: ne' una fermata di meno (il mezzo e' li' adesso), ne'
 * una di piu'.
 */
class RouteInfoServedTest {

    private val tmp = ArrayList<File>()

    @After
    fun pulisci() {
        tmp.forEach { it.delete() }
    }

    private fun alle(ora: Int, minuti: Int): Instant = ZonedDateTime.of(
        TestBundle.feedStart.plusDays(3),
        LocalTime.of(ora, minuti),
        Ftb.ROME,
    ).toInstant()

    /** La corsa delle 08:00 tocca A alle 08:00, B alle 08:02, C alle 08:04. */
    private fun linea(now: Instant): List<Boolean> =
        BundleReader(TestBundle.write(tmp)).use { r ->
            val info = RouteInfo.build(r, routeIndex = 0, now = now)
            info.directions.first().stops.map { it.served }
        }

    @Test
    fun `prima che parta non c'e' niente di passato`() {
        assertEquals(listOf(false, false, false), linea(alle(7, 55)))
    }

    @Test
    fun `a meta' corsa si spegne solo quello che il mezzo ha passato`() {
        // 08:04: A (08:00) e B (08:02) sono alle spalle da piu' del minuto
        // di cortesia; C e' quella di adesso, e resta accesa.
        assertEquals(listOf(true, true, false), linea(alle(8, 4)))
    }

    @Test
    fun `il minuto di cortesia tiene accesa la fermata appena passata`() {
        // 08:02:30 sarebbe "gia' passata" al secondo, ma chi arriva alla
        // fermata mezzo minuto dopo l'orario il bus lo sta ancora cercando:
        // il confine e' un minuto dopo, come in tutto il resto dell'app.
        assertEquals(listOf(true, false, false), linea(alle(8, 2)))
    }
}
