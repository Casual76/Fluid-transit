package dev.antigravity.fluidtransit.ui.map

import android.content.Context
import java.io.File
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Il client HTTP della mappa, condiviso con MapLibre via `HttpRequestUtil`.
 *
 * Tre lavori, tutti misurati in Fase 1:
 *
 *  1. **Cache su disco (128 MB)** per le tile.
 *  2. **Riscrittura di Cache-Control per le ortofoto**: il WMS della Regione
 *     non manda nessun header di cache, quindi ogni ritorno sulla stessa zona
 *     sarebbe traffico nuovo. E' un volo aereo del 2013: 30 giorni di cache
 *     sono onesti. Misurato: lo stesso gesto passa da 16 richieste / 800 KB a
 *     6 richieste / 204 KB.
 *  3. **Memoizzazione della testa dei PMTiles**: MapLibre rilegge header
 *     (127 B) e root directory a ogni tile, da piu' thread, senza memoria —
 *     40 richieste su 64 erano riletture degli stessi 525 byte. Qui la prima
 *     lettura scarica i primi 16 KB una volta e ogni richiesta successiva
 *     dentro quel prefisso riceve una 206 locale.
 */
object MapHttp {

    private const val ORTHO_MAX_AGE = 30 * 24 * 3600
    private const val CACHE_BYTES = 128L * 1024 * 1024
    private const val HEAD_BYTES = 16 * 1024

    private class PmtilesHead(val total: Long, val bytes: ByteArray)

    private val heads = HashMap<String, PmtilesHead>()

    @Volatile
    private var client: OkHttpClient? = null

    fun client(context: Context): OkHttpClient {
        client?.let { return it }
        synchronized(this) {
            client?.let { return it }
            val built = OkHttpClient.Builder()
                .cache(Cache(File(context.cacheDir, "tiles"), CACHE_BYTES))
                .addInterceptor(pmtilesHeadInterceptor)
                .addNetworkInterceptor(orthoCacheRewrite)
                .build()
            client = built
            return built
        }
    }

    /**
     * Le richieste Range dentro i primi 16 KB di un .pmtiles si servono da
     * una copia in memoria, scaricata una volta per URL. Vale per header e
     * root directory; le tile vere (offset piu' avanti) passano oltre.
     */
    private val pmtilesHeadInterceptor = Interceptor { chain ->
        val request = chain.request()
        val url = request.url.toString()
        val range = request.header("Range")
        if (!url.contains(".pmtiles") || range == null) {
            return@Interceptor chain.proceed(request)
        }
        val match = RANGE_RE.matchEntire(range.trim())
            ?: return@Interceptor chain.proceed(request)
        val from = match.groupValues[1].toLong()
        val to = match.groupValues[2].toLong()
        if (to >= HEAD_BYTES) return@Interceptor chain.proceed(request)

        val head = synchronized(heads) { heads[url] } ?: run {
            val fetched = chain.proceed(
                request.newBuilder()
                    .header("Range", "bytes=0-${HEAD_BYTES - 1}")
                    .build(),
            )
            if (fetched.code != 206) return@Interceptor fetched // il server non fa range: passa
            val body = fetched.body!!.bytes()
            val total = fetched.header("Content-Range")
                ?.substringAfter('/')?.toLongOrNull() ?: -1L
            fetched.close()
            PmtilesHead(total, body).also { synchronized(heads) { heads[url] = it } }
        }

        val hi = minOf(to, head.bytes.size - 1L)
        val slice = head.bytes.copyOfRange(from.toInt(), (hi + 1).toInt())
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(206)
            .message("Partial Content (memo)")
            .header("Content-Range", "bytes $from-$hi/${if (head.total > 0) head.total else "*"}")
            .header("Accept-Ranges", "bytes")
            .body(slice.toResponseBody("application/octet-stream".toMediaType()))
            .build()
    }

    private val RANGE_RE = Regex("bytes=(\\d+)-(\\d+)")

    /**
     * Da network interceptor, non da application interceptor: deve vedere la
     * risposta di rete prima che la cache decida se conservarla. Pragma ed
     * Expires vanno rimossi o vincono loro.
     */
    private val orthoCacheRewrite = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (!chain.request().url.host.contains("regione.toscana")) {
            response
        } else {
            response.newBuilder()
                .header("Cache-Control", "public, max-age=$ORTHO_MAX_AGE")
                .removeHeader("Pragma")
                .removeHeader("Expires")
                .build()
        }
    }
}
