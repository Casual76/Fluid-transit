package dev.antigravity.fluidtransit.data.rt

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il lettore dello snapshot del proxy.
 *
 * E' il punto in cui due programmi scritti in linguaggi diversi devono essere
 * d'accordo byte per byte: il Worker scrive (`worker/src/snapshot.js`), l'app
 * legge. Finora nessuno dei due lati aveva un test, e un disallineamento di un
 * offset si sarebbe visto come "i bus stanno nel posto sbagliato" — cioe'
 * lontanissimo dalla causa.
 *
 * Qui si fissa il CONTRATTO, non la conformita' del Worker: i byte li
 * costruisce il test. Che i due lati parlino davvero la stessa lingua lo dira'
 * il banco di fedelta', che legge i byte veri.
 */
class RtCodecTest {

    // --------------------------------------------------------------- veicoli

    @Test
    fun `un veicolo completo si rilegge come era`() {
        val bytes = vehicles(
            generatedAt = 1_700_000_000L,
            feedTs = 1_699_999_940L,
            records = listOf(
                vehicleRecord(
                    tripHash = 0x0123456789ABCDEFL,
                    routeHash = 0x7766554433221100L,
                    lat = 43.7696,
                    lon = 11.2558,
                    bearing = 275,
                    ageSec = 42,
                    startSec = 27_000,
                    direction = 1,
                    speedMs = 8.3,
                    vehKey = 991_122,
                ),
            ),
        )

        val parsed = RtCodec.parseVehicles(bytes)

        assertEquals(1_700_000_000L, parsed.generatedAt)
        assertEquals(1_699_999_940L, parsed.feedTimestamp)
        assertEquals(1, parsed.list.size)
        val v = parsed.list[0]
        assertEquals(0x0123456789ABCDEFL, v.tripHash)
        assertEquals(0x7766554433221100L, v.routeHash)
        // Le coordinate viaggiano come interi a un milionesimo di grado: il
        // giro e' esatto alla sesta cifra, non oltre.
        assertEquals(43.7696, v.lat, 1e-6)
        assertEquals(11.2558, v.lon, 1e-6)
        assertEquals(275, v.bearingDeg)
        assertEquals(42, v.fixAgeSec)
        assertEquals(27_000, v.startTimeSec)
        assertEquals(1, v.direction)
        assertEquals(8.3, v.speedMs, 0.05)
        assertEquals(991_122, v.vehKey)
    }

    @Test
    fun `i campi che il feed non manda diventano meno uno, non zero`() {
        // Zero e' un valore legittimo per tutti e quattro (rotta a nord, fix
        // appena preso, partenza a mezzanotte, mezzo fermo): confonderlo con
        // "non lo so" farebbe disegnare frecce a caso e mezzi congelati.
        val bytes = vehicles(
            records = listOf(
                vehicleRecord(
                    tripHash = 1L,
                    routeHash = 2L,
                    lat = 43.0,
                    lon = 11.0,
                    bearing = null,
                    ageSec = null,
                    startSec = null,
                    direction = null,
                    speedMs = null,
                    vehKey = 7,
                ),
            ),
        )

        val v = RtCodec.parseVehicles(bytes).list.single()

        assertEquals(-1, v.bearingDeg)
        assertEquals(-1, v.fixAgeSec)
        assertEquals(-1, v.startTimeSec)
        assertEquals(-1, v.direction)
        assertEquals(-1.0, v.speedMs, 0.0)
    }

    @Test
    fun `i campi senza segno restano senza segno`() {
        // Rotta, eta' del fix e velocita' stanno su u16. Letti con segno,
        // qualunque valore sopra 32767 diventerebbe negativo — e un'eta'
        // negativa manda il mezzo a estrapolare all'indietro.
        val bytes = vehicles(
            records = listOf(
                vehicleRecord(
                    tripHash = 1L,
                    routeHash = 2L,
                    lat = 43.0,
                    lon = 11.0,
                    bearing = 359,
                    ageSec = 65_000,
                    startSec = 100_000,
                    direction = 0,
                    speedMs = 0.0,
                    vehKey = 1,
                ),
            ),
        )

        val v = RtCodec.parseVehicles(bytes).list.single()

        assertEquals(359, v.bearingDeg)
        assertEquals(65_000, v.fixAgeSec)
        assertEquals(100_000, v.startTimeSec)
        assertEquals(0, v.direction)
        assertEquals(0.0, v.speedMs, 0.0)
    }

