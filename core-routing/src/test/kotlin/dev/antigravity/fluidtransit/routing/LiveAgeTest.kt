package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L'eta' "di adesso", non quella del momento del poll.
 *
 * Il difetto: con l'app in secondo piano l'assistente leggeva uno snapshot di
 * ore prima con l'eta' congelata a quella di allora, e il suo "posizione di X
 * fa" (soglia 180 s) non scattava mai.
 */
class LiveAgeTest {

    @Test
    fun `l'eta' del feed cresce col tempo passato dal poll`() {
        // Vista 40 s al poll delle 1000; alle 1600 sono passati altri 600 s.
        assertEquals(640L, LiveAge.feedNow(40L, 1000L, 1600L))
        assertEquals(40L, LiveAge.feedNow(40L, 1000L, 1000L))
    }

    @Test
    fun `un'eta' ignota resta ignota e un orologio indietro non la accorcia`() {
        assertNull(LiveAge.feedNow(null, 1000L, 1600L))
        assertEquals(40L, LiveAge.feedNow(40L, 2000L, 1600L))
        assertEquals(40L, LiveAge.feedNow(40L, null, 1600L))
    }

    @Test
    fun `il rilevamento di un mezzo invecchia dopo la risoluzione`() {
        // Risolto subito dopo la generazione (10 s): 20 s di rilevamento, 10 min dopo e' 620.
        assertEquals(20, LiveAge.fixNow(20, resolvedAtEpoch = 1010L, generatedAtEpoch = 1000L, nowEpoch = 1010L))
        assertEquals(620, LiveAge.fixNow(20, resolvedAtEpoch = 1010L, generatedAtEpoch = 1000L, nowEpoch = 1610L))
    }

    @Test
    fun `il tetto della risoluzione si recupera`() {
        // Risolto quando lo snapshot aveva gia' 900 s: la risoluzione ne ha contati 300, ne mancano 600.
        assertEquals(20 + 600, LiveAge.fixNow(20, resolvedAtEpoch = 1900L, generatedAtEpoch = 1000L, nowEpoch = 1900L))
    }

    @Test
    fun `un rilevamento di eta' ignota non si inventa`() {
        assertEquals(-1, LiveAge.fixNow(-1, 1000L, 1000L, 5000L))
    }

    @Test
    fun `lo snapshot vecchio di piu' di dieci minuti e' troppo vecchio`() {
        assertFalse(LiveAge.snapshotStale(generatedAtEpoch = 1000L, resolvedAtEpoch = 1005L, nowEpoch = 1500L))
        assertTrue(LiveAge.snapshotStale(generatedAtEpoch = 1000L, resolvedAtEpoch = 1005L, nowEpoch = 1700L))
        // Senza data di generazione vale il momento della risoluzione.
        assertTrue(LiveAge.snapshotStale(generatedAtEpoch = 0L, resolvedAtEpoch = 1000L, nowEpoch = 1700L))
    }
}
