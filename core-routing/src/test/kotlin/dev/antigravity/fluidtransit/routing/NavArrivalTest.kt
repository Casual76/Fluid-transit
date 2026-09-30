package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Sei arrivato" e' una frase che non si puo' ritirare: il servizio si ferma,
 * il GPS si spegne, e l'avviso forte e' partito. Questi test fissano da che
 * parte si sbaglia quando l'orologio non basta: restando a bordo.
 */
class NavArrivalTest {

    private fun hold(
        late: Long,
        followed: Boolean = false,
        passed: Boolean = false,
        skipped: Boolean = false,
        meters: Int = -1,
    ) = NavArrival.holdRide(late, followed, passed, skipped, meters)

    @Test
    fun `una corsa non seguita dal feed non si chiude sull'ora di tabella`() {
        // Bus in ritardo di cinque minuti, il feed non lo vede: all'ora di
        // tabella non si deve dire "Scendi qui".
        assertTrue(hold(late = 0))
        assertTrue(hold(late = 120))
        assertFalse(hold(late = NavArrival.GRACE_SECONDS))
    }

    @Test
    fun `una corsa seguita ha gia' il ritardo dentro l'orario`() {
        // Il feed ha detto +5: l'orario di discesa e' quello vero, e senza
        // posizione non c'e' motivo di aspettare oltre.
        assertFalse(hold(late = 0, followed = true))
    }

    @Test
    fun `lontano dalla discesa non si chiude mai sull'orologio`() {
        assertTrue(hold(late = 10 * 60, followed = true, meters = 2_000))
        assertTrue(hold(late = 10 * 60, followed = false, meters = 650))
        // Ma non per sempre: chi e' sceso prima non resta a vita su "scendi".
        assertFalse(hold(late = NavArrival.GRACE_FAR_SECONDS, meters = 2_000))
    }

    @Test
    fun `vicino alla discesa la posizione vince sulla tolleranza`() {
        assertFalse(hold(late = 30, meters = 120))
        assertFalse(hold(late = 30, meters = NavArrival.NEAR_METERS))
    }

    @Test
    fun `se il feed dice che la fermata e' servita non c'e' niente da aspettare`() {
        assertFalse(hold(late = 30, passed = true))
        assertFalse(hold(late = 30, passed = true, meters = 2_000))
    }

    @Test
    fun `una discesa saltata non e' un arrivo nemmeno col feed`() {
        assertTrue(hold(late = 60, followed = true, skipped = true))
        // Oltre la tolleranza corta si resta a bordo: la fermata alternativa
        // puo' essere a piu' di quattro minuti.
        assertTrue(hold(late = NavArrival.GRACE_SECONDS, followed = true, skipped = true))
        // Col bus che passa a meno di 300 m senza fermarsi la vicinanza non e'
        // un arrivo.
        assertTrue(hold(late = 30, skipped = true, meters = 120))
        assertTrue(hold(late = 30, followed = true, skipped = true, meters = NavArrival.NEAR_METERS))
        // Il tetto resta, quello largo.
        assertFalse(hold(late = NavArrival.GRACE_FAR_SECONDS, skipped = true, meters = 120))
        // Se il feed dichiara servita la fermata non c'e' niente da aspettare.
        assertFalse(hold(late = 30, passed = true, skipped = true))
    }

    @Test
    fun `la camminata dopo un bus in ritardo parte quando si scende`() {
        // Piano: si scende alle 10:00 e si cammina per 5 minuti. Il bus
        // arriva alle 10:06: la camminata non puo' risultare finita.
        val planStart = 10 * 3600L
        val alighted = planStart + 6 * 60
        assertEquals(alighted, NavArrival.walkStart(planStart, alighted))
        // Senza una corsa vista finire, vale il piano.
        assertEquals(planStart, NavArrival.walkStart(planStart, 0L))
    }

    @Test
    fun `l'alternativa alla discesa saltata e' la piu' vicina prima, poi dopo`() {
        // Fermate 0..9, discesa alla 6, saltate la 6 e la 5.
        val saltate = setOf(6, 5)
        val prima = assertNotNull(
            NavArrival.alternativeStop({ it in saltate }, from = 2, alight = 6, stopCount = 10),
        )
        assertEquals(4, prima.position)
        assertTrue(prima.before)

        // Se quelle prima sono gia' alle spalle (from) o saltate, si passa a dopo.
        val dopo = assertNotNull(
            NavArrival.alternativeStop({ it in saltate }, from = 5, alight = 6, stopCount = 10),
        )
        assertEquals(7, dopo.position)
        assertFalse(dopo.before)

        // Tutte saltate: nessuna alternativa, e chi chiama lo deve dire.
        assertNull(NavArrival.alternativeStop({ true }, from = 2, alight = 6, stopCount = 10))
    }

    @Test
    fun `le frasi della fermata saltata`() {
        assertEquals("La 20 non ferma a DUOMO", DepartureText.skippedLine("20", "DUOMO"))
        assertEquals("scendi prima, a PONTE", DepartureText.afterSkippedAlight("PONTE", true))
        assertEquals("scendi dopo, a PONTE", DepartureText.afterSkippedAlight("PONTE", false))
        assertEquals(
            "scendi alla fermata piu' vicina",
            DepartureText.afterSkippedAlight(null, true),
        )
    }
}
