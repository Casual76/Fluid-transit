package dev.antigravity.fluidtransit.ui.map

import android.content.Context

/**
 * Le scelte che uno fa sulla mappa e si aspetta di ritrovare.
 *
 * Il filtro urbani/extraurbani viveva solo nello stato salvabile della
 * schermata: sopravviveva a una rotazione e non alla chiusura dell'app. Chi
 * lo metteva su "Urbani" lo ritrovava su "Tutti" il giorno dopo, senza che
 * niente glielo dicesse — e' piccolo, ma e' della stessa famiglia del "si
 * comporta in modo diverso ogni volta".
 *
 * Due righe di SharedPreferences, come il modo di viaggio: non serve altro, e
 * un archivio in piu' sarebbe piu' di quello che la cosa vale.
 */
class MapPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("map", Context.MODE_PRIVATE)

    var filter: CategoryFilter
        get() = runCatching { CategoryFilter.valueOf(prefs.getString(KEY_FILTER, null) ?: "") }
            .getOrDefault(CategoryFilter.ALL)
        set(value) {
            prefs.edit().putString(KEY_FILTER, value.name).apply()
        }

    /**
     * L'ultima inquadratura, per riaprire dove si stava guardando.
     *
     * Senza posizione, la mappa ripartiva ogni volta dalla Toscana intera a
     * zoom 7,6 — una vista da cui non si fa niente. Chi ha il permesso della
     * posizione non se ne accorge, perche' la camera lo segue subito; chi non
     * ce l'ha ricominciava da capo ogni volta.
     *
     * Non si salva lo zoom da lontanissimo: se uno ha chiuso l'app guardando
     * tutta la regione, riaprirla li' e' giusto, ma non vale la pena
     * scriverlo.
     */
    var camera: DoubleArray?
        get() {
            val lat = prefs.getFloat(KEY_LAT, Float.NaN).toDouble()
            val lon = prefs.getFloat(KEY_LON, Float.NaN).toDouble()
            val zoom = prefs.getFloat(KEY_ZOOM, Float.NaN).toDouble()
            if (lat.isNaN() || lon.isNaN() || zoom.isNaN()) return null
            return doubleArrayOf(lat, lon, zoom, 0.0, 0.0)
        }
        set(value) {
            if (value == null || value.size < 3) return
            prefs.edit()
                .putFloat(KEY_LAT, value[0].toFloat())
                .putFloat(KEY_LON, value[1].toFloat())
                .putFloat(KEY_ZOOM, value[2].toFloat())
                .apply()
        }

    private companion object {
        const val KEY_FILTER = "filter"
        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"
        const val KEY_ZOOM = "zoom"
    }
}
