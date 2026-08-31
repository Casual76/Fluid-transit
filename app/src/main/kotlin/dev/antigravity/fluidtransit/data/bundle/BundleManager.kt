package dev.antigravity.fluidtransit.data.bundle

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dev.antigravity.fluidtransit.routing.BundleReader
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Il ciclo di vita del bundle orari sul dispositivo.
 *
 * All'avvio apre il bundle attivo se c'e'; altrimenti guida il primo
 * download (la schermata di benvenuto e' la vista di questo stato). Il
 * protocollo di installazione e' sempre lo stesso, e l'ordine e' il punto:
 * scarica su `.part` -> gunzip -> verifica sha256 -> apri e fai una query di
 * fumo -> rinomina -> sostituisci il lettore. Un bundle scaduto non si
 * cancella mai prima di avere il sostituto: meglio orari di ieri con un
 * avviso che nessun orario.
 *
 * Su rete a consumo il primo download chiede il permesso ([BundleState.AskMetered]);
 * "aspetta il Wi-Fi" registra un callback di rete e parte da solo quando
 * arriva una rete non a consumo. E' la decisione presa nel piano, tradotta.
 */
class BundleManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    sealed interface BundleState {
        /** Nessun bundle e nessun download in corso: primo avvio. */
        data object Missing : BundleState

        /** Rete a consumo: si chiede prima di scaricare [bytes]. */
        data class AskMetered(val bytes: Long) : BundleState

        /** In attesa di una rete non a consumo, come chiesto dall'utente. */
        data object WaitingForWifi : BundleState

        data class Downloading(val progress: Float) : BundleState

        data class Ready(
            val reader: BundleReader,
            val buildId: Long,
            /** Il PMTiles della rete (linee+fermate) pubblicato accanto al bundle, se noto. */
            val overlayUrl: String?,
        ) : BundleState

        data class Failed(val message: String) : BundleState
    }

    private val _state = MutableStateFlow<BundleState>(BundleState.Missing)
    val state: StateFlow<BundleState> = _state

    private val dir = File(context.filesDir, "bundles")
    private val active = File(dir, "active.ftb")
    private val meta = File(dir, "active.meta.json")
    private val mutex = Mutex()
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    /** Da chiamare una volta, all'avvio. Non blocca: lo stato arriva sul flow. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                if (active.isFile) {
                    runCatching { openAndSmoke(active) }
                        .onSuccess { reader ->
                            _state.value = BundleState.Ready(reader, reader.buildId, readMetaOverlay())
                            return@withLock
                        }
                        .onFailure {
                            // Un bundle attivo che non si apre e' corrotto o di
                            // un formato vecchio: si riscarica, non si tiene.
                            active.delete()
                            meta.delete()
                        }
                }
                requestDownload(userApprovedMetered = false)
            }
            // Con un bundle gia' attivo, il controllo di freschezza corre in
            // sottofondo: se stanotte e' uscito un bundle nuovo si scarica e
            // si sostituisce senza passare dalla schermata di benvenuto.
            if (state.value is BundleState.Ready) {
                mutex.withLock { refreshSilently() }
            }
        }
    }

    private fun readMetaOverlay(): String? = runCatching {
        JSONObject(meta.readText()).optString("overlayUrl").takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * Aggiornamento silenzioso: nessun cambio di stato finche' il nuovo
     * bundle non e' installato. Su rete a consumo non fa niente - il bundle
     * di ieri e' ancora valido e la domanda non vale la pena.
     */
    private fun refreshSilently() {
        val current = state.value as? BundleState.Ready ?: return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm.isActiveNetworkMetered) return
        runCatching {
            val index = fetchIndex()
            if (index.buildId == java.lang.Long.toHexString(current.buildId)) {
                // Stesso bundle, ma l'overlay puo' essere cambiato lo stesso:
                // la pipeline delle tile ha una sua versione (il map matching
                // e' arrivato cosi') e pubblica sotto un nome nuovo anche a
                // parita' di orari.
                if (index.overlayUrl != null && index.overlayUrl != current.overlayUrl) {
                    writeMeta(index)
                    _state.value = BundleState.Ready(current.reader, current.buildId, index.overlayUrl)
                }
                return
            }
            installFrom(index)
        }
    }

    /** L'utente ha accettato il download su rete a consumo. */
    fun downloadOnMetered() {
        scope.launch(Dispatchers.IO) { mutex.withLock { requestDownload(userApprovedMetered = true) } }
    }

    /** L'utente preferisce aspettare il Wi-Fi. */
    fun waitForWifi() {
        _state.value = BundleState.WaitingForWifi
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (wifiCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (cm.isActiveNetworkMetered) return
                cm.unregisterNetworkCallback(this)
                wifiCallback = null
                scope.launch(Dispatchers.IO) { mutex.withLock { requestDownload(userApprovedMetered = false) } }
            }
        }
        wifiCallback = callback
        cm.registerDefaultNetworkCallback(callback)
    }

    fun retry() {
        scope.launch(Dispatchers.IO) { mutex.withLock { requestDownload(userApprovedMetered = true) } }
    }

    private fun requestDownload(userApprovedMetered: Boolean) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (!userApprovedMetered && cm.isActiveNetworkMetered) {
            _state.value = BundleState.AskMetered(EXPECTED_BYTES)
            return
        }
        _state.value = BundleState.Downloading(0f)
        try {
            val index = fetchIndex()
            installFrom(index) { done ->
                _state.value = BundleState.Downloading(
                    if (index.bytes > 0) (done.toFloat() / index.bytes).coerceIn(0f, 1f) else 0f,
                )
            }
        } catch (e: Exception) {
            _state.value = if (active.isFile) {
                // C'era gia' un bundle valido: si continua con quello.
                runCatching { openAndSmoke(active) }
                    .map { BundleState.Ready(it, it.buildId, readMetaOverlay()) as BundleState }
                    .getOrElse { BundleState.Failed(e.message ?: "errore sconosciuto") }
            } else {
                BundleState.Failed(e.message ?: "errore sconosciuto")
            }
        }
    }

    /** Scarica, verifica, promuove e pubblica il nuovo Ready. */
    private fun installFrom(index: BundleIndex, onProgress: (Long) -> Unit = {}) {
        val part = File(dir, "incoming.ftb.part")
        downloadGunzip(index.url, part, onProgress)
        if (index.sha256.isNotEmpty()) {
            val actual = sha256Of(part)
            check(actual.equals(index.sha256, ignoreCase = true)) {
                "bundle corrotto in transito (sha256 diverso)"
            }
        }
        // La query di fumo prima della promozione: un bundle che non si
        // apre non deve mai diventare quello attivo.
        openAndSmoke(part).close()
        val previous = (state.value as? BundleState.Ready)?.reader
        if (active.isFile) active.delete()
        check(part.renameTo(active)) { "impossibile installare il bundle scaricato" }
        writeMeta(index)
        val reader = openAndSmoke(active)
        _state.value = BundleState.Ready(reader, reader.buildId, index.overlayUrl)
        previous?.close()
    }

    private fun writeMeta(index: BundleIndex) {
        runCatching {
            meta.writeText(
                JSONObject()
                    .put("buildId", index.buildId)
                    .put("overlayUrl", index.overlayUrl ?: JSONObject.NULL)
                    .toString(),
            )
        }
    }

    private class BundleIndex(
        val buildId: String,
        val url: String,
        val bytes: Long,
        val sha256: String,
        val overlayUrl: String?,
    )

    private fun fetchIndex(): BundleIndex {
        val json = JSONObject(httpGetText(INDEX_URL))
        return BundleIndex(
            buildId = json.optString("buildId"),
            url = json.getString("url"),
            bytes = json.optLong("bytes", -1),
            sha256 = json.optString("sha256"),
            overlayUrl = json.optString("overlayUrl").takeIf { it.isNotEmpty() },
        )
    }

    private fun openAndSmoke(file: File): BundleReader {
        val reader = BundleReader(file)
        // Tocca le sezioni che l'app usera' per prime: se una e' corrotta il
        // CRC di sezione lo dice adesso, non alla prima schermata.
        check(reader.stopCount > 0) { "bundle senza fermate" }
        check(reader.tripCount > 0) { "bundle senza corse" }
        reader.stopName(0)
        return reader
    }

    /**
     * Scarica e decomprime in un passaggio.
     *
     * Non usa EngineHttp: quello scarica un file e basta, e la schermata di
     * benvenuto ha bisogno del progresso. Il conteggio e' sui byte compressi
     * ricevuti, che sono quelli che l'utente sta pagando.
     */
    private fun downloadGunzip(url: String, out: File, onProgress: (Long) -> Unit) {
        out.parentFile?.mkdirs()
        var current = url
        var redirects = 0
        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            // Niente gzip di trasporto sopra un corpo gia' gzip.
            conn.setRequestProperty("Accept-Encoding", "identity")
            val code = conn.responseCode
            if (code in 301..308 && redirects < 5) {
                val location = conn.getHeaderField("Location") ?: error("redirect senza destinazione")
                conn.disconnect()
                current = URL(URL(current), location).toString()
                redirects++
                continue
            }
            check(code == 200) { "download fallito: HTTP $code" }
            var received = 0L
            val counting = object : java.io.FilterInputStream(conn.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) {
                        received += n
                        onProgress(received)
                    }
                    return n
                }
            }
            GZIPInputStream(counting, 1 shl 16).use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output, 1 shl 16) }
            }
            conn.disconnect()
            return
        }
    }

    private fun httpGetText(url: String): String {
        var current = url
        var redirects = 0
        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val code = conn.responseCode
            if (code in 301..308 && redirects < 5) {
                val location = conn.getHeaderField("Location") ?: error("redirect senza destinazione")
                conn.disconnect()
                current = URL(URL(current), location).toString()
                redirects++
                continue
            }
            check(code == 200) { "HTTP $code su $current" }
            return conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * L'indice del bundle: una release GitHub con tag fisso `dati`, i cui
         * asset vengono sostituiti dal job notturno. GitHub Releases e' un
         * CDN vero (verificato in Fase 1: Fastly, range request, cache HIT).
         */
        const val INDEX_URL =
            "https://github.com/Casual76/Fluid-transit/releases/download/dati/index.json"

        const val USER_AGENT = "FluidTransit/0.1 (+https://github.com/Casual76/Fluid-transit)"

        /** Stima mostrata prima di conoscere l'indice, per la domanda su rete a consumo. */
        const val EXPECTED_BYTES = 6L * 1024 * 1024
    }
}
