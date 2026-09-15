package dev.antigravity.fluidtransit.data.rt

import java.io.ByteArrayOutputStream

/**
 * Un scrittore di protobuf minimo, per i test.
 *
 * L'app legge GTFS-RT a mano sul wire format, senza le classi generate
 * (`GtfsRtLite`): per provarla serviva un modo di FABBRICARE quei byte, e
 * tirare dentro protobuf-java solo per i test avrebbe messo in gioco un
 * secondo lettore — cioe' avrebbe provato la nostra lettura contro la
 * scrittura di qualcun altro invece che contro il formato.
 *
 * Sono quaranta righe: tag = (campo &lt;&lt; 3) | tipo, varint LEB128, e i
 * sottomessaggi che si misurano da soli.
 */
class Proto {

    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): Proto {
        tag(field, 0)
        writeVarint(value)
        return this
    }

    fun string(field: Int, value: String): Proto = bytes(field, value.toByteArray())

    fun bytes(field: Int, value: ByteArray): Proto {
        tag(field, 2)
        writeVarint(value.size.toLong())
        out.write(value)
        return this
    }

    /** float a 32 bit, little endian: e' cosi' che viaggiano le coordinate. */
    fun float(field: Int, value: Float): Proto {
        tag(field, 5)
        val bits = java.lang.Float.floatToIntBits(value)
        for (i in 0 until 4) out.write((bits ushr (8 * i)) and 0xff)
        return this
    }

    fun message(field: Int, block: Proto.() -> Unit): Proto =
        bytes(field, Proto().apply(block).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wire: Int) = writeVarint(((field shl 3) or wire).toLong())

    private fun writeVarint(value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7f).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b)
                return
            }
            out.write(b or 0x80)
        }
    }
}

/** Un veicolo del feed, coi campi che l'app legge davvero. */
class VehicleFixture(
    val tripId: String? = null,
    val routeId: String? = null,
    val startTime: String? = null,
    val directionId: Int? = null,
    val lat: Float? = 43.7696f,
    val lon: Float? = 11.2558f,
    val bearing: Float? = null,
    val speed: Float? = null,
    val timestamp: Long? = null,
    val vehicleId: String? = null,
)

/** Un `FeedMessage` di vehicle-positions come lo manda l'origine. */
fun vehiclePositionsFeed(feedTimestamp: Long, vehicles: List<VehicleFixture>): ByteArray {
    val feed = Proto()
    feed.message(1) {
        string(1, "2.0")
        varint(3, feedTimestamp)
    }
    vehicles.forEachIndexed { i, v ->
        feed.message(2) {
            string(1, "entity-$i")
            message(4) {
                if (v.tripId != null || v.routeId != null ||
                    v.startTime != null || v.directionId != null
                ) {
                    message(1) {
                        v.tripId?.let { string(1, it) }
                        v.startTime?.let { string(2, it) }
                        v.routeId?.let { string(5, it) }
                        v.directionId?.let { varint(6, it.toLong()) }
                    }
                }
                if (v.lat != null || v.lon != null) {
                    message(2) {
                        v.lat?.let { float(1, it) }
                        v.lon?.let { float(2, it) }
                        v.bearing?.let { float(3, it) }
                        v.speed?.let { float(5, it) }
                    }
                }
                v.timestamp?.let { varint(5, it) }
                v.vehicleId?.let { id -> message(8) { string(1, id) } }
            }
        }
    }
    return feed.toByteArray()
}
