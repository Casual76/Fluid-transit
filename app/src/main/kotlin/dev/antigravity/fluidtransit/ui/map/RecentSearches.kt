package dev.antigravity.fluidtransit.ui.map

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Le ricerche recenti, persistite in un file JSON minuscolo.
 *
 * Niente Room per dieci righe: quando in Fase 6 arrivera' il database dei
 * preferiti, questa lista puo' traslocarci senza cambiare chi la usa.
 */
class RecentSearches(context: Context) {

    class Entry(
        val kind: String, // "stop" | "route"
        val key: String, // hash esadecimale dell'id GTFS
        val title: String,
        val subtitle: String,
        val colorRgb: Int,
        val lat: Double,
        val lon: Double,
    )

    private val file = File(context.filesDir, "recent-searches.json")

    fun load(): List<Entry> = runCatching {
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Entry(
                kind = o.getString("kind"),
                key = o.getString("key"),
                title = o.getString("title"),
                subtitle = o.optString("subtitle"),
                colorRgb = o.optInt("color"),
                lat = o.optDouble("lat"),
                lon = o.optDouble("lon"),
            )
        }
    }.getOrElse { emptyList() }

    fun add(entry: Entry) {
        val merged = (listOf(entry) + load().filter { it.key != entry.key }).take(MAX)
        runCatching {
            val array = JSONArray()
            for (e in merged) {
                array.put(
                    JSONObject()
                        .put("kind", e.kind)
                        .put("key", e.key)
                        .put("title", e.title)
                        .put("subtitle", e.subtitle)
                        .put("color", e.colorRgb)
                        .put("lat", e.lat)
                        .put("lon", e.lon),
                )
            }
            file.writeText(array.toString())
        }
    }

    private companion object {
        const val MAX = 10
    }
}
