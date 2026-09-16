package dev.antigravity.fluidtransit.data.rt

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /**
     * Gli indirizzi non sono costanti ma parametri con un default, per un
     * motivo solo: la macchina a tre stati qui sotto — tre errori di fila, tre
     * giri stantii di fila, il blocco di cinque minuti su DIRECT — e' la
     * logica piu' delicata dell'app e non aveva una sola asserzione. Con gli
     * indirizzi iniettabili si puo' metterle davanti un proxy che sbaglia,
     * uno che tace e uno che risponde 304, senza toccare la rete vera.
     */
    private val proxyBase: String = PROXY_BASE,
    private val directVehiclesUrl: String = DIRECT_VEHICLES,
    /**
     * C'e' rete?
     *
     * Un giro fallito col telefono scollegato non dice niente sul proxy.
     * Senza questa domanda, tre giri falliti in metropolitana contavano come
     * tre guasti del proxy: si scendeva su DIRECT con cinque minuti di
     * blocco, e quando la rete tornava l'app passava altri cinque minuti
     * senza ritardi dicendo "il nostro proxy non risponde" — che era falso, e
     * dare la colpa a se' stessi quando la colpa non c'e' e' un modo lento di
     * far perdere fiducia.
     */
    private val online: () -> Boolean = { true },
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

    /**
     * Le previsioni fermata per fermata.
     *
     * Sono quelle che fanno combaciare i nostri minuti con quelli ufficiali:
     * [delays] porta UN numero per corsa e lascia all'app il compito di
     * inventarsi come si propaga, queste portano quello che il feed dice
     * davvero a ogni fermata. Restano null se il proxy non le serve — un
     * Worker piu' vecchio dell'app — e in quel caso vale [delays], che non si
     * smette mai di scaricare.
     */
    private val _predictions = MutableStateFlow<RtPredictionSet?>(null)
    val predictions: StateFlow<RtPredictionSet?> = _predictions

    private val _status = MutableStateFlow(
        Status(Source.SCHEDULE_ONLY, null, null, null, 0, 0),
    )
    val status: StateFlow<Status> = _status

    /** Scritta dalla mappa quando risolve lo snapshot contro il bundle: diagnostica. */
    val resolvedPercent = MutableStateFlow<Int?>(null)

    /**
     * Un giro per volta, per ognuno dei tre.
     *
     * I contatori qui sotto — tre errori di fila, tre giri stantii di fila,
     * il blocco di cinque minuti — decidono da dove vengono i numeri, e sono
     * `var` normali letti e scritti da piu' coroutine: la mappa chiama
     * [refreshVehicles] al suo ritmo, il giro dei tabelloni chiama
     * [refreshDelays] (che a processo freddo chiama a sua volta
     * [refreshVehicles]), e il widget chiama tutti e due per conto suo. Due
     * giri sovrapposti si contano i fallimenti a vicenda e si scrivono sopra
     * l'etag: a parita' di rete, l'app poteva finire su PROXY o su DIRECT a
     * seconda di chi arrivava primo. E' una delle facce di "si comporta in
     * modo diverso ogni volta".
     *
     * Chi arriva secondo aspetta il primo: non si evita la sua richiesta, ma
     * la fa con l'etag appena ricevuto e si prende un 304 senza corpo, invece
     * di scaricare e rifare il parse degli stessi byte mentre l'altro li sta
     * ancora scrivendo.
     *
     * Tre serrature separate e mai incrociate: si prende quella dei ritardi e
     * poi, eventualmente, quella dei mezzi — mai il contrario — quindi non si
     * puo' incastrare.
     */
    private val vehiclesLock = Mutex()
    private val delaysLock = Mutex()
    private val predictionsLock = Mutex()

    /**
     * E gli avvisi, che hanno la stessa forma e lo stesso problema.
     *
     * Li chiedono due schermate diverse — la scheda Oggi e la schermata degli
     * avvisi — e la loro cache in memoria e' un `var` con dentro una lista.
     * Una lista pubblicata senza barriera puo' farsi vedere da un altro
     * thread con la lunghezza giusta e l'array ancora nullo: non e' un caso
     * frequente, ma e' lo stesso difetto che sulle sezioni del bundle si e'
     * chiuso stanotte, e qui costa tre righe.
     */
    private val alertsLock = Mutex()

    private var proxyFailures = 0
    private var staleStrikes = 0
    private var directHoldUntilMs = 0L
    private var vehiclesEtag: String? = null
    private var delaysEtag: String? = null
    private var predictionsEtag: String? = null

    /**
     * Il proxy non serve le previsioni: si smette di chiederle.
     *
     * Appiccicoso di proposito. Un 404 qui non e' un intoppo passeggero, e'
     * un Worker piu' vecchio dell'app; riprovare ogni trenta secondi per
     * tutta la sessione sarebbe una richiesta buttata ogni trenta secondi.
     */
    private var predictionsAbsent = false

    /** La cadenza suggerita per il prossimo giro, secondo lo stato corrente. */
    fun vehiclesIntervalMs(): Long = when (_status.value.source) {
        Source.PROXY -> 30_000L
        Source.DIRECT -> 180_000L
        Source.SCHEDULE_ONLY -> 60_000L
    }

    suspend fun refreshVehicles() = vehiclesLock.withLock { fetchVehicles() }

    private suspend fun fetchVehicles() = withContext(Dispatchers.IO) {
        val proxyOk = runCatching { proxyAllowed() }.getOrDefault(true)
        val nowMs = System.currentTimeMillis()

        if (proxyOk && nowMs >= directHoldUntilMs) {
            try {
                val fetched = fetchBinary("$proxyBase/vehicles", vehiclesEtag)
                val bytes = fetched.bytes
                val age: Long?
                if (bytes != null) {
                    val parsed = RtCodec.parseVehicles(bytes)
                    vehiclesEtag = fetched.etag
                    age = fetched.serverFeedAge ?: feedAge(parsed.feedTimestamp)
                    // Anche un dato vecchio e' il migliore che abbiamo: si
                    // mostra comunque, e' l'eta' a dire quanto fidarsi.
                    _vehicles.value = parsed
                } else {
                    // 304: dati identici, si aggiorna solo l'eta' — quella
                    // del proxy, che la calcola col proprio orologio.
                    age = fetched.serverFeedAge ?: feedAge(_vehicles.value?.feedTimestamp)
                }
                proxyFailures = 0
                lastProxyError = null
                if (age == null || age <= STALE_SECONDS) {
                    staleStrikes = 0
                    publish(Source.PROXY, age, null)
                    return@withContext
                }
                // Feed stantio, e adesso la domanda e': stantio di chi?
                //
                // Se lo snapshot del proxy e' fresco, il proxy ha appena
                // riletto l'origine e quel numero vecchio e' il numero che
                // l'origine pubblica. Andare diretti significherebbe andare
                // a prendere lo stesso identico dato fermo, rinunciando ai
                // ritardi — che dall'origine non si scaricano — e passando a
                // un giro da tre minuti. Si resta, e lo si dice.
                //
                // Osservato il 15/09: l'app passava all'origine mentre il
                // proxy rispondeva benissimo, perche' la Regione pubblicava
                // un feed fermo da 377 s. Risultato: zero ritardi in tutta
                // l'app, e la schermata dello stato dati che diceva "il
                // proxy non rispondeva" — cioe' la cosa sbagliata.
                val snapshotAge = fetched.snapshotAge
                if (snapshotAge != null && snapshotAge <= SNAPSHOT_FRESH_SECONDS) {
                    staleStrikes = 0
                    publish(Source.PROXY, age, "il feed della Regione e' fermo da ${age}s")
                    return@withContext
                }
                // Lo snapshot stesso e' vecchio: il proxy non sta rileggendo
                // niente, e l'origine puo' essere piu' fresca di lui. Qui il
                // cambio di strada ha senso, ma solo dopo tre giri DI FILA —
                // la richiesta stessa sveglia il refresh pigro del proxy, e
                // buttarsi al primo colpo era il motivo del ritmo lento.
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
            val bytes = fetchRaw(directVehiclesUrl)
            val parsed = GtfsRtLite.parseVehiclePositions(bytes, Instant.now().epochSecond)
            staleStrikes = 0
            _vehicles.value = parsed
            // In DIRECT i ritardi non si scaricano: quelli vecchi mentirebbero.
            _delays.value = null
            _predictions.value = null
            publish(Source.DIRECT, feedAge(parsed.feedTimestamp), null)
        } catch (e: Exception) {
            publish(Source.SCHEDULE_ONLY, feedAge(_vehicles.value?.feedTimestamp), e.message)
        }
    }

    suspend fun refreshDelays() = delaysLock.withLock { fetchDelays() }

    private suspend fun fetchDelays() = withContext(Dispatchers.IO) {
        // In un processo fresco lo stato parte da SCHEDULE_ONLY e nessuno ha
        // ancora interrogato il proxy. Uscire di qui voleva dire che il
        // widget, le routine e la scheda Oggi non vedevano MAI un ritardo,
        // perche' erano gli unici a chiedere e nessuno prima di loro aveva
        // stabilito lo stato. Il giro dei veicoli e' quello che lo stabilisce.
        if (_status.value.source != Source.PROXY && _status.value.lastSuccessAt == null) {
            vehiclesLock.withLock { fetchVehicles() }
        }
        // Poi vale il compromesso deciso per DIRECT: i trip-updates integrali
        // dall'origine sono 1-2 MB al minuto, non roba da telefono.
        if (_status.value.source != Source.PROXY) return@withContext
        try {
            val fetched = fetchBinary("$proxyBase/updates", delaysEtag)
            val bytes = fetched.bytes ?: return@withContext
            _delays.value = RtCodec.parseDelays(bytes)
            delaysEtag = fetched.etag
            _status.value = _status.value.let {
                Status(it.source, it.feedAgeSeconds, it.lastSuccessAt, it.lastError, it.vehicleCount, _delays.value?.byTripHash?.size ?: 0)
            }
        } catch (_: Exception) {
            // I ritardi sono un di piu': un giro mancato non cambia stato.
        }
    }

    /**
     * Le previsioni per fermata, dal proxy.
     *
     * Stesso patto dei ritardi: vale solo da PROXY, perche' dall'origine
     * costerebbero i trip-updates integrali. Un 404 vuol dire che il proxy
     * non le conosce, e allora si smette di chiederle per questa sessione.
     */
    suspend fun refreshPredictions() = predictionsLock.withLock { fetchPredictions() }

    private suspend fun fetchPredictions() = withContext(Dispatchers.IO) {
        if (predictionsAbsent) return@withContext
        if (_status.value.source != Source.PROXY) return@withContext
        try {
            val fetched = fetchBinary("$proxyBase/predictions", predictionsEtag)
            val bytes = fetched.bytes ?: return@withContext
            _predictions.value = RtPredictionCodec.parse(bytes)
            predictionsEtag = fetched.etag
        } catch (e: IOException) {
            if (e.message?.contains("404") == true) {
                predictionsAbsent = true
                _predictions.value = null
            }
        } catch (_: Exception) {
            // Come i ritardi: un giro mancato non cambia stato. Quello che
            // l'app mostra resta quello di prima, con la sua eta'.
        }
    }

    private var alertsCache: List<GtfsRtLite.RtAlert>? = null
    private var alertsCacheAt = 0L
    private var alertsEtag: String? = null

    /**
     * Gli avvisi di servizio, dal proxy, con 5 minuti di cache: la scheda
     * Oggi li chiede a ogni apertura e gli avvisi non cambiano al minuto.
     */
    suspend fun fetchAlerts(): List<GtfsRtLite.RtAlert> =
        alertsLock.withLock { fetchAlertsLocked() }

    private suspend fun fetchAlertsLocked(): List<GtfsRtLite.RtAlert> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        alertsCache?.let { if (now - alertsCacheAt < 5 * 60_000) return@withContext it }
        runCatching {
            // Col condizionale come le altre due sezioni: gli alerts sono la
            // fetta piu' grossa dello snapshot (centinaia di kB di protobuf
            // grezzo) e cambiano di rado. Un 304 qui vale piu' che altrove.
            val fetched = fetchBinary("$proxyBase/alerts", alertsEtag)
            val bytes = fetched.bytes
            if (bytes == null) {
                // Invariati: si rinnova solo la scadenza della cache locale.
                alertsCacheAt = now
                alertsCache ?: emptyList()
            } else {
                alertsEtag = fetched.etag
                GtfsRtLite.parseAlerts(bytes).also {
                    alertsCache = it
                    alertsCacheAt = now
                }
            }
        }.getOrElse { alertsCache ?: emptyList() }
    }

    /** true se e' ora di provare l'origine diretta, false se si riprova col proxy. */
    private fun registerProxyFailure(message: String): Boolean {
        if (!online()) {
            // Niente rete: non e' una prova contro il proxy, e nemmeno vale
            // la pena provare l'origine, che sta dietro la stessa rete.
            publish(Source.SCHEDULE_ONLY, feedAge(_vehicles.value?.feedTimestamp), message)
            return false
        }
        proxyFailures++
        lastProxyError = message
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

    /**
     * Perche' il proxy ha smesso di rispondere.
     *
     * Si tiene anche quando la strada diretta funziona: quella schermata
     * esiste per spiegare lo stato dei dati, e diceva "il proxy non
     * rispondeva" senza mai dire cosa fosse successo — un timeout, un 500,
     * un nome che non si risolve sono tre problemi diversi, e uno solo di
     * essi e' colpa nostra.
     */
    private var lastProxyError: String? = null

    private fun publish(source: Source, age: Long?, error: String?) {
        _status.value = Status(
            source = source,
            feedAgeSeconds = age,
            lastSuccessAt = if (error == null) Instant.now() else _status.value.lastSuccessAt,
            // Sulla strada diretta l'errore del proxy resta scritto: e' la
            // ragione per cui siamo qui, non un dettaglio del passato.
            lastError = error ?: if (source == Source.DIRECT) lastProxyError else null,
            vehicleCount = _vehicles.value?.list?.size ?: 0,
            delayCount = _delays.value?.byTripHash?.size ?: 0,
        )
    }

    private fun feedAge(feedTs: Long?): Long? =
        if (feedTs == null || feedTs == 0L) null else Instant.now().epochSecond - feedTs

    private class Fetched(
        /** null quando il proxy ha risposto 304: i byte sono quelli di prima. */
        val bytes: ByteArray?,
        val etag: String?,
        /**
         * L'eta' del dato secondo il PROXY, che la calcola sul timestamp
         * dell'origine col proprio orologio. Meglio della nostra: se il
         * telefono ha l'ora sbagliata, il feed sembrerebbe stantio (o
         * fresco) senza motivo.
         */
        val serverFeedAge: Long?,
        /**
         * Quanto e' vecchio lo SNAPSHOT del proxy, che e' un'altra domanda.
         *
         * [serverFeedAge] misura l'origine; questo misura noi. Servono
         * separate perche' un feed vecchio puo' voler dire due cose opposte,
         * e solo una delle due si risolve cambiando strada.
         */
        val snapshotAge: Long?,
    )

    /** GET col condizionale: null = 304, i dati che abbiamo valgono ancora. */
    /**
     * Una sezione dal proxy. Mai null: un 304 e' una risposta, non un buco.
     *
     * Prima il 304 tornava `null` e con lui sparivano le intestazioni. Il
     * proxy le manda apposta anche sul 304 — "porta comunque l'eta', che e'
     * esattamente il caso in cui conta di piu'" dice il suo commento — e
     * l'app le buttava, ricalcolando l'eta' con l'orologio del telefono.
     */
    private fun fetchBinary(url: String, etag: String?): Fetched {
        val req = Request.Builder().url(url).header("User-Agent", UA)
        if (etag != null) req.header("If-None-Match", etag)
        http.newCall(req.build()).execute().use { res ->
            if (res.code != 304 && !res.isSuccessful) throw IOException("HTTP ${res.code}")
            return Fetched(
                bytes = if (res.code == 304) null else gunzipIfNeeded(res.body!!.bytes()),
                etag = res.header("ETag") ?: etag,
                serverFeedAge = res.header("X-Feed-Age")?.toLongOrNull(),
                snapshotAge = res.header("X-Snapshot-Age")?.toLongOrNull(),
            )
        }
    }

    /**
     * Byte compressi che nessuno ha dichiarato tali.
     *
     * OkHttp scompatta da solo quando l'intestazione lo dice. Ma il 15/09,
     * misurato: il nostro proxy su un cache HIT di Cloudflare serviva il corpo
     * ancora compresso SENZA `content-encoding`. Il lettore trovava "magic
     * sbagliato", tre giri cosi' e l'app scendeva sulla strada diretta
     * perdendo tutti i ritardi — a intermittenza, perche' dipendeva dal cache
     * HIT. Era una delle facce di "a volte i minuti sono veri e a volte no".
     *
     * Il proxy adesso dichiara sempre la codifica. Questo resta lo stesso: il
     * magic di gzip sono due byte, guardarli costa niente, e un'app che si
     * rompe perche' un intermediario ha sbagliato un'intestazione e' un'app
     * fragile. Chi sta in mezzo fra noi e il telefono non e' solo il nostro
     * proxy.
     */
    private fun gunzipIfNeeded(raw: ByteArray): ByteArray {
        if (raw.size < 2 || raw[0] != 0x1f.toByte() || raw[1] != 0x8b.toByte()) return raw
        return runCatching {
            java.util.zip.GZIPInputStream(raw.inputStream()).use { it.readBytes() }
        }.getOrElse { raw }
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
        const val DIRECT_VEHICLES =
            "https://regionetoscana.smartregion.toscana.it/mobility/artifacts/gtfs-rt/vehicle-positions"
        private const val UA = "FluidTransit/1.0 (+https://github.com/Casual76/Fluid-transit)"

        /** Oltre questa eta' il live non e' piu' live: soglia del piano. */
        const val STALE_SECONDS = 300L

        /**
         * Sotto questa eta' lo snapshot del proxy si considera appena fatto.
         *
         * Il proxy si rinfresca PRIMA di rispondere quando il suo snapshot
         * supera i 90 s, quindi quello che serve non e' quasi mai piu'
         * vecchio di cosi'. Il doppio del suo limite lascia spazio al viaggio
         * della risposta senza scambiare un proxy sano per uno fermo.
         */
        const val SNAPSHOT_FRESH_SECONDS = 180L
        private const val DIRECT_HOLD_MS = 5 * 60_000L
    }
}
