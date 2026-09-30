package dev.antigravity.fluidtransit.ai.net

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il tempo massimo di una richiesta e' un tempo massimo.
 *
 * Una lettura su `HttpURLConnection` non vede la cancellazione delle coroutine: la trascrizione di
 * una domanda parlata, con un limite di quaranta secondi, restava sotto "Un attimo..." per i dieci
 * secondi di connessione piu' i sessanta di lettura di ogni provider, e alla fine diceva "Non sono
 * riuscito a capire l'audio". Con un server locale che accetta la connessione e poi tace si vede la
 * differenza senza rete vera.
 */
class AiHttpCancellationTest {

    @Test
    fun `un server che tace si lascia dopo il tempo massimo e non dopo la lettura`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            // La lettura durerebbe quindici secondi: se il limite fosse rispettato solo a parole,
            // il test se ne accorge.
            val http = AiHttp("test", connectTimeoutMillis = 2_000, readTimeoutMillis = 15_000)
            val inizio = System.nanoTime()
            val risposta = withTimeoutOrNull(300) {
                http.getJson("http://127.0.0.1:${server.localPort}/muto", emptyMap())
            }
            val trascorsi = (System.nanoTime() - inizio) / 1_000_000
            // Null vuol dire "scaduto": non un errore di rete, che sarebbe "niente rete".
            assertNull(risposta)
            assertTrue("ci ha messo $trascorsi ms", trascorsi < 6_000)
        }
    }

    @Test
    fun `una risposta normale arriva intera`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val corpo = "{\"ok\":true}"
            val servizio = thread(isDaemon = true) {
                server.accept().use { socket ->
                    // Si legge la richiesta fino alla riga vuota, poi si risponde e si chiude.
                    val lettore = socket.getInputStream().bufferedReader()
                    while (true) {
                        val riga = lettore.readLine() ?: break
                        if (riga.isEmpty()) break
                    }
                    val uscita = socket.getOutputStream()
                    uscita.write(
                        (
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${corpo.length}\r\nConnection: close\r\n\r\n$corpo"
                            ).toByteArray(Charsets.UTF_8),
                    )
                    uscita.flush()
                }
            }
            val http = AiHttp("test", connectTimeoutMillis = 2_000, readTimeoutMillis = 5_000)
            val risposta = http.getJson("http://127.0.0.1:${server.localPort}/modelli", emptyMap())
            assertEquals(200, risposta.code)
            assertNotNull(risposta.body)
            assertEquals(corpo, risposta.body.toString())
            servizio.join(2_000)
        }
    }
}
