package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Il tuo bus e' a tre fermate da te".
 *
 * E' la frase su cui si regge tutta la navigazione in attesa, e ha un modo
 * solo di essere sbagliata in modo grave: dire un numero quando non c'e'
 * nessun mezzo. Senza un feed che segua la corsa, la tabella dice dove il
 * bus DOVREBBE essere — che non e' dove e', e su una corsa mai partita non
 * e' proprio da nessuna parte.
 *
 * Per questo "non si sa" e' -1 e non 0: zero vuol dire "il bus e' alla tua
 * fermata", ed e' la frase che fa correre.
 */
class NavApproachTest {

    /** Dieci fermate, una ogni cinque minuti. */
    private val dayStart = 1_700_000_000L
    private val stopCount = 10
    private fun orario(position: Int) = dayStart + position * 300L
    private fun nome(position: Int) = "FERMATA $position"

    /** Un feed che dichiara servite le fermate fino a [servite] escluso. */
    private fun feed(servite: Int, delay: Int = 0, seguita: Boolean = true) = object : LiveTimes {
        override fun at(tripIndex: Int, position: Int, stopCount: Int, nowEpoch: Long) =
            if (position < servite) {
                LiveTimes.At(delay, Certainty.SERVED)
            } else {
                LiveTimes.At(delay, Certainty.DECLARED)
            }

        override fun monitored(tripIndex: Int, nowEpoch: Long): Boolean = seguita
    }

    private fun quante(
        live: LiveTimes?,
        toPosition: Int,
        now: Long,
        maxDots: Int = 6,
    ) = NavApproach.between(
        live = live,
        trip = 0,
        stopCount = stopCount,
        toPosition = toPosition,
        nowEpoch = now,
        maxDots = maxDots,
        name = ::nome,
        scheduledAt = ::orario,
    )

    @Test
    fun `le fermate fra il mezzo e me si contano dal feed quando il feed parla`() {
        val now = dayStart + 12 * 60
        val s = quante(feed(servite = 3), toPosition = 7, now = now)
        assertEquals(4, s.stopsAway)
        assertEquals(5, s.stops.size, "dalla posizione del bus fino alla mia compresa")
        assertEquals(3, s.stops.first().position)
        assertEquals(7, s.stops.last().position, "l'ultima della lista e' la mia")
        assertEquals("FERMATA 7", s.stops.last().name)
        assertEquals(orario(7), s.arrivalEpoch)
    }

    @Test
    fun `su una corsa in anticipo il conto segue le fermate servite, non l'orologio`() {
        // Alle 08:12 l'orologio direbbe che il bus e' alla terza fermata.
        // Il feed dice che ne ha gia' servite sei: chi guarda l'orologio
        // annuncia un bus a quattro fermate quando ne manca una sola, e
        // quello e' il bus che si perde.
        val now = dayStart + 12 * 60
        assertEquals(3, TripProgress.nextPosition(null, 0, stopCount, now) { orario(it) })
        val s = quante(feed(servite = 6), toPosition = 7, now = now)
        assertEquals(1, s.stopsAway)
    }

    @Test
    fun `un mezzo gia' oltre la mia fermata da' zero e non un numero negativo`() {
        // Il bus che ti e' appena sfuggito esiste, e non e' un errore da
        // nascondere: e' un numero da non far diventare "-6 fermate".
        val now = dayStart + 12 * 60
        val s = quante(feed(servite = 9), toPosition = 3, now = now)
        assertEquals(0, s.stopsAway)
        assertTrue(s.stops.isNotEmpty(), "la mia fermata resta nella lista")
        assertEquals(3, s.stops.last().position)
    }

    @Test
    fun `senza un feed che segua la corsa non si inventa un numero`() {
        val now = dayStart + 12 * 60
        val senza = quante(null, toPosition = 7, now = now)
        assertEquals(-1, senza.stopsAway)
        assertTrue(senza.stops.isEmpty())
        assertFalse(senza.known)

        // Il feed c'e' ma non sta seguendo QUESTA corsa: gli orari li sa,
        // il mezzo no. E' il caso piu' comune di tutti fuori dalle ore di
        // punta.
        val nonSeguita = quante(feed(servite = 3, seguita = false), toPosition = 7, now = now)
        assertEquals(-1, nonSeguita.stopsAway)
        assertTrue(nonSeguita.stops.isEmpty())
    }

    @Test
    fun `una corsa finita non ha piu' niente da aspettare`() {
        val now = dayStart + 3 * 3600
        assertEquals(-1, quante(feed(servite = 10), toPosition = 7, now = now).stopsAway)
    }

    @Test
    fun `l'elenco dei pallini non supera mai il tetto`() {
        // Dieci fermate in fila su uno schermo da 360 dp sono una riga di
        // puntini illeggibile. Si tiene dov'e' il bus e le ultime prima di
        // me; il buco in mezzo lo racconta un numero.
        val now = dayStart - 600
        val s = quante(feed(servite = 0), toPosition = 9, now = now, maxDots = 4)
        assertEquals(9, s.stopsAway, "il conto vero non si accorcia")
        assertEquals(4, s.stops.size)
        assertEquals(6, s.hidden)
        assertEquals(0, s.stops.first().position, "dov'e' il bus adesso")
        assertEquals(9, s.stops.last().position, "e dove sono io")
    }

    @Test
    fun `il ritardo del feed sposta l'ora di arrivo, non il conto delle fermate`() {
        val now = dayStart + 12 * 60
        val s = quante(feed(servite = 3, delay = 180), toPosition = 7, now = now)
        assertEquals(4, s.stopsAway)
        assertEquals(orario(7) + 180, s.arrivalEpoch)
        assertEquals(Certainty.DECLARED, s.certainty)
    }

    @Test
    fun `una posizione fuori dal pattern non risponde niente`() {
        val now = dayStart + 12 * 60
        assertEquals(-1, quante(feed(servite = 3), toPosition = 99, now = now).stopsAway)
        assertEquals(-1, quante(feed(servite = 3), toPosition = -1, now = now).stopsAway)
    }
}