    @Test
    fun `nessun veicolo e' una risposta valida, non un errore`() {
        val parsed = RtCodec.parseVehicles(vehicles(records = emptyList()))
        assertTrue(parsed.list.isEmpty())
    }

    // --------------------------------------------------------------- ritardi

    @Test
    fun `un ritardo completo si rilegge come era`() {
        val bytes = delays(
            records = listOf(
                delayRecord(
                    tripHash = 0x1111222233334444L,
                    routeHash = 0x5555666677778888L,
                    startSec = 30_600,
                    delaySec = 420,
                    status = 0,
                    direction = 0,
                    seq = 14,
                ),
            ),
        )

        val d = RtCodec.parseDelays(bytes).byTripHash.values.single()

        assertEquals(0x1111222233334444L, d.tripHash)
        assertEquals(0x5555666677778888L, d.routeHash)
        assertEquals(30_600, d.startTimeSec)
        assertEquals(420, d.delaySec)
        assertEquals(false, d.canceled)
        assertEquals(false, d.noData)
        assertEquals(0, d.direction)
        assertEquals(14, d.nextStopSeq)
    }

    @Test
    fun `un anticipo resta negativo`() {
        // delaySec sta su i16 e un bus in anticipo esiste: leggerlo senza
        // segno lo trasformerebbe in diciotto ore di ritardo.
        val bytes = delays(records = listOf(delayRecord(tripHash = 9L, delaySec = -180)))
        assertEquals(-180, RtCodec.parseDelays(bytes).byTripHash.getValue(9L).delaySec)
    }

    @Test
    fun `cancellata e senza dati sono due cose diverse`() {
        val canceled = RtCodec.parseDelays(
            delays(records = listOf(delayRecord(tripHash = 1L, status = 1))),
        ).byTripHash.getValue(1L)
        val noData = RtCodec.parseDelays(
            delays(records = listOf(delayRecord(tripHash = 2L, status = 3))),
        ).byTripHash.getValue(2L)

        assertTrue(canceled.canceled)
        assertTrue(!canceled.noData)
        assertTrue(noData.noData)
        assertTrue(!noData.canceled)
    }

    @Test
    fun `una corsa che il feed non nomina non entra nella mappa`() {
        // tripHash 0 vuol dire "il feed non dichiara la corsa": tenerlo
        // farebbe collassare tutte quelle corse su un'unica voce.
        val bytes = delays(
            records = listOf(
                delayRecord(tripHash = 0L, delaySec = 999),
                delayRecord(tripHash = 5L, delaySec = 60),
            ),
        )

        val map = RtCodec.parseDelays(bytes).byTripHash

        assertEquals(1, map.size)
        assertNull(map[0L])
        assertEquals(60, map.getValue(5L).delaySec)
    }

    @Test
    fun `la fermata successiva ignota e' meno uno`() {
        val bytes = delays(records = listOf(delayRecord(tripHash = 3L, seq = null)))
        assertEquals(-1, RtCodec.parseDelays(bytes).byTripHash.getValue(3L).nextStopSeq)
    }

    // ------------------------------------------------ risposte che non vanno

    @Test
    fun `una risposta con la firma sbagliata si rifiuta`() {
        val bytes = vehicles(records = emptyList())
        bytes[1] = 'X'.code.toByte()
        assertRejected { RtCodec.parseVehicles(bytes) }
    }

    @Test
    fun `una versione che non conosciamo si rifiuta`() {
        // E' la corsia di sicurezza per il giorno in cui il Worker cambia
        // formato: meglio nessun dato che dati letti col righello sbagliato.
        val bytes = vehicles(records = emptyList(), version = 2)
        assertRejected { RtCodec.parseVehicles(bytes) }
    }

    @Test
    fun `la sezione sbagliata si rifiuta`() {
        assertRejected { RtCodec.parseDelays(vehicles(records = emptyList())) }
        assertRejected { RtCodec.parseVehicles(delays(records = emptyList())) }
    }

