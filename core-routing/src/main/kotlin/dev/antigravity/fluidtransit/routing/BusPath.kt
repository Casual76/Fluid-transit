package dev.antigravity.fluidtransit.routing

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * La geometria di un pattern pronta per farci correre sopra un bus: i
 * vertici, l'ascissa curvilinea di ognuno, e le ascisse delle fermate.
 *
 * Tutto il conto sta su un piano locale (equirettangolare centrato sulla
 * tratta): su venti chilometri l'errore e' sotto il metro e costa due
 * moltiplicazioni invece di una haversine per segmento — e qui si campiona
 * a 8 Hz per qualche centinaio di mezzi.
 */
class PathIndex private constructor(
    private val lat: DoubleArray,
    private val lon: DoubleArray,
    /** Ascissa curvilinea di ogni vertice, in metri. Monotona crescente. */
    private val cum: DoubleArray,
    /** Ascissa di ogni fermata del pattern, in ordine di percorrenza. */
    val stopS: DoubleArray,
    private val mx: Double,
    private val my: Double,
) {
    val length: Double get() = cum[cum.size - 1]

    val vertexCount: Int get() = lat.size

    /**
     * Dove sta e verso dove guarda un mezzo all'ascissa [s]. Riempie [out]
     * con (lat, lon, rotta in gradi): nessuna allocazione, perche' questo
     * gira per ogni bus a ogni fotogramma.
     */
    fun sample(s: Double, out: DoubleArray) {
        val clamped = s.coerceIn(0.0, length)
        val i = segmentAt(clamped)
        val a = cum[i]
        val b = cum[i + 1]
        val t = if (b > a) (clamped - a) / (b - a) else 0.0
        out[0] = lat[i] + (lat[i + 1] - lat[i]) * t
        out[1] = lon[i] + (lon[i + 1] - lon[i]) * t
        out[2] = headingAt(clamped, i)
    }

    /**
     * L'ascissa del punto della tratta piu' vicino a ([pLat], [pLon]).
     *
     * Con un [hint] si guarda prima la finestra davanti al mezzo: una tratta
     * che passa due volte per la stessa via (i capolinea ad anello lo fanno
     * sempre) altrimenti aggancia il ramo sbagliato e il bus torna indietro.
     * Se nella finestra non c'e' niente di credibile si ripiega sull'intera
     * tratta, che tanto sono un centinaio di segmenti.
     */
    fun project(pLat: Double, pLon: Double, hint: Double = -1.0): Double {
        if (hint >= 0) {
            val from = (hint - HINT_BACK_M).coerceAtLeast(0.0)
            val to = (hint + HINT_AHEAD_M).coerceAtMost(length)
            val (s, d) = projectRange(pLat, pLon, from, to)
            // Se il piede della perpendicolare cade sul BORDO della finestra,
            // il punto vero sta quasi sempre fuori e quello che abbiamo
            // trovato e' solo il pezzo di tratta piu' vicino fra quelli che
            // abbiamo guardato. Fidarsene ancorava il mezzo al bordo.
            val atEdge = (to < length && s >= to - EDGE_EPS_M) ||
                (from > 0.0 && s <= from + EDGE_EPS_M)
            if (d <= HINT_TRUST_M && !atEdge) return s
        }
        return projectRange(pLat, pLon, 0.0, length).first
    }

    /** La distanza in metri fra ([pLat], [pLon]) e la tratta. */
    fun distanceTo(pLat: Double, pLon: Double): Double =
        projectRange(pLat, pLon, 0.0, length).second

    /**
     * Quanto gira la strada attorno a [s]: gradi di variazione di rotta per
     * cento metri. E' la misura che fa rallentare in curva e alle rotonde
     * senza sapere niente di rotonde.
     */
    fun turnRateAt(s: Double): Double {
        val before = headingAt((s - CURVE_WINDOW_M).coerceIn(0.0, length))
        val after = headingAt((s + CURVE_WINDOW_M).coerceIn(0.0, length))
        var diff = abs(after - before) % 360.0
        if (diff > 180.0) diff = 360.0 - diff
        return diff * 100.0 / (2.0 * CURVE_WINDOW_M)
    }

    /** La prima fermata a valle di [s], oppure -1 se il capolinea e' passato. */
    fun nextStopIndex(s: Double): Int {
        for (i in stopS.indices) if (stopS[i] > s + STOP_REACHED_M) return i
        return -1
    }

    // --------------------------------------------------------------- interni

    private fun segmentAt(s: Double): Int {
        var lo = 0
        var hi = cum.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= s) lo = mid else hi = mid
        }
        return lo.coerceAtMost(cum.size - 2)
    }

    private fun headingAt(s: Double, segment: Int = -1): Double {
        val i = if (segment >= 0) segment else segmentAt(s.coerceIn(0.0, length))
        val dx = (lon[i + 1] - lon[i]) * mx
        val dy = (lat[i + 1] - lat[i]) * my
        if (dx == 0.0 && dy == 0.0) return 0.0
        val deg = Math.toDegrees(atan2(dx, dy))
        return ((deg % 360.0) + 360.0) % 360.0
    }

    /** (ascissa del piede della perpendicolare, distanza in metri). */
    private fun projectRange(
        pLat: Double,
        pLon: Double,
        fromS: Double,
        toS: Double,
    ): Pair<Double, Double> {
        val px = pLon * mx
        val py = pLat * my
        var bestS = 0.0
        var bestD2 = Double.MAX_VALUE
        val first = segmentAt(fromS.coerceIn(0.0, length))
        val last = segmentAt(toS.coerceIn(0.0, length))
        for (i in first..last) {
            val ax = lon[i] * mx
            val ay = lat[i] * my
            val abx = lon[i + 1] * mx - ax
            val aby = lat[i + 1] * my - ay
            val ab2 = abx * abx + aby * aby
            val t = if (ab2 <= 0.0) {
                0.0
            } else {
                (((px - ax) * abx + (py - ay) * aby) / ab2).coerceIn(0.0, 1.0)
            }
            val qx = ax + abx * t
            val qy = ay + aby * t
            val d2 = (px - qx) * (px - qx) + (py - qy) * (py - qy)
            if (d2 < bestD2) {
                bestD2 = d2
                bestS = cum[i] + (cum[i + 1] - cum[i]) * t
            }
        }
        return bestS to sqrt(bestD2)
    }

    companion object {
        /** Quanto indietro e quanto avanti si cerca l'aggancio, dato un hint. */
        private const val HINT_BACK_M = 250.0
        private const val HINT_AHEAD_M = 1800.0

        /** Oltre questa distanza dalla finestra non ci si fida e si riscandisce. */
        private const val HINT_TRUST_M = 150.0

        /** Quanto vicino al bordo della finestra fa scattare il sospetto. */
        private const val EDGE_EPS_M = 5.0

        private const val CURVE_WINDOW_M = 30.0

        /** Entro questo raggio una fermata si considera raggiunta. */
        const val STOP_REACHED_M = 15.0

        /**
         * Costruisce l'indice per il pattern [p]. Decodifica qualche migliaio
         * di varint: SOLO fuori dal main thread (lo dice anche
         * [BundleReader.patternPolyline]).
         */
        fun build(reader: BundleReader, p: Int): PathIndex? {
            val poly = reader.patternPolyline(p) ?: return null
            val stopCount = reader.patternStopCount(p)
            val vertices = IntArray(stopCount) { reader.patternStopVertex(p, it) }
            return of(poly.lat, poly.lon, vertices)
        }

        /**
         * Come [build], ma sui dati nudi: e' la porta d'ingresso dei test,
         * che non hanno un bundle sotto.
         */
        fun of(lat: DoubleArray, lon: DoubleArray, stopVertices: IntArray): PathIndex? {
            val n = lat.size
            if (n < 2 || lon.size != n) return null
            val latRef = lat[n / 2]
            val mx = 111_320.0 * cos(Math.toRadians(latRef))
            val my = 110_540.0
            val cum = DoubleArray(n)
            for (i in 1 until n) {
                val dx = (lon[i] - lon[i - 1]) * mx
                val dy = (lat[i] - lat[i - 1]) * my
                cum[i] = cum[i - 1] + sqrt(dx * dx + dy * dy)
            }
            if (cum[n - 1] <= 0.0) return null

            // Le ascisse delle fermate: l'aggancio nel bundle e' un indice di
            // vertice, gia' monotono per costruzione. Qui si impone comunque,
            // che una regressione a monte non faccia tornare indietro un bus.
            val stops = DoubleArray(stopVertices.size)
            var last = 0.0
            for (k in stopVertices.indices) {
                val v = stopVertices[k]
                val s = if (v in 0 until n) cum[v] else last
                last = max(last, s)
                stops[k] = last
            }
            return PathIndex(lat, lon, cum, stops, mx, my)
        }
    }
}

