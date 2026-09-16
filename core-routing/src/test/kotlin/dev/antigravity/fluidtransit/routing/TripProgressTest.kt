package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Qual e' la prossima fermata.
 *
 * La stessa domanda aveva tre risposte diverse: la scheda della corsa
 * credeva al feed, il piano di "sono su questo bus" e la navigazione a bordo
 * guardavano solo l'orologio. Su una corsa in anticipo — il feed dichiara
 * servita una fermata il cui orario di tabella non e' ancora arrivato — le
 * due risposte differiscono di una fermata, e quella che si perde e'
 * proprio quella verso cui il bus sta andando.
 */
class TripProgressTest {

    /** Dieci fermate, una ogni cinque minuti, a partire dalle 08:00. */
    private val dayStart = 1_700_000_000L
    private val stopCount = 10
    private fun orario(position: Int) = dayStart + position * 300L

    /** Un feed che dichiara servite le fermate fino a [servite] escluso. */
    private fun feed(servite: Int, delay: Int = 0) = object : LiveTimes {
        override fun at(tripIndex: Int, position: Int, stopCount: Int, nowEpoch: Long) =
            if (position < servite) {
                LiveTimes.At(delay, Certainty.SERVED)
            } else {
                LiveTimes.At(delay, Certainty.DECLARED)
            }
    }

    @Test
    fun `senza feed decide l'orologio`() {
        // Le 08:12: le fermate delle 08:00, 08:05 e 08:10 sono passate.
        val now = dayStart + 12 * 60
        val pos = TripProgress.nextPosition(null, 0, stopCount, now) { orario(it) }
        assertEquals(3, pos, "la prossima e' quella delle 08:15")
    }

    @Test
    fun `il ritardo sposta avanti la prossima fermata`() {
        // Le 08:12 con dieci minuti di ritardo: il bus e' ancora fra la
        // seconda e la terza, e quella delle 08:10 passera' alle 08:20.
        val now = dayStart + 12 * 60
        val pos = TripProgress.nextPosition(null, 0, stopCount, now, fallbackDelaySeconds = 600) {
            orario(it)
        }
        assertEquals(1, pos, "in ritardo, la prossima e' ancora la seconda")
    }

    @Test
    fun `quando il feed parla vince il feed`() {
        // Corsa in anticipo: il feed dichiara servite le prime sei fermate,
        // ma l'orologio ne vedrebbe passate solo tre. Chi guardava l'orologio
        // faceva salire la persona a una fermata che il bus aveva gia'
        // superato.
        val now = dayStart + 12 * 60
        val pos = TripProgress.nextPosition(feed(servite = 6), 0, stopCount, now) { orario(it) }
        assertEquals(6, pos)
    }

    @Test
    fun `una corsa finita non ha una prossima fermata`() {
        // Il caso che rendeva morto il tasto "sono su questo bus": il ciclo
        // non trovava nessuna fermata futura e lasciava la posizione a zero,
        // cioe' proponeva di salire al capolinea di partenza di un'ora prima.
        val now = dayStart + 3 * 3600
        assertEquals(-1, TripProgress.nextPosition(null, 0, stopCount, now) { orario(it) })
        assertEquals(-1, TripProgress.nextPosition(feed(servite = 10), 0, stopCount, now) { orario(it) })
    }

    @Test
    fun `la corsa non ancora partita comincia dalla prima`() {
        val now = dayStart - 600
        assertEquals(0, TripProgress.nextPosition(null, 0, stopCount, now) { orario(it) })
    }

    @Test
    fun `il minuto di grazia tiene la fermata sotto il bus`() {
        // Il mezzo fermo ALLA fermata ha gia' l'orario di partenza alle
        // spalle di qualche secondo: farla sparire vuol dire far sparire
        // quella dove si sta salendo.
        assertFalse(TripProgress.served(null, dayStart, dayStart + 30))
        assertTrue(TripProgress.served(null, dayStart, dayStart + 61))
    }
}
