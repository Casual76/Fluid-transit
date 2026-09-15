package dev.antigravity.fluidtransit.data.rt

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il lettore delle previsioni per fermata.
 *
 * Stesso patto di `RtCodecTest`: qui si fissa il CONTRATTO del formato, e i
 * byte li costruisce il test. Che il proxy scriva davvero cosi' lo tiene
 * `worker/test/predictions.test.js`, che pinza gli stessi offset dall'altra
 * parte. I due test insieme sono il contratto; separatamente non dimostrano
 * niente.
 */
class RtPredictionCodecTest {

    // ---------------------------------------------------------- il formato

    @Test
    fun `una corsa con le sue previsioni si rilegge come era`() {
        val bytes = section(
            trips = listOf(
                trip(
                    tripHash = 0x0123456789ABCDEFL,
                    routeHash = 0x1122334455667788L,
                    startSec = 30_600,
                    status = 0,
                    direction = 1,
                    tripDelay = 240,
                    firstStopId32 = 0x11223344,
                    lastStopId32 = 0x55667788,
                    points = listOf(P(12, 300), P(14, 240), P(20, 60)),
                ),
            ),
        )

        val set = RtPredictionCodec.parse(bytes)
        val t = set.byTripHash.getValue(0x0123456789ABCDEFL)

        assertEquals(0x1122334455667788L, t.routeHash)
        assertEquals(30_600, t.startTimeSec)
        assertEquals(0, t.status)
        assertEquals(1, t.direction)
        assertEquals(240, t.tripDelaySec)
        assertEquals(0x11223344, t.firstStopId32)
        assertEquals(0x55667788, t.lastStopId32)
        assertEquals(listOf(12, 14, 20), t.points.map { it.stopSeq })
        assertEquals(listOf(300, 240, 60), t.points.map { it.delaySec })
    }

    @Test
    fun `i ritardi viaggiano come differenze e si risommano`() {
        // E' la ragione per cui la sezione pesa un terzo: i ritardi sono
        // numeri quasi casuali che gzip non comprime, gli scarti fra fermate
        // vicine sono piccoli e quasi tutti col byte alto a zero.
        val bytes = section(
            trips = listOf(trip(tripHash = 1L, points = listOf(P(1, 1_000), P(2, 1_030), P(3, 980)))),
        )

        val t = RtPredictionCodec.parse(bytes).byTripHash.getValue(1L)
        assertEquals(listOf(1_000, 1_030, 980), t.points.map { it.delaySec })
    }

    @Test
    fun `le differenze reggono anche a cavallo dello zero`() {
        val bytes = section(
            trips = listOf(trip(tripHash = 1L, points = listOf(P(1, 600), P(2, -600), P(3, 0)))),
        )
        val t = RtPredictionCodec.parse(bytes).byTripHash.getValue(1L)
        assertEquals(listOf(600, -600, 0), t.points.map { it.delaySec })
    }

    @Test
    fun `due corse non si rubano i punti`() {
        val bytes = section(
            trips = listOf(
                trip(tripHash = 1L, points = listOf(P(1, 60), P(2, 90))),
                trip(tripHash = 2L, points = listOf(P(7, -30))),
            ),
        )
        val set = RtPredictionCodec.parse(bytes)

        assertEquals(listOf(60, 90), set.byTripHash.getValue(1L).points.map { it.delaySec })
        assertEquals(listOf(-30), set.byTripHash.getValue(2L).points.map { it.delaySec })
        assertEquals(listOf(7), set.byTripHash.getValue(2L).points.map { it.stopSeq })
    }

    @Test
    fun `i sentinella restano sentinella`() {
        val bytes = section(
            trips = listOf(
                trip(
                    tripHash = 1L,
                    startSec = -1,
                    direction = null,
                    tripDelay = null,
                    firstStopId32 = 0,
                    lastStopId32 = 0,
                    points = listOf(P(seq = null, delay = 60, from = null)),
                ),
            ),
        )

        val t = RtPredictionCodec.parse(bytes).byTripHash.getValue(1L)
        assertEquals(-1, t.startTimeSec)
        assertEquals(-1, t.direction)
        assertEquals(null, t.tripDelaySec)
        assertEquals(0, t.firstStopId32)
        assertEquals(-1, t.points[0].stopSeq)
        assertEquals(-1, t.points[0].from)
    }

    @Test
    fun `zero e assente non sono la stessa cosa nel ritardo complessivo`() {
        val puntuale = RtPredictionCodec.parse(
            section(trips = listOf(trip(tripHash = 1L, tripDelay = 0, points = emptyList()))),
        ).byTripHash.getValue(1L)
        val muta = RtPredictionCodec.parse(
            section(trips = listOf(trip(tripHash = 2L, tripDelay = null, points = emptyList()))),
        ).byTripHash.getValue(2L)

        assertEquals(0, puntuale.tripDelaySec)
        assertEquals(null, muta.tripDelaySec)
    }

    @Test
    fun `saltata e senza dati si distinguono da una previsione normale`() {
        val bytes = section(
            trips = listOf(
                trip(
                    tripHash = 1L,
                    points = listOf(P(1, 60), P(2, 0, relation = 1), P(3, 0, relation = 2)),
                ),
            ),
        )
        val t = RtPredictionCodec.parse(bytes).byTripHash.getValue(1L)

        assertTrue(!t.points[0].skipped && !t.points[0].noData)
        assertTrue(t.points[1].skipped)
        assertTrue(t.points[2].noData)
    }

