package dev.antigravity.fluidtransit.routing

import java.io.File
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Il tabellone di una fermata: il calcolo che prima stava scritto in sei
 * posti diversi, ognuno con la sua idea di cosa mostrare.
 *
 * Le regole qui sotto non sono nuove: sono la fusione della versione migliore
 * di ognuna delle sei. Quello che e' nuovo e' che siano UNA. Prima, per dire,
 * la scheda fermata scartava le corse gia' servite dal ritardo e i Preferiti
 * no, quindi la stessa fermata mostrava minuti diversi a seconda di da dove la
 * si guardava.
 */
class DeparturesTest {

    private val tmp = ArrayList<File>()

    @AfterTest
    fun cleanup() {
        tmp.forEach { it.delete() }
    }

    /** Quattro corse ravvicinate dalla stessa fermata: 08:00, 08:05, 08:10, 08:20. */
    private fun busy(): File = TestBundle.write(
        tmp,
        tripIds = listOf("c1", "c2", "c3", "c4"),
        dep0s = listOf(8 * 3600, 8 * 3600 + 300, 8 * 3600 + 600, 8 * 3600 + 1200),
    )

    private fun at(hhmm: String): Instant {
        val (h, m) = hhmm.split(":").map { it.toInt() }
        return Ftb.serviceDayStart(TestBundle.feedStart.plusDays(2))
            .plusSeconds((h * 3600 + m * 60).toLong())
    }

    /** Un tempo reale finto, che dice esattamente quello che il test vuole. */
    private class Live(
        val delays: Map<Int, Pair<Int, Certainty>> = emptyMap(),
        val canceled: Set<Int> = emptySet(),
        val skippedAt: Set<Pair<Int, Int>> = emptySet(),
        val monitored: Set<Int> = emptySet(),
    ) : LiveTimes {
        override fun at(tripIndex: Int, position: Int, stopCount: Int, nowEpoch: Long): LiveTimes.At? =
            delays[tripIndex]?.let { LiveTimes.At(it.first, it.second) }

        override fun canceled(tripIndex: Int) = tripIndex in canceled
        override fun skipped(tripIndex: Int, position: Int) = (tripIndex to position) in skippedAt
        override fun monitored(tripIndex: Int) = tripIndex in monitored
    }

    // --------------------------------------------------------- senza il vivo

    @Test
    fun `senza tempo reale vale l'orario di tabella`() {
        BundleReader(busy()).use { r ->
            val board = Departures.build(r, stopIndex = 0, now = at("07:50"))

            assertEquals("Piazza Alfa", board.stopName)
            assertEquals(4, board.rows.size)
            val first = board.rows[0]
            assertNull(first.delaySeconds)
            assertNull(first.certainty)
            assertEquals(first.scheduledEpoch, first.effectiveEpoch)
            assertTrue(!first.live)
        }
    }

    @Test
    fun `l'istante del calcolo e' uno solo per tutto il tabellone`() {
        // E' la ragione per cui due schermate che guardano lo stesso tabellone
        // non possono piu' mostrare minuti diversi: i numeri si contano da qui.
        BundleReader(busy()).use { r ->
            val now = at("07:50")
            val board = Departures.build(r, stopIndex = 0, now = now)
            assertEquals(now.epochSecond, board.computedAtEpoch)
        }
    }

    // ---------------------------------------------------------- col vivo

    @Test
    fun `un ritardo sposta l'orario e dichiara da dove viene`() {
        BundleReader(busy()).use { r ->
            val board = Departures.build(
                r, stopIndex = 0, now = at("07:50"),
                live = Live(delays = mapOf(0 to (180 to Certainty.DECLARED))),
            )

            val row = board.rows.first { it.tripIndex == 0 }
            assertEquals(180, row.delaySeconds)
            assertEquals(Certainty.DECLARED, row.certainty)
            assertEquals(row.scheduledEpoch + 180, row.effectiveEpoch)
            assertTrue(row.fromFeed, "una previsione dichiarata viene dal feed")
        }
    }

