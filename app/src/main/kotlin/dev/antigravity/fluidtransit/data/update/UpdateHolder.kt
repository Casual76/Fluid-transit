package dev.antigravity.fluidtransit.data.update

import android.content.Context
import dev.antigravity.fluidengine.foundation.AppUpdateInstallState
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.antigravity.fluidengine.net.EngineHttp
import dev.antigravity.fluidengine.update.AndroidAppUpdateInstaller
import dev.antigravity.fluidengine.update.EngineAppUpdater
import dev.antigravity.fluidengine.update.UpdateSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * L'aggiornamento in-app dal Pampa Store.
 *
 * L'app non passa da uno store che aggiorna da solo: se non se lo chiede lei,
 * una versione nuova resta dov'e'. Il controllo parte all'avvio e si ripete
 * al ritorno in primo piano (con un intervallo, vedi [UpdateCheckPolicy]), in
 * sottofondo, e se trova qualcosa lo dice sulla mappa con una capsula —
 * scelta dell'utente, contro il tenerlo nascosto in Impostazioni.
 *
 * Il canale e' stabile per tutti, con la beta a scelta: chi collauda si
 * aggiorna dallo store invece che via cavo.
 */
class UpdateHolder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val manifestUrl: String,
    private val applicationId: String,
    private val currentVersion: String,
    private val userAgent: String,
) {
    private val prefs = context.getSharedPreferences("aggiornamenti", Context.MODE_PRIVATE)

    private val updater = EngineAppUpdater(
        http = EngineHttp(userAgent = userAgent),
        source = UpdateSource(manifestUrl = manifestUrl, applicationId = applicationId),
        installer = AndroidAppUpdateInstaller(context, EngineHttp(userAgent = userAgent)),
    )

    private val _available = MutableStateFlow<AvailableAppUpdate?>(null)
    val available: StateFlow<AvailableAppUpdate?> = _available

    private val _install = MutableStateFlow<AppUpdateInstallState?>(null)
    val install: StateFlow<AppUpdateInstallState?> = _install

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking

    /** L'ultimo controllo e' fallito? Serve solo a non mentire in Impostazioni. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    /** Segui anche le versioni di prova. */
    var beta: Boolean
        get() = prefs.getBoolean("beta", false)
        set(value) {
            prefs.edit().putBoolean("beta", value).apply()
            // Cambiare canale senza ricontrollare lascerebbe a schermo la
            // risposta della domanda precedente.
            check()
        }

    /**
     * La capsula sulla mappa e' stata scacciata. Solo per questa sessione, e
     * non persistita apposta: un'app che si aggiorna da sola non deve poter
     * perdere per sempre un aggiornamento perche' un giorno hai sfiorato
     * "non ora". In Impostazioni resta comunque.
     */
    private val _capsuleDismissed = MutableStateFlow(false)
    val capsuleDismissed: StateFlow<Boolean> = _capsuleDismissed

    /** Quando e' finito l'ultimo controllo di questo processo, e com'e' andato. */
    @Volatile
    private var lastCheckAtMs: Long? = null

    @Volatile
    private var lastCheckOk = false

    /**
     * Ricontrolla solo se e' ora (vedi [UpdateCheckPolicy]): e' quello che si
     * chiama dal ritorno in primo piano e dal ritorno della rete, dove
     * chiamare [check] a ogni occasione sarebbe una richiesta a ogni apertura.
     */
    fun checkIfDue(reteTornata: Boolean = false) {
        // Se e' tornata la rete e l'ultimo giro e' fallito, non c'e' niente da
        // aspettare: quel fallimento era la rete, e adesso non c'e' piu'.
        if (reteTornata && !lastCheckOk && lastCheckAtMs != null) {
            check()
            return
        }
        if (UpdateCheckPolicy.due(lastCheckAtMs, lastCheckOk, System.currentTimeMillis())) check()
    }

    fun check() {
        // Un solo controllo alla volta, e in modo atomico: all'avvio lo
        // chiedono l'Application, il ritorno in primo piano e il ritorno
        // della rete quasi nello stesso istante, e con un semplice "se sta
        // girando esci" due partivano insieme.
        if (!_checking.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                // Un'installazione fallita non deve restare scritta sopra un
                // controllo nuovo: la riga in Impostazioni la mostra come
                // "in corso" e nasconde il resto.
                if (_install.value is AppUpdateInstallState.Error) _install.value = null
                awaitNetwork()
                val channel = if (beta) UpdateChannel.BETA else UpdateChannel.STABLE
                updater.check(currentVersion, channel, "")
                    .onSuccess {
                        // "Non ora" vale per la versione offerta, non per
                        // sempre: se ne esce una diversa la capsula torna.
                        // Senza questo, un controllo ripetuto al ritorno in
                        // primo piano la rimetterebbe ogni volta.
                        if (it?.version != _available.value?.version) {
                            _capsuleDismissed.value = false
                        }
                        _available.value = it
                        _lastError.value = null
                        lastCheckOk = true
                    }
                    .onFailure {
                        // Il messaggio grezzo: lo traduce chi lo mostra
                        // ([UpdateFailure]), e vuoto vale "non so perche'".
                        _lastError.value = it.message.orEmpty()
                        lastCheckOk = false
                    }
                lastCheckAtMs = System.currentTimeMillis()
            } finally {
                _checking.value = false
            }
        }
    }

    /**
     * All'avvio la rete non c'e' ancora: nell'`onCreate` il processo non e' in
     * primo piano e il sistema gliela tiene chiusa per una frazione di
     * secondo. Il controllo partiva li' dentro e moriva su "Unable to
     * resolve host", poi non si ripeteva mai. Si aspetta come fa il download
     * degli orari, con la stessa domanda (`Metered`) e non con le callback
     * di rete, che arrivano anche per una rete ancora bloccata.
     */
    private suspend fun awaitNetwork() {
        val cm = runCatching {
            context.getSystemService(android.net.ConnectivityManager::class.java)
        }.getOrNull() ?: return
        runCatching { dev.antigravity.fluidtransit.data.net.Metered.await(cm) }
    }

    private var installJob: Job? = null

    fun install() {
        val update = _available.value ?: return
        // Un'installazione alla volta: il tasto sta sia in Impostazioni sia
        // nella capsula, e due flussi sullo stesso APK si pestano.
        if (installJob?.isActive == true) return
        installJob = scope.launch(Dispatchers.IO) {
            try {
                updater.install(update).collect { _install.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Un'eccezione dal flusso dell'installazione lasciava la
                // riga ferma a "Aggiornamento in corso": la si trasforma
                // nell'errore che la riga sa mostrare, col suo "Riprova".
                _install.value = AppUpdateInstallState.Error(e.message.orEmpty())
            }
        }
    }

    /** "Non ora": la capsula sparisce, la voce in Impostazioni resta. */
    fun dismissCapsule() {
        _capsuleDismissed.value = true
    }
}
