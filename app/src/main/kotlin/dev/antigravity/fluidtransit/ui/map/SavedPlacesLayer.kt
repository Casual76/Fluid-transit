package dev.antigravity.fluidtransit.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import dev.antigravity.fluidtransit.routing.Relevance
import org.maplibre.android.maps.Style

/** Un posto salvato come lo vuole la mappa. */
class SavedRender(
    val id: Long,
    val label: String,
    val lat: Double,
    val lon: Double,
)

/**
 * I posti salvati sulla mappa: una pastiglia nell'accento dell'app con
 * dentro il segno di cosa sono, e il nome accanto.
 *
 * Fino alla Fase 8 si salvavano e sparivano: restavano in una lista, ma
 * sulla mappa non c'era niente: nessun layer, nessuna icona. Ed e' la prima
 * cosa che uno cerca dopo aver salvato "Casa".
 *
 * I segni sono disegnati a mano invece che presi da un set di icone perche'
 * servono come bitmap per MapLibre, e sono quattro forme semplici: le
 * riconosci a venti pixel, che e' l'unica cosa che conta qui.
 */
object SavedIcons {

    const val HOME = "casa"
    const val WORK = "lavoro"
    const val SCHOOL = "scuola"
    const val OTHER = "altro"

    /**
     * Che segno merita un'etichetta. Si guarda la parola, non una scelta
     * dell'utente: chi scrive "Casa dei nonni" vuole comunque una casetta.
     */
    fun glyphFor(label: String): String {
        val l = Relevance.normalize(label)
        return when {
            l.contains("casa") || l.contains("home") || l.contains("abitazione") -> HOME
            l.contains("lavoro") || l.contains("ufficio") || l.contains("work") -> WORK
            l.contains("scuola") || l.contains("liceo") || l.contains("universita") ||
                l.contains("istituto") || l.contains("school") -> SCHOOL
            else -> OTHER
        }
    }

    fun iconName(glyph: String, accentRgb: Int): String =
        "posto-$glyph-%06x".format(accentRgb and 0xFFFFFF)

    /**
     * I nomi gia' registrati, PER stile. Come per i bus: interrogare lo
     * stile a ogni aggiornamento sarebbe una chiamata JNI che copia la
     * bitmap, e la mappa debole muore con lo stile.
     */
    private val registered = java.util.WeakHashMap<Style, HashSet<String>>()

    fun ensure(style: Style, accentRgb: Int, density: Float) {
        val done = registered.getOrPut(style) { HashSet() }
        for (glyph in listOf(HOME, WORK, SCHOOL, OTHER)) {
            val name = iconName(glyph, accentRgb)
            if (!done.add(name)) continue
            style.addImage(name, bitmap(glyph, accentRgb, density))
        }
    }

    private fun bitmap(glyph: String, accentRgb: Int, density: Float): Bitmap {
        val size = (30 * density).toInt().coerceAtLeast(28)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val w = size.toFloat()
        val c = w / 2f

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF000000.toInt() or (accentRgb and 0xFFFFFF)
        }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
            color = 0xFFFFFFFF.toInt()
        }
        canvas.drawCircle(c, c, c - 2f * density, fill)
        canvas.drawCircle(c, c, c - 2f * density, ring)

        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFFFFFFF.toInt()
        }
        val whiteStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * density
            strokeJoin = Paint.Join.ROUND
            color = 0xFFFFFFFF.toInt()
        }
        when (glyph) {
            HOME -> canvas.drawPath(house(w), white)
            WORK -> {
                canvas.drawRoundRect(
                    RectF(w * 0.24f, w * 0.42f, w * 0.76f, w * 0.74f),
                    w * 0.05f, w * 0.05f, white,
                )
                // Il manico, disegnato a filo: cosi' la cartella si legge
                // anche quando l'icona e' alta venti pixel.
                canvas.drawPath(
                    Path().apply {
                        moveTo(w * 0.40f, w * 0.42f)
                        lineTo(w * 0.40f, w * 0.32f)
                        lineTo(w * 0.60f, w * 0.32f)
                        lineTo(w * 0.60f, w * 0.42f)
                    },
                    whiteStroke,
                )
            }

            SCHOOL -> {
                // Il tocco: un rombo schiacciato con la nappa di lato.
                canvas.drawPath(
                    Path().apply {
                        moveTo(c, w * 0.28f)
                        lineTo(w * 0.84f, w * 0.46f)
                        lineTo(c, w * 0.64f)
                        lineTo(w * 0.16f, w * 0.46f)
                        close()
                    },
                    white,
                )
                canvas.drawPath(
                    Path().apply {
                        moveTo(w * 0.74f, w * 0.51f)
                        lineTo(w * 0.74f, w * 0.70f)
                    },
                    whiteStroke,
                )
            }

            else -> canvas.drawPath(bookmark(w), white)
        }
        return bmp
    }

    private fun house(w: Float): Path = Path().apply {
        moveTo(w * 0.5f, w * 0.26f)
        lineTo(w * 0.82f, w * 0.52f)
        lineTo(w * 0.72f, w * 0.52f)
        lineTo(w * 0.72f, w * 0.74f)
        lineTo(w * 0.28f, w * 0.74f)
        lineTo(w * 0.28f, w * 0.52f)
        lineTo(w * 0.18f, w * 0.52f)
        close()
    }

    private fun bookmark(w: Float): Path = Path().apply {
        moveTo(w * 0.32f, w * 0.28f)
        lineTo(w * 0.68f, w * 0.28f)
        lineTo(w * 0.68f, w * 0.74f)
        lineTo(w * 0.5f, w * 0.60f)
        lineTo(w * 0.32f, w * 0.74f)
        close()
    }
}
