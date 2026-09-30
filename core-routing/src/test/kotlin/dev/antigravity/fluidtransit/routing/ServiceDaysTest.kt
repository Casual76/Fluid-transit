package dev.antigravity.fluidtransit.routing

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I giorni di servizio che contano adesso.
 *
 * Alle 00:30 la corsa che sta per passare e' una "24:40" di IERI. La scheda
 * linea e lo strumento `orari_linea` guardavano solo la data di oggi, quindi
 * a mezzanotte e mezza una linea notturna sembrava senza il suo bus: le
 * fermate mostravano la prima corsa del mattino e l'"ultima 00:40" del giorno
 * che comincia si leggeva come "l'ultimo bus e' fra dieci minuti".
 */
class ServiceDaysTest {

    private val tmp = ArrayList<File>()

    @AfterTest
    fun cleanup() {
        tmp.forEach { it.delete() }
    }

    private fun alle(giorno: LocalDate, ora: Int, minuti: Int): Instant =
        ZonedDateTime.of(giorno, LocalTime.of(ora, minuti), Ftb.ROME).toInstant()

    /** Il tetto di questo feed: le corse arrivano fino alle 30:10. */
    private val fineCorse = 30 * 3600 + 10 * 60

    private val inizio = LocalDate.of(2026, 9, 1)
    private val giorno = LocalDate.of(2026, 9, 10)

    // ------------------------------------------------------- i giorni

    @Test
    fun `a mezzanotte e mezza i giorni sono ieri e oggi`() {
        val giorni = ServiceDays.at(inizio, 30, fineCorse, alle(giorno, 0, 30))

        assertEquals(listOf(giorno.minusDays(1), giorno), giorni.map { it.date })
        assertEquals(listOf(false, true), giorni.map { it.isToday })
        // L'indice e' quello che chiede `serviceActive`: giorni dall'inizio.
        assertEquals(listOf(8, 9), giorni.map { it.dayIndex })
    }

    @Test
    fun `a mezzogiorno ieri e' chiuso da un pezzo`() {
        val giorni = ServiceDays.at(inizio, 30, fineCorse, alle(giorno, 12, 0))
        assertEquals(listOf(giorno), giorni.map { it.date })
    }

    @Test
    fun `oggi resta anche a fine giornata`() {
        // "Prima e ultima corsa di oggi" sono fatti del giorno, non di
        // quest'ora: un bundle le cui corse finiscono tutte prima delle 24
        // non deve far sparire oggi alle 23:59.
        val giorni = ServiceDays.at(inizio, 30, 23 * 3600, alle(giorno, 23, 59))
        assertEquals(listOf(giorno), giorni.map { it.date })
    }

    @Test
    fun `il giorno da 25 ore resta di 25 ore`() {
        // Il 24 ottobre 2026 e' il giorno di servizio da 25 ore: l'ora
        // ripetuta cade nella sua estensione 24:00-27:00.
        val ottobre = LocalDate.of(2026, 10, 1)
        val cambio = LocalDate.of(2026, 10, 25)
        val giorni = ServiceDays.at(ottobre, 60, fineCorse, alle(cambio, 0, 30))

        assertEquals(listOf(cambio.minusDays(1), cambio), giorni.map { it.date })
        assertEquals(25 * 3600L, giorni[1].startEpoch - giorni[0].startEpoch)
        assertEquals(Ftb.serviceDayStart(cambio.minusDays(1)).epochSecond, giorni[0].startEpoch)
    }

    @Test
    fun `il tetto si misura fra istanti e non fra orologi`() {
        // Nel giorno da 25 ore la corsa delle "30:10" parte alle 05:10 ora
        // solare. Alle 05:00 ieri e' ancora aperto; alle 05:20 e' chiuso —
        // ma l'orologio dice 29 ore e 20 dalla mezzanotte di ieri, che a
        // contarlo cosi' sembrerebbe ancora aperto.
        val ottobre = LocalDate.of(2026, 10, 1)
        val cambio = LocalDate.of(2026, 10, 25)

        val alle0500 = ServiceDays.at(ottobre, 60, fineCorse, alle(cambio, 5, 0))
        assertEquals(listOf(cambio.minusDays(1), cambio), alle0500.map { it.date })

        val alle0520 = ServiceDays.at(ottobre, 60, fineCorse, alle(cambio, 5, 20))
        assertEquals(listOf(cambio), alle0520.map { it.date })
    }

    @Test
    fun `il giorno da 23 ore resta di 23 ore`() {
        val marzo = LocalDate.of(2026, 3, 1)
        val cambio = LocalDate.of(2026, 3, 29)
        val giorni = ServiceDays.at(marzo, 60, fineCorse, alle(cambio, 0, 30))

        assertEquals(listOf(cambio.minusDays(1), cambio), giorni.map { it.date })
        assertEquals(23 * 3600L, giorni[1].startEpoch - giorni[0].startEpoch)
    }

