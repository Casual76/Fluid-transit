package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Le parole di una partenza.
 *
 * Il valore di questi test non e' che le frasi siano queste: e' che siano
 * SOLE. Prima la stessa partenza si leggeva in quattro modi, e nessuno dei
 * quattro posti sapeva degli altri tre.
 */
class DepartureTextTest {

    private val now = 1_700_000_000L

    private fun row(
        scheduledIn: Int,
        delay: Int? = null,
        certainty: Certainty? = null,
        canceled: Boolean = false,
        monitored: Boolean = false,
    ) = NextDeparture(
        tripIndex = 1,
        patternIndex = 0,
        routeIndex = 0,
        stopIndex = 0,
        positionInPattern = 0,
        scheduledEpoch = now + scheduledIn,
        delaySeconds = delay,
        certainty = certainty,
        canceled = canceled,
        skipped = false,
        monitored = monitored,
        line = "23",
        destination = "Careggi",
        colorRgb = 0x112233,
        stopName = "Piazza Alfa",
    )

    // ------------------------------------------------------------ il numero

    @Test
    fun `sotto l'ora si contano i minuti`() {
        assertEquals("5 min", DepartureText.phrase(row(300), now).headline)
    }

    @Test
    fun `sopra l'ora si passa all'orologio`() {
        // I minuti oltre un'ora non dicono niente a nessuno. E' la regola che
        // la scheda fermata aveva gia' e le altre tre no.
        val p = DepartureText.phrase(row(2 * 3600), now)
        assertTrue(p.headline.contains(":"), "atteso un orario, trovato ${p.headline}")
    }

    @Test
    fun `imminente si dice a parole`() {
        assertEquals("ora", DepartureText.phrase(row(10), now).headline)
    }

    @Test
    fun `il numero mostrato tiene conto del ritardo`() {
        // Cinque minuti di tabella piu' tre di ritardo fanno otto.
        val p = DepartureText.phrase(row(300, delay = 180, certainty = Certainty.DECLARED), now)
        assertEquals("8 min", p.headline)
    }

    // ------------------------------------------------------- la provenienza

    @Test
    fun `una previsione del feed si dichiara come tale`() {
        val p = DepartureText.phrase(row(300, delay = 180, certainty = Certainty.DECLARED), now)
        assertEquals(DepartureText.Tone.LIVE, p.tone)
        assertTrue(p.support.startsWith("dal bus"), p.support)
        assertTrue(p.pulse, "una previsione per QUESTA fermata fa pulsare il pallino")
    }

    @Test
    fun `una previsione propagata viene comunque dal feed`() {
        val p = DepartureText.phrase(row(300, delay = 180, certainty = Certainty.PROPAGATED), now)
        assertEquals(DepartureText.Tone.LIVE, p.tone)
        assertTrue(p.support.startsWith("dal bus"), p.support)
        assertTrue(!p.pulse, "propagata non e' dichiarata qui: il pallino non pulsa")
    }

    @Test
    fun `una stima nostra si dichiara stima`() {
        val p = DepartureText.phrase(row(300, delay = 180, certainty = Certainty.ESTIMATED), now)
        assertEquals(DepartureText.Tone.ESTIMATED, p.tone)
        assertTrue(p.support.startsWith("stimato"), p.support)
    }

    @Test
    fun `senza dati dal vivo si dice che l'orario e' quello di tabella`() {
        val p = DepartureText.phrase(row(300), now)
        assertEquals(DepartureText.Tone.SCHEDULED, p.tone)
        assertEquals("orario da tabella", p.support)
    }

    @Test
    fun `in orario non e' la stessa cosa di non sapere niente`() {
        // Il feed segue la corsa e dice zero: e' un'informazione. Prima le due
        // cose erano indistinguibili perche' si guardava `delay != 0`.
        val puntuale = DepartureText.phrase(row(300, delay = 0, certainty = Certainty.DECLARED), now)
        val ignota = DepartureText.phrase(row(300), now)

        assertTrue(puntuale.support.contains("in orario"), puntuale.support)
        assertTrue(!ignota.support.contains("in orario"), ignota.support)
        assertEquals(DepartureText.Tone.LIVE, puntuale.tone)
        assertEquals(DepartureText.Tone.SCHEDULED, ignota.tone)
    }

    @Test
    fun `una corsa seguita che non dichiara un ritardo lo dice`() {
        val p = DepartureText.phrase(row(300, monitored = true), now)
        assertTrue(p.support.contains("in strada"), p.support)
    }

    @Test
    fun `l'orario di tabella resta leggibile accanto a quello vero`() {
        val p = DepartureText.phrase(row(300, delay = 180, certainty = Certainty.DECLARED), now)
        assertTrue(p.support.contains("da tabella alle"), p.support)
    }

    @Test
    fun `previsto non vuol piu' dire due cose opposte`() {
        // Nel vocabolario di prima "previsto" era sia l'orario di tabella
        // ("previsto 14:32") sia l'assenza di dati dal vivo ("orario
        // previsto"): i due significati fra cui la persona deve distinguere,
        // chiamati con la stessa parola.
        val conLive = DepartureText.phrase(row(300, delay = 60, certainty = Certainty.DECLARED), now)
        val senzaLive = DepartureText.phrase(row(300), now)

        assertTrue(!conLive.support.contains("previst"), conLive.support)
        assertTrue(!senzaLive.support.contains("previst"), senzaLive.support)
    }

    // ------------------------------------------------------------ cancellate

    @Test
    fun `una corsa cancellata lo dice al posto del numero`() {
        val p = DepartureText.phrase(row(300, canceled = true), now)
        assertEquals("Cancellata", p.headline)
        assertEquals(DepartureText.Tone.CANCELED, p.tone)
        assertTrue(!p.pulse)
    }

    @Test
    fun `cancellata vince anche se c'e' un ritardo`() {
        val p = DepartureText.phrase(
            row(300, delay = 180, certainty = Certainty.DECLARED, canceled = true), now,
        )
        assertEquals("Cancellata", p.headline)
    }

    // --------------------------------------------------------- la riga corta

    @Test
    fun `la forma corta usa le stesse parole`() {
        assertEquals("23 5 min", DepartureText.compact(row(300), now))
        assertEquals("23 cancellata", DepartureText.compact(row(300, canceled = true), now))
    }

    @Test
    fun `la forma corta passa all'orologio come quella lunga`() {
        val compact = DepartureText.compact(row(2 * 3600), now)
        assertTrue(compact.contains(":"), compact)
    }

    // ------------------------------------------------ la provenienza globale

    @Test
    fun `un tabellone dichiara la provenienza migliore che ha`() {
        fun board(vararg rows: NextDeparture) = DepartureBoard(0, "Alfa", now, rows.toList())

        assertEquals(
            "dal bus",
            DepartureText.boardSource(
                board(row(300, delay = 60, certainty = Certainty.DECLARED), row(600)),
            ),
        )
        assertEquals(
            "stimati",
            DepartureText.boardSource(
                board(row(300, delay = 60, certainty = Certainty.ESTIMATED), row(600)),
            ),
        )
        assertEquals("orari da tabella", DepartureText.boardSource(board(row(300), row(600))))
    }
}
