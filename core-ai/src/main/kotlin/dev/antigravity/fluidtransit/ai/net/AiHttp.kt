package dev.antigravity.fluidtransit.ai.net

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Una risposta 2xx gia' letta: il JSON, gli header (minuscoli) e cio' che dicono sui limiti. */
class AiResponse(
  val code: Int,
  val body: JsonElement?,
  val headers: Map<String, String>,
  val rateLimit: RateLimitInfo,
)

/**
 * Il client HTTP dei provider IA: POST con corpo JSON, POST in streaming (SSE), POST multipart per
 * l'audio, GET per i cataloghi. Su `HttpURLConnection` come `EngineHttp`, perche' l'app non ha e
 * non vuole una libreria HTTP: quello che serve in piu' rispetto all'engine (un corpo, un flusso
 * di eventi) sta tutto qui, in un file.
 *
 * Lo streaming e' un `callbackFlow` con un thread di lettura: una coroutine bloccata in
 * `readLine()` non vede la cancellazione, e una socket non si interrompe — l'unico modo affidabile
 * di fermare una lettura e' `disconnect()` da un altro thread, che fa fallire la `readLine()`.
 * `awaitClose` fa esattamente quello quando chi raccoglie il flusso se ne va.
 */
class AiHttp(
  private val userAgent: String,
  private val connectTimeoutMillis: Int = 10_000,
  private val readTimeoutMillis: Int = 60_000,
  /** Per lettura, in streaming: il thinking puo' tacere per un po', ma non per mezzo minuto. */
  private val streamChunkTimeoutMillis: Int = 30_000,
  private val io: CoroutineDispatcher = Dispatchers.IO,
) {

  suspend fun postJson(url: String, headers: Map<String, String>, body: JsonObject): AiResponse =
    withContext(io) {
      val connection = open(url, "POST", headers, streaming = false)
      try {
        blocking(connection) {
          writeJson(connection, body)
          readResponse(connection)
        }
      } catch (t: Throwable) {
        throw AiErrorMapper.wrap(t)
      } finally {
        connection.disconnect()
      }
    }

  suspend fun getJson(url: String, headers: Map<String, String>): AiResponse = withContext(io) {
    val connection = open(url, "GET", headers, streaming = false)
    try {
      blocking(connection) { readResponse(connection) }
    } catch (t: Throwable) {
      throw AiErrorMapper.wrap(t)
    } finally {
      connection.disconnect()
    }
  }

  suspend fun postMultipart(
    url: String,
    headers: Map<String, String>,
    fields: Map<String, String>,
    file: File,
    fileMime: String,
    fileName: String,
    fileFieldName: String = "file",
  ): AiResponse = withContext(io) {
    val parts = MultipartBody.build(fields, fileFieldName, fileName, fileMime)
    val connection = open(url, "POST", headers, streaming = false)
    try {
      blocking(connection) {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", parts.contentType)
        connection.setFixedLengthStreamingMode(parts.contentLength(file.length()))
        connection.outputStream.use { out ->
          out.write(parts.prelude)
          file.inputStream().use { it.copyTo(out) }
          out.write(parts.epilogue)
        }
        readResponse(connection)
      }
    } catch (t: Throwable) {
      throw AiErrorMapper.wrap(t)
    } finally {
      connection.disconnect()
    }
  }

  /**
   * Gli eventi `data:` di uno stream SSE, uno per emissione, gia' scorporati dal parser; termina a
   * `[DONE]` o alla chiusura del server. Gli errori HTTP arrivano come [AiError] prima del primo
   * evento; quelli di rete a meta' come chiusura del flusso con causa.
   */
  fun postJsonStream(
    url: String,
    headers: Map<String, String>,
    body: JsonObject,
    /** Gli header della risposta 2xx (minuscoli), appena arrivano: i limiti stanno li'. */
    onHeaders: (Map<String, String>) -> Unit = {},
  ): Flow<String> = callbackFlow {
    val connection = open(url, "POST", headers, streaming = true)
    val reader = launch(io) {
      try {
        writeJson(connection, body)
        val code = connection.responseCode
        if (code !in 200..299) {
          val text = connection.errorStream?.use { it.readUtf8() }.orEmpty()
          throw AiErrorMapper.map(code, connection.headerFields, text)
        }
        onHeaders(AiErrorMapper.normalize(connection.headerFields))
        val parser = SseParser()
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
          while (isActive) {
            val line = input.readLine() ?: break
            val payload = parser.feed(line) ?: continue
            if (payload == DONE) break
            send(payload)
          }
          if (isActive) parser.flush()?.takeIf { it != DONE }?.let { send(it) }
        }
        close()
      } catch (t: Throwable) {
        close(if (t is CancellationException) null else AiErrorMapper.wrap(t))
      }
    }
    awaitClose {
      connection.disconnect()
      reader.cancel()
    }
  }

  /**
   * Una richiesta bloccante che la cancellazione ferma davvero.
   *
   * Una lettura su `HttpURLConnection` non vede la cancellazione delle coroutine: un `withTimeout`
   * attorno a una richiesta muta aspettava comunque i dieci secondi di connessione e i sessanta di
   * lettura, e "il tempo massimo" non era un tempo massimo — la trascrizione di una domanda
   * parlata restava sotto il suo cerchio per minuti, senza rete vera. Come per lo streaming,
   * l'unico modo di interrompere una socket e' `disconnect()` da un altro thread: un guardiano
   * aspetta la cancellazione e chiude la connessione, e la lettura ferma cade con un'eccezione.
   *
   * Quell'eccezione e' un effetto della cancellazione, non un errore di rete: se il chiamante e'
   * stato cancellato si lascia passare la cancellazione (cosi' un `withTimeoutOrNull` risponde
   * null e non "niente rete"), altrimenti l'errore e' quello vero.
   *
   * Il guardiano parte subito (`UNDISPATCHED`): se aspettasse il suo turno sul pool, una
   * cancellazione arrivata prima lo troverebbe gia' cancellato, senza corpo e senza `disconnect()`,
   * e la richiesta girerebbe fino in fondo.
   */
  private suspend fun <T> blocking(connection: HttpURLConnection, block: () -> T): T = coroutineScope {
    val guard = launch(io, start = CoroutineStart.UNDISPATCHED) {
      try {
        awaitCancellation()
      } finally {
        runCatching { connection.disconnect() }
      }
    }
    try {
      ensureActive()
      block()
    } catch (t: Throwable) {
      ensureActive()
      throw t
    } finally {
      guard.cancel()
    }
  }

  private fun open(url: String, method: String, headers: Map<String, String>, streaming: Boolean): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = method
      connectTimeout = connectTimeoutMillis
      readTimeout = if (streaming) streamChunkTimeoutMillis else readTimeoutMillis
      // Mai ri-inviare un corpo con Authorization dietro a un redirect.
      instanceFollowRedirects = false
      useCaches = false
      setRequestProperty("User-Agent", userAgent)
      setRequestProperty("Accept", if (streaming) "text/event-stream" else "application/json")
      // Android aggiunge gzip da solo, e il gzip bufferizza i delta: in streaming si chiede identita'.
      if (streaming) setRequestProperty("Accept-Encoding", "identity")
      headers.forEach { (key, value) -> setRequestProperty(key, value) }
    }

  private fun writeJson(connection: HttpURLConnection, body: JsonObject) {
    val bytes = body.toString().toByteArray(Charsets.UTF_8)
    connection.doOutput = true
    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
    connection.setFixedLengthStreamingMode(bytes.size)
    connection.outputStream.use { it.write(bytes) }
  }

  private fun readResponse(connection: HttpURLConnection): AiResponse {
    val code = connection.responseCode
    val headers = AiErrorMapper.normalize(connection.headerFields)
    if (code !in 200..299) {
      val text = connection.errorStream?.use { it.readUtf8() }.orEmpty()
      throw AiErrorMapper.map(code, connection.headerFields, text)
    }
    val text = connection.inputStream.use { it.readUtf8() }
    val json = if (text.isBlank()) {
      null
    } else {
      runCatching { Json.parseToJsonElement(text) }.getOrElse { throw AiError.Parse("risposta non JSON", it) }
    }
    // Un documento HTML (un proxy, un captive portal) passa il parser come letterale: non e' JSON.
    if (json != null && json !is JsonObject && json !is JsonArray) throw AiError.Parse("risposta non JSON")
    return AiResponse(code, json, headers, AiErrorMapper.parseRateLimit(headers))
  }

  private fun InputStream.readUtf8(): String = readBytes().toString(Charsets.UTF_8)

  private companion object {
    const val DONE = "[DONE]"
  }
}
