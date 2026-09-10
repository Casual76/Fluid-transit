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

    /**
     * I timeout sono espliciti perche' i default di OkHttp non coprono il
     * caso che conta: il proxy, quando lo snapshot e' vecchio, puo' fermarsi
     * a rifare il giro verso l'origine prima di rispondere. Senza
     * `callTimeout` un giro impantanato tiene occupato il ciclo di poll e la
     * mappa resta con i dati di prima senza dire niente.
     */
    private val http = OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .readTimeout(java.time.Duration.ofSeconds(20))
        .callTimeout(java.time.Duration.ofSeconds(30))
        .build()

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
                    val parsed = RtCodec.parseVehicles(fetched.bytes)
                    vehiclesEtag = fetched.etag
                    age = fetched.serverFeedAge ?: feedAge(parsed.feedTimestamp)
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
                // Si scende in DIRECT solo dopo tre errori DI FILA, che e' il
                // contratto scritto in testa alla classe. Prima l'esecuzione
                // cadeva sul ramo qui sotto gia' al primo intoppo: un singolo
                // timeout portava la cadenza a tre minuti e azzerava i
                // ritardi, senza che niente lo dicesse all'utente.
                if (!registerProxyFailure(e.message ?: e.javaClass.simpleName)) {
                    return@withContext
                }
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
        // In un processo fresco lo stato parte da SCHEDULE_ONLY e nessuno ha
        // ancora interrogato il proxy. Uscire di qui voleva dire che il
        // widget, le routine e la scheda Oggi non vedevano MAI un ritardo,
        // perche' erano gli unici a chiedere e nessuno prima di loro aveva
        // stabilito lo stato. Il giro dei veicoli e' quello che lo stabilisce.
        if (_status.value.source != Source.PROXY && _status.value.lastSuccessAt == null) {
            refreshVehicles()
        }
        // Poi vale il compromesso deciso per DIRECT: i trip-updates integrali
        // dall'origine sono 1-2 MB al minuto, non roba da telefono.
        if (_status.value.source != Source.PROXY) return@withContext
        try {
            val fetched = fetchBinary("$PROXY_BASE/updates", delaysEtag) ?: return@withContext
            _delays.value = RtCodec.parseDelays(fetched.bytes)
            delaysEtag = fetched.etag
            _status.value = _status.value.let {
                Status(it.source, it.feedAgeSeconds, it.lastSuccessAt, it.lastError, it.vehicleCount, _delays.value?.byTripHash?.size ?: 0)
            }
        } catch (_: Exception) {
            // I ritardi sono un di piu': un giro mancato non cambia stato.
        }
    }

    private var alertsCache: List<GtfsRtLite.RtAlert>? = null
    private var alertsCacheAt = 0L
    private var alertsEtag: String? = null

    /**
     * Gli avvisi di servizio, dal proxy, con 5 minuti di cache: la scheda
     * Oggi li chiede a ogni apertura e gli avvisi non cambiano al minuto.
     */
    suspend fun fetchAlerts(): List<GtfsRtLite.RtAlert> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        alertsCache?.let { if (now - alertsCacheAt < 5 * 60_000) return@withContext it }
        runCatching {
            // Col condizionale come le altre due sezioni: gli alerts sono la
            // fetta piu' grossa dello snapshot (centinaia di kB di protobuf
            // grezzo) e cambiano di rado. Un 304 qui vale piu' che altrove.
            val fetched = fetchBinary("$PROXY_BASE/alerts", alertsEtag)
            if (fetched == null) {
                // Invariati: si rinnova solo la scadenza della cache locale.
                alertsCacheAt = now
                alertsCache ?: emptyList()
            } else {
                alertsEtag = fetched.etag
                GtfsRtLite.parseAlerts(fetched.bytes).also {
                    alertsCache = it
                    alertsCacheAt = now
                }
            }
        }.getOrElse { alertsCache ?: emptyList() }
    }

    /** true se e' ora di provare l'origine diretta, false se si riprova col proxy. */
    private fun registerProxyFailure(message: String): Boolean {
        proxyFailures++
        val giveUp = proxyFailures >= 3
        if (giveUp) {
            directHoldUntilMs = System.currentTimeMillis() + DIRECT_HOLD_MS
            proxyFailures = 0
        }
        _status.value = _status.value.let {
            Status(it.source, it.feedAgeSeconds, it.lastSuccessAt, message, it.vehicleCount, it.delayCount)
        }
        return giveUp
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

    private class Fetched(
        val bytes: ByteArray,
        val etag: String?,
        /**
         * L'eta' del dato secondo il PROXY, che la calcola sul timestamp
         * dell'origine col proprio orologio. Meglio della nostra: se il
         * telefono ha l'ora sbagliata, il feed sembrerebbe stantio (o
         * fresco) senza motivo.
         */
        val serverFeedAge: Long?,
    )

    /** GET col condizionale: null = 304, i dati che abbiamo valgono ancora. */
    private fun fetchBinary(url: String, etag: String?): Fetched? {
        val req = Request.Builder().url(url).header("User-Agent", UA)
        if (etag != null) req.header("If-None-Match", etag)
        http.newCall(req.build()).execute().use { res ->
            if (res.code == 304) return null
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            return Fetched(
                bytes = res.body!!.bytes(),
                etag = res.header("ETag"),
                serverFeedAge = res.header("X-Feed-Age")?.toLongOrNull(),
            )
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
