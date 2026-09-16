package dev.antigravity.fluidtransit.data.favorites

import android.content.Context
import dev.antigravity.fluidtransit.data.store.Durable
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
class Favorites(private val file: File) {

    /**
     * Il costruttore di tutti i giorni.
     *
     * Quello sopra prende il file e basta: e' cio' che permette di provare
     * questo archivio senza un telefono. Le stelle sono l'unica cosa in
     * quest'app che l'utente ha creato a mano, e finora non c'era una riga di
     * prova che dicesse che sopravvivono a un giro di scrittura e rilettura.
     */
    constructor(context: Context) : this(File(context.filesDir, "favorites.json"))

    class Stop(val idHashHex: String, val name: String)
    class Route(val idHashHex: String, val shortName: String, val colorRgb: Int)
    val version = MutableStateFlow(0)

    /**
     * L'ultimo contenuto letto dal disco.
     *
     * Ogni `stops()`, `routes()` e `isStopFavorite()` apriva il file e ne
     * rifaceva il parse JSON — sul thread della UI, dentro le righe di una
     * lista. Una scheda Oggi con sei fermate stellate lo faceva una decina di
     * volte per composizione, per un contenuto che cambia solo quando sei tu
     * a toccare una stella. Il file resta la verita'; questa e' la sua copia,
     * e si butta a ogni scrittura.
     */
    @Volatile
    private var cache: Pair<List<Stop>, List<Route>>? = null

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

    private fun load(): Pair<List<Stop>, List<Route>> =
        cache ?: readFromDisk().also { cache = it }

    private fun readFromDisk(): Pair<List<Stop>, List<Route>> = runCatching {
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
        val scritto = runCatching {
            val o = JSONObject()
            o.put("stops", JSONArray().apply {
                stops.forEach { put(JSONObject().put("h", it.idHashHex).put("n", it.name)) }
            })
            o.put("routes", JSONArray().apply {
                routes.forEach {
                    put(JSONObject().put("h", it.idHashHex).put("n", it.shortName).put("c", it.colorRgb))
                }
            })
            Durable.write(file, o.toString())
        }.getOrDefault(false)
        // Se non si e' scritto niente, la copia in memoria non deve dire il
        // contrario: la stella tornerebbe indietro al riavvio, e nel
        // frattempo l'app avrebbe fatto finta di aver salvato.
        if (scritto) cache = stops to routes else cache = null
        version.value++
    }
}