    @Test
    fun `una corsa cancellata si riconosce`() {
        val bytes = section(trips = listOf(trip(tripHash = 1L, status = 1, points = emptyList())))
        assertTrue(RtPredictionCodec.parse(bytes).byTripHash.getValue(1L).canceled)
    }

    @Test
    fun `una sezione tagliata lo dichiara`() {
        assertTrue(RtPredictionCodec.parse(section(trips = emptyList(), flags = 1)).truncated)
        assertTrue(!RtPredictionCodec.parse(section(trips = emptyList())).truncated)
    }

    @Test
    fun `una sezione vuota e' una risposta valida`() {
        val set = RtPredictionCodec.parse(section(trips = emptyList()))
        assertTrue(set.byTripHash.isEmpty())
    }

    @Test
    fun `una corsa che il feed non nomina non entra nella mappa`() {
        val bytes = section(
            trips = listOf(
                trip(tripHash = 0L, points = listOf(P(1, 60))),
                trip(tripHash = 5L, points = listOf(P(1, 90))),
            ),
        )
        val map = RtPredictionCodec.parse(bytes).byTripHash

        assertEquals(1, map.size)
        assertEquals(90, map.getValue(5L).points[0].delaySec)
    }

    // ------------------------------------------------ risposte che non vanno

    @Test
    fun `le risposte malformate si rifiutano invece di leggere spazzatura`() {
        assertRejected { RtPredictionCodec.parse(ByteArray(8)) }

        val wrongMagic = section(trips = emptyList()).also { it[1] = 'X'.code.toByte() }
        assertRejected { RtPredictionCodec.parse(wrongMagic) }

        val wrongVersion = section(trips = emptyList()).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(4, 9)
        }
        assertRejected { RtPredictionCodec.parse(wrongVersion) }

        val wrongKind = section(trips = emptyList()).also { it[6] = 2 }
        assertRejected { RtPredictionCodec.parse(wrongKind) }

        val wrongTripRecord = section(trips = emptyList()).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 48)
        }
        assertRejected { RtPredictionCodec.parse(wrongTripRecord) }
    }

    @Test
    fun `una sezione tronca si rifiuta`() {
        val full = section(trips = listOf(trip(tripHash = 1L, points = listOf(P(1, 60), P(2, 90)))))
        assertRejected { RtPredictionCodec.parse(full.copyOf(full.size - 4)) }
    }

    @Test
    fun `un indice dei punti fuori scala si rifiuta`() {
        // Non e' paranoia: e' la differenza fra rifiutare una risposta e
        // leggere la memoria accanto.
        val bytes = section(trips = listOf(trip(tripHash = 1L, points = listOf(P(1, 60)))))
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(32 + 20, 9_999)
        assertRejected { RtPredictionCodec.parse(bytes) }
    }

    // ------------------------------------------------------------ costruzione

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        } catch (_: IndexOutOfBoundsException) {
            return
        }
        throw AssertionError("la risposta malformata e' stata accettata")
    }

    private class P(val seq: Int?, val delay: Int, val relation: Int = 0, val from: Int? = 1)

    private class T(
        val tripHash: Long,
        val routeHash: Long,
        val startSec: Int,
        val status: Int,
        val direction: Int?,
        val tripDelay: Int?,
        val firstStopId32: Int,
        val lastStopId32: Int,
        val points: List<P>,
    )

    private fun trip(
        tripHash: Long,
        routeHash: Long = 0L,
        startSec: Int = 0,
        status: Int = 0,
        direction: Int? = 0,
        tripDelay: Int? = null,
        firstStopId32: Int = 0,
        lastStopId32: Int = 0,
        points: List<P> = emptyList(),
    ) = T(tripHash, routeHash, startSec, status, direction, tripDelay, firstStopId32, lastStopId32, points)

    /** Lo stesso layout di `buildPredictions` nel Worker. */
    private fun section(trips: List<T>, flags: Int = 0): ByteArray {
        val pointCount = trips.sumOf { it.points.size }
        val out = ByteArray(32 + trips.size * 40 + pointCount * 6)
        val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        out[0] = 'F'.code.toByte()
        out[1] = 'T'.code.toByte()
        out[2] = 'R'.code.toByte()
        out[3] = 'T'.code.toByte()
        b.putShort(4, 2)
        b.put(6, 4)
        b.put(7, 32)
        b.putInt(8, 1_000)
        b.putInt(12, 900)
        b.putInt(16, trips.size)
        b.putShort(20, 40)
        b.putShort(22, flags.toShort())
        b.putInt(24, pointCount)
        b.putShort(28, 6)

        val pointsOff = 32 + trips.size * 40
        var cursor = 0
        trips.forEachIndexed { i, t ->
            val o = 32 + i * 40
            b.putLong(o, t.tripHash)
            b.putLong(o + 8, t.routeHash)
            b.putInt(o + 16, t.startSec)
            b.putInt(o + 20, cursor)
            b.putShort(o + 24, t.points.size.toShort())
            b.put(o + 26, t.status.toByte())
            b.put(o + 27, (t.direction ?: 0xff).toByte())
            b.putShort(o + 28, (t.tripDelay ?: -32768).toShort())
            b.putInt(o + 32, t.firstStopId32)
            b.putInt(o + 36, t.lastStopId32)
            var previous = 0
            for (p in t.points) {
                val po = pointsOff + cursor * 6
                b.putShort(po, (p.seq ?: 0xffff).toShort())
                b.putShort(po + 2, (p.delay - previous).toShort())
                b.put(po + 4, p.relation.toByte())
                b.put(po + 5, (p.from ?: 0xff).toByte())
                previous = p.delay
                cursor++
            }
        }
        return out
    }
}
