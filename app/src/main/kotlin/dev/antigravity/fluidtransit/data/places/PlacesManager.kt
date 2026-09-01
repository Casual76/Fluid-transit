package dev.antigravity.fluidtransit.data.places

import android.content.Context
import android.net.ConnectivityManager
import dev.antigravity.fluidtransit.data.bundle.BundleManager
import dev.antigravity.fluidtransit.routing.PlacesReader
import dev.antigravity.fluidtransit.routing.PlacesSearch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Il file dei luoghi (`luoghi.bin`), gestito come il bundle: scarica su
 * rete non a consumo, verifica lo sha256, sostituzione atomica, mmap.
 *
 * A differenza del bundle non blocca niente: finche' non c'e', la ricerca
 * lavora su fermate e linee e la sezione Luoghi semplicemente non compare.
 */
class PlacesManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        object Missing : State
        object Downloading : State
        class Ready(val reader: PlacesReader, val search: PlacesSearch, val sha: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state

    private val file get() = File(context.filesDir, "luoghi.bin")
    private val meta get() = File(context.filesDir, "luoghi.meta.json")

    fun start() {
        scope.launch(Dispatchers.IO) {
            // Prima il file gia' in tasca: la ricerca luoghi parte subito.
            runCatching {
                if (file.isFile && meta.isFile) {
                    val sha = JSONObject(meta.readText()).optString("sha256")
                    open(sha)
                }
            }
            refresh()
        }
    }

    private fun open(sha: String) {
        val reader = PlacesReader(file)
        _state.value = State.Ready(reader, PlacesSearch(reader), sha)
    }

    /** Scarica la versione dell'indice se diversa da quella in tasca. */
    private fun refresh() {
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            if (cm.isActiveNetworkMetered && _state.value is State.Ready) return
            val index = JSONObject(httpGetText(BundleManager.INDEX_URL))
            val url = index.optString("placesUrl").takeIf { it.isNotEmpty() } ?: return
            val sha = index.optString("placesSha256")
            val current = (_state.value as? State.Ready)?.sha
            if (sha.isNotEmpty() && sha == current) return

            if (_state.value !is State.Ready) _state.value = State.Downloading
            val part = File(context.filesDir, "luoghi.bin.part")
            downloadGunzip(url, part)
            val actual = sha256(part)
            if (sha.isNotEmpty() && actual != sha) {
                part.delete()
                if (_state.value is State.Downloading) _state.value = State.Missing
                return
            }
            // Sostituzione atomica: prima si chiude la mappa vecchia.
            (_state.value as? State.Ready)?.reader?.close()
            _state.value = State.Missing
            if (!part.renameTo(file)) {
                file.delete()
                part.renameTo(file)
            }
            meta.writeText(JSONObject().put("sha256", actual).toString())
            open(actual)
        }.onFailure {
            if (_state.value is State.Downloading) _state.value = State.Missing
        }
    }

    private fun httpGetText(url: String): String {
        var current = url
        repeat(5) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", BundleManager.USER_AGENT)
            when (conn.responseCode) {
                in 300..399 -> {
                    val location = conn.getHeaderField("Location") ?: error("redirect senza Location")
                    current = URL(URL(current), location).toString()
                }

                200 -> return conn.inputStream.bufferedReader().readText()
                else -> error("HTTP ${conn.responseCode} su $current")
            }
        }
        error("troppi redirect per $url")
    }

    private fun downloadGunzip(url: String, out: File) {
        var current = url
        repeat(5) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", BundleManager.USER_AGENT)
            when (conn.responseCode) {
                in 300..399 -> {
                    val location = conn.getHeaderField("Location") ?: error("redirect senza Location")
                    current = URL(URL(current), location).toString()
                }

                200 -> {
                    GZIPInputStream(conn.inputStream, 1 shl 16).use { zin ->
                        FileOutputStream(out).use { o -> zin.copyTo(o, 1 shl 16) }
                    }
                    return
                }

                else -> error("HTTP ${conn.responseCode} su $current")
            }
        }
        error("troppi redirect per $url")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
