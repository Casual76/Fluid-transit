package dev.antigravity.fluidtransit.routing

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Lo scambio del bundle a viaggio in corso.
 *
 * La navigazione segue indici, e gli indici cambiano di notte: basta che il
 * feed aggiunga una corsa prima della tua perche' la tua scivoli di un posto,
 * e "la corsa 0" diventi quella delle sette invece che quella delle otto.
 * Qui si prova che la tappa si ritrova per hash, e che quando non si ritrova
 * la risposta e' null e non un'altra corsa.
 */
class NavRebindTest {

    private val tmp = ArrayList<File>()

    @AfterTest
    fun pulisci() {
        tmp.forEach { it.delete() }
    }

    private val stopHash = TestBundle.stopIds.map { Ftb.hash64(it) }

    /** Ieri: la corsa del mattino e quella di notte. */
    private fun ieri(patternStops: List<Int> = listOf(0, 1, 2)) = BundleReader(
        TestBundle.write(tmp, patternStops = patternStops),
    )

    /** Oggi: una corsa in piu' alle sette, che spinge in avanti le altre. */
    private fun oggi(
        tripIds: List<String> = listOf("R1-early", "R1-morning", "R1-night"),
        patternStops: List<Int> = listOf(0, 1, 2),
    ) = BundleReader(
        TestBundle.write(
            tmp,
            tripIds = tripIds,
            dep0s = listOf(7 * 3600, 8 * 3600, 25 * 3600 + 30 * 60).take(tripIds.size),
            patternStops = patternStops,
        ),
    )

    @Test
    fun `l'hash di una corsa torna quello del suo trip_id`() {
        ieri().use { r ->
            for (id in TestBundle.tripIds) {
                val t = r.findTripByTripId(id)
                assertEquals(Ftb.hash64(id), r.tripIdHash(t), id)
            }
            assertEquals(0L, r.tripIdHash(99), "una corsa che non c'e' non ha hash")
        }
    }

    @Test
    fun `dopo lo scambio la corsa si ritrova anche se ha cambiato indice`() {
        val vecchio = ieri()
        val nuovo = oggi()
        vecchio.use { a ->
            nuovo.use { b ->
                val tripA = a.findTripByTripId("R1-morning")
                val tripB = b.findTripByTripId("R1-morning")
                check(tripA != tripB) { "il test ha senso solo se l'indice cambia" }

                val r = NavRebind.ride(
                    reader = b,
                    tripHash = a.tripIdHash(tripA),
                    boardStopHash = stopHash[0],
                    alightStopHash = stopHash[2],
                    boardPosition = 0,
                    alightPosition = 2,
                )
                assertNotNull(r)
                assertEquals(tripB, r.trip, "la stessa corsa, non quella che ne ha preso il posto")
                assertEquals(0, r.boardPosition)
                assertEquals(2, r.alightPosition)
                assertEquals(8 * 3600, r.dep0)
            }
        }
    }

    @Test
    fun `una corsa che negli orari nuovi non c'e' piu' non si sostituisce con un'altra`() {
        oggi(tripIds = listOf("R1-early", "R1-night")).use { b ->
            val r = NavRebind.ride(
                reader = b,
                tripHash = Ftb.hash64("R1-morning"),
                boardStopHash = stopHash[0],
                alightStopHash = stopHash[2],
                boardPosition = 0,
                alightPosition = 2,
            )
            assertNull(r)
        }
    }

    @Test
    fun `una corsa che non tocca piu' la fermata di discesa non si ritrova`() {
        // Il percorso accorciato: la corsa c'e' ancora, ma la fermata dove
        // dovevo scendere non la tocca. Seguirla vorrebbe dire non dire mai
        // "scendi".
        oggi(patternStops = listOf(0, 1)).use { b ->
            val r = NavRebind.ride(
                reader = b,
                tripHash = Ftb.hash64("R1-morning"),
                boardStopHash = stopHash[0],
                alightStopHash = stopHash[2],
                boardPosition = 0,
                alightPosition = 2,
            )
            assertNull(r)
        }
    }

    @Test
    fun `la discesa sta dopo la salita, anche se la fermata compare prima`() {
        // Sull'anello A, B, C, B, A: salgo in C e scendo in B. La B che
        // conta e' quella DOPO la C, non quella dell'andata — anche se il
        // bundle vecchio, con un pattern diverso, la metteva alla posizione 1:
        // la vicinanza al vecchio indice non puo' vincere sull'ordine.
        oggi(patternStops = listOf(0, 1, 2, 1, 0)).use { b ->
            val r = NavRebind.ride(
                reader = b,
                tripHash = Ftb.hash64("R1-morning"),
                boardStopHash = stopHash[2],
                alightStopHash = stopHash[1],
                boardPosition = 2,
                alightPosition = 1,
            )
            assertNotNull(r)
            assertEquals(2, r.boardPosition)
            assertEquals(3, r.alightPosition)
        }
    }

    @Test
    fun `sull'anello fra due passaggi si sceglie quello piu' vicino a dov'era`() {
        // Salgo in B al ritorno (posizione 3) e scendo in A (4): la B
        // dell'andata e' la stessa fermata, ma un altro momento della corsa.
        // E la A della partenza, prima della salita, non puo' essere la
        // discesa anche se il vecchio indice la indicava.
        oggi(patternStops = listOf(0, 1, 2, 1, 0)).use { b ->
            val r = NavRebind.ride(
                reader = b,
                tripHash = Ftb.hash64("R1-morning"),
                boardStopHash = stopHash[1],
                alightStopHash = stopHash[0],
                boardPosition = 3,
                alightPosition = 0,
            )
            assertNotNull(r)
            assertEquals(3, r.boardPosition)
            assertEquals(4, r.alightPosition)
        }
    }

    @Test
    fun `una discesa che negli orari nuovi viene solo prima della salita non si ritrova`() {
        // Il verso opposto: la corsa tocca ancora le due fermate, ma
        // nell'ordine sbagliato. Seguirla vorrebbe dire aspettare un
        // "scendi" su una fermata gia' passata.
        oggi(patternStops = listOf(0, 1, 2)).use { b ->
            assertNull(NavRebind.ride(b, Ftb.hash64("R1-morning"), stopHash[2], stopHash[0], 2, 0))
        }
    }

    @Test
    fun `la posizione piu' vicina si cerca solo da dove si puo'`() {
        val a = 11L
        val bb = 22L
        val c = 33L
        assertNull(NavRebind.nearest(longArrayOf(a, bb, c), a, from = 1, near = 0))
        assertEquals(3, NavRebind.nearest(longArrayOf(a, bb, c, bb, a), bb, from = 3, near = 1))
        assertEquals(1, NavRebind.nearest(longArrayOf(a, bb, c, bb, a), bb, from = 0, near = 0))
    }

    @Test
    fun `senza hash non si ritrova niente`() {
        // I piani fatti prima di questa versione non li hanno: meglio
        // saperlo che cercare lo zero.
        oggi().use { b ->
            assertNull(NavRebind.ride(b, 0L, stopHash[0], stopHash[2], 0, 2))
        }
    }
}
