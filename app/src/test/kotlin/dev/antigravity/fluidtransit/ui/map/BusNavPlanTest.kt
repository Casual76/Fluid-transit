package dev.antigravity.fluidtransit.ui.map

import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Certainty
import dev.antigravity.fluidtransit.routing.Ftb
import dev.antigravity.fluidtransit.routing.LiveTimes
import dev.antigravity.fluidtransit.routing.TestBundle
import java.io.File
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Il piano di "sono su questo bus".
 *
 * Due difetti, sulla stessa manciata di righe. Il primo: la corsa gia'
 * finita non veniva mai riconosciuta — il ciclo cercava la prima fermata
 * ancora da fare e, non trovandone nessuna, lasciava la posizione a zero,
 * cioe' costruiva un viaggio che partiva dal capolinea di ore prima. Il
 * messaggio scritto apposta per quel caso ("questa corsa e' finita") non e'
 * mai comparso a nessuno, perche' la condizione che lo accendeva —
 * `boardPos >= n - 1` — non poteva essere vera: quel valore non supera mai
 * `n - 2`.
 *
 * Il secondo: si guardava solo l'orologio. Il feed dice fin dove il mezzo e'
 * arrivato davvero, e la lista di fermate mostrata subito sopra gli credeva
 * gia': su una corsa in anticipo le due cose sullo stesso schermo
 * rispondevano con due fermate diverse.
 */
class BusNavPlanTest {

    private val tmp = ArrayList<File>()

    @After
    fun pulisci() {
        tmp.forEach { it.delete() }
    }

    /** L'inizio del giorno di servizio di adesso, come lo calcola l'app. */
    private fun dayStart(): Long {
        val today = Instant.now().atZone(Ftb.ROME).toLocalDate()
        return Ftb.serviceDayStart(today).epochSecond
    }

    /**
     * Una corsa che parte [secondiFa] secondi fa. Le tre fermate della rete
     * di prova sono a 0, 120 e 240 secondi dalla partenza.
     */
    private fun corsa(secondiFa: Long): BundleReader {
        val dep0 = (Instant.now().epochSecond - dayStart() - secondiFa).toInt()
        return BundleReader(TestBundle.write(tmp, listOf("in-viaggio"), listOf(dep0)))
    }

    /** Un feed che dichiara servite le fermate fino a [servite] escluso. */
    private fun feed(servite: Int) = object : LiveTimes {
        override fun at(tripIndex: Int, position: Int, stopCount: Int, nowEpoch: Long) =
            LiveTimes.At(0, if (position < servite) Certainty.SERVED else Certainty.DECLARED)
    }

    @Test
    fun `una corsa finita non produce nessun piano`() {
        // Partita un'ora fa, dura quattro minuti: e' finita da un pezzo.
        corsa(secondiFa = 3600).use { r ->
            assertNull(
                "un viaggio che parte dal capolinea di un'ora fa non e' un viaggio",
                buildBusNavPlan(r, 0, delaySec = 0, live = null),
            )
        }
    }

    @Test
    fun `a meta' corsa si sale dalla fermata appena lasciata`() {
        // Partita 200 s fa: la prima e la seconda fermata sono passate, la
        // terza arriva fra quaranta secondi.
        corsa(secondiFa = 200).use { r ->
            val plan = buildBusNavPlan(r, 0, delaySec = 0, live = null)
            assertNotNull(plan)
            val leg = plan!!.legs.first() as dev.antigravity.fluidtransit.data.nav.NavLeg.Ride
            assertEquals(1, leg.boardPosition)
            assertEquals(2, leg.alightPosition)
        }
    }

    @Test
    fun `quando il feed dichiara le fermate servite si crede al feed`() {
        // Partita 100 s fa: l'orologio vede passata solo la prima fermata, e
        // il piano partirebbe da li'. Il feed dice che il mezzo ha gia'
        // servito anche la seconda — e' in anticipo — e allora si sale da
        // quella, come mostra la lista di fermate sopra il tasto.
        corsa(secondiFa = 100).use { r ->
            val soloOrologio = buildBusNavPlan(r, 0, delaySec = 0, live = null)
            val legOrologio =
                soloOrologio!!.legs.first() as dev.antigravity.fluidtransit.data.nav.NavLeg.Ride
            assertEquals(0, legOrologio.boardPosition)

            val colFeed = buildBusNavPlan(r, 0, delaySec = 0, live = feed(servite = 2))
            val legFeed =
                colFeed!!.legs.first() as dev.antigravity.fluidtransit.data.nav.NavLeg.Ride
            assertEquals(1, legFeed.boardPosition)
        }
    }
}
