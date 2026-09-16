package dev.antigravity.fluidtransit.routing

import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Le linee che passano due volte dalla stessa fermata.
 *
 * L'indice fermata->pattern porta una voce per PASSAGGIO: un anello che
 * tocca la stessa fermata all'andata e al ritorno ci scrive dentro lo stesso
 * pattern due volte. Chi legge le partenze scandisce gia' da se' tutte le
 * posizioni in cui la fermata compare, quindi ogni voce in piu' e' una
 * partenza contata in piu'.
 *
 * Il sintomo l'ha trovato il cancello notturno del 16/09, non una persona:
 * al RISTORANTE LA BIANCA il tabellone dava alle 12:00 la stessa corsa due
 * volte di fila, allo stesso minuto, e la quinta partenza vera finiva fuori
 * dalla lista. Un tabellone che ripete un bus e ne nasconde un altro e' il
 * genere di cosa per cui non ci si fida piu' di nessuno dei cinque numeri.
 */
class LoopPatternTest {

    private val tmp = ArrayList<File>()

    @AfterTest
    fun pulisci() {
        tmp.forEach { it.delete() }
    }

    /** A, B, C, B, A: la fermata B toccata all'andata e al ritorno. */
    private fun anello() = BundleReader(
        TestBundle.write(tmp, patternStops = listOf(0, 1, 2, 1, 0)),
    )

    private fun alle(ora: Int): Instant = ZonedDateTime.of(
        TestBundle.feedStart.plusDays(3),
        LocalTime.of(ora, 0),
        Ftb.ROME,
    ).toInstant()

    @Test
    fun `il pattern di un anello si elenca una volta sola`() {
        anello().use { r ->
            val b = r.findStopByIdHash(Ftb.hash64(TestBundle.stopIds[1]))
            assertEquals(1, r.patternsAtStop(b).size)
        }
    }

    @Test
    fun `i due passaggi sono due partenze diverse, non due copie della stessa`() {
        anello().use { r ->
            val b = r.findStopByIdHash(Ftb.hash64(TestBundle.stopIds[1]))
            val deps = r.nextDepartures(b, alle(7), limit = 10, horizonSeconds = 3 * 3600)
            assertEquals(2, deps.size, "due passaggi, non quattro")
            assertEquals(listOf(1, 3), deps.map { it.positionInPattern })
            // 08:00 + due minuti, e 08:00 + sei.
            assertEquals(
                listOf(8 * 3600 + 120L, 8 * 3600 + 360L),
                deps.map { it.instant.epochSecond - alle(0).epochSecond },
            )
        }
    }

    @Test
    fun `una fermata toccata una volta sola non cambia comportamento`() {
        anello().use { r ->
            val c = r.findStopByIdHash(Ftb.hash64(TestBundle.stopIds[2]))
            val deps = r.nextDepartures(c, alle(7), limit = 10, horizonSeconds = 3 * 3600)
            assertEquals(1, deps.size)
            assertEquals(2, deps[0].positionInPattern)
        }
    }
}
