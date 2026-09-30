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
    /**
     * L'orologio delle regole che dipendono dal tempo — il blocco su DIRECT,
     * l'eta' degli avvisi — iniettabile perche' si possano provare.
     */
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    enum class Source { PROXY, DIRECT, SCHEDULE_ONLY }

    data class Status(
        val source: Source,
        /**
         * Eta' del feed vehicle-positions rispetto al timestamp DELL'ORIGINE,
         * calcolata nell'istante [polledAt]: per sapere quanto e' vecchio
         * ADESSO si usa [ageAt], non questo numero da solo.
         */
        val feedAgeSeconds: Long?,
        val lastSuccessAt: Instant?,
        val lastError: String?,
        val vehicleCount: Int,
        val delayCount: Int,
        /**
         * Quando e' stato calcolato [feedAgeSeconds]: l'ultimo giro che ha
         * prodotto uno stato, riuscito o no.
         *
         * Non si puo' usare [lastSuccessAt] per questo: avanza solo quando
         * non c'e' errore, e nei casi "il feed della Regione e' fermo" il
         * giro e' riuscito ma porta un messaggio, quindi [lastSuccessAt]
         * resta al giro fresco di prima. Sommare il tempo trascorso da li'
         * a un'eta' che gia' comprende quell'intervallo la conterebbe due
         * volte: 18 minuti di feed fermo letti come 36.
         */
        val polledAt: Instant? = null,
    ) {
        /**
         * L'eta' del dato a questo istante.
         *
         * La schermata dello stato dei dati restava a "40 s" per quanto
         * tempo si fosse lasciata la mappa, perche' mostrava il numero
         * dell'ultimo giro come se fosse di adesso. Qui l'eta' cresce col
         * tempo trascorso dall'ultimo giro; senza l'istante del giro (stato
         * mai pubblicato) si dice quello che si sa.
         */
        fun ageAt(now: Instant): Long? =
            dev.antigravity.fluidtransit.routing.LiveAge.feedNow(
                feedAgeSeconds?.toLong(),
                polledAt?.epochSecond,
                now.epochSecond,
            )
    }

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
        val nowMs = clockMs()
        // Come siamo arrivati all'origine, se ci arriviamo: per un proxy che
        // NON RISPONDE, o per un proxy che risponde con roba vecchia. Sono
        // due situazioni diverse e vogliono due decisioni diverse, e prima
        // finivano nello stesso ramo.
        var perStantio = false

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
                    publish(
                        Source.PROXY, age,
                        "il feed della Regione e' fermo da " +
                            dev.antigravity.fluidtransit.routing.Words.age(age),
                    )
                    return@withContext
                }
                // Lo snapshot stesso e' vecchio: il proxy non sta rileggendo
                // niente, e l'origine puo' essere piu' fresca di lui. Qui il
                // cambio di strada ha senso, ma solo dopo tre giri DI FILA —
                // la richiesta stessa sveglia il refresh pigro del proxy, e
                // buttarsi al primo colpo era il motivo del ritmo lento.
                staleStrikes++
                if (staleStrikes < 3) {
                    publish(
                        Source.PROXY, age,
                        "feed vecchio di " +
                            dev.antigravity.fluidtransit.routing.Words.age(age) + ", riprovo",
                    )
                    return@withContext
                }
                perStantio = true
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
            val etaDelProxy = _status.value.feedAgeSeconds
            val bytes = fetchRaw(directVehiclesUrl)
            val parsed = GtfsRtLite.parseVehiclePositions(bytes, Instant.now().epochSecond)
            val etaDellOrigine = feedAge(parsed.feedTimestamp)

            // L'origine non e' piu' fresca del proxy: si torna indietro.
            //
            // Ci si arriva quando il proxy serve uno snapshot vecchio, e la
            // ragione piu' comune non e' che il proxy sia rotto: e' che la
            // Regione pubblica lo stesso feed da un pezzo, quindi il proxy
            // non riscrive niente e il suo snapshot invecchia insieme al
            // dato. In quel caso andare diretti prende lo STESSO dato fermo
            // e ci rinuncia i ritardi, che dall'origine non si scaricano:
            // si perde tutto e non si guadagna niente.
            //
            // Quindi si guarda quanto e' piu' fresca davvero. Un minuto e'
            // la soglia perche' il prezzo del cambio e' alto — tutti i
            // ritardi e tutte le previsioni — e sotto il minuto non lo
            // ripaga.
            //
            // Vale SOLO per il proxy stantio. Se il proxy non risponde
            // proprio, l'origine e' meglio anche vecchia: meglio bus fermi
            // sulla mappa che nessun bus.
            if (perStantio && etaDelProxy != null && etaDellOrigine != null &&
                etaDellOrigine + DIRECT_MUST_BE_FRESHER_SECONDS > etaDelProxy
            ) {
                staleStrikes = 0
                publish(
                    Source.PROXY,
                    etaDellOrigine,
                    "il feed della Regione e' fermo da " +
                        dev.antigravity.fluidtransit.routing.Words.age(etaDellOrigine),
                )
                return@withContext
            }
            staleStrikes = 0
            _vehicles.value = parsed
            // In DIRECT i ritardi non si scaricano: quelli vecchi mentirebbero.
            _delays.value = null
            _predictions.value = null
            publish(Source.DIRECT, feedAge(parsed.feedTimestamp), null)
        } catch (e: Exception) {
            // Non risponde nemmeno l'origine: il guasto e' la rete, non il
            // proxy. Con copertura scarsa tre timeout di fila portavano qui,
            // e il blocco di cinque minuti sul proxy restava: cinque minuti
            // senza ritardi anche dopo che la rete era tornata, e lo stato dei
            // dati che dava la colpa al proxy. Il blocco si toglie, e la colpa
            // pure.
            directHoldUntilMs = 0L
            lastProxyError = null
            publish(Source.SCHEDULE_ONLY, feedAge(_vehicles.value?.feedTimestamp), e.message)
        }
    }

    suspend fun refreshDelays() = delaysLock.withLock { fetchDelays() }

    /** Il collegamento di adesso, nelle parole di chi spiega una riga. */
    fun link(): dev.antigravity.fluidtransit.routing.DepartureText.LiveLink = when (_status.value.source) {
        Source.PROXY -> dev.antigravity.fluidtransit.routing.DepartureText.LiveLink.FULL
        Source.DIRECT -> dev.antigravity.fluidtransit.routing.DepartureText.LiveLink.VEHICLES_ONLY
        Source.SCHEDULE_ONLY -> dev.antigravity.fluidtransit.routing.DepartureText.LiveLink.NONE
    }

    /**
     * I ritardi per corsa sono ancora buoni per un calcolo?
     *
     * Senza rete il pacchetto dei ritardi resta quello di prima — si butta
     * solo quando l'origine diretta risponde — e il pianificatore lo
     * applicava a un viaggio calcolato un'ora dopo, chiamando "dal bus" un
     * ritardo di un'ora fa mentre il tabellone della stessa fermata diceva
     * gia' "orario da tabella". Oltre i tre quarti d'ora del modello dei
     * ritardi, un numero non entra piu' nei calcoli.
     */
    fun delaysFresh(nowEpoch: Long = clockMs() / 1000): Boolean {
        val d = _delays.value ?: return false
        val quando = d.feedTimestamp.takeIf { it > 0 } ?: d.generatedAt
        if (quando <= 0) return false
        return nowEpoch - quando <= dev.antigravity.fluidtransit.data.departures.LiveFromPredictions.FORGET_SECONDS
    }

    private suspend fun fetchDelays() = withContext(Dispatchers.IO) {
        // In un processo fresco lo stato parte da SCHEDULE_ONLY e nessuno ha
        // ancora interrogato il proxy. Uscire di qui voleva dire che il
        // widget, le routine e la scheda Oggi non vedevano MAI un ritardo,
        // perche' erano gli unici a chiedere e nessuno prima di loro aveva
        // stabilito lo stato. Il giro dei veicoli e' quello che lo stabilisce.
        //
        // E non solo in un processo fresco: dopo un calo di copertura lo
        // stato resta su DIRECT o SCHEDULE_ONLY, e se l'unico a chiedere e'
        // la scheda Oggi, un widget o una routine — cioe' la mappa e' chiusa
        // e nessuno fa girare i mezzi — i ritardi non tornavano piu', anche a
        // rete tornata da un pezzo. Finito il blocco su DIRECT, un giro di
        // ritardi riprova la strada del proxy come farebbe quello dei mezzi.
        if (_status.value.source != Source.PROXY &&
            (_status.value.lastSuccessAt == null || clockMs() >= directHoldUntilMs)
        ) {
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
            _status.value = _status.value.copy(
                delayCount = _delays.value?.byTripHash?.size ?: 0,
            )
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
     *
     * Oppure null se non siamo riusciti a saperlo. C'era anche una versione
     * che trasformava il null in lista vuota, e le quattro schede che la
     * usavano sopra una fermata non mostravano nessun avviso anche col
     * download fallito: e' stata tolta perche' non ci ricada nessuno.
     *
     * La differenza non e' accademica: la schermata degli avvisi, quando la
     * lista tornava vuota, scriveva "Nessun avviso in corso" — che e' una
     * frase rassicurante. Con la rete giu' durante uno sciopero diceva
     * esattamente il contrario del vero, e lo diceva con sicurezza. Qui il
     * fallimento smette di somigliare a una buona notizia.
     *
     * Quando il download fallisce la cache vale ancora come risposta, ma non
     * a qualunque eta', e non in silenzio. Prima valeva sempre: aperta l'app
     * alle 08:00 con zero avvisi e persa la rete alle 14:00, la schermata
     * diceva "Nessun avviso in corso" con una lista di sei ore prima — e uno
     * sciopero annunciato alle nove non c'era. Adesso:
     *
     * - una cache con degli avvisi dentro si serve anche vecchia: sono avvisi
     *   veri, e buttarli trasformerebbe uno sciopero noto in "non sappiamo";
     * - una cache VUOTA piu' vecchia di [ALERTS_EMPTY_TRUST_MS] non si serve:
     *   "non ce ne sono" detto con mezz'ora di ritardo e' un'affermazione che
     *   non possiamo piu' fare, e diventa null;
     * - in tutti e due i casi [alertsStaleSinceEpoch] dice da quando la
     *   risposta e' vecchia, perche' chi la mostra lo possa dire.
     *
     * [force] salta i cinque minuti di cache e chiede subito al proxy: e' per
     * chi ha appena tirato giu' o toccato "Riprova", che si aspetta una
     * risposta e non la stessa di prima. Il condizionale (`If-None-Match`) resta,
     * quindi se gli avvisi non sono cambiati costa un 304 e non il corpo. Non
     * tocca `alertsCacheAt` se il giro fallisce, quindi la fiducia nella lista
     * vuota ([ALERTS_EMPTY_TRUST_MS]) continua a contare da quando e' stata
     * confermata davvero.
     */
    suspend fun fetchAlertsOrNull(force: Boolean = false): List<GtfsRtLite.RtAlert>? =
        alertsLock.withLock { fetchAlertsLocked(force) }

    /**
     * Quando gli avvisi serviti sono stati confermati l'ultima volta, se
     * l'ultimo tentativo di scaricarli e' fallito; null se sono freschi.
     */
    fun alertsStaleSinceEpoch(): Long? =
        if (alertsLastFailed && alertsCache != null) alertsCacheAt / 1000 else null

    private var alertsLastFailed = false

    private suspend fun fetchAlertsLocked(force: Boolean): List<GtfsRtLite.RtAlert>? = withContext(Dispatchers.IO) {
        val now = clockMs()
        alertsCache?.let { if (!force && now - alertsCacheAt < 5 * 60_000) return@withContext it }
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
        }.onSuccess { alertsLastFailed = false }.getOrElse {
            alertsLastFailed = true
            alertsCache?.takeUnless { it.isEmpty() && now - alertsCacheAt > ALERTS_EMPTY_TRUST_MS }
        }
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
            directHoldUntilMs = clockMs() + DIRECT_HOLD_MS
            proxyFailures = 0
        }
        // Solo il messaggio: l'eta' resta quella dell'ultimo giro, e con lei
        // l'istante in cui e' stata calcolata.
        _status.value = _status.value.copy(lastError = message)
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
            polledAt = Instant.now(),
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

        /**
         * Ogni quanto una schermata aperta riscarica gli avvisi: appena oltre
         * i cinque minuti della cache, cosi' il giro trova una risposta nuova
         * e non quella in memoria. Uno solo per Oggi e per la schermata degli
         * avvisi: due copie di questo numero si sarebbero staccate dalla cache.
         */
        const val ALERTS_POLL_MS = 5 * 60_000L + 10_000L

        /** Oltre questa eta' "zero avvisi" non si ripete: vedi [fetchAlertsOrNull]. */
        const val ALERTS_EMPTY_TRUST_MS = 30 * 60_000L

        /**
         * Quanto piu' fresca deve essere l'origine per valere il cambio.
         *
         * Andare diretti costa TUTTI i ritardi e TUTTE le previsioni, che
         * dall'origine non si scaricano. Un minuto e' il minimo che ripaga
         * quel prezzo: sotto, si resta dal proxy e si dice che a essere
         * ferma e' la Regione.
         */
        private const val DIRECT_MUST_BE_FRESHER_SECONDS = 60L
    }
}
