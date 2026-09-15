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
                board(
                    row(300, delay = 60, certainty = Certainty.DECLARED),
                    row(600, delay = 20, certainty = Certainty.DECLARED),
                ),
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

    @Test
    fun `un tabellone misto non promette piu' di quello che ha`() {
        // Con dieci righe in memoria e sei a schermo, bastava che una riga
        // nascosta venisse dal feed perche' il piede dicesse "dal bus" sopra
        // sei righe che dicevano tutte "orario da tabella". Una contraddizione
        // dentro lo stesso pannello, a due centimetri di distanza.
        fun board(vararg rows: NextDeparture) = DepartureBoard(0, "Alfa", now, rows.toList())

        assertEquals(
            "in parte dal bus",
            DepartureText.boardSource(
                board(row(300, delay = 60, certainty = Certainty.DECLARED), row(600)),
            ),
        )
        assertEquals(
            "dal bus",
            DepartureText.boardSource(
                board(
                    row(300, delay = 60, certainty = Certainty.DECLARED),
                    row(600, delay = 30, certainty = Certainty.PROPAGATED),
                ),
            ),
        )
        assertEquals("orari da tabella", DepartureText.boardSource(board()))
    }

    // --------------------------------------- la stessa fermata, dall'altro lato

    @Test
    fun `una fermata lungo la corsa si legge come una partenza`() {
        // E' lo stesso fatto guardato dall'altro lato: "quando passa di qui"
        // e "dove passa questo" devono dire le stesse identiche parole. La
        // scheda corsa scriveva "previsto 14:32" dove la scheda fermata
        // scrive "da tabella alle 14:32".
        for (c in listOf(
            Certainty.DECLARED,
            Certainty.PROPAGATED,
            Certainty.ESTIMATED,
            null,
        )) {
            val delay = if (c == null) null else 180
            val dalla = DepartureText.phrase(row(300, delay = delay, certainty = c), now)
            val lungo = DepartureText.alongTrip(
                scheduledEpoch = now + 300,
                delaySeconds = delay,
                certainty = c,
                nowEpoch = now,
            )
            assertEquals(dalla.headline, lungo.headline, "il numero cambia per $c")
            assertEquals(dalla.support, lungo.support, "le parole cambiano per $c")
            assertEquals(dalla.tone, lungo.tone, "il tono cambia per $c")
            assertEquals(dalla.pulse, lungo.pulse, "il pallino cambia per $c")
        }
    }

    @Test
    fun `una fermata saltata lo dice`() {
        // Il feed lo dichiara (schedule_relationship SKIPPED) e l'app lo
        // sapeva senza mostrarlo: il tabellone della fermata la toglie
        // dall'elenco, ma chi guarda il percorso della corsa deve vederla.
        val p = DepartureText.alongTrip(
            scheduledEpoch = now + 300,
            delaySeconds = 60,
            certainty = Certainty.DECLARED,
            skipped = true,
            nowEpoch = now,
        )
        assertEquals("Non ferma", p.headline)
        assertEquals(DepartureText.Tone.CANCELED, p.tone)
        assertTrue(!p.pulse, "una fermata saltata non pulsa")
    }

    @Test
    fun `una corsa cancellata vince sul resto`() {
        val p = DepartureText.alongTrip(
            scheduledEpoch = now + 300,
            delaySeconds = 60,
            certainty = Certainty.DECLARED,
            canceled = true,
            skipped = true,
            nowEpoch = now,
        )
        assertEquals("Cancellata", p.headline)
    }

    // ------------------------------------------------- perche' questo numero

    @Test
    fun `ogni provenienza ha la sua spiegazione, e nessuna e' vuota`() {
        // Il punto non e' il testo: e' che per ogni stato in cui la riga puo'
        // trovarsi ci sia qualcosa da leggere. Una spiegazione che manca e'
        // peggio di nessun tocco, perche' promette e non mantiene.
        val casi = listOf(
            row(300, delay = 180, certainty = Certainty.DECLARED),
            row(300, delay = 180, certainty = Certainty.PROPAGATED),
            row(300, delay = 180, certainty = Certainty.ESTIMATED),
            row(300, delay = 0, certainty = Certainty.SERVED),
            row(300),
            row(300, monitored = true),
            row(300, canceled = true),
        )
        for (r in casi) {
            val why = DepartureText.why(r, now)
            assertTrue(why.title.isNotBlank(), "titolo vuoto per ${r.certainty}")
            assertTrue(why.lines.isNotEmpty(), "nessuna riga per ${r.certainty}")
            assertTrue(
                why.lines.all { it.isNotBlank() },
                "una riga vuota per ${r.certainty}",
            )
        }
    }

    @Test
    fun `la spiegazione dice sempre l'orario di tabella`() {
        // E' il numero da cui parte tutto: senza, la spiegazione non si
        // aggancia a niente di verificabile.
        val casi = listOf(
            row(300, delay = 180, certainty = Certainty.DECLARED),
            row(300, delay = 180, certainty = Certainty.ESTIMATED),
            row(300),
            row(300, canceled = true),
        )
        for (r in casi) {
            val why = DepartureText.why(r, now)
            assertTrue(
                why.lines.any { it.contains(Times.hhmm(r.scheduledEpoch)) },
                "manca l'orario di tabella per ${r.certainty}: ${why.lines}",
            )
        }
    }

    @Test
    fun `una stima nostra lo dichiara, una previsione del feed no`() {
        val stimata = DepartureText.why(
            row(300, delay = 180, certainty = Certainty.ESTIMATED), now,
        )
        assertTrue(
            stimata.lines.any { it.contains("stimato") },
            "la stima non si dichiara: ${stimata.lines}",
        )

        val dichiarata = DepartureText.why(
            row(300, delay = 180, certainty = Certainty.DECLARED), now,
        )
        assertTrue(
            dichiarata.lines.none { it.contains("stimiamo") },
            "una previsione del feed si spaccia per stima: ${dichiarata.lines}",
        )
    }

    @Test
    fun `propagata spiega da dove arriva il numero`() {
        // E' il caso che l'utente non puo' indovinare: il numero e' del feed,
        // ma non e' di questa fermata.
        val why = DepartureText.why(
            row(300, delay = 180, certainty = Certainty.PROPAGATED), now,
        )
        assertTrue(
            why.lines.any { it.contains("precedente") },
            "non dice che la previsione e' di un'altra fermata: ${why.lines}",
        )
    }
}
