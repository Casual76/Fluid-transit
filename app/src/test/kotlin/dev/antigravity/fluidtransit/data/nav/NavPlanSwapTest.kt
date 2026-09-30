package dev.antigravity.fluidtransit.data.nav

import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.TestBundle
import dev.antigravity.fluidtransit.ui.nav.taglia
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il viaggio in corso quando gli orari si scambiano, e il fuoco che ne
 * discende.
 *
 * La navigazione segue indici dentro un file, e a fine settembre 2026 il
 * file si scambiava verso mezzogiorno — cioe' mentre la gente viaggia. Qui
 * si prova che il piano si rilegge sul bundle nuovo, e che quando non si
 * puo' la risposta e' "non si puo'", non un'altra corsa.
 */
class NavPlanSwapTest {

    private val tmp = ArrayList<File>()

    @After
    fun pulisci() {
        tmp.forEach { it.delete() }
    }

    /** JUnit 4 non restituisce il valore: qui serve, e null e' gia' un difetto. */
    private fun <T> nn(v: T?): T {
        assertNotNull("atteso un valore, e' arrivato null", v)
        return v!!
    }

    private fun ieri() = BundleReader(TestBundle.write(tmp))

    /** Una corsa in piu' alle sette: quella delle otto scivola di un posto. */
    private fun oggi(tripIds: List<String> = listOf("R1-early", "R1-morning", "R1-night")) =
        BundleReader(
            TestBundle.write(
                tmp,
                tripIds = tripIds,
                dep0s = listOf(7 * 3600, 8 * 3600, 25 * 3600 + 30 * 60).take(tripIds.size),
            ),
        )

    /** Una tappa costruita come la costruisce `buildNavPlan`. */
    private fun ride(r: BundleReader, tripId: String, board: Int = 0, alight: Int = 2): NavLeg.Ride {
        val trip = r.findTripByTripId(tripId)
        val pattern = r.tripPattern(trip)
        return NavLeg.Ride(
            trip = trip,
            pattern = pattern,
            route = r.patternRoute(pattern),
            boardPosition = board,
            alightPosition = alight,
            dayStartEpoch = 1_789_000_000L,
            dep0 = r.tripDeparture0(trip),
            profile = r.tripProfile(trip),
            lineName = "1",
            alightName = r.stopName(r.patternStop(pattern, alight)),
            stopNames = (board..alight).map { r.stopName(r.patternStop(pattern, it)) },
            tripHash = r.tripIdHash(trip),
            boardStopHash = r.stopIdHash(r.patternStop(pattern, board)),
            alightStopHash = r.stopIdHash(r.patternStop(pattern, alight)),
        )
    }

    private fun walk() = NavLeg.Walk(seconds = 120, toName = "Piazza Alfa", startEpoch = 0L)

    @Test
    fun `sullo stesso bundle il piano resta lui`() {
        ieri().use { r ->
            val p = NavPlan("journey", "Corso Gamma", listOf(ride(r, "R1-morning")), r.buildId)
            assertSame(p, p.reboundTo(r, current = 0))
        }
    }

    @Test
    fun `dopo lo scambio la tappa punta alla stessa corsa col suo indice nuovo`() {
        val a = ieri()
        val b = oggi()
        a.use {
            b.use {
                val p = NavPlan("journey", "Corso Gamma", listOf(walk(), ride(a, "R1-morning")), a.buildId)
                val nuovo = nn(p.reboundTo(b, current = 0))
                val leg = nuovo.legs[1] as NavLeg.Ride
                assertEquals(b.findTripByTripId("R1-morning"), leg.trip)
                assertEquals(b.buildId, nuovo.buildId)
                assertEquals(1_789_000_000L, leg.dayStartEpoch)
                assertEquals(listOf("Piazza Alfa", "Via Beta", "Corso Gamma"), leg.stopNames)
                assertTrue("la camminata resta com'era", nuovo.legs[0] is NavLeg.Walk)
            }
        }
    }

    @Test
    fun `se la tappa in corso non c'e' piu' il piano non si rilegge`() {
        val a = ieri()
        val b = oggi(tripIds = listOf("R1-early", "R1-night"))
        a.use {
            b.use {
                val p = NavPlan("journey", "Corso Gamma", listOf(ride(a, "R1-morning")), a.buildId)
                assertNull(p.reboundTo(b, current = 0))
            }
        }
    }

