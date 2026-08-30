package dev.antigravity.fluidtransit.spike.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.Cache
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.IOException

/**
 * Traffico della mappa: misurato, e ridotto dove si può.
 *
 * Non è strumentazione da prototipo destinata a sparire. Risponde a una
 * domanda concreta del piano: il satellite è **l'unica funzione dell'app il
 * cui traffico cresce con gli utenti** — le ortofoto vengono da un server
 * della pubblica amministrazione senza CDN e andranno dietro la nostra cache,
 * contro un tetto di 100.000 richieste/giorno sulla Worker.
 *
 * Qui c'è anche il primo pezzo della risposta, non solo la misura: le
 * ortofoto arrivano **senza header di cache**, quindi né OkHttp né la cache
 * interna di MapLibre le conserverebbero, e ogni ritorno sulla stessa zona
 * sarebbe una richiesta nuova. Riscrivere `Cache-Control` in ingresso è
 * legittimo perché quel dato è un volo aereo del 2013: non cambierà.
 */
class NetworkStats(cacheDir: File) {

    data class Snapshot(
        val requests: Int = 0,
        val fromCache: Int = 0,
        val networkBytes: Long = 0,
        val perHost: Map<String, Int> = emptyMap(),
        val lastError: String? = null,
        val slowestMs: Long = 0,
    )

    var snapshot by mutableStateOf(Snapshot())
        private set

    fun reset() {
        snapshot = Snapshot()
    }

    private val startedAt = HashMap<Call, Long>()

    private val listener = object : EventListener() {

        override fun callStart(call: Call) {
            synchronized(this@NetworkStats) { startedAt[call] = System.nanoTime() }
            bump(host = call.request().url.host)
        }

        /**
         * I byte veri, letti dal socket. Il `Content-Length` non serve: il WMS
         * della Regione risponde in chunked e non lo manda, che è proprio il
         * caso che interessa misurare.
         */
        override fun responseBodyEnd(call: Call, byteCount: Long) {
            bump(networkBytes = byteCount)
        }

        override fun cacheHit(call: Call, response: Response) {
            bump(fromCache = 1)
        }

        override fun cacheConditionalHit(call: Call, cachedResponse: Response) {
            bump(fromCache = 1)
        }

        override fun callEnd(call: Call) = finish(call)

        override fun callFailed(call: Call, ioe: IOException) {
            finish(call)
            // MapLibre annulla di continuo le tile che ha prefetchato e non
            // servono più: è funzionamento normale, non un errore, e contarlo
            // come tale seppellirebbe gli errori veri.
            if (call.isCanceled()) return
            bump(error = ioe.message ?: ioe.javaClass.simpleName)
        }

        private fun finish(call: Call) {
            val begun = synchronized(this@NetworkStats) { startedAt.remove(call) } ?: return
            bump(elapsedMs = (System.nanoTime() - begun) / 1_000_000)
        }
    }

    @Synchronized
    private fun bump(
        host: String? = null,
        networkBytes: Long = 0,
        fromCache: Int = 0,
        elapsedMs: Long = 0,
        error: String? = null,
    ) {
        val current = snapshot
        val hosts = if (host == null) current.perHost else {
            val key = shortHost(host)
            current.perHost + (key to ((current.perHost[key] ?: 0) + 1))
        }
        snapshot = current.copy(
            requests = current.requests + if (host != null) 1 else 0,
            fromCache = current.fromCache + fromCache,
            networkBytes = current.networkBytes + networkBytes,
            perHost = hosts,
            lastError = error ?: current.lastError,
            slowestMs = maxOf(current.slowestMs, elapsedMs),
        )
    }

    /** `www502.regione.toscana.it` è troppo lungo per una riga di diagnostica. */
    private fun shortHost(host: String): String = when {
        host.contains("openfreemap") -> "openfreemap"
        host.contains("regione.toscana") -> "ortofoto RT"
        else -> host
    }

    private val cache = Cache(File(cacheDir, "tiles"), CACHE_BYTES)

    fun client(): OkHttpClient = OkHttpClient.Builder()
        .cache(cache)
        .eventListener(listener)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (!REWRITE_ORTHO_CACHE) return@addNetworkInterceptor response
            if (!chain.request().url.host.contains("regione.toscana")) return@addNetworkInterceptor response
            // Un volo aereo del 2013 non cambia: un mese di cache è
            // conservativo. `Pragma` ed `Expires` vanno tolti o vincono loro.
            response.newBuilder()
                .header("Cache-Control", "public, max-age=$ORTHO_MAX_AGE")
                .removeHeader("Pragma")
                .removeHeader("Expires")
                .build()
        }
        .build()

    private companion object {
        /**
         * Messo a `false` si misura il controfattuale: senza riscrittura le
         * ortofoto arrivano senza alcun header di cache e nessuno dei due
         * livelli le conserva.
         */
        const val REWRITE_ORTHO_CACHE = true
        const val CACHE_BYTES = 128L * 1024 * 1024
        const val ORTHO_MAX_AGE = 30 * 24 * 3600
    }
}
