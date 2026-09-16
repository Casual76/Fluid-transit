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

    private companion object {
        const val KEY_FILTER = "filter"
    }
}