    @Test
    fun `una stima si distingue da una previsione dichiarata`() {
        BundleReader(busy()).use { r ->
            val board = Departures.build(
                r, stopIndex = 0, now = at("07:50"),
                live = Live(delays = mapOf(0 to (180 to Certainty.ESTIMATED))),
            )

            val row = board.rows.first { it.tripIndex == 0 }
            assertTrue(row.live, "un numero c'e'")
            assertTrue(!row.fromFeed, "ma non lo dice il feed")
        }
    }

    @Test
    fun `un ritardo riferito a una fermata gia' passata non si applica`() {
        // Il mezzo e' oltre: quel numero non dice niente su quando passera'
        // QUI. Alcune schermate lo filtravano e altre no, e la stessa fermata
        // mostrava minuti diversi da Preferiti e dalla sua scheda.
        BundleReader(busy()).use { r ->
            val board = Departures.build(
                r, stopIndex = 0, now = at("07:50"),
                live = Live(delays = mapOf(0 to (600 to Certainty.SERVED))),
            )

            val row = board.rows.first { it.tripIndex == 0 }
            assertNull(row.delaySeconds)
            assertEquals(row.scheduledEpoch, row.effectiveEpoch)
        }
    }

    @Test
    fun `l'ordine e' quello dell'orario effettivo, non di quello di tabella`() {
        // Un bus in ritardo passa dopo uno puntuale che parte dopo di lui, e
        // il tabellone deve dirlo: e' il momento in cui la persona alla
        // fermata decide quale aspettare.
        BundleReader(busy()).use { r ->
            val board = Departures.build(
                r, stopIndex = 0, now = at("07:50"),
                live = Live(delays = mapOf(0 to (900 to Certainty.DECLARED))),
            )

            // La corsa delle 08:00 con quindici minuti di ritardo passa alle
            // 08:15: dopo quella delle 08:05 e quella delle 08:10.
            assertEquals(listOf(1, 2, 0, 3), board.rows.map { it.tripIndex })
        }
    }

    @Test
    fun `una corsa che il ritardo ha portato nel passato sparisce`() {
        BundleReader(busy()).use { r ->
            val board = Departures.build(
                r, stopIndex = 0, now = at("08:07"),
                // La corsa delle 08:10 e' in anticipo di cinque minuti: e'
                // passata alle 08:05, cioe' due minuti fa.
                live = Live(delays = mapOf(2 to (-300 to Certainty.DECLARED))),
            )

            // Il tabellone non e' vuoto: se lo fosse, "non c'e' la corsa 2"
            // sarebbe vero senza voler dire niente.
            assertTrue(board.rows.isNotEmpty(), "il tabellone e' vuoto: la prova non prova niente")
            assertTrue(board.rows.none { it.tripIndex == 2 }, "una corsa passata non e' imminente")
        }
    }

    @Test
    fun `una corsa appena partita resta per un momento`() {
        // Mezzo minuto di grazia: senza, la corsa spariva dal tabellone un
        // istante prima che il bus arrivasse davvero, proprio mentre la
        // persona lo stava aspettando.
        BundleReader(busy()).use { r ->
            val board = Departures.build(r, stopIndex = 0, now = at("08:00").plusSeconds(20))
            assertTrue(board.rows.any { it.tripIndex == 0 })
        }
    }

    // ------------------------------------------------- cancellate e saltate

    @Test
    fun `una corsa cancellata resta nel tabellone, dichiarata`() {
        // Sapere che il bus non viene e' piu' utile che non vedere niente e
        // continuare ad aspettarlo.
        BundleReader(busy()).use { r ->
            val board = Departures.build(
                r, stopIndex = 0, now = at("07:50"),
                live = Live(canceled = setOf(0)),
            )

            val row = board.rows.first { it.tripIndex == 0 }
            assertTrue(row.canceled)
        }
    }

