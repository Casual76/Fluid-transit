package dev.antigravity.fluidtransit.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import org.maplibre.android.maps.Style
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * I bus vivi come li vuole la mappa: cosa disegnare e dove, gia' risolto
 * contro il bundle (colore della linea, categoria per i filtri, hash per la
 * modalita' linea). Il vero movimento sta in [BusOverlay].
 */
class BusRender(
    val vehKey: Int,
    val lat: Double,
    val lon: Double,
    val bearingDeg: Int, // -1 = ignoto: si disegna il pallino
    val colorRgb: Int,
    val cat: String, // "u" | "e", per i chip
    val routeHashHex: String,
    val tripHashHex: String,
)

/**
 * Lo stato di interpolazione: fra un poll e l'altro (~30 s) ogni bus scivola
 * in linea retta dalla vecchia posizione alla nuova — deciso cosi', col
 * movimento stimato avanzato rimandato al map matching. Il tick gira a
 * ~8 Hz dal chiamante; qui si calcola solo dove sta ogni bus adesso.
 */
class BusOverlay {

    private class Anim(
        var fromLat: Double,
        var fromLon: Double,
        var toLat: Double,
        var toLon: Double,
        var startMs: Long,
        var durationMs: Long,
        var render: BusRender,
        /**
         * La rotta DERIVATA dal movimento fra due snapshot: il feed di at
         * non manda mai il bearing (misurato: 0 su 1105), quindi la freccia
         * decisa si orienta cosi' — e chi e' fermo resta un pallino.
         */
        var derivedBearing: Int = -1,
    ) {
        fun at(nowMs: Long): Pair<Double, Double> {
            if (durationMs <= 0) return toLat to toLon
            val t = ((nowMs - startMs).toDouble() / durationMs).coerceIn(0.0, 1.0)
            return (fromLat + (toLat - fromLat) * t) to (fromLon + (toLon - fromLon) * t)
        }
    }

    private val anims = LinkedHashMap<Int, Anim>()
    var selectedKey: Int? = null

    /** Un nuovo snapshot: ogni bus riparte da dov'e' ADESSO verso la nuova meta. */
    fun setTargets(list: List<BusRender>, nowMs: Long) {
        val seen = HashSet<Int>(list.size * 2)
        for (b in list) {
            seen.add(b.vehKey)
            val prev = anims[b.vehKey]
            if (prev == null) {
                anims[b.vehKey] = Anim(b.lat, b.lon, b.lat, b.lon, nowMs, 0, b)
            } else {
                val (curLat, curLon) = prev.at(nowMs)
                val jump = dev.antigravity.fluidtransit.routing.BundleReader
                    .haversine(curLat, curLon, b.lat, b.lon)
                if (jump > 2500) {
                    // Un salto cosi' non e' un movimento: teletrasporto onesto.
                    prev.fromLat = b.lat; prev.fromLon = b.lon
                    prev.durationMs = 0
                    prev.derivedBearing = -1
                } else {
                    prev.fromLat = curLat; prev.fromLon = curLon
                    prev.durationMs = GLIDE_MS
                    // Sopra i ~25 m il movimento e' vero e la rotta si
                    // deriva; sotto, e' rumore GPS e la freccia resta com'e'.
                    if (jump > 25) {
                        prev.derivedBearing = bearingDegrees(curLat, curLon, b.lat, b.lon)
                    }
                }
                prev.toLat = b.lat; prev.toLon = b.lon
                prev.startMs = nowMs
                prev.render = b
            }
        }
        val it = anims.keys.iterator()
        while (it.hasNext()) if (it.next() !in seen) it.remove()
    }

    val isEmpty: Boolean get() = anims.isEmpty()

    fun features(nowMs: Long, style: Style, density: Float): FeatureCollection {
        val out = ArrayList<Feature>(anims.size)
        for (a in anims.values) {
            val b = a.render
            val (lat, lon) = a.at(nowMs)
            BusIcons.ensure(style, b.colorRgb, density)
            val bearing = if (b.bearingDeg >= 0) b.bearingDeg else a.derivedBearing
            val f = Feature.fromGeometry(Point.fromLngLat(lon, lat))
            f.addStringProperty("sh", if (bearing >= 0) "a" else "d")
            f.addStringProperty("ci", BusIcons.hex(b.colorRgb))
            f.addNumberProperty("b", if (bearing >= 0) bearing else 0)
            f.addStringProperty("cat", b.cat)
            f.addStringProperty("rh", b.routeHashHex)
            f.addStringProperty("th", b.tripHashHex)
            f.addNumberProperty("vk", b.vehKey)
            f.addBooleanProperty("sel", b.vehKey == selectedKey)
            out.add(f)
        }
        return FeatureCollection.fromFeatures(out)
    }

    fun clear() = anims.clear()

    private companion object {
        /** Poco meno del poll da 30 s: il glide finisce appena prima del dato nuovo. */
        const val GLIDE_MS = 28_000L

        /** Rotta iniziale (gradi da nord, orari) dal punto vecchio al nuovo. */
        fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
            val f1 = Math.toRadians(lat1)
            val f2 = Math.toRadians(lat2)
            val dl = Math.toRadians(lon2 - lon1)
            val y = kotlin.math.sin(dl) * kotlin.math.cos(f2)
            val x = kotlin.math.cos(f1) * kotlin.math.sin(f2) -
                kotlin.math.sin(f1) * kotlin.math.cos(f2) * kotlin.math.cos(dl)
            val deg = Math.toDegrees(kotlin.math.atan2(y, x))
            return (((deg % 360) + 360) % 360).toInt()
        }
    }
}

