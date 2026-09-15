package dev.antigravity.fluidtransit.data.departures

import dev.antigravity.fluidtransit.data.rt.RtPrediction
import dev.antigravity.fluidtransit.data.rt.RtTripPrediction
import dev.antigravity.fluidtransit.routing.Certainty
import dev.antigravity.fluidtransit.routing.LiveTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La regola di propagazione di GTFS-RT, applicata alle nostre fermate.
 *
 * E' il contratto nuovo: **quando il feed dice qualcosa, si dice quello che
 * dice il feed**. Prima arrivava un numero per corsa e l'app si inventava come
 * propagarlo — un recupero del 30% verso il capolinea, salvo prove contrarie.
 * Era una stima ragionevole, e non era quella delle app ufficiali: i minuti
 * potevano non combaciare anche quando il feed era d'accordo.
 *
 * Il modello di prima resta, come ripiego dichiarato: a schermo la differenza
 * si legge, "dal bus" contro "stimato".
 */
class LiveFromPredictionsTest {

    private val now = 1_700_000_000L

    /** Il ripiego: risponde sempre, cosi' si vede quando viene interrogato. */
    private object Stima : LiveTimes {
        override fun at(
            tripIndex: Int,
            position: Int,
            stopCount: Int,
            nowEpoch: Long,
        ) = LiveTimes.At(999, Certainty.ESTIMATED)
    }

    /** Il ripiego muto: non sa niente. */
    private object Muto : LiveTimes {
        override fun at(
            tripIndex: Int,
            position: Int,
            stopCount: Int,
            nowEpoch: Long,
        ): LiveTimes.At? = null
    }

    private fun point(seq: Int, delay: Int, relation: Int = RtPrediction.REL_SCHEDULED) =
        RtPrediction(stopSeq = seq, delaySec = delay, relation = relation, from = 1)

    private fun trip(vararg points: RtPrediction, status: Int = 0) = RtTripPrediction(
        tripHash = 1L,
        routeHash = 2L,
        startTimeSec = 28_800,
        status = status,
        direction = 0,
        tripDelaySec = null,
        firstStopId32 = 0,
        lastStopId32 = 0,
        points = points.toList(),
    )

    /** Uno scarto di 1: la sequenza 1 del feed e' la posizione 0 del pattern. */
    private fun live(
        trip: RtTripPrediction,
        offset: Int? = 1,
        fallback: LiveTimes = Muto,
        feedTs: Long = now - 60,
    ) = LiveFromPredictions(
        byTrip = mapOf(7 to LiveFromPredictions.Resolved(trip, offset)),
        fallback = fallback,
        canceledTrips = emptySet(),
        withVehicle = emptySet(),
        feedTimestamp = feedTs,
    )

    // ---------------------------------------------------- la regola in se'

    @Test
    fun `una previsione per QUESTA fermata si dichiara tale`() {
        val l = live(trip(point(1, 60), point(5, 180)))
        val at = l.at(tripIndex = 7, position = 0, stopCount = 10, nowEpoch = now)!!

        assertEquals(60, at.delaySeconds)
        assertEquals(Certainty.DECLARED, at.certainty)
    }

    @Test
    fun `una previsione a monte arriva fin qui, e si sa che e' arrivata`() {
        // La regola di GTFS-RT: vale per tutte le fermate successive finche'
        // non ce n'e' un'altra.
        val l = live(trip(point(1, 60), point(5, 180)))
        val at = l.at(tripIndex = 7, position = 2, stopCount = 10, nowEpoch = now)!!

        assertEquals(60, at.delaySeconds, )
        assertEquals(Certainty.PROPAGATED, at.certainty)
    }

    @Test
    fun `la previsione successiva subentra alla sua fermata`() {
        val l = live(trip(point(1, 60), point(5, 180), point(8, 30)))

        assertEquals(60, l.at(7, 3, 10, now)!!.delaySeconds, )
        assertEquals(180, l.at(7, 4, 10, now)!!.delaySeconds, )
        assertEquals(180, l.at(7, 6, 10, now)!!.delaySeconds, )
        assertEquals(30, l.at(7, 7, 10, now)!!.delaySeconds, )
        assertEquals(30, l.at(7, 9, 10, now)!!.delaySeconds, )
    }