    @Test
    fun `una fermata saltata sparisce dal tabellone`() {
        // Diverso da cancellata: la corsa c'e', ma non passa di qui.
        // Mostrarla in ritardo sarebbe peggio che non mostrarla.
        BundleReader(busy()).use { r ->
            val board = Departures.build(
                r, stopIndex = 0, now = at("07:50"),
                live = Live(skippedAt = setOf(0 to 0)),
            )

            assertTrue(board.rows.none { it.tripIndex == 0 })
            assertEquals(3, board.rows.size)
        }
    }

    @Test
    fun `una corsa seguita ma puntuale si distingue da una di cui non si sa niente`() {
        BundleReader(busy()).use { r ->
            val board = Departures.build(
                r, stopIndex = 0, now = at("07:50"),
                live = Live(monitored = setOf(0)),
            )

            assertTrue(board.rows.first { it.tripIndex == 0 }.monitored)
            assertTrue(!board.rows.first { it.tripIndex == 1 }.monitored)
        }
    }

    // ------------------------------------------------------------- il limite

    @Test
    fun `il limite si applica dopo aver scartato, non prima`() {
        // Chiedere tre corse e riceverne due perche' una era saltata e' un
        // tabellone mezzo vuoto senza motivo.
        BundleReader(busy()).use { r ->
            val board = Departures.build(
                r, stopIndex = 0, now = at("07:50"), limit = 3,
                live = Live(skippedAt = setOf(0 to 0)),
            )

            assertEquals(3, board.rows.size)
            assertTrue(board.rows.none { it.tripIndex == 0 })
        }
    }

    // ----------------------------------------------------- piu' fermate

    @Test
    fun `piu' fermate insieme si mescolano per orario`() {
        // "Cosa passa qui intorno" non e' "cosa passa da ognuna di queste
        // fermate": elencarle raggruppate dava 3 min, 40 min, 5 min.
        BundleReader(busy()).use { r ->
            val board = Departures.merged(
                r, stops = listOf(0, 1), now = at("07:50"), limit = 8,
            )

            val times = board.rows.map { it.effectiveEpoch }
            assertEquals(times.sorted(), times, "non sono in ordine di orario")
            assertTrue(board.rows.isNotEmpty())
        }
    }

    @Test
    fun `lo stesso autobus non si conta due volte`() {
        // Le tre fermate della rete di prova stanno sulla stessa linea, quindi
        // ogni corsa passa da tutte e tre: chiedendole insieme, prima, uscivano
        // dodici righe per quattro autobus. Chi guardava leggeva dodici
        // occasioni, e nove non esistevano.
        BundleReader(busy()).use { r ->
            val board = Departures.merged(
                r, stops = listOf(0, 1, 2), now = at("07:50"), limit = 20,
            )
            val corse = board.rows.map { it.tripIndex }
            assertEquals(corse.distinct(), corse, "la stessa corsa compare piu' volte")
            assertEquals(4, board.rows.size)
        }
    }

    @Test
    fun `chi chiede decide da quale fermata mostrarlo`() {
        // L'ordine della lista e' un ordine di preferenza: "qui intorno" mette
        // per prima la fermata piu' vicina a chi guarda, e l'autobus si mostra
        // da quella. Ribaltando l'ordine si ribalta la scelta.
        BundleReader(busy()).use { r ->
            val daAlfa = Departures.merged(r, stops = listOf(0, 1, 2), now = at("07:50"), limit = 20)
            assertEquals(setOf("Piazza Alfa"), daAlfa.rows.map { it.stopName }.toSet())

            // Corso Gamma e' il capolinea e non ha partenze: la preferenza
            // cade sulla prima fermata della lista che ne ha davvero.
            val daBeta = Departures.merged(r, stops = listOf(2, 1, 0), now = at("07:50"), limit = 20)
            assertEquals(setOf("Via Beta"), daBeta.rows.map { it.stopName }.toSet())
        }
    }

    @Test
    fun `ogni riga sa da che fermata parte`() {
        BundleReader(busy()).use { r ->
            val board = Departures.merged(r, stops = listOf(0, 1), now = at("07:50"), limit = 8)
            assertTrue(board.rows.isNotEmpty(), "senza righe, quel controllo e' vero e non dice niente")
            assertTrue(board.rows.all { it.stopName.isNotEmpty() })
        }
    }

