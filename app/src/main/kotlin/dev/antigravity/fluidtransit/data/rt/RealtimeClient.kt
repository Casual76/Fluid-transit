package dev.antigravity.fluidtransit.data.rt

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Il client realtime coi tre stati decisi dal piano, guidati dai dati e non
 * da un timeout generico:
 *
 *   PROXY          → lo snapshot compatto della Worker (30 s di cadenza);
 *   DIRECT         → 3 errori consecutivi del proxy, feed piu' vecchio di
 *                    300 s, o kill switch remoto: vehicle-positions
 *                    direttamente dall'origine, a 3 minuti, e i ritardi
 *                    restano vuoti (trip-updates integrale costa troppo
 *                    fuori navigazione);
 *   SCHEDULE_ONLY  → nemmeno l'origine risponde: si mostrano solo gli orari.
 *
 * La UI non attende mai il realtime: renderizza dal bundle e questi flow
 * arrivano come aggiornamento. Le cadenze le decide chi consuma (la mappa),
 * chiamando [refreshVehicles]/[refreshDelays] al proprio ritmo: cosi' il
 * polling vive e muore col ciclo di vita della schermata, senza un motore
 * suo da spegnere.
 */
class RealtimeClient(
    private val proxyAllowed: suspend () -> Boolean,
) {
    enum class Source { PROXY, DIRECT, SCHEDULE_ONLY }

    class Status(
        val source: Source,
        /** Eta' del feed vehicle-positions rispetto al timestamp DELL'ORIGINE. */
        val feedAgeSeconds: Long?,
        val lastSuccessAt: Instant?,
        val lastError: String?,
        val vehicleCount: Int,
        val delayCount: Int,
    )

    private val http = OkHttpClient()

    private val _vehicles = MutableStateFlow<RtVehicles?>(null)
    val vehicles: StateFlow<RtVehicles?> = _vehicles

    private val _delays = MutableStateFlow<RtDelays?>(null)
    val delays: StateFlow<RtDelays?> = _delays

    private val _status = MutableStateFlow(
        Status(Source.SCHEDULE_ONLY, null, null, null, 0, 0),
    )
    val status: StateFlow<Status> = _status

    /** Scritta dalla mappa quando risolve lo snapshot contro il bundle: diagnostica. */
    val resolvedPercent = MutableStateFlow<Int?>(null)

    private var proxyFailures = 0
    private var staleStrikes = 0
    private var directHoldUntilMs = 0L
    private var vehiclesEtag: String? = null
    private var delaysEtag: String? = null

    /** La cadenza suggerita per il prossimo giro, secondo lo stato corrente. */
    fun vehiclesIntervalMs(): Long = when (_status.value.source) {
        Source.PROXY -> 30_000L
        Source.DIRECT -> 180_000L
        Source.SCHEDULE_ONLY -> 60_000L
    }

    suspend fun refreshVehicles() = withContext(Dispatchers.IO) {
        val proxyOk = runCatching { proxyAllowed() }.getOrDefault(true)
        val nowMs = System.currentTimeMillis()

        if (proxyOk && nowMs >= directHoldUntilMs) {
            try {
                val fetched = fetchBinary("$PROXY_BASE/vehicles", vehiclesEtag)
                val age: Long?
                if (fetched != null) {
                    val (bytes, etag) = fetched
                    val parsed = RtCodec.parseVehicles(bytes)
                    vehiclesEtag = etag
                    age = feedAge(parsed.feedTimestamp)
                    // Anche un dato vecchio e' il migliore che abbiamo: si
                    // mostra comunque, e' l'eta' a dire quanto fidarsi.
                    _vehicles.value = parsed
                } else {
                    // 304: dati identici, si aggiorna solo l'eta'.
                    age = feedAge(_vehicles.value?.feedTimestamp)
                }
                proxyFailures = 0
                if (age == null || age <= STALE_SECONDS) {
                    staleStrikes = 0
                    publish(Source.PROXY, age, null)
                    return@withContext
                }
                // Feed stantio. La richiesta stessa ha appena svegliato il
                // refresh pigro del proxy: quasi sempre il prossimo giro da
                // 30 s trova dati freschi. La strada diretta scatta solo
                // dopo tre giri stantii DI FILA — buttarsi sull'origine al
                // primo colpo era il motivo del ritmo lento da 3 minuti.
                staleStrikes++
                if (staleStrikes < 3) {
                    publish(Source.PROXY, age, "feed vecchio di ${age}s, riprovo")
                    return@withContext
                }
            } catch (e: Exception) {
                registerProxyFailure(e.message ?: e.javaClass.simpleName)
            }
        }

        // --- fallback: l'origine, senza intermediari ------------------------
        try {
            val bytes = fetchRaw(DIRECT_VEHICLES)
            val parsed = GtfsRtLite.parseVehiclePositions(bytes, Instant.now().epochSecond)
            staleStrikes = 0
            _vehicles.value = parsed
            // In DIRECT i ritardi non si scaricano: quelli vecchi mentirebbero.
            _delays.value = null
            publish(Source.DIRECT, feedAge(parsed.feedTimestamp), null)
        } catch (e: Exception) {
            publish(Source.SCHEDULE_ONLY, feedAge(_vehicles.value?.feedTimestamp), e.message)
        }
    }

    suspend fun refreshDelays() = withContext(Dispatchers.IO) {
        // Solo dal proxy: e' il compromesso deciso per DIRECT (trip-updates
        // integrale da 1-2 MB al minuto non e' roba da telefono).
        if (_status.value.source != Source.PROXY) return@withContext
        try {
            val fetched = fetchBinary("$PROXY_BASE/updates", delaysEtag) ?: return@withContext
            val (bytes, etag) = fetched
            _delays.value = RtCodec.parseDelays(bytes)
            delaysEtag = etag
            _status.value = _status.value.let {
                Status(it.source, it.feedAgeSeconds, it.lastSuccessAt, it.lastError, it.vehicleCount, _delays.value?.byTripHash?.size ?: 0)
            }
        } catch (_: Exception) {
            // I ritardi sono un di piu': un giro mancato non cambia stato.
        }
    }

    private var alertsCache: List<GtfsRtLite.RtAlert>? = null
    private var alertsCacheAt = 0L

    /**
     * Gli avvisi di servizio, dal proxy, con 5 minuti di cache: la scheda
     * Oggi li chiede a ogni apertura e gli avvisi non cambiano al minuto.
     */
    suspend fun fetchAlerts(): List<GtfsRtLite.RtAlert> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        alertsCache?.let { if (now - alertsCacheAt < 5 * 60_000) return@withContext it }
        runCatching {
            val bytes = fetchRaw("$PROXY_BASE/alerts")
            GtfsRtLite.parseAlerts(bytes).also {
                alertsCache = it
                alertsCacheAt = now
            }
        }.getOrElse { alertsCache ?: emptyList() }
    }

    private fun registerProxyFailure(message: String) {
        proxyFailures++
        if (proxyFailures >= 3) {
            directHoldUntilMs = System.currentTimeMillis() + DIRECT_HOLD_MS
            proxyFailures = 0
        }
        _status.value = _status.value.let {
            Status(it.source, it.feedAgeSeconds, it.lastSuccessAt, message, it.vehicleCount, it.delayCount)
        }
    }

    private fun publish(source: Source, age: Long?, error: String?) {
        _status.value = Status(
            source = source,
            feedAgeSeconds = age,
            lastSuccessAt = if (error == null) Instant.now() else _status.value.lastSuccessAt,
            lastError = error,
            vehicleCount = _vehicles.value?.list?.size ?: 0,
            delayCount = _delays.value?.byTripHash?.size ?: 0,
        )
    }

    private fun feedAge(feedTs: Long?): Long? =
        if (feedTs == null || feedTs == 0L) null else Instant.now().epochSecond - feedTs

    /** GET col condizionale: null = 304, i dati che abbiamo valgono ancora. */
    private fun fetchBinary(url: String, etag: String?): Pair<ByteArray, String?>? {
        val req = Request.Builder().url(url).header("User-Agent", UA)
        if (etag != null) req.header("If-None-Match", etag)
        http.newCall(req.build()).execute().use { res ->
            if (res.code == 304) return null
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            return res.body!!.bytes() to res.header("ETag")
        }
    }

    private fun fetchRaw(url: String): ByteArray {
        http.newCall(
            Request.Builder().url(url).header("User-Agent", UA).build(),
        ).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            return res.body!!.bytes()
        }
    }

    companion object {
        const val PROXY_BASE = "https://fluid-transit-rt.fluid-transit.workers.dev/rt/v1"
        private const val DIRECT_VEHICLES =
            "https://regionetoscana.smartregion.toscana.it/mobility/artifacts/gtfs-rt/vehicle-positions"
        private const val UA = "FluidTransit/1.0 (+https://github.com/Casual76/Fluid-transit)"

        /** Oltre questa eta' il live non e' piu' live: soglia del piano. */
        const val STALE_SECONDS = 300L
        private const val DIRECT_HOLD_MS = 5 * 60_000L
    }
}
