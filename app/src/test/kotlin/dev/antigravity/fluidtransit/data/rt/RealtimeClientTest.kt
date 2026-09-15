package dev.antigravity.fluidtransit.data.rt

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La macchina a tre stati del client realtime.
 *
 * E' la logica piu' delicata dell'app — decide se i numeri che vedi sono
 * live, vecchi o inventati — ed era anche l'unica senza una sola asserzione.
 * Ogni soglia qui sotto e' gia' costata un giro di correzioni sul telefono:
 * i tre errori DI FILA prima di scendere sull'origine, i tre giri stantii DI
 * FILA prima di dare per morto il proxy, il blocco di cinque minuti che
 * evita di rimbalzare avanti e indietro. Chi cambia quei numeri deve vedere
 * rompersi qualcosa.
 */
class RealtimeClientTest {

    private lateinit var proxy: MockWebServer
    private lateinit var origin: MockWebServer

    @Before
    fun start() {
        proxy = MockWebServer().also { it.start() }
        origin = MockWebServer().also { it.start() }
    }

    @After
    fun stop() {
        proxy.shutdown()
        origin.shutdown()
    }

    private fun client(proxyAllowed: Boolean = true) = RealtimeClient(
        proxyAllowed = { proxyAllowed },
        proxyBase = proxy.url("/rt/v1").toString().trimEnd('/'),
        directVehiclesUrl = origin.url("/vehicle-positions").toString(),
    )

    // ------------------------------------------------------- la strada buona

    @Test
    fun `uno snapshot fresco mette il client su PROXY`() = runTest {
        proxy.enqueue(snapshotResponse(vehicleCount = 3, feedAge = 20))

        val rt = client()
        rt.refreshVehicles()

        assertEquals(RealtimeClient.Source.PROXY, rt.status.value.source)
        assertEquals(3, rt.status.value.vehicleCount)
        assertEquals(20L, rt.status.value.feedAgeSeconds)
        assertNull(rt.status.value.lastError)
        assertNotNull(rt.status.value.lastSuccessAt)
        assertEquals(0, origin.requestCount)
    }

    @Test
    fun `un 304 tiene i dati di prima e aggiorna solo l'eta'`() = runTest {
        proxy.enqueue(snapshotResponse(vehicleCount = 2, feedAge = 10, etag = "W/\"abc\""))
        proxy.enqueue(MockResponse().setResponseCode(304).setHeader("X-Feed-Age", "50"))

        val rt = client()
        rt.refreshVehicles()
        val first = rt.vehicles.value
        rt.refreshVehicles()

        // Stessa identica istanza: un 304 non deve rifare il parse, e
        // soprattutto non deve azzerare i mezzi mentre la mappa li disegna.
        assertTrue(first === rt.vehicles.value)
        assertEquals(2, rt.status.value.vehicleCount)
        assertEquals(RealtimeClient.Source.PROXY, rt.status.value.source)
        // L'eta' e' quella che ha mandato il proxy, non quella ricalcolata
        // con l'orologio del telefono: il 304 la porta apposta, e prima
        // veniva buttata insieme a tutte le altre intestazioni.
        assertEquals(50L, rt.status.value.feedAgeSeconds)
        proxy.takeRequest()
        assertEquals("W/\"abc\"", proxy.takeRequest().getHeader("If-None-Match"))
    }

    // ------------------------------------------------------ quando si guasta

    @Test
    fun `un errore isolato non fa cadere il client sull'origine`() = runTest {
        // Prima della Fase 8 bastava un timeout per portare la cadenza a tre
        // minuti e azzerare i ritardi, senza che niente lo dicesse.
        proxy.enqueue(MockResponse().setResponseCode(500))

        val rt = client()
        rt.refreshVehicles()

        assertEquals(0, origin.requestCount)
        assertNotNull(rt.status.value.lastError)
    }

