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
 * La traduzione da pose a feature MapLibre, e le icone.
 *
 * Sta in un file suo perche' [BusOverlay] non deve sapere niente della
 * mappa: il moto dei mezzi e' la parte che si e' rotta piu' volte e che
 * serviva poter mettere sotto test, e un `Style` in mezzo lo impedirebbe.
 */
fun busFeatures(
    poses: List<BusPose>,
    style: Style,
    density: Float,
    selectedKey: Int?,
): FeatureCollection {
    val out = ArrayList<Feature>(poses.size)
    for (p in poses) {
        val b = p.render
        BusIcons.ensure(style, b.colorRgb, density)
        val f = Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat))
        f.addStringProperty("sh", if (p.bearingDeg >= 0) "a" else "d")
        f.addStringProperty("ci", BusIcons.hex(b.colorRgb))
        f.addNumberProperty("b", if (p.bearingDeg >= 0) p.bearingDeg else 0)
        f.addStringProperty("cat", b.cat)
        f.addStringProperty("rh", b.routeHashHex)
        f.addStringProperty("th", b.tripHashHex)
        f.addNumberProperty("vk", b.vehKey)
        f.addBooleanProperty("sel", b.vehKey == selectedKey)
        out.add(f)
    }
    return FeatureCollection.fromFeatures(out)
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

    /**
     * I colori gia' registrati, PER stile: interrogare style.getImage a ogni
     * fotogramma sarebbe una chiamata JNI che copia la bitmap — a 8 Hz per
     * mille bus e' un costo vero. La mappa debole muore con lo stile.
     */
    private val registered = java.util.WeakHashMap<Style, HashSet<Int>>()

    fun ensure(style: Style, colorRgb: Int, density: Float) {
        val colors = registered.getOrPut(style) { HashSet() }
        if (!colors.add(colorRgb)) return
        style.addImage(arrowName(colorRgb), arrowBitmap(colorRgb, density))
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
