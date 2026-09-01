package dev.antigravity.fluidtransit.data.favorites

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Le fermate e le linee stellate. Chiavi = hash degli id GTFS, come i
 * preferiti devono essere: stabili fra i bundle, al contrario degli indici
 * che ogni notte cambiano. Il nome si salva accanto come ripiego per quando
 * il bundle non e' pronto (widget, avvii freddi).
 *
 * JSON su file come gli altri archivi utente; [version] scatta a ogni
 * modifica cosi' la UI si ricompone senza rileggere il disco a ogni frame.
 */
class Favorites(context: Context) {

    class Stop(val idHashHex: String, val name: String)
    class Route(val idHashHex: String, val shortName: String, val colorRgb: Int)

    private val file = File(context.filesDir, "favorites.json")
    val version = MutableStateFlow(0)

    fun stops(): List<Stop> = load().first
    fun routes(): List<Route> = load().second

    fun isStopFavorite(idHashHex: String): Boolean = stops().any { it.idHashHex == idHashHex }
    fun isRouteFavorite(idHashHex: String): Boolean = routes().any { it.idHashHex == idHashHex }

    fun toggleStop(idHashHex: String, name: String) {
        val (s, r) = load()
        val without = s.filter { it.idHashHex != idHashHex }
        write(if (without.size == s.size) s + Stop(idHashHex, name) else without, r)
    }

    fun toggleRoute(idHashHex: String, shortName: String, colorRgb: Int) {
        val (s, r) = load()
        val without = r.filter { it.idHashHex != idHashHex }
        write(s, if (without.size == r.size) r + Route(idHashHex, shortName, colorRgb) else without)
    }

    private fun load(): Pair<List<Stop>, List<Route>> = runCatching {
        if (!file.isFile) return emptyList<Stop>() to emptyList()
        val o = JSONObject(file.readText())
        val stops = o.optJSONArray("stops")?.let { a ->
            (0 until a.length()).map { i ->
                val e = a.getJSONObject(i)
                Stop(e.getString("h"), e.optString("n"))
            }
        }.orEmpty()
        val routes = o.optJSONArray("routes")?.let { a ->
            (0 until a.length()).map { i ->
                val e = a.getJSONObject(i)
                Route(e.getString("h"), e.optString("n"), e.optInt("c"))
            }
        }.orEmpty()
        stops to routes
    }.getOrElse { emptyList<Stop>() to emptyList() }

    private fun write(stops: List<Stop>, routes: List<Route>) {
        runCatching {
            val o = JSONObject()
            o.put("stops", JSONArray().apply {
                stops.forEach { put(JSONObject().put("h", it.idHashHex).put("n", it.name)) }
            })
            o.put("routes", JSONArray().apply {
                routes.forEach {
                    put(JSONObject().put("h", it.idHashHex).put("n", it.shortName).put("c", it.colorRgb))
                }
            })
            file.writeText(o.toString())
        }
        version.value++
    }
}