    @Test
    fun `due errori non bastano, tre di fila portano all'origine`() = runTest {
        repeat(3) { proxy.enqueue(MockResponse().setResponseCode(500)) }
        origin.enqueue(feedResponse(vehicles = 1))

        val rt = client()
        rt.refreshVehicles()
        rt.refreshVehicles()
        assertEquals("al secondo errore l'origine non si tocca ancora", 0, origin.requestCount)

        rt.refreshVehicles()
        assertEquals(RealtimeClient.Source.DIRECT, rt.status.value.source)
        assertEquals(1, origin.requestCount)
    }

    @Test
    fun `un successo in mezzo azzera il contatore degli errori`() = runTest {
        proxy.enqueue(MockResponse().setResponseCode(500))
        proxy.enqueue(MockResponse().setResponseCode(500))
        proxy.enqueue(snapshotResponse(vehicleCount = 1, feedAge = 5))
        proxy.enqueue(MockResponse().setResponseCode(500))
        proxy.enqueue(MockResponse().setResponseCode(500))

        val rt = client()
        repeat(5) { rt.refreshVehicles() }

        // Due errori, poi due errori: nessuna serie di tre, quindi l'origine
        // resta intoccata. Un contatore che non si azzera avrebbe fatto
        // scendere in DIRECT un proxy che funziona a singhiozzo ma funziona.
        assertEquals(0, origin.requestCount)
    }

    @Test
    fun `se non risponde nemmeno l'origine restano gli orari`() = runTest {
        repeat(3) { proxy.enqueue(MockResponse().setResponseCode(500)) }
        origin.enqueue(MockResponse().setResponseCode(503))

        val rt = client()
        repeat(3) { rt.refreshVehicles() }

        assertEquals(RealtimeClient.Source.SCHEDULE_ONLY, rt.status.value.source)
    }

    @Test
    fun `dopo il passaggio all'origine il proxy si lascia in pace`() = runTest {
        repeat(3) { proxy.enqueue(MockResponse().setResponseCode(500)) }
        origin.enqueue(feedResponse(vehicles = 1))
        origin.enqueue(feedResponse(vehicles = 1))

        val rt = client()
        repeat(3) { rt.refreshVehicles() }
        val askedSoFar = proxy.requestCount

        rt.refreshVehicles()

        // Il blocco di cinque minuti esiste perche' un proxy in ginocchio
        // risponde lento, non subito: continuare a interrogarlo a ogni giro
        // vuol dire aspettare il timeout prima di ogni dato utile.
        assertEquals(askedSoFar, proxy.requestCount)
        assertEquals(2, origin.requestCount)
    }

    @Test
    fun `passando all'origine i ritardi si azzerano invece di mentire`() = runTest {
        proxy.enqueue(snapshotResponse(vehicleCount = 1, feedAge = 5))
        proxy.enqueue(delaysResponse(delayCount = 4))
        repeat(3) { proxy.enqueue(MockResponse().setResponseCode(500)) }
        origin.enqueue(feedResponse(vehicles = 1))

        val rt = client()
        rt.refreshVehicles()
        rt.refreshDelays()
        assertEquals(4, rt.delays.value?.byTripHash?.size)

        repeat(3) { rt.refreshVehicles() }

        assertEquals(RealtimeClient.Source.DIRECT, rt.status.value.source)
        assertNull("in DIRECT i trip-updates non arrivano: i vecchi sarebbero bugie", rt.delays.value)
    }

    // ------------------------------------------------------- il feed stantio

    @Test
    fun `un feed stantio non basta, ne servono tre di fila`() = runTest {
        repeat(3) { proxy.enqueue(snapshotResponse(vehicleCount = 1, feedAge = 400)) }
        origin.enqueue(feedResponse(vehicles = 1))

        val rt = client()
        rt.refreshVehicles()
        rt.refreshVehicles()

        // La richiesta stessa sveglia il refresh pigro del proxy: quasi
        // sempre il giro dopo trova dati freschi. Buttarsi sull'origine al
        // primo colpo era il motivo del ritmo lento da tre minuti.
        assertEquals(RealtimeClient.Source.PROXY, rt.status.value.source)
        assertEquals(400L, rt.status.value.feedAgeSeconds)
        assertNotNull(rt.status.value.lastError)
        assertEquals(0, origin.requestCount)

        rt.refreshVehicles()
        assertEquals(RealtimeClient.Source.DIRECT, rt.status.value.source)
    }