    @Test
    fun `una tappa gia' fatta che non si ritrova resta, ma senza indici`() {
        // Primo bus fatto, adesso si aspetta il secondo: la prima corsa negli
        // orari nuovi non c'e', la seconda si'. Fermare tutto per un pezzo
        // di viaggio gia' percorso sarebbe peggio.
        val a = ieri()
        val b = oggi(tripIds = listOf("R1-early", "R1-night"))
        a.use {
            b.use {
                val p = NavPlan(
                    "journey",
                    "Corso Gamma",
                    listOf(ride(a, "R1-morning"), walk(), ride(a, "R1-night")),
                    a.buildId,
                )
                val nuovo = nn(p.reboundTo(b, current = 1))
                val fatta = nuovo.legs[0] as NavLeg.Ride
                assertTrue(fatta.orphaned)
                assertEquals(-1, fatta.route)
                val prossima = nuovo.legs[2] as NavLeg.Ride
                assertEquals(b.findTripByTripId("R1-night"), prossima.trip)
            }
        }
    }

    @Test
    fun `un piano senza hash non si ritrova`() {
        // I piani della versione di prima: meglio fermarsi e dirlo che
        // cercare lo zero.
        val a = ieri()
        val b = oggi()
        a.use {
            b.use {
                val vecchio = ride(a, "R1-morning")
                val senza = NavLeg.Ride(
                    trip = vecchio.trip, pattern = vecchio.pattern, route = vecchio.route,
                    boardPosition = 0, alightPosition = 2, dayStartEpoch = 0L,
                    dep0 = vecchio.dep0, profile = vecchio.profile, lineName = "1",
                    alightName = "Corso Gamma", stopNames = emptyList(),
                )
                val p = NavPlan("journey", "Corso Gamma", listOf(senza), a.buildId)
                assertNull(p.reboundTo(b, current = 0))
            }
        }
    }

    // ------------------------------------------------------------- il fuoco

    @Test
    fun `il fuoco accende solo le fermate fra salita e discesa`() {
        ieri().use { r ->
            val p = NavPlan("journey", "Corso Gamma", listOf(ride(r, "R1-morning", 0, 1)), r.buildId)
            val f = nn(buildNavFocus(r, p, 0))
            assertEquals(2, f.stopHashes.size)
            assertEquals(0, f.boardPosition)
            assertEquals(1, f.alightPosition)
            assertTrue("la mia linea sta fra quelle accese", f.usefulRouteHashes.contains(f.routeHashHex))
            assertTrue("nessuna alternativa su una rete di una linea", f.useful.isEmpty())
        }
    }

    @Test
    fun `una discesa oltre il capolinea si riporta dentro il pattern`() {
        ieri().use { r ->
            val p = NavPlan("journey", "Corso Gamma", listOf(ride(r, "R1-morning", 0, 2)), r.buildId)
            val largo = p.legs[0] as NavLeg.Ride
            val fuori = NavPlan(
                "journey",
                "Corso Gamma",
                listOf(
                    NavLeg.Ride(
                        trip = largo.trip, pattern = largo.pattern, route = largo.route,
                        boardPosition = 0, alightPosition = 9, dayStartEpoch = 0L,
                        dep0 = largo.dep0, profile = largo.profile, lineName = "1",
                        alightName = "Corso Gamma", stopNames = emptyList(),
                    ),
                ),
                r.buildId,
            )
            val f = nn(buildNavFocus(r, fuori, 0))
            assertEquals(2, f.alightPosition)
        }
    }

    @Test
    fun `salita e discesa alla stessa fermata non fanno un fuoco`() {
        ieri().use { r ->
            val p = NavPlan("journey", "Via Beta", listOf(ride(r, "R1-morning", 1, 1)), r.buildId)
            assertNull(buildNavFocus(r, p, 0))
        }
    }

    @Test
    fun `una camminata non ha un fuoco`() {
        ieri().use { r ->
            val p = NavPlan("journey", "Piazza Alfa", listOf(walk()), r.buildId)
            assertNull(buildNavFocus(r, p, 0))
        }
    }

    // ------------------------------------------------------------- la scia

    @Test
    fun `la scia si taglia dove sta il mezzo`() {
        ieri().use { r ->
            val p = NavPlan("journey", "Corso Gamma", listOf(ride(r, "R1-morning", 1, 2)), r.buildId)
            val f = nn(buildNavFocus(r, p, 0))
            val path = nn(f.path)
            check(f.sAlight > f.sBoard) { "la tratta ha una lunghezza" }

            // Il bus deve ancora arrivare da me: niente di fatto, tutto da
            // fare, e il pezzo fra lui e me tratteggiato.
            val prima = taglia(path, f, sBus = f.sBoard / 2)
            assertNull(prima.done)
            assertNotNull(prima.remaining)
            assertNotNull(prima.approach)

            // A meta' della mia tratta: un pezzo fatto, un pezzo da fare.
            val meta = taglia(path, f, sBus = (f.sBoard + f.sAlight) / 2)
            assertNotNull(meta.done)
            assertNotNull(meta.remaining)
            assertNull(meta.approach)
        }
    }
}