/**
 * Il moto di UN mezzo lungo la sua tratta.
 *
 * Il feed di at manda una posizione nuova ogni ~120 secondi (misurato). Fra
 * un dato e l'altro il bus non si ferma nella realta', quindi non si ferma
 * neanche qui: avanza da solo lungo la strada vera alla velocita' che il
 * feed dichiara, rallenta dove la strada gira, sosta alle fermate e
 * riparte. Quando il dato vero arriva non si teletrasporta: l'errore si
 * riassorbe in un paio di secondi.
 */
class BusPathMotion(
    private val path: PathIndex,
    startS: Double,
    startSpeed: Double,
) {
    /** Dove disegniamo il mezzo adesso. */
    var s: Double = startS.coerceIn(0.0, path.length)
        private set

    /** La velocita' con cui lo stiamo muovendo, m/s. */
    var speed: Double = startSpeed.coerceIn(0.0, MAX_SPEED)
        private set

    /** L'ultima velocita' dichiarata dal feed, -1 se il mezzo non la manda. */
    private var feedSpeed: Double = startSpeed

    /** Metri di errore ancora da riassorbire dopo un dato nuovo. */
    private var pending: Double = 0.0

    /** Finche' non scade, il mezzo e' fermo a una fermata. */
    private var dwellUntilMs: Long = 0L

    /** L'ultima fermata dove ha sostato: non ci si ferma due volte. */
    private var servedStop: Int = -1

    private var lastFixS: Double = startS
    private var lastFixMs: Long = 0L

    /** Il primo dato non e' una correzione: e' l'unica cosa che sappiamo. */
    private var hasFix = false

    val arrived: Boolean get() = s >= path.length - PathIndex.STOP_REACHED_M

    /**
     * Un dato nuovo dal feed. [fixAgeSec] e' l'eta' del rilevamento GPS alla
     * generazione dello snapshot: il bus non era li' adesso, era li' allora,
     * quindi la posizione si porta avanti di quel tanto prima di
     * confrontarla con la nostra.
     */
    fun onFix(lat: Double, lon: Double, speedMs: Double, fixAgeSec: Int, nowMs: Long) {
        val fixS = path.project(lat, lon, hint = s)
        if (speedMs >= 0) feedSpeed = speedMs.coerceIn(0.0, MAX_SPEED)

        // Velocita' osservata fra due dati veri: e' la piu' onesta che
        // abbiamo quando il feed tace, e serve da controprova quando parla.
        if (hasFix && nowMs > lastFixMs) {
            val dt = (nowMs - lastFixMs) / 1000.0
            val moved = fixS - lastFixS
            if (dt >= 20.0 && moved >= 0) {
                val observed = (moved / dt).coerceIn(0.0, MAX_SPEED)
                feedSpeed = if (speedMs >= 0) feedSpeed * 0.6 + observed * 0.4 else observed
            }
        }
        lastFixS = fixS
        lastFixMs = nowMs

        val ahead = if (fixAgeSec > 0 && feedSpeed > 0) {
            feedSpeed * min(fixAgeSec, MAX_FIX_AGE_SEC)
        } else {
            0.0
        }
        val target = (fixS + ahead).coerceIn(0.0, path.length)
        val error = target - s
        if (!hasFix || abs(error) > SNAP_M) {
            hasFix = true
            // Non e' una correzione, e' un altro posto: mezzo che rientra in
            // servizio, corsa riassegnata, aggancio sbagliato. Si riparte.
            s = target
            pending = 0.0
            servedStop = -1
            dwellUntilMs = 0L
        } else {
            pending = error
        }
        if (feedSpeed >= 0) speed = feedSpeed
    }

    /** Avanza di [dtMs] millisecondi. */
    fun tick(dtMs: Long, nowMs: Long) {
        if (dtMs <= 0L) return
        if (dtMs > COARSE_AFTER_MS) {
            // La mappa era zoomata fuori, o l'app in pausa: il terreno si
            // recupera in un colpo, senza stare a simulare curve e soste di
            // un minuto intero che nessuno ha visto.
            val dt = dtMs.coerceAtMost(COARSE_CAP_MS) / 1000.0
            val cruise = if (feedSpeed >= 0) feedSpeed else DEFAULT_SPEED
            s = (s + cruise * dt).coerceIn(0.0, path.length)
            pending = 0.0
            dwellUntilMs = 0L
            return
        }
        val dt = dtMs / 1000.0

        // Il pezzo di errore che si riassorbe in questo fotogramma.
        if (pending != 0.0) {
            val step = pending * min(1.0, dtMs.toDouble() / RECONCILE_MS)
            s = (s + step).coerceIn(0.0, path.length)
            pending -= step
            if (abs(pending) < 0.5) pending = 0.0
        }

        if (nowMs < dwellUntilMs) {
            speed = 0.0
            return
        }

        val cruise = if (feedSpeed >= 0) feedSpeed else DEFAULT_SPEED
        var wanted = cruise

        // Rallenta dove la strada gira: una svolta ad angolo retto lascia
        // circa il 40% della velocita'.
        wanted /= 1.0 + path.turnRateAt(s) / TURN_HALVES_AT_DEG_PER_100M

        // Frena verso la prossima fermata.
        val next = path.nextStopIndex(s)
        if (next >= 0) {
            val gap = path.stopS[next] - s
            val brakeDistance = wanted * wanted / (2.0 * BRAKE_ACCEL)
            if (gap in 0.0..brakeDistance) {
                wanted = min(wanted, sqrt(2.0 * BRAKE_ACCEL * gap).coerceAtLeast(0.6))
            }
        }
        // E quando ci arriva, ci sosta.
        val here = path.nextStopIndex(s - PathIndex.STOP_REACHED_M * 2)
        if (here >= 0 && here != servedStop &&
            abs(path.stopS[here] - s) <= PathIndex.STOP_REACHED_M
        ) {
            servedStop = here
            dwellUntilMs = nowMs + DWELL_MS
            speed = 0.0
            return
        }

        // La velocita' si muove con gradualita': niente scatti a ogni curva.
        val maxStep = ACCEL * dt
        speed = (speed + (wanted - speed).coerceIn(-maxStep * 2.0, maxStep))
            .coerceIn(0.0, MAX_SPEED)
        s = (s + speed * dt).coerceIn(0.0, path.length)
    }

    fun sample(out: DoubleArray) = path.sample(s, out)

    private companion object {
        /** In quanto si riassorbe l'errore quando arriva il dato vero. */
        const val RECONCILE_MS = 2_500.0

        /** Oltre questo scarto non e' una correzione: e' un altro posto. */
        const val SNAP_M = 1_200.0

        /** Quando il feed non dichiara la velocita': ~25 km/h. */
        const val DEFAULT_SPEED = 7.0

        const val MAX_SPEED = 30.0
        const val ACCEL = 1.1
        const val BRAKE_ACCEL = 1.3

        /**
         * A quanti gradi di curva per cento metri la velocita' si dimezza.
         * Una svolta ad angolo retto vale ~90: resta il 40% della velocita'.
         */
        const val TURN_HALVES_AT_DEG_PER_100M = 60.0

        /** Sosta alla fermata: il feed non la sa, e sei secondi sono onesti. */
        const val DWELL_MS = 6_000L

        /** Non si estrapola oltre due minuti di eta' del fix. */
        const val MAX_FIX_AGE_SEC = 120

        /** Oltre questo salto fra fotogrammi si recupera in blocco. */
        const val COARSE_AFTER_MS = 3_000L
        const val COARSE_CAP_MS = 180_000L
    }
}