    @Test
    fun `se e' l'origine a essere ferma, cambiare strada non serve`() = runTest {
        // Visto il 15/09 sul telefono: la Regione pubblicava un feed fermo da
        // 377 s mentre il proxy rispondeva benissimo. L'app scendeva
        // sull'origine — a prendere lo STESSO dato fermo — e per farlo
        // rinunciava a tutti i ritardi, che dall'origine non si scaricano.
        // Lo snapshot fresco e' quello che distingue i due casi.
        repeat(6) {
            proxy.enqueue(
                snapshotResponse(vehicleCount = 1, feedAge = 400)
                    .setHeader("X-Snapshot-Age", "12"),
            )
        }

        val rt = client()
        repeat(6) { rt.refreshVehicles() }

        assertEquals(RealtimeClient.Source.PROXY, rt.status.value.source)
        assertEquals(0, origin.requestCount)
        assertEquals(400L, rt.status.value.feedAgeSeconds)
        assertTrue(
            "lo stato non dice di chi e' la colpa: ${rt.status.value.lastError}",
            rt.status.value.lastError?.contains("Regione") == true,
        )
    }

    @Test
    fun `se e' il proxy a essere fermo, l'origine vale la pena`() = runTest {
        // Snapshot vecchio quanto il feed: il proxy non sta rileggendo
        // niente, e l'origine puo' essere piu' fresca di lui.
        repeat(3) {
            proxy.enqueue(
                snapshotResponse(vehicleCount = 1, feedAge = 400)
                    .setHeader("X-Snapshot-Age", "400"),
            )
        }
        origin.enqueue(feedResponse(vehicles = 1))

        val rt = client()
        repeat(3) { rt.refreshVehicles() }

        assertEquals(RealtimeClient.Source.DIRECT, rt.status.value.source)
    }

    @Test
    fun `un feed fresco in mezzo azzera anche i giri stantii`() = runTest {
        proxy.enqueue(snapshotResponse(vehicleCount = 1, feedAge = 400))
        proxy.enqueue(snapshotResponse(vehicleCount = 1, feedAge = 400))
        proxy.enqueue(snapshotResponse(vehicleCount = 1, feedAge = 12))
        proxy.enqueue(snapshotResponse(vehicleCount = 1, feedAge = 400))
        proxy.enqueue(snapshotResponse(vehicleCount = 1, feedAge = 400))

        val rt = client()
        repeat(5) { rt.refreshVehicles() }

        assertEquals(RealtimeClient.Source.PROXY, rt.status.value.source)
        assertEquals(0, origin.requestCount)
    }

    @Test
    fun `un dato vecchio si mostra lo stesso, e' l'eta' a dire quanto fidarsi`() = runTest {
        proxy.enqueue(snapshotResponse(vehicleCount = 7, feedAge = 400))

        val rt = client()
        rt.refreshVehicles()

        assertEquals(7, rt.vehicles.value?.list?.size)
    }

    // -------------------------------------------------------- il kill switch

    @Test
    fun `col proxy spento si va dritti all'origine`() = runTest {
        origin.enqueue(feedResponse(vehicles = 2))

        val rt = client(proxyAllowed = false)
        rt.refreshVehicles()

        assertEquals(0, proxy.requestCount)
        assertEquals(RealtimeClient.Source.DIRECT, rt.status.value.source)
        assertEquals(2, rt.vehicles.value?.list?.size)
    }

    // ------------------------------------------------------------- i ritardi

    @Test
    fun `il primo giro di ritardi stabilisce da se' lo stato`() = runTest {
        // Il widget, le routine e la scheda Oggi sono gli unici a chiedere i
        // ritardi in un processo fresco: se `refreshDelays` fosse uscito
        // subito perche' lo stato e' ancora SCHEDULE_ONLY, non avrebbero mai
        // visto un ritardo in vita loro.
        proxy.enqueue(snapshotResponse(vehicleCount = 1, feedAge = 5))
        proxy.enqueue(delaysResponse(delayCount = 2))

        val rt = client()
        rt.refreshDelays()

        assertEquals(2, rt.delays.value?.byTripHash?.size)
        assertEquals(2, proxy.requestCount)
        assertEquals("/rt/v1/vehicles", proxy.takeRequest().path)
        assertEquals("/rt/v1/updates", proxy.takeRequest().path)
    }

