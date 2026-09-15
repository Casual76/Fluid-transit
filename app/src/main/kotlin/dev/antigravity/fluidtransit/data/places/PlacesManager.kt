package dev.antigravity.fluidtransit.data.places

import android.content.Context
import android.net.ConnectivityManager
import dev.antigravity.fluidtransit.data.bundle.BundleManager
import dev.antigravity.fluidtransit.data.store.Durable
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

    /**
     * Il lettore vecchio si chiude dopo, non subito: un minuto, come per il
     * bundle, perche' una ricerca in corso sta leggendo da quella mappa.
     */
    private fun retire(previous: PlacesReader?) {
        if (previous == null) return
        scope.launch {
            kotlinx.coroutines.delay(60_000L)
            runCatching { previous.close() }
        }
    }

    private fun open(sha: String) {
        val reader = PlacesReader(file)
        val search = PlacesSearch(reader)
        // Gli indici normalizzati si costruiscono adesso, che nessuno
        // aspetta: altrimenti il mezzo secondo lo paga la prima ricerca.
        // Siamo gia' su Dispatchers.IO, chiamati da start().
        runCatching { search.warmUp() }
        _state.value = State.Ready(reader, search, sha)
    }

    /** Scarica la versione dell'indice se diversa da quella in tasca. */
    private fun refresh() {
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            // Undici megabyte non si scaricano sui dati mobili senza
            // chiedere: i luoghi sono un di piu', e possono aspettare il
            // Wi-Fi — che e' esattamente quello che lo Stato dei dati
            // promette all'utente. Prima il controllo valeva solo a file
            // gia' presente, quindi al primo avvio in mobilita' partiva
            // comunque.
            if (cm.isActiveNetworkMetered) return
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
            // Prima si toglie di mezzo il lettore vecchio dallo stato, poi
            // lo si ritira con calma: chiuderlo vuol dire smappare il file, e
            // chi sta cercando un indirizzo in quel momento non prende
            // un'eccezione ma un segnale dal sistema, cioe' il processo
            // muore. Stessa ragione e stessa grazia del bundle.
            val uscente = (_state.value as? State.Ready)?.reader
            _state.value = State.Missing
            retire(uscente)
            // Sostituzione vera e propria: uno spostamento atomico, non una
            // cancellazione seguita da una rinomina. Con la seconda, se la
            // rinomina fallisce non resta niente — ed e' esattamente il
            // difetto che per il bundle era gia' stato chiuso.
            java.nio.file.Files.move(
                part.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
            // Troncata, questa scheda vale come assente, e l'app si
            // riscarica undici megabyte di luoghi che ha gia'.
            Durable.write(meta, JSONObject().put("sha256", actual).toString())
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
