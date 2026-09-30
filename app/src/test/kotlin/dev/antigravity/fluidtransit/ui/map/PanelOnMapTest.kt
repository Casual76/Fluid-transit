package dev.antigravity.fluidtransit.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Cosa il pannello ritrovato dice alla mappa di accendere.
 *
 * Il difetto si vedeva solo dopo una ricreazione dell'Activity o una visita
 * ad Avvisi: la scheda della linea c'era, la mappa era la rete intera. La
 * derivazione e' l'unica parte che si puo' provare senza una mappa vera, ed
 * e' quella che decide se un pannello lascia la mappa nuda.
 */
class PanelOnMapTest {

    private val luogo = PlaceRef("Ponte all'Asse", "Firenze", 43.78, 11.22)

    private fun corsa(routeIndex: Int) = TripRef(
        vehKey = 555,
        tripHash = 1L,
        routeHash = 2L,
        tripIndex = 3,
        routeIndex = routeIndex,
    )

    @Test
    fun `la scheda di una linea accende la linea, ridotta o intera`() {
        assertEquals(23, PanelOnMap.of(Panel.RouteMini(23)).routeIndex)
        assertEquals(23, PanelOnMap.of(Panel.RouteFull(23)).routeIndex)
        assertNull(PanelOnMap.of(Panel.RouteMini(23)).vehKey)
        assertNull(PanelOnMap.of(Panel.RouteMini(23)).place)
    }

    @Test
    fun `la scheda di una corsa accende la linea e tiene in evidenza il bus`() {
        for (p in listOf(Panel.TripMini(corsa(12)), Panel.TripFull(corsa(12)))) {
            val m = PanelOnMap.of(p)
            assertEquals(12, m.routeIndex)
            assertEquals(555, m.vehKey)
            assertNull(m.place)
        }
    }

    @Test
    fun `una corsa di una linea sconosciuta ha il bus e nessuna tratta`() {
        val m = PanelOnMap.of(Panel.TripMini(corsa(-1)))
        assertEquals(-1, m.routeIndex)
        assertEquals(555, m.vehKey)
    }

    @Test
    fun `un luogo rimette il segnaposto e nient'altro`() {
        val m = PanelOnMap.of(Panel.Place(luogo))
        assertEquals(-1, m.routeIndex)
        assertNull(m.vehKey)
        assertSame(luogo, m.place)
    }

    @Test
    fun `gli altri pannelli non chiedono niente alla mappa`() {
        val nessuno = listOf<Panel?>(
            null,
            Panel.Stop(StopTap("abc", "Duomo")),
            Panel.Nearby,
            Panel.Journeys(luogo),
            Panel.JourneyDetail(luogo, 0),
        )
        for (p in nessuno) {
            val m = PanelOnMap.of(p)
            assertEquals(-1, m.routeIndex)
            assertNull(m.vehKey)
            assertNull(m.place)
        }
    }
}
