package dev.antigravity.fluidtransit.data.rt

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Le previsioni fermata per fermata, come le manda il proxy.
 *
 * E' il lettore speculare di `worker/src/predictions.js`, e il pezzo che
 * cambia la risposta alla domanda "perche' i vostri minuti non sono quelli
 * ufficiali". Fino a qui il feed arrivava all'app ridotto a UN numero per
 * corsa, e come quel numero si propagasse lungo il percorso se lo inventava
 * l'app. Adesso arriva quello che il feed dice davvero.
 *
 * Misurato sul feed vero il 15/09/2026: lo stesso ritardo non si ripete mai
 * fra due fermate consecutive — scarto mediano 14 secondi, 75esimo percentile
 * 24. Il numero che ci inventavamo era quindi sistematicamente diverso dal
 * loro, e per le fermate lontane lungo la corsa la differenza cresce.
 *
 * Il formato, e perche' e' fatto cosi', stanno in testa a `predictions.js`.
 * Qui basti: un indice a record fissi ordinato per hash della corsa, che
 * punta dentro un blob di punti a record fissi; i ritardi viaggiano come
 * differenze e si risommano scorrendo; lo `stop_id` viaggia solo per il primo
 * e l'ultimo punto, come ancora da verificare.
 */

/** Una previsione, a una fermata. */
class RtPrediction(
    /** Lo `stop_sequence` dichiarato dal feed. -1 se assente. */
    val stopSeq: Int,
    /** Il ritardo in secondi, gia' risommato. */
    val delaySec: Int,
    /** 0 prevista, 1 SALTATA, 2 senza dati. */
    val relation: Int,
    /** 0 dall'arrivo, 1 dalla partenza, -1 da nessuno dei due. */
    val from: Int,
) {
    val skipped: Boolean get() = relation == REL_SKIPPED
    val noData: Boolean get() = relation == REL_NO_DATA

    companion object {
        const val REL_SCHEDULED = 0
        const val REL_SKIPPED = 1
        const val REL_NO_DATA = 2
    }
}

/** Quello che il feed dice di una corsa. */
class RtTripPrediction(
    val tripHash: Long,
    val routeHash: Long,
    val startTimeSec: Int,
    /** 0 ok, 1 cancellata, 2 aggiunta, 3 senza dati. */
    val status: Int,
    val direction: Int,
    /** Il ritardo complessivo dichiarato dal TripUpdate. null se assente. */
    val tripDelaySec: Int?,
    /**
     * Le due ancore: i 32 bit bassi dell'hash dello `stop_id` del primo e
     * dell'ultimo punto. Servono a VERIFICARE che la mappatura fra
     * `stop_sequence` e posizione nel pattern sia quella giusta, non a
     * cercare niente. 0 = il feed non ha dichiarato lo stop_id.
     */
    val firstStopId32: Int,
    val lastStopId32: Int,
    val points: List<RtPrediction>,
) {
    val canceled: Boolean get() = status == 1
}

class RtPredictionSet(
    val generatedAt: Long,
    val feedTimestamp: Long,
    /** La sezione e' stata troncata per stare nel tetto di peso. */
    val truncated: Boolean,
    val byTripHash: Map<Long, RtTripPrediction>,
)

object RtPredictionCodec {

    private const val KIND = 4
    private const val VERSION = 2
    private const val HEADER_LEN = 32
    private const val TRIP_RECORD = 40
    private const val POINT_RECORD = 6

    /** Assente, per il ritardo complessivo. */
    private const val NO_DELAY = -32768

    fun parse(bytes: ByteArray): RtPredictionSet {
        require(bytes.size >= HEADER_LEN) { "risposta troppo corta (${bytes.size} B)" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(
            bytes[0] == 'F'.code.toByte() && bytes[1] == 'T'.code.toByte() &&
                bytes[2] == 'R'.code.toByte() && bytes[3] == 'T'.code.toByte(),
        ) { "magic sbagliato" }
        val version = buf.getShort(4).toInt()
        require(version == VERSION) { "versione previsioni $version non supportata" }
        require(buf.get(6).toInt() == KIND) { "kind ${buf.get(6)}, attese le previsioni" }
        require(buf.get(7).toInt() == HEADER_LEN) { "testata da ${buf.get(7)} B" }

        val generatedAt = buf.getInt(8).toLong() and 0xffffffffL
        val feedTs = buf.getInt(12).toLong() and 0xffffffffL
        val tripCount = buf.getInt(16)
        val tripRecord = buf.getShort(20).toInt()
        val flags = buf.getShort(22).toInt() and 0xffff
        val pointCount = buf.getInt(24)
        val pointRecord = buf.getShort(28).toInt()
        require(tripRecord == TRIP_RECORD) { "record corsa da $tripRecord B" }
        require(pointRecord == POINT_RECORD) { "record previsione da $pointRecord B" }

        val pointsOff = HEADER_LEN + tripCount * TRIP_RECORD
        require(bytes.size >= pointsOff + pointCount * POINT_RECORD) { "sezione previsioni tronca" }

        val map = HashMap<Long, RtTripPrediction>(tripCount * 2)
        for (i in 0 until tripCount) {
            val o = HEADER_LEN + i * TRIP_RECORD
            val tripHash = buf.getLong(o)
            val start = buf.getInt(o + 16)
            val first = buf.getInt(o + 20)
            val n = buf.getShort(o + 24).toInt() and 0xffff
            val dir = buf.get(o + 27).toInt() and 0xff
            val overall = buf.getShort(o + 28).toInt()

            require(first >= 0 && first + n <= pointCount) { "indice dei punti fuori scala" }
            val points = ArrayList<RtPrediction>(n)
            // I ritardi sono differenze: si risommano scorrendo, che e'
            // l'ordine in cui si leggono comunque.
            var running = 0
            for (k in 0 until n) {
                val po = pointsOff + (first + k) * POINT_RECORD
                val seq = buf.getShort(po).toInt() and 0xffff
                running += buf.getShort(po + 2).toInt()
                val relation = buf.get(po + 4).toInt() and 0xff
                val from = buf.get(po + 5).toInt() and 0xff
                points.add(
                    RtPrediction(
                        stopSeq = if (seq == 0xffff) -1 else seq,
                        delaySec = running,
                        relation = relation,
                        from = if (from == 0xff) -1 else from,
                    ),
                )
            }

            if (tripHash == 0L) continue
            map[tripHash] = RtTripPrediction(
                tripHash = tripHash,
                routeHash = buf.getLong(o + 8),
                startTimeSec = if (start == -1) -1 else start,
                status = buf.get(o + 26).toInt() and 0xff,
                direction = if (dir == 0xff) -1 else dir,
                tripDelaySec = overall.takeIf { it != NO_DELAY },
                firstStopId32 = buf.getInt(o + 32),
                lastStopId32 = buf.getInt(o + 36),
                points = points,
            )
        }
        return RtPredictionSet(
            generatedAt = generatedAt,
            feedTimestamp = feedTs,
            truncated = (flags and FLAG_TRUNCATED) != 0,
            byTripHash = map,
        )
    }

    /** bit 0: la sezione e' stata tagliata per stare nel tetto di peso. */
    const val FLAG_TRUNCATED = 1
}