    @Test
    fun `dopo l'ultimo giorno valido restano le corse notturne di quello`() {
        // Il bundle e' scaduto a mezzanotte. Alle 00:30 i bus notturni
        // dell'ultimo giorno valido girano ancora, e sono nel bundle: si
        // vedono. Oggi invece non c'e'.
        val fine = inizio.plusDays(20)
        val dopo = fine.plusDays(1)

        val notte = ServiceDays.at(inizio, 21, fineCorse, alle(dopo, 0, 30))
        assertEquals(listOf(fine), notte.map { it.date })
        assertFalse(notte.any { it.isToday })

        // A mezzogiorno non c'e' piu' niente.
        assertTrue(ServiceDays.at(inizio, 21, fineCorse, alle(dopo, 12, 0)).isEmpty())
    }

    @Test
    fun `prima dell'inizio non c'e' nemmeno ieri`() {
        val giorni = ServiceDays.at(inizio, 21, fineCorse, alle(inizio, 0, 30))
        assertEquals(listOf(inizio), giorni.map { it.date })
    }

    // -------------------------------------------------- la validita'

    @Test
    fun `gli orari coprono i giorni della loro finestra, estremi compresi`() {
        val fine = inizio.plusDays(20)
        assertTrue(ServiceDays.covers(inizio, fine, inizio))
        assertTrue(ServiceDays.covers(inizio, fine, fine))
        assertFalse(ServiceDays.covers(inizio, fine, inizio.minusDays(1)))
        assertFalse(ServiceDays.covers(inizio, fine, fine.plusDays(1)))
    }

    // ---------------------------------------- le corse di una linea

    /** La linea di prova, con una corsa diurna alle 08:00 e una notturna alle 24:40. */
    private fun conNotturna(): File = TestBundle.write(
        tmp,
        dep0s = listOf(8 * 3600, 24 * 3600 + 40 * 60),
    )

    @Test
    fun `alle 00 e 30 la prossima corsa e' quella delle 24 e 40 di ieri`() {
        BundleReader(conNotturna()).use { r ->
            val oggi = TestBundle.feedStart.plusDays(4)
            val now = alle(oggi, 0, 30)
            val trips = ServiceDays.tripsOfRoute(r, 0, ServiceDays.at(r, now))

            // Due corse, ognuna in due giorni: il servizio circola tutti i giorni.
            assertEquals(4, trips.size)

            val prossima = assertNotNull(
                trips
                    .filter { it.departureEpoch >= now.epochSecond }
                    .minByOrNull { it.departureEpoch },
            )
            assertEquals(alle(oggi, 0, 40).epochSecond, prossima.departureEpoch)
            assertEquals(oggi.minusDays(1), prossima.day.date)
            assertFalse(prossima.day.isToday)
            assertEquals(24 * 3600 + 40 * 60, prossima.departureSeconds)
        }
    }

    @Test
    fun `la coda della notte e' l'ultima partenza di ieri che deve ancora venire`() {
        BundleReader(conNotturna()).use { r ->
            val oggi = TestBundle.feedStart.plusDays(4)
            val now = alle(oggi, 0, 30)
            val trips = ServiceDays.tripsOfRoute(r, 0, ServiceDays.at(r, now))

            assertEquals(alle(oggi, 0, 40).epochSecond, ServiceDays.tail(trips, now.epochSecond))
            // Passata la corsa, la coda e' finita.
            assertNull(ServiceDays.tail(trips, alle(oggi, 0, 41).epochSecond))
        }
    }

    @Test
    fun `a mezzogiorno non c'e' nessuna coda`() {
        BundleReader(conNotturna()).use { r ->
            val now = alle(TestBundle.feedStart.plusDays(4), 12, 0)
            val trips = ServiceDays.tripsOfRoute(r, 0, ServiceDays.at(r, now))

            assertNull(ServiceDays.tail(trips, now.epochSecond))
            assertTrue(trips.all { it.day.isToday })
        }
    }

    @Test
    fun `dopo la scadenza la coda di ieri c'e' e oggi no`() {
        BundleReader(conNotturna()).use { r ->
            val scaduto = r.feedEnd.plusDays(1)
            val now = alle(scaduto, 0, 30)
            val trips = ServiceDays.tripsOfRoute(r, 0, ServiceDays.at(r, now))

            assertFalse(ServiceDays.covers(r, scaduto))
            assertTrue(ServiceDays.covers(r, r.feedEnd))
            assertEquals(alle(scaduto, 0, 40).epochSecond, ServiceDays.tail(trips, now.epochSecond))
            assertTrue(trips.none { it.day.isToday })
        }
    }

    @Test
    fun `senza giorni non ci sono corse`() {
        BundleReader(conNotturna()).use { r ->
            assertTrue(ServiceDays.tripsOfRoute(r, 0, emptyList()).isEmpty())
        }
    }

    // ----------------------------------------------------- le parole

    @Test
    fun `la coda della notte si dice col suo giorno`() {
        val oggi = TestBundle.feedStart.plusDays(4)
        assertEquals(
            "Stanotte: ultima corsa alle 00:40",
            Times.serviceTail(alle(oggi, 0, 40).epochSecond),
        )
    }
}
