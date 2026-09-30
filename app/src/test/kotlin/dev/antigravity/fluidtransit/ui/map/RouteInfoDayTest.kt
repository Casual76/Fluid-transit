package dev.antigravity.fluidtransit.ui.map

import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.DepartureText
import dev.antigravity.fluidtransit.routing.Ftb
import dev.antigravity.fluidtransit.routing.TestBundle
import dev.antigravity.fluidtransit.routing.Times
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cosa fa una linea a mezzanotte e mezza, e a orari scaduti.
 *
 * Alle 00:30 la corsa che sta per passare e' una "24:40" del giorno di
 * servizio di IERI. La scheda linea guardava solo la data di oggi: le
 * fermate mostravano gli orari della prima corsa del mattino e l'"ultima
 * 00:40 di notte" del giorno che comincia si leggeva come "l'ultimo bus e'
 * fra dieci minuti" — proprio per una linea notturna, cioe' quella per cui
 * si guarda l'app a quell'ora.
 *
 * E con gli orari scaduti ogni scheda linea scriveva "Oggi questa linea non
 * ha corse", nello stesso istante in cui il tabellone di una fermata diceva
 * "Gli orari sono scaduti": una lista vuota che passa per "niente servizio"
 * mentre il guasto e' nostro.
 */
class RouteInfoDayTest {

    private val tmp = ArrayList<File>()

    @After
    fun pulisci() {
        tmp.forEach { it.delete() }
    }

    private fun alle(giorno: LocalDate, ora: Int, minuti: Int): Instant =
        ZonedDateTime.of(giorno, LocalTime.of(ora, minuti), Ftb.ROME).toInstant()

    /** La corsa delle 08:00 e la notturna delle 24:40, cioe' le 00:40 del giorno dopo. */
    private fun notturna(): File = TestBundle.write(
        tmp,
        dep0s = listOf(8 * 3600, 24 * 3600 + 40 * 60),
    )

    private val oggi = TestBundle.feedStart.plusDays(4)

    private fun orari(info: RouteInfo): List<String> =
        info.directions.first().stops.map { Times.hhmm(it.timeEpoch) }

    // ---------------------------------------------------- dopo mezzanotte

    @Test
    fun `alle 00 e 30 le fermate mostrano la corsa notturna di ieri`() {
        BundleReader(notturna()).use { r ->
            val info = RouteInfo.build(r, routeIndex = 0, now = alle(oggi, 0, 30))
            // A 00:40, B 00:42, C 00:44: non la corsa delle 08:00.
            assertEquals(listOf("00:40", "00:42", "00:44"), orari(info))
        }
    }

    @Test
    fun `alle 00 e 30 la coda della notte si dice a parte`() {
        BundleReader(notturna()).use { r ->
            val info = RouteInfo.build(r, routeIndex = 0, now = alle(oggi, 0, 30))

            assertEquals("Stanotte: ultima corsa alle 00:40", info.tailNote)
            // Il giorno che comincia e' un'altra cosa, e non si confonde: la
            // sua ultima corsa e' quella di domani notte.
            assertEquals("08:00", info.firstDepToday)
            assertEquals("00:40 di notte", info.lastDepToday)
        }
    }

    @Test
    fun `alle 00 e 30 la testata dice prima la notte e poi il giorno`() {
        BundleReader(notturna()).use { r ->
            val info = RouteInfo.build(r, routeIndex = 0, now = alle(oggi, 0, 30))

            assertEquals(
                "Stanotte: ultima corsa alle 00:40\n" +
                    "Oggi: prima 08:00, ultima 00:40 di notte\n" +
                    "Gli orari qui sotto sono della prossima corsa, da tabella.",
                info.headerText(info.directions.first()),
            )
        }
    }

    @Test
    fun `passata la corsa notturna la coda e' finita`() {
        BundleReader(notturna()).use { r ->
            // 00:50: la corsa delle 00:40 e' partita da dieci minuti, oltre i
            // cinque di tolleranza. Le fermate tornano alla prossima vera.
            val info = RouteInfo.build(r, routeIndex = 0, now = alle(oggi, 0, 50))

            assertNull(info.tailNote)
            assertEquals(listOf("08:00", "08:02", "08:04"), orari(info))
        }
    }