    @Test
    fun `fuori da PROXY i ritardi non si chiedono nemmeno`() = runTest {
        repeat(3) { proxy.enqueue(MockResponse().setResponseCode(500)) }
        origin.enqueue(feedResponse(vehicles = 1))

        val rt = client()
        repeat(3) { rt.refreshVehicles() }
        val askedSoFar = proxy.requestCount
        rt.refreshDelays()

        // I trip-updates integrali dall'origine sono 1-2 MB al minuto: non
        // roba da telefono, e infatti in DIRECT non si chiedono proprio.
        assertEquals(askedSoFar, proxy.requestCount)
    }

    @Test
    fun `un giro di ritardi mancato non cambia lo stato`() = runTest {
        proxy.enqueue(snapshotResponse(vehicleCount = 1, feedAge = 5))
        proxy.enqueue(MockResponse().setResponseCode(500))

        val rt = client()
        rt.refreshVehicles()
        rt.refreshDelays()

        assertEquals(RealtimeClient.Source.PROXY, rt.status.value.source)
    }

    // ------------------------------------------------------------- la cadenza

    @Test
    fun `la cadenza segue lo stato`() = runTest {
        val rt = client()
        assertEquals(60_000L, rt.vehiclesIntervalMs())

        proxy.enqueue(snapshotResponse(vehicleCount = 1, feedAge = 5))
        rt.refreshVehicles()
        assertEquals(30_000L, rt.vehiclesIntervalMs())

        repeat(3) { proxy.enqueue(MockResponse().setResponseCode(500)) }
        origin.enqueue(feedResponse(vehicles = 1))
        repeat(3) { rt.refreshVehicles() }
        assertEquals(180_000L, rt.vehiclesIntervalMs())
    }

    // ---------------------------------------------------------- le risposte

    private fun snapshotResponse(
        vehicleCount: Int,
        feedAge: Long,
        etag: String? = null,
    ): MockResponse {
        val body = section(kind = 1, recordSize = 40, count = vehicleCount)
        return binary(body).setHeader("X-Feed-Age", feedAge.toString()).apply {
            if (etag != null) setHeader("ETag", etag)
        }
    }

    private fun delaysResponse(delayCount: Int): MockResponse =
        binary(section(kind = 2, recordSize = 32, count = delayCount) { buf, i, off ->
            // tripHash diverso per ognuno, o finiscono tutti sulla stessa voce.
            buf.putLong(off, (i + 1).toLong())
        })

    private fun feedResponse(vehicles: Int): MockResponse = binary(
        vehiclePositionsFeed(
            feedTimestamp = System.currentTimeMillis() / 1000 - 30,
            vehicles = List(vehicles) { VehicleFixture(tripId = "t$it", routeId = "r$it") },
        ),
    )

    private fun binary(body: ByteArray) =
        MockResponse().setBody(Buffer().write(body)).setHeader("Content-Type", "application/octet-stream")

    private fun section(
        kind: Int,
        recordSize: Int,
        count: Int,
        fill: (ByteBuffer, Int, Int) -> Unit = { buf, i, off -> buf.putLong(off, (i + 1).toLong()) },
    ): ByteArray {
        val out = ByteArray(24 + count * recordSize)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        out[0] = 'F'.code.toByte()
        out[1] = 'T'.code.toByte()
        out[2] = 'R'.code.toByte()
        out[3] = 'T'.code.toByte()
        buf.putShort(4, 1)
        buf.put(6, kind.toByte())
        buf.putInt(8, (System.currentTimeMillis() / 1000).toInt())
        buf.putInt(12, (System.currentTimeMillis() / 1000).toInt())
        buf.putInt(16, count)
        buf.putShort(20, recordSize.toShort())
        for (i in 0 until count) fill(buf, i, 24 + i * recordSize)
        return out
    }
}