/**
 * Le icone dei bus, disegnate al volo e registrate nello stile: una freccia
 * di navigazione e un pallino per ogni colore di linea incontrato. La
 * tavolozza vera e' di ~12 tinte, quindi sono poche bitmap piccole — e non
 * serve il giro degli SDF, che sfocano i bordi.
 */
object BusIcons {

    fun hex(colorRgb: Int): String = "%06x".format(colorRgb and 0xFFFFFF)

    fun arrowName(colorRgb: Int) = "bus-a-${hex(colorRgb)}"

    fun dotName(colorRgb: Int) = "bus-d-${hex(colorRgb)}"

    fun ensure(style: Style, colorRgb: Int, density: Float) {
        val arrow = arrowName(colorRgb)
        if (style.getImage(arrow) != null) return
        style.addImage(arrow, arrowBitmap(colorRgb, density))
        style.addImage(dotName(colorRgb), dotBitmap(colorRgb, density))
    }

    /** La freccia di marcia: punta in alto, il layer la ruota col bearing. */
    private fun arrowBitmap(colorRgb: Int, density: Float): Bitmap {
        val size = (26 * density).toInt().coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val w = size.toFloat()
        val path = Path().apply {
            moveTo(w * 0.5f, w * 0.06f) // punta
            lineTo(w * 0.88f, w * 0.88f) // ala destra
            lineTo(w * 0.5f, w * 0.66f) // incavo
            lineTo(w * 0.12f, w * 0.88f) // ala sinistra
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF000000.toInt() or (colorRgb and 0xFFFFFF)
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeJoin = Paint.Join.ROUND
            color = 0xFFFFFFFF.toInt()
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
        return bmp
    }

    /** Il ripiego senza direzione: pallino pieno col bordo bianco. */
    private fun dotBitmap(colorRgb: Int, density: Float): Bitmap {
        val size = (18 * density).toInt().coerceAtLeast(16)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val c = size / 2f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF000000.toInt() or (colorRgb and 0xFFFFFF)
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = 0xFFFFFFFF.toInt()
        }
        canvas.drawCircle(c, c, c - 2.5f * density, fill)
        canvas.drawCircle(c, c, c - 2.5f * density, stroke)
        return bmp
    }
}
