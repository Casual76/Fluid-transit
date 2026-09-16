package dev.antigravity.fluidtransit.data.fidelity

import dev.antigravity.fluidtransit.routing.FidelityText
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * L'ultimo confronto fra i nostri minuti e quelli della Regione.
 *
 * Due volte al giorno un workflow scarica il feed GTFS-RT grezzo e la sezione
 * che il nostro proxy serve all'app, e confronta i ritardi uno per uno. E'
 * l'unica risposta possibile a "non so nemmeno se i dati sono accurati":
 * qualunque cosa mostri l'app, guardandola da sola la si sta confrontando con
 * se' stessa.
 *
 * Quel verdetto viveva solo nei log del workflow, cioe' in nessun posto che
 * una persona apra. Adesso il workflow lo posa accanto agli orari, sulla
 * release a tag fisso `dati`, e la schermata "Stato dei dati" lo legge.
 *
 * Si scarica solo quando quella schermata si apre: sono duecento byte, ma
 * duecento byte che nessuno ha chiesto sono comunque duecento byte.
 */
object FidelityCheck {

    const val URL =
        "https://github.com/Casual76/Fluid-transit/releases/download/dati/fedelta.json"

    suspend fun fetch(): FidelityText.Verdict? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "FluidTransit")
            }
            val text = conn.use { it.inputStream.bufferedReader().readText() }
            val o = JSONObject(text)
            FidelityText.Verdict(
                atEpoch = o.optLong("at"),
                esito = o.optString("esito"),
                punti = o.optInt("punti"),
                diversi = o.optInt("diversi"),
            )
        }.getOrNull()
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T = try {
        block(this)
    } finally {
        disconnect()
    }
}
