package dev.antigravity.fluidtransit.data.rt

import dev.antigravity.fluidtransit.routing.Ftb
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Il parser GTFS-RT del fallback DIRECT: quando il proxy non risponde, l'app
 * legge vehicle-positions direttamente dall'origine.
 *
 * Scritto a mano sul wire format per lo stesso motivo del Worker: il
 * `parseFrom` generato costruisce centinaia di migliaia di oggetti (200-500
 * ms e tempesta di GC, dice il piano); qui si leggono i soli campi usati e
 * si saltano gli altri contando i byte. Il risultato e' lo stesso
 * [RtVehicles] che produrrebbe il proxy — a valle nessuno sa la differenza.
 */
object GtfsRtLite {

    fun parseVehiclePositions(bytes: ByteArray, nowEpochSec: Long): RtVehicles {
        val r = Cursor(bytes)
        var feedTs = 0L
        val list = ArrayList<RtVehicle>(2048)
        while (r.hasMore()) {
            val tag = r.varint()
            when (tag.field) {
                1 -> { // FeedHeader: timestamp(3)
                    val end = r.messageEnd()
                    while (r.pos < end) {
                        val t2 = r.varint()
                        if (t2.field == 3 && t2.wire == 0) feedTs = r.varintValue() else r.skip(t2.wire)
                    }
                }

                2 -> { // FeedEntity: vehicle(4)
                    val end = r.messageEnd()
                    while (r.pos < end) {
                        val t2 = r.varint()
                        if (t2.field == 4 && t2.wire == 2) {
                            parseVehicle(r, nowEpochSec)?.let { list.add(it) }
                        } else {
                            r.skip(t2.wire)
                        }
                    }
                }

                else -> r.skip(tag.wire)
            }
        }
        return RtVehicles(generatedAt = nowEpochSec, feedTimestamp = feedTs, list = list)
    }

    private fun parseVehicle(r: Cursor, nowEpochSec: Long): RtVehicle? {
        val end = r.messageEnd()
        var tripHash = 0L
        var routeHash = 0L
        var startTime = -1
        var direction = -1
        var lat = Double.NaN
        var lon = Double.NaN
        var bearing = -1
        var speed = -1.0
        var ts = 0L
        var vehKey = 0
        while (r.pos < end) {
            val t = r.varint()
            when {
                t.field == 1 && t.wire == 2 -> { // TripDescriptor
                    val tEnd = r.messageEnd()
                    while (r.pos < tEnd) {
                        val t2 = r.varint()
                        when {
                            t2.field == 1 && t2.wire == 2 -> tripHash = Ftb.hash64(r.string())
                            t2.field == 2 && t2.wire == 2 -> startTime = parseStartTime(r.string())
                            t2.field == 5 && t2.wire == 2 -> routeHash = Ftb.hash64(r.string())
                            t2.field == 6 && t2.wire == 0 -> direction = r.varintValue().toInt()
                            else -> r.skip(t2.wire)
                        }
                    }
                }

                t.field == 2 && t.wire == 2 -> { // Position
                    val pEnd = r.messageEnd()
                    while (r.pos < pEnd) {
                        val t2 = r.varint()
                        if (t2.wire == 5) {
                            val v = r.float32()
                            when (t2.field) {
                                1 -> lat = v.toDouble()
                                2 -> lon = v.toDouble()
                                3 -> bearing = ((v.toInt() % 360) + 360) % 360
                                5 -> speed = v.toDouble()
                            }
                        } else {
                            r.skip(t2.wire)
                        }
                    }
                }

                t.field == 5 && t.wire == 0 -> ts = r.varintValue()

                t.field == 8 && t.wire == 2 -> { // VehicleDescriptor: id(1)
                    val vEnd = r.messageEnd()
                    while (r.pos < vEnd) {
                        val t2 = r.varint()
                        if (t2.field == 1 && t2.wire == 2) vehKey = fnv32(r.string()) else r.skip(t2.wire)
                    }
                }

                else -> r.skip(t.wire)
            }
        }
        if (lat.isNaN() || lon.isNaN()) return null
        return RtVehicle(
            tripHash = tripHash,
            routeHash = routeHash,
            lat = lat,
            lon = lon,
            bearingDeg = bearing,
            fixAgeSec = if (ts > 0) (nowEpochSec - ts).coerceIn(0, 0xfffe).toInt() else -1,
            startTimeSec = startTime,
            direction = direction,
            speedMs = speed,
            vehKey = vehKey,
        )
    }

    private fun parseStartTime(s: String): Int {
        val parts = s.split(':')
        if (parts.size != 3) return -1
        val h = parts[0].toIntOrNull() ?: return -1
        val m = parts[1].toIntOrNull() ?: return -1
        val sec = parts[2].toIntOrNull() ?: return -1
        return h * 3600 + m * 60 + sec
    }

    /** La stessa FNV-1a 32 del Worker, per la chiave-veicolo. */
    private fun fnv32(s: String): Int {
        var h = 0x811c9dc5.toInt()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toInt() and 0xff)
            h *= 0x01000193
        }
        return h
    }

    private class Tag(val field: Int, val wire: Int)

    private class Cursor(private val bytes: ByteArray) {
        var pos = 0
        private val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        fun hasMore() = pos < bytes.size

        fun varint(): Tag {
            val v = rawVarint()
            return Tag(field = (v ushr 3).toInt(), wire = (v and 7L).toInt())
        }

        private fun rawVarint(): Long {
            var shift = 0
            var out = 0L
            while (true) {
                val b = bytes[pos++].toInt()
                out = out or ((b.toLong() and 0x7f) shl shift)
                if (b >= 0) return out
                shift += 7
                require(shift <= 63) { "varint troppo lungo" }
            }
        }

        /** Il varint come valore (per timestamp e enum). */
        fun varintValue(): Long = rawVarint()

        fun messageEnd(): Int {
            val len = rawVarint().toInt()
            return pos + len
        }

        fun string(): String {
            val end = messageEnd()
            val s = String(bytes, pos, end - pos, Charsets.UTF_8)
            pos = end
            return s
        }

        fun float32(): Float {
            val f = view.getFloat(pos)
            pos += 4
            return f
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> rawVarint()
                1 -> pos += 8
                2 -> pos = messageEnd()
                5 -> pos += 4
                else -> error("wire type $wire inatteso")
            }
        }
    }

}
