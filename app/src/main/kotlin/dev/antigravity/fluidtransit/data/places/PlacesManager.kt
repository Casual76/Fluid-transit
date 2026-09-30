package dev.antigravity.fluidtransit.data.places

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import dev.antigravity.fluidtransit.data.bundle.BundleManager
import dev.antigravity.fluidtransit.data.net.Metered
import dev.antigravity.fluidtransit.data.store.Durable
import dev.antigravity.fluidtransit.routing.PlacesReader
import dev.antigravity.fluidtransit.routing.PlacesSearch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Il file dei luoghi (`luoghi.bin`), gestito come il bundle: scarica su
 * rete non a consumo, verifica lo sha256, sostituzione atomica, mmap.
 *
 * A differenza del bundle non blocca niente: finche' non c'e', la ricerca
 * lavora su fermate e linee e la sezione Luoghi semplicemente non compare.
 *
 * Il file non si tenta una volta e basta: [start], il ritorno in primo piano
 * ([refreshIfNeeded]) e la comparsa di una rete non a consumo lo riprovano,
 * tutti attraverso la stessa porta — un solo giro alla volta, e una soglia
 * ([PlacesRefreshPolicy]) che impedisce a tanti inneschi di diventare tanti
 * download.
 */
class PlacesManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        /** Non ancora provato, o l'ultimo aggiornamento notturno non comprende i luoghi: niente da aspettare. */
        object Missing : State

        object Downloading : State

        /**
         * Il file manca e la rete e' a consumo, o non c'e': parte da solo
         * appena ne compare una non a consumo.
         */
        object WaitingForWifi : State

        /** L'ultimo tentativo e' fallito: si riprova da solo, e a ogni ritorno in primo piano. */
        class Failed(val message: String) : State

        class Ready(val reader: PlacesReader, val search: PlacesSearch, val sha: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state

    private val file get() = File(context.filesDir, "luoghi.bin")
    private val meta get() = File(context.filesDir, "luoghi.meta.json")

    /**
     * Un giro alla volta. Il file si scrive su `luoghi.bin.part` e si sposta
     * su `luoghi.bin`: due giri insieme si pesterebbero sullo stesso `.part`.
     * Chi arriva mentre un altro sta girando aspetta il proprio turno, e al
     * turno la soglia ([PlacesRefreshPolicy.due]) gli dice che il lavoro e'
     * gia' fatto — meglio che scartarlo, perche' un innesco scartato per un
     * soffio (la rete compare mentre si sta ancora registrando l'attesa) non
     * si ripresenta piu'.
     */
    private val mutex = Mutex()

    // Li tocca solo chi ha il mutex.
    private var lastAttemptAt = 0L
    private var last = PlacesRefreshPolicy.Last.Never

    private val networkCallback = AtomicReference<ConnectivityManager.NetworkCallback?>(null)
    private val retryScheduled = AtomicBoolean(false)

    fun start() {
        scope.launch(Dispatchers.IO) {
            // Prima il file gia' in tasca: la ricerca luoghi parte subito.
            mutex.withLock { openExisting() }
            refreshGuarded()
        }
    }

    /**
     * Prova a scaricare i luoghi se serve, e solo se e' ora.
     *
     * Non costa niente chiamarla spesso: se l'ultimo giro e' recente, o se non
     * c'e' una rete non a consumo, torna subito senza toccare la rete.
     */
    fun refreshIfNeeded() {
        scope.launch(Dispatchers.IO) { refreshGuarded() }
    }

    private suspend fun refreshGuarded() {
        mutex.withLock { refresh() }
    }

    /** Apre il file che c'e' gia' in tasca, se c'e' e se si apre. */
    private fun openExisting(): Boolean = runCatching {
        if (!file.isFile || !meta.isFile) return@runCatching false
        val sha = JSONObject(meta.readText()).optString("sha256")
        open(sha)
        true
    }.getOrDefault(false)

    /**
     * Il lettore vecchio si chiude dopo, non subito: un minuto, come per il
     * bundle, perche' una ricerca in corso sta leggendo da quella mappa.
     */
    private fun retire(previous: PlacesReader?) {
        if (previous == null) return
        scope.launch {
            delay(60_000L)
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

    /**
     * Scarica la versione dell'indice se diversa da quella in tasca.
     *
     * Si chiama sempre col mutex in mano.
     */
    private suspend fun refresh() {
        if (!PlacesRefreshPolicy.due(SystemClock.elapsedRealtime(), lastAttemptAt, last)) return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        // Come per il bundle: all'avvio "a consumo?" puo' non avere ancora
        // risposta, e la vecchia domanda diceva si' e saltava il giro.
        val consumo = Metered.await(cm)
        when (PlacesRefreshPolicy.verdict(consumo, ready = _state.value is State.Ready)) {
            PlacesRefreshPolicy.Verdict.Leave -> {
                stopWatchingNetwork()
                return
            }

            PlacesRefreshPolicy.Verdict.WaitForWifi -> {
                // Undici megabyte non si scaricano sui dati mobili senza
                // chiedere: i luoghi sono un di piu', e possono aspettare il
                // Wi-Fi — che e' esattamente quello che lo Stato dei dati
                // promette all'utente. Ma la promessa vale solo se poi
                // qualcuno resta ad aspettarlo: prima il tentativo era uno
                // per processo, e un Wi-Fi arrivato a app aperta non faceva
                // partire niente.
                _state.value = State.WaitingForWifi
                watchNetwork()
                return
            }

            PlacesRefreshPolicy.Verdict.Attempt -> Unit
        }
        try {
            val index = JSONObject(httpGetText(BundleManager.INDEX_URL))
            val url = index.optString("placesUrl").takeIf { it.isNotEmpty() }
            if (url == null) {
                // L'estratto OSM e' facoltativo, e un aggiornamento notturno
                // puo' uscire senza i luoghi: non c'e' niente da aspettare, e
                // dirlo "in arrivo" sarebbe una promessa che nessuno mantiene.
                last = PlacesRefreshPolicy.Last.Fine
                stopWatchingNetwork()
                if (_state.value !is State.Ready) _state.value = State.Missing
                return
            }
            val sha = index.optString("placesSha256")
            val current = (_state.value as? State.Ready)?.sha
            // Senza sha in indice non si sa se il file e' cambiato: con un
            // file in uso non si rimettono undici megabyte in coda a ogni
            // ritorno in primo piano per scoprirlo.
            if (current != null && (sha.isEmpty() || sha == current)) {
                last = PlacesRefreshPolicy.Last.Fine
                stopWatchingNetwork()
                return
            }

            if (_state.value !is State.Ready) _state.value = State.Downloading
            val part = File(context.filesDir, "luoghi.bin.part")
            downloadGunzip(url, part)
            val actual = sha256(part)
            if (sha.isNotEmpty() && actual != sha) {
                part.delete()
                // Un errore, non un ritorno a mani vuote: prima il file
                // scartato lasciava lo stato com'era e nessuno lo sapeva.
                error("luoghi corrotti in transito (sha256 diverso)")
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
            last = PlacesRefreshPolicy.Last.Fine
            stopWatchingNetwork()
        } catch (e: Exception) {
            last = PlacesRefreshPolicy.Last.Failed
            runCatching { File(context.filesDir, "luoghi.bin.part").delete() }
            // Un errore non deve lasciare l'app senza il file che aveva: se
            // lo scambio e' arrivato a togliere il lettore dallo stato, il
            // file di ieri e' ancora li' e si riapre. Se non c'e' niente, lo
            // stato lo dice — "Missing" voleva dire anche "e' andata male",
            // e la ricerca prometteva un download che non partiva.
            if (_state.value !is State.Ready && !openExisting()) {
                _state.value = State.Failed(e.message ?: "errore sconosciuto")
                watchNetwork()
                scheduleRetry()
            }
        } finally {
            lastAttemptAt = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Un altro giro fra qualche minuto, per chi non tocca l'app.
     *
     * Il ritorno in primo piano e la comparsa di una rete riprovano, ma un
     * telefono fermo su un Wi-Fi stabile non produce nessuno dei due: senza
     * questo, "riprovo da solo" sarebbe una frase senza nessuno che la fa.
     */
    private fun scheduleRetry() {
        if (!retryScheduled.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            // Un secondo in piu' della soglia: il conto parte da quando finisce
            // il giro fallito (lastAttemptAt si scrive dopo questa riga), e il
            // timer parte da prima. Preciso alla soglia, il risveglio poteva
            // trovarsi qualche millisecondo sotto, `due` diceva "presto", e
            // nessun altro giro veniva programmato: la frase "riprovo da solo"
            // restava senza nessuno che la mantenesse.
            delay(PlacesRefreshPolicy.AFTER_FAILURE_MS + RETRY_SLACK_MS)
            retryScheduled.set(false)
            refreshGuarded()
        }
    }

    /**
     * Guarda la rete finche' il file non c'e': quando ne compare una non a
     * consumo e con internet vero, riprova.
     *
     * Le capacita' della rete, non `onAvailable` — come per il bundle: li'
     * possono non essere ancora note e la risposta di ripiego e' "a consumo".
     * E `VALIDATED` perche' una callback di rete arriva anche per una rete
     * ancora chiusa a quest'app o dietro una pagina di accesso, e il download
     * che parte subito muore su "Unable to resolve host".
     *
     * Non scollega dopo il primo scatto, a differenza del bundle: qui un giro
     * fallito e' normale (un Wi-Fi che non regge), e il prossimo scatto deve
     * poterlo riprovare. La soglia impedisce che diventi una raffica.
     */
    private fun watchNetwork() {
        if (networkCallback.get() != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (Metered.isMetered(caps)) return
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                refreshIfNeeded()
            }
        }
        if (!networkCallback.compareAndSet(null, callback)) return
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure { networkCallback.compareAndSet(callback, null) }
    }

    private fun stopWatchingNetwork() {
        val callback = networkCallback.getAndSet(null) ?: return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    /**
     * Con i tempi: senza, una rete che accetta la connessione e poi tace — una
     * galleria a meta' dei undici megabyte — teneva lo stato su "Downloading"
     * finche' il socket moriva da solo, cioe' quasi mai, perche' quelli di
     * `HttpURLConnection` non hanno un tempo di default. La riga "Luoghi in
     * arrivo" e la barra di ricerca non si risolvevano piu'.
     */
    private fun httpGetText(url: String): String {
        var current = url
        for (giro in 0 until MAX_REDIRECTS) {
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_INDEX_MS
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", BundleManager.USER_AGENT)
                val code = conn.responseCode
                when (code) {
                    in 300..399 -> {
                        val location = conn.getHeaderField("Location") ?: error("redirect senza Location")
                        current = URL(URL(current), location).toString()
                    }

                    200 -> return conn.inputStream.bufferedReader().use { it.readText() }
                    else -> error("HTTP $code su $current")
                }
            } finally {
                conn.disconnect()
            }
        }
        error("troppi redirect per $url")
    }

    private fun downloadGunzip(url: String, out: File) {
        var current = url
        for (giro in 0 until MAX_REDIRECTS) {
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_DOWNLOAD_MS
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", BundleManager.USER_AGENT)
                val code = conn.responseCode
                when (code) {
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

                    else -> error("HTTP $code su $current")
                }
            } finally {
                conn.disconnect()
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

    private companion object {
        const val MAX_REDIRECTS = 5

        /** Gli stessi tempi del bundle. */
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_INDEX_MS = 15_000
        const val READ_TIMEOUT_DOWNLOAD_MS = 30_000

        const val RETRY_SLACK_MS = 1_000L
    }
}