    @Test
    fun `un record di dimensione diversa si rifiuta`() {
        val bytes = vehicles(records = emptyList())
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 44)
        assertRejected { RtCodec.parseVehicles(bytes) }
    }

    @Test
    fun `una sezione tagliata a meta' si rifiuta invece di leggere spazzatura`() {
        val full = vehicles(
            records = listOf(
                vehicleRecord(tripHash = 1L, routeHash = 2L, lat = 43.0, lon = 11.0, vehKey = 1),
                vehicleRecord(tripHash = 3L, routeHash = 4L, lat = 43.1, lon = 11.1, vehKey = 2),
            ),
        )
        assertRejected { RtCodec.parseVehicles(full.copyOf(full.size - 8)) }
    }

    @Test
    fun `una risposta piu' corta della testata si rifiuta`() {
        assertRejected { RtCodec.parseVehicles(ByteArray(12)) }
        assertRejected { RtCodec.parseDelays(ByteArray(0)) }
    }

    // ----------------------------------------------------------- costruzione

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

    /** Lo stesso layout di `buildSnapshot` + `sliceSection` nel Worker. */
    private fun section(
        kind: Int,
        recordSize: Int,
        records: List<ByteArray>,
        generatedAt: Long,
        feedTs: Long,
        version: Int,
    ): ByteArray {
        val out = ByteArray(24 + records.size * recordSize)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        out[0] = 'F'.code.toByte()
        out[1] = 'T'.code.toByte()
        out[2] = 'R'.code.toByte()
        out[3] = 'T'.code.toByte()
        buf.putShort(4, version.toShort())
        buf.put(6, kind.toByte())
        buf.putInt(8, generatedAt.toInt())
        buf.putInt(12, feedTs.toInt())
        buf.putInt(16, records.size)
        buf.putShort(20, recordSize.toShort())
        records.forEachIndexed { i, r -> r.copyInto(out, 24 + i * recordSize) }
        return out
    }

    private fun vehicles(
        records: List<ByteArray>,
        generatedAt: Long = 1_000L,
        feedTs: Long = 900L,
        version: Int = 1,
    ) = section(1, 40, records, generatedAt, feedTs, version)

    private fun delays(
        records: List<ByteArray>,
        generatedAt: Long = 1_000L,
        feedTs: Long = 900L,
        version: Int = 1,
    ) = section(2, 32, records, generatedAt, feedTs, version)

    private fun vehicleRecord(
        tripHash: Long,
        routeHash: Long,
        lat: Double,
        lon: Double,
        bearing: Int? = null,
        ageSec: Int? = null,
        startSec: Int? = null,
        direction: Int? = null,
        speedMs: Double? = null,
        vehKey: Int,
    ): ByteArray {
        val r = ByteArray(40)
        val b = ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN)
        b.putLong(0, tripHash)
        b.putLong(8, routeHash)
        b.putInt(16, Math.round(lat * 1e6).toInt())
        b.putInt(20, Math.round(lon * 1e6).toInt())
        b.putShort(24, (bearing ?: 0xffff).toShort())
        b.putShort(26, (ageSec ?: 0xffff).toShort())
        b.putInt(28, startSec ?: -1)
        b.put(32, (direction ?: 0xff).toByte())
        b.putShort(34, (speedMs?.let { Math.round(it * 10).toInt() } ?: 0xffff).toShort())
        b.putInt(36, vehKey)
        return r
    }

    private fun delayRecord(
        tripHash: Long,
        routeHash: Long = 0L,
        startSec: Int = -1,
        delaySec: Int = 0,
        status: Int = 0,
        direction: Int? = 0,
        seq: Int? = 1,
    ): ByteArray {
        val r = ByteArray(32)
        val b = ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN)
        b.putLong(0, tripHash)
        b.putLong(8, routeHash)
        b.putInt(16, startSec)
        b.putShort(20, delaySec.toShort())
        b.put(22, status.toByte())
        b.put(23, (direction ?: 0xff).toByte())
        b.putShort(24, (seq ?: 0xffff).toShort())
        return r
    }
}
