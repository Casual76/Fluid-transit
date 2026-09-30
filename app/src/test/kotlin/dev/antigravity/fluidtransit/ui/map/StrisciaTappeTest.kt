package dev.antigravity.fluidtransit.ui.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quando la striscia delle tappe toglie i minuti delle camminate.
 *
 * La striscia era una `Row` e i minuti si toglievano solo da tre corse in su,
 * una soglia tarata sul corpo normale del testo: col corpo grande le ultime
 * pastiglie non si disegnavano. Adesso la striscia va a capo e non perde piu'
 * niente; la regola resta per tenerla su una riga finche' si puo', senza
 * togliere i minuti al viaggio con un cambio, che e' il piu' comune.
 */
class StrisciaTappeTest {

    private val normale = 1.0f
    private val grande = 1.3f
    private val enorme = 2.0f

    @Test
    fun `una corsa sola non toglie mai i minuti`() {
        // A piedi, bus, a piedi.
        assertFalse(stripCompact(rides = 1, walkLegs = 2, fontScale = normale))
        assertFalse(stripCompact(rides = 1, walkLegs = 2, fontScale = grande))
        assertFalse(stripCompact(rides = 1, walkLegs = 2, fontScale = enorme))
    }

    @Test
    fun `due corse con la camminata di cambio in mezzo non tolgono i minuti`() {
        // Bus, a piedi, bus: una sola camminata, la striscia e' corta.
        assertFalse(stripCompact(rides = 2, walkLegs = 1, fontScale = normale))
        assertFalse(stripCompact(rides = 2, walkLegs = 1, fontScale = enorme))
    }

    @Test
    fun `due corse con tre camminate stanno larghe a corpo normale`() {
        // A piedi, bus, a piedi, bus, a piedi: il caso che prima si stringeva
        // solo a tre corse e ora si stringe solo quando il testo cresce.
        assertFalse(stripCompact(rides = 2, walkLegs = 3, fontScale = normale))
    }

    @Test
    fun `due corse con tre camminate si stringono col corpo grande`() {
        assertTrue(stripCompact(rides = 2, walkLegs = 3, fontScale = grande))
        assertTrue(stripCompact(rides = 2, walkLegs = 3, fontScale = enorme))
    }

    @Test
    fun `tre corse si stringono sempre`() {
        assertTrue(stripCompact(rides = 3, walkLegs = 0, fontScale = normale))
        assertTrue(stripCompact(rides = 3, walkLegs = 2, fontScale = normale))
        assertTrue(stripCompact(rides = 4, walkLegs = 5, fontScale = enorme))
    }

    @Test
    fun `la soglia e' esattamente 1,15 e non prima`() {
        // 1,15 non e' "grande": e' l'ingrandimento di un telefono con il
        // testo appena piu' alto del solito.
        assertFalse(stripCompact(rides = 2, walkLegs = 3, fontScale = 1.15f))
        assertTrue(stripCompact(rides = 2, walkLegs = 3, fontScale = 1.16f))
    }
}
