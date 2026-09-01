package dev.antigravity.fluidtransit.data.places

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * I posti dell'utente: Casa, Lavoro, Scuola o un nome libero — decisi con
 * lui. Si salvano dal pannello di un luogo o tenendo premuto sulla mappa, e
 * stanno SEMPRE in cima al pannello di ricerca.
 *
 * File JSON come le ricerche recenti: quando in Fase 6 arrivera' il
 * database dei preferiti, trasloca senza cambiare chi lo usa.
 */
class SavedPlaces(context: Context) {

    class Entry(
        val id: Long,
        val label: String, // "Casa", "Lavoro", "Scuola" o libero
        val lat: Double,
        val lon: Double,
    )

    private val file = File(context.filesDir, "saved-places.json")

    fun load(): List<Entry> = runCatching {
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Entry(
                id = o.getLong("id"),
                label = o.getString("label"),
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
            )
        }
    }.getOrElse { emptyList() }

    /** Un'etichetta esiste una volta sola: salvare "Casa" altrove la sposta. */
    fun add(label: String, lat: Double, lon: Double) {
        val cleaned = label.trim().ifEmpty { "Posto salvato" }
        val kept = load().filter { !it.label.equals(cleaned, ignoreCase = true) }
        write(kept + Entry(System.currentTimeMillis(), cleaned, lat, lon))
    }

    fun remove(id: Long) {
        write(load().filter { it.id != id })
    }

    private fun write(entries: List<Entry>) {
        runCatching {
            val array = JSONArray()
            for (e in entries) {
                array.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("label", e.label)
                        .put("lat", e.lat)
                        .put("lon", e.lon),
                )
            }
            file.writeText(array.toString())
        }
    }
}
