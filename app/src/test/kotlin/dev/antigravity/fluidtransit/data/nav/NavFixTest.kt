package dev.antigravity.fluidtransit.data.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un punto con un errore di chilometri non e' una posizione: con la sola
 * posizione approssimativa, "Stai per arrivare" scattava a caso.
 */
class NavFixTest {

    private val recente = 5_000_000_000L

    @Test
    fun `un fix preciso e recente vale`() {
        assertTrue(NavFix.usable(recente, hasAccuracy = true, accuracyM = 12f))
        assertTrue(NavFix.usable(recente, hasAccuracy = true, accuracyM = NavFix.MAX_ACCURACY_M))
    }

    @Test
    fun `un fix approssimativo non da' distanze`() {
        // NETWORK_PROVIDER con la sola posizione approssimativa, Android 12+.
        assertFalse(NavFix.usable(recente, hasAccuracy = true, accuracyM = 2_000f))
        // Anche col GPS vero: un canyon urbano.
        assertFalse(NavFix.usable(recente, hasAccuracy = true, accuracyM = 200f))
    }

    @Test
    fun `senza accuratezza dichiarata non ci si fida`() {
        assertFalse(NavFix.usable(recente, hasAccuracy = false, accuracyM = 0f))
    }

    @Test
    fun `un fix vecchio non vale anche se preciso`() {
        assertFalse(
            NavFix.usable(NavFix.MAX_AGE_NANOS + 1, hasAccuracy = true, accuracyM = 5f),
        )
    }
}
