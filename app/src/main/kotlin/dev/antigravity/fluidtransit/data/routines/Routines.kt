package dev.antigravity.fluidtransit.data.routines

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Le routine ricorrenti, decise cosi': nascono dal dettaglio di un viaggio
 * ("Rendine una routine"), hanno i giorni della settimana, e ognuna sceglie
 * il suo ancoraggio — "arriva entro" o "parti alle". Nei giorni giusti
 * l'app ricalcola il viaggio coi ritardi live e manda "esci tra X minuti".
 *
 * `lastAdvice*` e' l'ultimo consiglio calcolato dalle sveglie: la scheda
 * Oggi lo mostra senza rifare il calcolo.
 */
class Routines(context: Context) {

    class Routine(
        val id: Long,
        val label: String,
        val fromLat: Double,
        val fromLon: Double,
        val toLat: Double,
        val toLon: Double,
        val toName: String,
        /** Lunedi' = 1 ... Domenica = 7, come java.time.DayOfWeek. */
        val days: Set<Int>,
        val anchor: String, // "arrive" | "depart"
        /** Minuti dalla mezzanotte locale dell'orario di ancoraggio. */
        val anchorMinutes: Int,
        val enabled: Boolean,
        val lastAdviceEpoch: Long = 0, // quando USCIRE, epoch s (0 = mai calcolato)
        val lastAdviceText: String = "",
    )

    private val file = File(context.filesDir, "routines.json")
    val version = MutableStateFlow(0)

    fun list(): List<Routine> = runCatching {
        if (!file.isFile) return emptyList()
        val a = JSONArray(file.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Routine(
                id = o.getLong("id"),
                label = o.optString("label"),
                fromLat = o.getDouble("fromLat"),
                fromLon = o.getDouble("fromLon"),
                toLat = o.getDouble("toLat"),
                toLon = o.getDouble("toLon"),
                toName = o.optString("toName"),
                days = o.getJSONArray("days").let { d -> (0 until d.length()).map { d.getInt(it) } }.toSet(),
                anchor = o.optString("anchor", "arrive"),
                anchorMinutes = o.getInt("anchorMinutes"),
                enabled = o.optBoolean("enabled", true),
                lastAdviceEpoch = o.optLong("adviceEpoch"),
                lastAdviceText = o.optString("adviceText"),
            )
        }
    }.getOrElse { emptyList() }

    fun add(r: Routine) = write(list().filter { it.id != r.id } + r)

    fun remove(id: Long) = write(list().filter { it.id != id })

    fun update(id: Long, transform: (Routine) -> Routine) {
        write(list().map { if (it.id == id) transform(it) else it })
    }

    private fun write(routines: List<Routine>) {
        runCatching {
            val a = JSONArray()
            for (r in routines) {
                a.put(
                    JSONObject()
                        .put("id", r.id)
                        .put("label", r.label)
                        .put("fromLat", r.fromLat)
                        .put("fromLon", r.fromLon)
                        .put("toLat", r.toLat)
                        .put("toLon", r.toLon)
                        .put("toName", r.toName)
                        .put("days", JSONArray().apply { r.days.sorted().forEach { put(it) } })
                        .put("anchor", r.anchor)
                        .put("anchorMinutes", r.anchorMinutes)
                        .put("enabled", r.enabled)
                        .put("adviceEpoch", r.lastAdviceEpoch)
                        .put("adviceText", r.lastAdviceText),
                )
            }
            file.writeText(a.toString())
        }
        version.value++
    }
}