    @Test
    fun `una fermata prima della prima previsione e' gia' passata`() {
        // Gli update partono dalla prossima fermata: se la prima previsione e'
        // per la sequenza 5, le fermate prima il mezzo le ha gia' fatte, e
        // quel ritardo non dice niente su quando passera' li'.
        val l = live(trip(point(5, 180)))
        val at = l.at(tripIndex = 7, position = 1, stopCount = 10, nowEpoch = now)!!

        assertEquals(Certainty.SERVED, at.certainty)
    }

    @Test
    fun `un ritardo negativo resta un anticipo`() {
        val l = live(trip(point(1, -120)))
        assertEquals(-120, l.at(7, 0, 10, now)!!.delaySeconds)
    }

    // -------------------------------------------------- quando si ripiega

    @Test
    fun `una corsa che il feed non copre passa alla stima`() {
        val l = live(trip(point(1, 60)), fallback = Stima)
        val at = l.at(tripIndex = 99, position = 0, stopCount = 10, nowEpoch = now)!!

        assertEquals(999, at.delaySeconds)
        assertEquals(Certainty.ESTIMATED, at.certainty)
    }

    @Test
    fun `senza scarto verificato si ripiega, invece di indovinare`() {
        // Attribuire un ritardo alla fermata sbagliata e' l'errore che non si
        // vede: i minuti restano plausibili, solo appartengono a un'altra
        // fermata. Meglio dire "stimato".
        val l = live(trip(point(1, 60)), offset = null, fallback = Stima)
        assertEquals(Certainty.ESTIMATED, l.at(7, 0, 10, now)!!.certainty)
    }

    @Test
    fun `una previsione senza dati riporta alla stima da li' in poi`() {
        val l = live(
            trip(point(1, 60), point(4, 0, RtPrediction.REL_NO_DATA)),
            fallback = Stima,
        )

        assertEquals(Certainty.DECLARED, l.at(7, 0, 10, now)!!.certainty)
        assertEquals(Certainty.ESTIMATED, l.at(7, 4, 10, now)!!.certainty)
    }

    @Test
    fun `una previsione vecchia non e' la previsione di adesso`() {
        val l = live(trip(point(1, 60)), fallback = Stima, feedTs = now - 3_600)
        assertEquals(Certainty.ESTIMATED, l.at(7, 0, 10, now)!!.certainty)
    }

    @Test
    fun `senza previsioni e senza ripiego non si inventa niente`() {
        val l = live(trip(point(1, 60)))
        assertNull(l.at(tripIndex = 42, position = 0, stopCount = 10, nowEpoch = now))
    }

    // ------------------------------------------------- saltate e cancellate

    @Test
    fun `una fermata saltata si dichiara`() {
        // Il fatto nuovo: la corsa c'e' ma non passa di qui.
        val l = live(trip(point(1, 60), point(3, 0, RtPrediction.REL_SKIPPED)))

        assertTrue(l.skipped(tripIndex = 7, position = 2))
        assertTrue(!l.skipped(tripIndex = 7, position = 0))
        assertTrue(!l.skipped(tripIndex = 99, position = 2))
    }

    @Test
    fun `senza scarto verificato non si dichiara nessuna fermata saltata`() {
        val l = live(trip(point(3, 0, RtPrediction.REL_SKIPPED)), offset = null)
        assertTrue(!l.skipped(tripIndex = 7, position = 2))
    }

    @Test
    fun `una corsa cancellata dal feed si riconosce`() {
        val l = live(trip(point(1, 60), status = 1))
        assertTrue(l.canceled(tripIndex = 7))
        assertTrue(!l.canceled(tripIndex = 99))
    }

    @Test
    fun `una corsa con previsioni e' per definizione seguita`() {
        val l = live(trip(point(1, 60)))
        assertTrue(l.monitored(tripIndex = 7))
        assertTrue(!l.monitored(tripIndex = 99))
    }

    // ------------------------------------------------------- casi al limite

    @Test
    fun `una corsa senza punti non fa saltare niente`() {
        val l = live(trip(), fallback = Stima)
        assertEquals(Certainty.ESTIMATED, l.at(7, 0, 10, now)!!.certainty)
    }

    @Test
    fun `i punti senza sequenza si saltano senza confondere gli altri`() {
        val l = live(trip(point(-1, 900), point(2, 60)))
        assertEquals(60, l.at(7, 1, 10, now)!!.delaySeconds)
        assertEquals(Certainty.DECLARED, l.at(7, 1, 10, now)!!.certainty)
    }
}