    @Test
    fun `a mezzogiorno non c'e' nessuna coda`() {
        BundleReader(notturna()).use { r ->
            val info = RouteInfo.build(r, routeIndex = 0, now = alle(oggi, 12, 0))

            assertNull(info.tailNote)
            assertFalse(info.outsideValidity)
            assertEquals("08:00", info.firstDepToday)
        }
    }

    // ------------------------------------------------- orari scaduti

    @Test
    fun `con gli orari scaduti la scheda non dice che la linea non ha corse`() {
        BundleReader(notturna()).use { r ->
            val scaduto = r.feedEnd.plusDays(1)
            val info = RouteInfo.build(r, routeIndex = 0, now = alle(scaduto, 12, 0))

            assertTrue(info.outsideValidity)
            assertNull(info.firstDepToday)
            val testo = info.headerText(info.directions.first())
            assertTrue(testo, testo.startsWith("Gli orari sono scaduti."))
            assertFalse(testo, testo.contains("non ha corse"))
            // Nessuna corsa candidata: le fermate restano senza orari, e la
            // frase "della prossima corsa" non compare.
            assertFalse(testo, testo.contains("prossima corsa"))
        }
    }

    @Test
    fun `dopo la scadenza i bus notturni dell'ultimo giorno valido si vedono`() {
        BundleReader(notturna()).use { r ->
            val scaduto = r.feedEnd.plusDays(1)
            val info = RouteInfo.build(r, routeIndex = 0, now = alle(scaduto, 0, 30))

            // Alle 00:30 quei bus girano ancora e sono nel bundle: dire che
            // gli orari sono scaduti e nascondere anche loro sarebbe la
            // lista vuota che passa per "niente servizio".
            assertEquals("Stanotte: ultima corsa alle 00:40", info.tailNote)
            assertEquals(listOf("00:40", "00:42", "00:44"), orari(info))
            assertTrue(info.outsideValidity)
            val testo = info.headerText(info.directions.first())
            assertTrue(testo, testo.startsWith("Stanotte: ultima corsa alle 00:40\nGli orari sono scaduti."))
        }
    }

    // ------------------------------------------------- le tre risposte

    /** Una scheda senza direzioni: quello che si prova qui sono le parole della testata. */
    private fun scheda(
        prima: String? = null,
        ultima: String? = null,
        ritmo: Int? = null,
        coda: String? = null,
        scaduti: Boolean = false,
    ) = RouteInfo(
        routeIndex = 0,
        shortName = "1",
        longName = "Alfa - Gamma",
        agency = "at - Test urbano",
        category = "Urbano",
        colorRgb = 0x9B6DD6,
        directions = emptyList(),
        firstDepToday = prima,
        lastDepToday = ultima,
        headwayMinutes = ritmo,
        tailNote = coda,
        outsideValidity = scaduti,
    )

    @Test
    fun `un giorno normale dice prima, ultima e ogni quanto`() {
        assertEquals(
            "Oggi: prima 05:10, ultima 00:40 di notte · circa ogni 10 min a quest'ora",
            scheda("05:10", "00:40 di notte", ritmo = 10).headerText(null),
        )
    }

    @Test
    fun `senza corse oggi, con gli orari validi, lo dice`() {
        assertEquals(
            "Oggi questa linea non ha corse.",
            scheda().headerText(null),
        )
    }

    @Test
    fun `senza corse oggi ma con la coda di ieri non e' senza corse`() {
        val testo = scheda(coda = "Stanotte: ultima corsa alle 00:40").headerText(null)
        assertEquals("Stanotte: ultima corsa alle 00:40\nOggi questa linea non ha corse.", testo)
    }

    @Test
    fun `con gli orari scaduti dice che sono scaduti, con le parole del tabellone`() {
        val scaduti = DepartureText.empty(DepartureText.Trouble.ORARI_SCADUTI)
        val testo = scheda(scaduti = true).headerText(null)
        assertEquals("${scaduti.title}. ${scaduti.detail}", testo)
        assertFalse(testo, testo.contains("non ha corse"))
    }
}