    @Test
    fun `un anello che ripassa dalla stessa fermata resta due occasioni`() {
        // Il caso opposto, e va tenuto: 215 dei 8.331 pattern del feed vero
        // toccano due volte la stessa fermata, e li' il secondo passaggio e'
        // un autobus che si puo' davvero prendere mezz'ora dopo. A separare i
        // due casi non e' il tempo — le due distribuzioni si sovrappongono —
        // ma la fermata: due fermate diverse sono un passaggio solo, la stessa
        // fermata due volte sono due passaggi.
        val corsa = 7
        val righe = listOf(
            riga(corsa, stop = 5, epoch = 1000, posizione = 2),
            riga(corsa, stop = 9, epoch = 1100, posizione = 3),
            riga(corsa, stop = 5, epoch = 3400, posizione = 18),
        )
        val tenute = Departures.oneRowPerBus(righe) { if (it == 5) 0 else 1 }
        assertEquals(listOf(1000L, 3400L), tenute.map { it.scheduledEpoch })
        assertTrue(tenute.all { it.stopIndex == 5 })
    }

    @Test
    fun `corse diverse restano corse diverse`() {
        val righe = listOf(
            riga(1, stop = 5, epoch = 1000, posizione = 0),
            riga(2, stop = 9, epoch = 1100, posizione = 0),
            riga(3, stop = 5, epoch = 1200, posizione = 0),
        )
        val tenute = Departures.oneRowPerBus(righe) { if (it == 5) 0 else 1 }
        assertEquals(3, tenute.size)
    }

    /** Una riga finta: servono solo corsa, fermata, posizione e orario. */
    private fun riga(trip: Int, stop: Int, epoch: Long, posizione: Int) = NextDeparture(
        tripIndex = trip,
        patternIndex = 0,
        routeIndex = 0,
        stopIndex = stop,
        positionInPattern = posizione,
        scheduledEpoch = epoch,
        delaySeconds = null,
        certainty = null,
        canceled = false,
        skipped = false,
        monitored = false,
        line = "1",
        destination = "Gamma",
        colorRgb = 0,
        stopName = "fermata $stop",
    )

    @Test
    fun `una fermata senza passaggi da un tabellone vuoto, non un errore`() {
        BundleReader(busy()).use { r ->
            // D non e' servita da nessun pattern.
            val board = Departures.build(r, stopIndex = 3, now = at("07:50"))
            assertTrue(board.rows.isEmpty())
            assertEquals("Borgo Delta", board.stopName)
        }
    }
    @Test
    fun `un giorno fuori dalla validita' si dichiara, invece di sembrare notte`() {
        // Un bundle scaduto da' tabelloni vuoti dappertutto, e "nessun
        // passaggio nelle prossime due ore" e' la spiegazione sbagliata di un
        // problema che non ha niente a che fare con gli autobus: e' la stessa
        // frase che si legge alle tre di notte, quando invece e' vera. A
        // settembre 2026 il cancello del job notturno ha bloccato sette notti
        // di fila un feed sano.
        BundleReader(busy()).use { r ->
            val dentro = Departures.build(r, stopIndex = 0, now = at("08:00"))
            assertTrue(!dentro.outsideValidity, "questo giorno e' coperto")

            val dopo = Ftb.serviceDayStart(TestBundle.feedStart.plusDays(90))
                .plusSeconds(8 * 3600)
            val fuori = Departures.build(r, stopIndex = 0, now = dopo)
            assertTrue(fuori.outsideValidity, "questo giorno non e' coperto")
            assertTrue(fuori.rows.isEmpty())
        }
    }

    @Test
    fun `il tabellone unito dichiara la scadenza come quelli singoli`() {
        BundleReader(busy()).use { r ->
            val dopo = Ftb.serviceDayStart(TestBundle.feedStart.plusDays(90))
                .plusSeconds(8 * 3600)
            assertTrue(Departures.merged(r, listOf(0, 1), dopo).outsideValidity)
        }
    }

}
