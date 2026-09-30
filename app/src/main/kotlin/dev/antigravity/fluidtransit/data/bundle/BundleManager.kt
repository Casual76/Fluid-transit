package dev.antigravity.fluidtransit.data.bundle

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dev.antigravity.fluidtransit.data.net.Metered
import dev.antigravity.fluidtransit.data.store.Durable
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Ftb
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
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
 * Il ciclo di vita del bundle orari sul dispositivo.
 *
 * All'avvio apre il bundle attivo se c'e'; altrimenti guida il primo
 * download (la schermata di benvenuto e' la vista di questo stato). Il
 * protocollo di installazione e' sempre lo stesso, e l'ordine e' il punto:
 * scarica su `.part` -> gunzip -> verifica sha256 -> apri e fai una query di
 * fumo -> rinomina -> sostituisci il lettore. Un bundle scaduto non si
 * cancella mai prima di avere il sostituto: meglio orari di ieri con un
 * avviso che nessun orario.
 *
 * Su rete a consumo il primo download chiede il permesso ([BundleState.AskMetered]);
 * "aspetta il Wi-Fi" registra un callback di rete e parte da solo quando
 * arriva una rete non a consumo. E' la decisione presa nel piano, tradotta.
 *
 * Con gli orari gia' in tasca la stessa decisione vale per l'aggiornamento,
 * ma lo stato non cambia: l'app resta sulla sua schermata e la domanda
 * ([UpdateOffer]) sta a parte. Cambiare [BundleState] per un aggiornamento
 * vorrebbe dire rimettere la schermata di benvenuto sopra la mappa.
 */
class BundleManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    sealed interface BundleState {
        /** Nessun bundle e nessun download in corso: primo avvio. */
        data object Missing : BundleState

        /** Rete a consumo: si chiede prima di scaricare [bytes]. */
        data class AskMetered(val bytes: Long) : BundleState

        /** In attesa di una rete non a consumo, come chiesto dall'utente. */
        data object WaitingForWifi : BundleState

        data class Downloading(val progress: Float) : BundleState

        data class Ready(
            val reader: BundleReader,
            val buildId: Long,
            /** Il PMTiles della rete (linee+fermate) pubblicato accanto al bundle, se noto. */
            val overlayUrl: String?,
        ) : BundleState

        data class Failed(val message: String) : BundleState
    }

    /**
     * Orari nuovi che aspettano una decisione, mentre quelli in tasca restano in uso.
     *
     * Sta fuori da [BundleState] perche' `AppRoot` mostra la schermata di
     * benvenuto per qualunque stato diverso da Ready: pubblicare qui un
     * "scarico" o un "aspetto" avrebbe tirato via la mappa e il pannello
     * aperti per fare posto a "Serve solo la prima volta", su un'app che gli
     * orari li ha gia'.
     */
    sealed interface UpdateOffer {
        /** Rete a consumo e orari in scadenza: c'e' un bundle nuovo di circa [bytes], non lo scarico senza chiedere. */
        data class Offered(val bytes: Long) : UpdateOffer

        /** L'utente ha scelto di aspettare il Wi-Fi: parte da solo appena c'e'. */
        data class WaitingForWifi(val bytes: Long) : UpdateOffer

        /** L'utente ha detto di si': si scarica e, a scaricato, gli orari si sostituiscono. */
        data class Downloading(val progress: Float) : UpdateOffer

        /**
         * Un aggiornamento che serviva non e' riuscito.
         *
         * Si mostra solo quando gli orari in tasca stanno finendo o quando
         * l'utente l'aveva chiesto: con orari ancora buoni un giro andato a
         * vuoto non e' una notizia.
         */
        data class Failed(val message: String) : UpdateOffer
    }

    private val _state = MutableStateFlow<BundleState>(BundleState.Missing)
    val state: StateFlow<BundleState> = _state

    private val _updateOffer = MutableStateFlow<UpdateOffer?>(null)

    /** Null quando non c'e' niente da decidere. */
    val updateOffer: StateFlow<UpdateOffer?> = _updateOffer

    private val dir = File(context.filesDir, "bundles")
    private val active = File(dir, "active.ftb")

    /**
     * Il bundle di ieri, messo da parte durante la promozione.
     *
     * Esiste solo per il tempo di un rename. Se lo si trova all'avvio vuol
     * dire che il processo e' morto durante uno scambio, ed e' l'unico orario
     * rimasto: si rimette al suo posto.
     */
    private val previousFile = File(dir, "active.previous.ftb")
    private val meta = File(dir, "active.meta.json")

    /** Quanto vive ancora il lettore vecchio dopo lo scambio. */
    private val RETIRE_GRACE_MS = 60_000L
    private val mutex = Mutex()

    /**
     * Il callback "aspetto il Wi-Fi" in carica, se c'e'.
     *
     * Un riferimento atomico e non una `var`: lo tocca il thread principale
     * (i tasti), lo tocca il thread della rete (il callback che scatta) e lo
     * tocca IO. Chi lo prende lo prende una volta sola, ed e' la garanzia che
     * un callback rimasto in giro non possa partire due volte.
     */
    private val wifiCallback = AtomicReference<ConnectivityManager.NetworkCallback?>(null)

    /** Da chiamare una volta, all'avvio. Non blocca: lo stato arriva sul flow. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                // Uno scambio interrotto a meta': il bundle di ieri e' ancora
                // li' di lato, ed e' meglio di niente.
                BundleSwap.recover(active, previousFile)
                if (active.isFile) {
                    runCatching { openAndSmoke(active) }
                        .onSuccess { reader ->
                            _state.value = BundleState.Ready(reader, reader.buildId, readMetaOverlay())
                            return@withLock
                        }
                        .onFailure {
                            // Un bundle attivo che non si apre e' corrotto o di
                            // un formato vecchio: si riscarica, non si tiene.
                            active.delete()
                            meta.delete()
                        }
                }
                requestDownload(userApprovedMetered = false)
            }
            // Con un bundle gia' attivo, il controllo di freschezza corre in
            // sottofondo: se stanotte e' uscito un bundle nuovo si scarica e
            // si sostituisce senza passare dalla schermata di benvenuto.
            if (state.value is BundleState.Ready) {
                lastCheckAt = System.currentTimeMillis()
                // Un giro che non ha trovato nemmeno una rete non ha controllato
                // niente: non deve valere un'ora di silenzio (vedi refreshOnForeground).
                if (!mutex.withLock { refreshSilently() }) lastCheckAt = 0L
            }
        }
    }

    // Lo scrive il giro in sottofondo, lo legge il ritorno in primo piano dal
    // thread principale: due lati diversi, quindi una barriera.
    @Volatile
    private var lastCheckAt = 0L

    /**
     * Ricontrolla se stanotte e' uscito un bundle nuovo.
     *
     * `start()` girava una volta sola, all'avvio del PROCESSO. Un telefono
     * che tiene l'app in memoria per giorni — cioe' il caso normale — non
     * rivedeva mai il bundle notturno: continuava a servire gli orari del
     * giorno in cui l'app era stata aperta l'ultima volta. E' successo per
     * davvero, e la conseguenza era quella peggiore possibile: orari vecchi
     * mostrati come se fossero di oggi.
     *
     * Il controllo costa una richiesta di poche centinaia di byte, e su rete
     * a consumo non scarica: con orari ancora buoni non fa nemmeno la
     * richiesta, con orari in scadenza offre l'aggiornamento e aspetta un si'.
     * Un'ora di intervallo e' generosa.
     *
     * L'ora si conta solo da un controllo vero. Un giro che non ha trovato
     * nemmeno una rete — l'app aperta in metropolitana, o nel secondo prima
     * che il sistema apra la rete al processo — non ha guardato niente, e
     * contarlo lasciava un telefono con gli orari scaduti senza la domanda
     * "aggiorno?" per un'ora dopo il ritorno della rete.
     */
    fun refreshOnForeground() {
        scope.launch(Dispatchers.IO) {
            if (state.value !is BundleState.Ready) return@launch
            val now = System.currentTimeMillis()
            val prima = lastCheckAt
            if (now - prima < CHECK_EVERY_MS) return@launch
            lastCheckAt = now
            if (!mutex.withLock { refreshSilently() }) lastCheckAt = prima
        }
    }

    private fun readMetaOverlay(): String? = runCatching {
        JSONObject(meta.readText()).optString("overlayUrl").takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * Aggiornamento senza cambiare stato: [BundleState.Ready] resta Ready per
     * tutto il tempo, finche' il nuovo bundle non e' installato.
     *
     * Che cosa e' lecito fare con la rete lo decide [BundleRefreshPolicy]: su
     * rete non a consumo si scarica; su rete a consumo con orari ancora buoni
     * non si fa niente (il bundle di ieri e' valido e la domanda non vale la
     * pena); su rete a consumo con orari scaduti o in scadenza si OFFRE
     * l'aggiornamento invece di farlo, e la scelta sta in [updateOffer].
     *
     * L'ultimo caso, prima, scaricava e basta. Chi usa solo i dati mobili
     * aveva cosi' un modo di aggiornare degli orari scaduti, ma i megabyte
     * partivano dal suo piano senza che nessuno glielo dicesse: il contrario
     * di quello che l'app fa gia' al primo avvio, dove su rete a consumo si
     * chiede prima.
     *
     * @param userApproved l'utente ha detto "aggiorna ora": la rete non conta
     *   piu', e il progresso si vede nella domanda.
     * @return `false` se il giro non ha trovato una rete per noi (vedi
     *   `Metered`): non ha controllato niente, e chi lo ha chiamato non lo
     *   deve contare come un controllo. Un giro che ha deciso di non fare
     *   niente su rete a consumo, o che e' andato male, e' un controllo.
     */
    private suspend fun refreshSilently(userApproved: Boolean = false): Boolean {
        val current = state.value as? BundleState.Ready ?: return true
        val cm = context.getSystemService(ConnectivityManager::class.java)
        // "Non lo so ancora" all'avvio si aspetta un attimo invece di saltare
        // il giro, perche' il prossimo controllo e' fra un'ora (vedi Metered).
        // Con il si' dell'utente la rete non serve nemmeno guardarla.
        val consumo: Boolean? = if (userApproved) null else Metered.await(cm)
        val scadono = BundleRefreshPolicy.expiring(LocalDate.now(Ftb.ROME), current.reader.feedEnd)
        val rete = BundleRefreshPolicy.network(consumo, scadono, userApproved)
        if (rete == BundleRefreshPolicy.Network.Skip) return consumo != null
        try {
            val index = fetchIndex()
            val stesso = index.buildId == java.lang.Long.toHexString(current.buildId)
            // Stesso bundle, ma l'overlay puo' essere cambiato lo stesso: la
            // pipeline delle tile ha una sua versione (il map matching e'
            // arrivato cosi') e pubblica sotto un nome nuovo anche a parita'
            // di orari.
            val overlayNuovo = index.overlayUrl != null && index.overlayUrl != current.overlayUrl
            // Gli orari in tasca sono gia' quelli dell'indice: se c'era una
            // domanda aperta (un Wi-Fi arrivato nel frattempo ha gia'
            // aggiornato) non c'e' piu' niente da offrire ne' da aspettare.
            if (stesso) _updateOffer.value = null
            when (BundleRefreshPolicy.step(rete, stesso, overlayNuovo)) {
                BundleRefreshPolicy.Step.Nothing -> Unit

                BundleRefreshPolicy.Step.OverlayOnly -> {
                    writeMeta(index)
                    _state.value = BundleState.Ready(current.reader, current.buildId, index.overlayUrl)
                }

                BundleRefreshPolicy.Step.Install -> {
                    installFrom(index) { done ->
                        // Il progresso si pubblica solo se qualcuno lo sta
                        // guardando: il giro in sottofondo non ha una riga
                        // che lo mostri.
                        //
                        // E anche chi aspettava il Wi-Fi lo sta guardando:
                        // arrivato il Wi-Fi, per tutto il download la riga
                        // diceva ancora "Aspetto il Wi-Fi" col tasto per
                        // scaricare sui dati acceso.
                        if (userApproved || _updateOffer.value is UpdateOffer.WaitingForWifi ||
                            _updateOffer.value is UpdateOffer.Downloading
                        ) {
                            _updateOffer.value = UpdateOffer.Downloading(
                                if (index.bytes > 0) (done.toFloat() / index.bytes).coerceIn(0f, 1f) else 0f,
                            )
                        }
                    }
                    _updateOffer.value = null
                }

                BundleRefreshPolicy.Step.Offer -> offer(index)
            }
        } catch (e: Exception) {
            // Un giro andato a vuoto con orari ancora buoni non e' una
            // notizia: quelli in tasca servono. Con orari finiti, o con un si'
            // dell'utente appena dato, il silenzio sarebbe la peggiore delle
            // risposte: la riga della domanda resterebbe su "Scarico" per
            // sempre, o sparirebbe, e gli orari scaduti resterebbero scaduti
            // senza che nessuno sappia perche'.
            if (userApproved || scadono) {
                _updateOffer.value = UpdateOffer.Failed(e.message ?: "errore sconosciuto")
            }
        }
        return true
    }

    /** Pubblica la domanda "aggiorno sulla rete mobile?", senza disturbare chi ha gia' scelto. */
    private fun offer(index: BundleIndex) {
        val bytes = index.bytes.takeIf { it > 0 } ?: EXPECTED_BYTES
        val attuale = _updateOffer.value
        // Chi ha detto "aspetto il Wi-Fi" o sta scaricando non si disturba:
        // rifare la domanda a ogni controllo e' il modo in cui una scelta
        // diventa una seccatura.
        if (attuale == null || attuale is UpdateOffer.Offered || attuale is UpdateOffer.Failed) {
            _updateOffer.value = UpdateOffer.Offered(bytes)
        }
    }

    /**
     * L'utente ha detto "aggiorna gli orari" sulla domanda, anche su rete mobile.
     *
     * Vale anche come "riprova" dopo un aggiornamento fallito. Lo stato non
     * cambia: gli orari in tasca restano in uso finche' quelli nuovi non sono
     * scaricati e verificati, e a quel punto il lettore si sostituisce da solo.
     */
    fun acceptUpdateOnMetered() {
        val prima = _updateOffer.value
        // Senza domanda non c'e' niente da accettare, e con un download gia'
        // in corso un secondo tocco non ne fa partire un altro.
        if (prima == null || prima is UpdateOffer.Downloading) return
        _updateOffer.value = UpdateOffer.Downloading(0f)
        // Se aspettava il Wi-Fi, adesso non piu': il callback rimasto in
        // carica scatterebbe sopra un aggiornamento gia' fatto.
        stopWaitingForWifi()
        scope.launch(Dispatchers.IO) { mutex.withLock { refreshSilently(userApproved = true) } }
    }

    /** L'utente preferisce aspettare il Wi-Fi per gli orari nuovi. */
    fun waitForWifiToUpdate() {
        val offerta = _updateOffer.value as? UpdateOffer.Offered ?: return
        _updateOffer.value = UpdateOffer.WaitingForWifi(offerta.bytes)
        watchForWifi {
            // Sotto il mutex si guarda com'e' adesso, non com'era al tocco:
            // nel frattempo l'utente puo' aver cambiato idea.
            val attesa = _updateOffer.value as? UpdateOffer.WaitingForWifi ?: return@watchForWifi
            refreshSilently()
            // Se il giro non ha cambiato niente — la rete e' tornata a
            // consumo, o non rispondeva — "aspetto il Wi-Fi" non e' piu'
            // vero: il callback e' gia' scattato e non ne resta nessuno in
            // carica. Si torna alla domanda, che ha i suoi tasti.
            if (_updateOffer.value == attesa) _updateOffer.value = UpdateOffer.Offered(attesa.bytes)
        }
    }

    /** L'utente ha accettato il download su rete a consumo. */
    fun downloadOnMetered() {
        // Scegliere di scaricare vuol dire smettere di aspettare: il callback
        // del Wi-Fi rimasto in carica ripartiva a scaricare a bundle gia'
        // installato (vedi watchForWifi).
        stopWaitingForWifi()
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                // Se nel frattempo gli orari sono arrivati non c'e' piu' un
                // primo scarico da approvare.
                if (state.value !is BundleState.Ready) requestDownload(userApprovedMetered = true)
            }
        }
    }

    /** L'utente preferisce aspettare il Wi-Fi. */
    fun waitForWifi() {
        // Solo dalla domanda del primo scarico: da qualunque altro stato,
        // Ready compreso, scriverci sopra WaitingForWifi rimetterebbe la
        // schermata di benvenuto al posto dell'app.
        if (_state.value !is BundleState.AskMetered) return
        _state.value = BundleState.WaitingForWifi
        watchForWifi {
            // Si controlla sotto il mutex, che e' l'unico momento in cui la
            // risposta e' vera: il callback puo' scattare mentre un "Scarica
            // ora sulla rete mobile" sta ancora installando, e a bundle
            // installato ripartire era il difetto. Se il tentativo su rete
            // mobile e' fallito lo stato e' Failed, e a decidere e' Riprova.
            if (state.value is BundleState.WaitingForWifi) requestDownload(userApprovedMetered = false)
        }
    }

    /**
     * Mette un callback "quando arriva una rete non a consumo, fai [action]".
     *
     * Le capacita' della rete, non la domanda "la rete attiva e' a consumo?":
     * in onAvailable le capacita' possono non essere ancora note, e la
     * risposta di ripiego e' "si'" — il Wi-Fi appena agganciato veniva
     * scartato e non se ne aspettava un altro.
     *
     * Scatta una volta sola. Prima si scollegava solo dentro il proprio
     * scatto, quindi un "Scarica ora sulla rete mobile" a callback gia'
     * registrato lo lasciava in giro: a download finito, l'app in mano, il
     * telefono entrava in un Wi-Fi e la schermata di benvenuto tornava sopra
     * la mappa a dire "Sto scaricando gli orari di tutta la regione".
     */
    private fun watchForWifi(action: suspend () -> Unit) {
        // Uno alla volta: uno rimasto in carica non deve poter scattare sopra
        // la scelta nuova.
        stopWaitingForWifi()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) {
                if (Metered.isMetered(caps)) return
                // Se qualcun altro l'ha gia' tolto — un tasto, o un altro
                // scatto dello stesso callback — non e' piu' il suo turno.
                if (!wifiCallback.compareAndSet(this, null)) return
                runCatching { cm.unregisterNetworkCallback(this) }
                scope.launch(Dispatchers.IO) { mutex.withLock { action() } }
            }
        }
        wifiCallback.set(callback)
        cm.registerDefaultNetworkCallback(callback)
    }

    /**
     * Smette di aspettare il Wi-Fi, se si stava aspettando.
     *
     * `runCatching` perche' scollegare due volte lo stesso callback lancia
     * IllegalArgumentException, e i tasti e il callback stesso possono
     * arrivarci insieme.
     */
    private fun stopWaitingForWifi() {
        val callback = wifiCallback.getAndSet(null) ?: return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    /** L'ultimo controllo chiesto con [retry]: chi l'ha chiesto ne aspetta l'esito. */
    @Volatile
    private var ultimoControllo: kotlinx.coroutines.Job? = null

    /**
     * Aspetta la fine del controllo chiesto con [retry], fino a [timeoutMs].
     * True se e' finito. Serve all'assistente per dire com'e' andata invece
     * di "fatto" subito dopo averlo lanciato.
     */
    suspend fun awaitRetry(timeoutMs: Long): Boolean {
        val job = ultimoControllo ?: return true
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { job.join(); true } ?: false
    }

    fun retry() {
        // Da Ready non c'e' il primo scarico da smettere di aspettare: l'unico
        // callback in carica e' quello di "aspetto il Wi-Fi per gli orari
        // nuovi" (waitForWifiToUpdate), e scollegarlo lascerebbe la domanda a
        // dire "aspetto" con nessuno ad aspettare. Succede quando l'assistente
        // controlla i dati mentre l'utente ha scelto di aspettare.
        if (state.value !is BundleState.Ready) stopWaitingForWifi()
        ultimoControllo = scope.launch(Dispatchers.IO) {
            mutex.withLock {
                // Con gli orari in tasca "riprova" non riscarica da capo: si
                // guarda se ce ne sono di nuovi, e li si prende anche sui dati
                // mobili — chi chiama retry() da qui e' l'assistente, dopo che
                // l'utente ha confermato "aggiorna gli orari". Quella conferma
                // e' il permesso: prima il giro la ignorava, sui dati non
                // faceva niente, e l'assistente rispondeva "fatto". Prima passava da requestDownload,
                // che da Ready pubblica Downloading: la richiesta di
                // aggiornare i dati fatta all'assistente — due volte, dalla
                // sua azione e dal suo strumento — riportava l'app sulla
                // schermata di benvenuto, su qualunque rete, mobile compresa.
                if (state.value is BundleState.Ready) {
                    refreshSilently(userApproved = true)
                } else {
                    requestDownload(userApprovedMetered = true)
                }
            }
        }
    }

    private suspend fun requestDownload(userApprovedMetered: Boolean) {
        // Da Ready non si scarica "da capo". Pubblicare Downloading fa scegliere
        // ad AppRoot la schermata di benvenuto, cioe' butta via la mappa e il
        // pannello aperti per dire "Serve solo la prima volta" a un'app che gli
        // orari li ha. Chi ha gia' un bundle passa da refreshSilently, che non
        // cambia stato: questa e' solo la strada del primo scarico.
        if (state.value is BundleState.Ready) return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        // Si chiede solo davanti a una rete che SAPPIAMO a consumo. Senza
        // rete ancora aperta si prova: se davvero non c'e', il download
        // fallisce e lo dice con le sue parole, che sono vere; "Sei su rete
        // mobile" su un Wi-Fi non lo era.
        if (!userApprovedMetered && Metered.await(cm) == true) {
            _state.value = BundleState.AskMetered(EXPECTED_BYTES)
            return
        }
        _state.value = BundleState.Downloading(0f)
        try {
            val index = fetchIndex()
            installFrom(index) { done ->
                _state.value = BundleState.Downloading(
                    if (index.bytes > 0) (done.toFloat() / index.bytes).coerceIn(0f, 1f) else 0f,
                )
            }
        } catch (e: Exception) {
            _state.value = if (active.isFile) {
                // C'era gia' un bundle valido: si continua con quello.
                runCatching { openAndSmoke(active) }
                    .map { BundleState.Ready(it, it.buildId, readMetaOverlay()) as BundleState }
                    .getOrElse { BundleState.Failed(e.message ?: "errore sconosciuto") }
            } else {
                BundleState.Failed(e.message ?: "errore sconosciuto")
            }
        }
    }

    /** Scarica, verifica, promuove e pubblica il nuovo Ready. */
    private fun installFrom(index: BundleIndex, onProgress: (Long) -> Unit = {}) {
        val part = File(dir, "incoming.ftb.part")
        downloadGunzip(index.url, part, onProgress)
        if (index.sha256.isNotEmpty()) {
            val actual = sha256Of(part)
            check(actual.equals(index.sha256, ignoreCase = true)) {
                "bundle corrotto in transito (sha256 diverso)"
            }
        }
        // La query di fumo prima della promozione: un bundle che non si
        // apre non deve mai diventare quello attivo.
        openAndSmoke(part).close()
        val previous = (state.value as? BundleState.Ready)?.reader

        // La promozione non passa mai da "nessun bundle": il vecchio si
        // sposta di lato, il nuovo prende il suo posto, e solo alla fine il
        // vecchio si butta. Il perche', e il ramo che fallisce, stanno in
        // BundleSwap insieme ai loro test.
        BundleSwap.promote(part, active, previousFile)
        writeMeta(index)
        val reader = openAndSmoke(active)
        _state.value = BundleState.Ready(reader, reader.buildId, index.overlayUrl)
        retire(previous)
        previousFile.delete()
    }

    /**
     * Il lettore vecchio si chiude dopo, non subito.
     *
     * Chiuderlo vuol dire smappare il file, e chi sta leggendo da quella
     * mappa in quel momento non prende un'eccezione: prende un segnale dal
     * sistema, cioe' il processo muore. E qualcuno sta quasi sempre leggendo:
     * un tabellone si calcola sul pool di sfondo prendendo il lettore dallo
     * stato e usandolo per qualche millisecondo, un giro di RAPTOR per
     * qualche decimo di secondo, uno strumento dell'assistente anche di piu'.
     * Fra il momento in cui lo stato pubblica il lettore nuovo e il momento
     * in cui il vecchio viene smappato non c'era niente.
     *
     * Succede una volta al giorno, quando arriva il bundle della notte, e da
     * quando l'app ricontrolla anche tornando in primo piano puo' succedere
     * mentre la si sta guardando.
     *
     * Un minuto di grazia non e' una garanzia e non si spaccia per tale: e'
     * quattro ordini di grandezza piu' del piu' lento dei lettori. La
     * garanzia vera vorrebbe un conteggio dei riferimenti attraverso tutti i
     * chiamanti, che e' un cambiamento molto piu' grosso di quello che il
     * difetto merita. Il file si cancella subito lo stesso: su Android una
     * mappa tiene vivo il contenuto anche dopo la cancellazione del nome.
     */
    private fun retire(previous: BundleReader?) {
        if (previous == null) return
        scope.launch {
            delay(RETIRE_GRACE_MS)
            runCatching { previous.close() }
        }
    }

    private fun writeMeta(index: BundleIndex) {
        runCatching {
            // Se questa si tronca a meta', `readMetaOverlay` non legge piu'
            // niente e la mappa resta senza le linee fino allo scambio della
            // notte dopo: la scheda vale la stessa cura del bundle.
            Durable.write(
                meta,
                JSONObject()
                    .put("buildId", index.buildId)
                    .put("overlayUrl", index.overlayUrl ?: JSONObject.NULL)
                    .toString(),
            )
        }
    }

    private class BundleIndex(
        val buildId: String,
        val url: String,
        val bytes: Long,
        val sha256: String,
        val overlayUrl: String?,
    )

    private fun fetchIndex(): BundleIndex {
        val json = JSONObject(httpGetText(INDEX_URL))
        return BundleIndex(
            buildId = json.optString("buildId"),
            url = json.getString("url"),
            bytes = json.optLong("bytes", -1),
            sha256 = json.optString("sha256"),
            overlayUrl = json.optString("overlayUrl").takeIf { it.isNotEmpty() },
        )
    }

    private fun openAndSmoke(file: File): BundleReader {
        val reader = BundleReader(file)
        // Tocca le sezioni che l'app usera' per prime: se una e' corrotta il
        // CRC di sezione lo dice adesso, non alla prima schermata.
        check(reader.stopCount > 0) { "bundle senza fermate" }
        check(reader.tripCount > 0) { "bundle senza corse" }
        reader.stopName(0)
        return reader
    }

    /**
     * Scarica e decomprime in un passaggio.
     *
     * Non usa EngineHttp: quello scarica un file e basta, e la schermata di
     * benvenuto ha bisogno del progresso. Il conteggio e' sui byte compressi
     * ricevuti, che sono quelli che l'utente sta pagando.
     */
    private fun downloadGunzip(url: String, out: File, onProgress: (Long) -> Unit) {
        out.parentFile?.mkdirs()
        var current = url
        var redirects = 0
        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            // Niente gzip di trasporto sopra un corpo gia' gzip.
            conn.setRequestProperty("Accept-Encoding", "identity")
            val code = conn.responseCode
            if (code in 301..308 && redirects < 5) {
                val location = conn.getHeaderField("Location") ?: error("redirect senza destinazione")
                conn.disconnect()
                current = URL(URL(current), location).toString()
                redirects++
                continue
            }
            check(code == 200) { "download fallito: HTTP $code" }
            var received = 0L
            val counting = object : java.io.FilterInputStream(conn.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) {
                        received += n
                        onProgress(received)
                    }
                    return n
                }
            }
            GZIPInputStream(counting, 1 shl 16).use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output, 1 shl 16) }
            }
            conn.disconnect()
            return
        }
    }

    private fun httpGetText(url: String): String {
        var current = url
        var redirects = 0
        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val code = conn.responseCode
            if (code in 301..308 && redirects < 5) {
                val location = conn.getHeaderField("Location") ?: error("redirect senza destinazione")
                conn.disconnect()
                current = URL(URL(current), location).toString()
                redirects++
                continue
            }
            check(code == 200) { "HTTP $code su $current" }
            return conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * L'indice del bundle: una release GitHub con tag fisso `dati`, i cui
         * asset vengono sostituiti dal job notturno. GitHub Releases e' un
         * CDN vero (verificato in Fase 1: Fastly, range request, cache HIT).
         */
        const val INDEX_URL =
            "https://github.com/Casual76/Fluid-transit/releases/download/dati/index.json"

        const val USER_AGENT = "FluidTransit/0.1 (+https://github.com/Casual76/Fluid-transit)"

        /** Stima mostrata prima di conoscere l'indice, per la domanda su rete a consumo. */
        const val EXPECTED_BYTES = 6L * 1024 * 1024

        /**
         * Ogni quanto si ricontrolla l'indice tornando davanti.
         *
         * Il bundle cambia una volta per notte, quindi piu' spesso di cosi'
         * sarebbe rumore; meno spesso e' un telefono che tiene l'app in
         * memoria tutto il giorno e non vede mai il cambio.
         */
        const val CHECK_EVERY_MS = 60L * 60 * 1000
    }
}
