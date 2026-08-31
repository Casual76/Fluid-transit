package dev.antigravity.fluidtransit.data.rt

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * I dati realtime come arrivano dal proxy: record binari fissi, id come
 * hash FNV-1a 64 identici a quelli del bundle. Il formato e' documentato in
 * `worker/src/snapshot.js` — questo file e' il suo lettore speculare.
 */

/** Un veicolo vivo. I campi ignoti valgono -1 (o null dove indicato). */
class RtVehicle(
    val tripHash: Long, // 0 = il feed non dichiara la corsa
    val routeHash: Long, // 0 = il feed non dichiara la linea
    val lat: Double,
    val lon: Double,
    val bearingDeg: Int, // -1 = ignoto
    val fixAgeSec: Int, // eta' del fix alla generazione dello snapshot; -1 = ignota
    val startTimeSec: Int, // secondi dal giorno di servizio; -1 = ignoto
    val direction: Int, // -1 = ignota
    val speedMs: Double, // -1.0 = ignota
    val vehKey: Int, // chiave stabile del mezzo, per la continuita' visiva
)

/** Il ritardo corrente di una corsa, dal feed trip-updates. */
class RtDelay(
    val tripHash: Long,
    val routeHash: Long,
    val startTimeSec: Int,
    val delaySec: Int,
    val canceled: Boolean,
    val noData: Boolean,
    val direction: Int,
    val nextStopSeq: Int,
)

class RtVehicles(
    val generatedAt: Long,
    val feedTimestamp: Long,
    val list: List<RtVehicle>,
)

class RtDelays(
    val generatedAt: Long,
    val feedTimestamp: Long,
    val byTripHash: Map<Long, RtDelay>,
)

object RtCodec {

    private const val KIND_VEHICLES = 1
    private const val KIND_DELAYS = 2

    private fun header(bytes: ByteArray, expectedKind: Int): ByteBuffer {
        require(bytes.size >= 24) { "risposta troppo corta (${bytes.size} B)" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(
            bytes[0] == 'F'.code.toByte() && bytes[1] == 'T'.code.toByte() &&
                bytes[2] == 'R'.code.toByte() && bytes[3] == 'T'.code.toByte(),
        ) { "magic sbagliato" }
        val version = buf.getShort(4).toInt()
        require(version == 1) { "versione snapshot $version non supportata" }
        val kind = buf.get(6).toInt()
        require(kind == expectedKind) { "kind $kind, atteso $expectedKind" }
        return buf
    }

    fun parseVehicles(bytes: ByteArray): RtVehicles {
        val buf = header(bytes, KIND_VEHICLES)
        val generatedAt = buf.getInt(8).toLong() and 0xffffffffL
        val feedTs = buf.getInt(12).toLong() and 0xffffffffL
        val count = buf.getInt(16)
        val recordSize = buf.getShort(20).toInt()
        require(recordSize == 40) { "record veicolo da $recordSize B" }
        require(bytes.size >= 24 + count * recordSize) { "sezione veicoli tronca" }
        val list = ArrayList<RtVehicle>(count)
        for (i in 0 until count) {
            val o = 24 + i * recordSize
            val bearing = buf.getShort(o + 24).toInt() and 0xffff
            val age = buf.getShort(o + 26).toInt() and 0xffff
            val start = buf.getInt(o + 28)
            val dir = buf.get(o + 32).toInt() and 0xff
            val speed = buf.getShort(o + 34).toInt() and 0xffff
            list.add(
                RtVehicle(
                    tripHash = buf.getLong(o),
                    routeHash = buf.getLong(o + 8),
                    lat = buf.getInt(o + 16) / 1e6,
                    lon = buf.getInt(o + 20) / 1e6,
                    bearingDeg = if (bearing == 0xffff) -1 else bearing,
                    fixAgeSec = if (age == 0xffff) -1 else age,
                    startTimeSec = if (start == -1) -1 else start,
                    direction = if (dir == 0xff) -1 else dir,
                    speedMs = if (speed == 0xffff) -1.0 else speed / 10.0,
                    vehKey = buf.getInt(o + 36),
                ),
            )
        }
        return RtVehicles(generatedAt, feedTs, list)
    }

    fun parseDelays(bytes: ByteArray): RtDelays {
        val buf = header(bytes, KIND_DELAYS)
        val generatedAt = buf.getInt(8).toLong() and 0xffffffffL
        val feedTs = buf.getInt(12).toLong() and 0xffffffffL
        val count = buf.getInt(16)
        val recordSize = buf.getShort(20).toInt()
        require(recordSize == 32) { "record ritardo da $recordSize B" }
        require(bytes.size >= 24 + count * recordSize) { "sezione ritardi tronca" }
        val map = HashMap<Long, RtDelay>(count * 2)
        for (i in 0 until count) {
            val o = 24 + i * recordSize
            val start = buf.getInt(o + 16)
            val status = buf.get(o + 22).toInt() and 0xff
            val dir = buf.get(o + 23).toInt() and 0xff
            val seq = buf.getShort(o + 24).toInt() and 0xffff
            val d = RtDelay(
                tripHash = buf.getLong(o),
                routeHash = buf.getLong(o + 8),
                startTimeSec = if (start == -1) -1 else start,
                delaySec = buf.getShort(o + 20).toInt(),
                canceled = status == 1,
                noData = status == 3,
                direction = if (dir == 0xff) -1 else dir,
                nextStopSeq = if (seq == 0xffff) -1 else seq,
            )
            if (d.tripHash != 0L) map[d.tripHash] = d
        }
        return RtDelays(generatedAt, feedTs, map)
    }
}
