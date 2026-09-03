package dev.antigravity.fluidtransit.data.update

import android.content.Context
import dev.antigravity.fluidengine.foundation.AppUpdateInstallState
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.antigravity.fluidengine.net.EngineHttp
import dev.antigravity.fluidengine.update.AndroidAppUpdateInstaller
import dev.antigravity.fluidengine.update.EngineAppUpdater
import dev.antigravity.fluidengine.update.UpdateSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * L'aggiornamento in-app dal Pampa Store.
 *
 * L'app non passa da uno store che aggiorna da solo: se non se lo chiede lei,
 * una versione nuova resta dov'e'. Il controllo parte all'avvio, in
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

    fun check() {
        if (_checking.value) return
        scope.launch(Dispatchers.IO) {
            _checking.value = true
            _capsuleDismissed.value = false
            val channel = if (beta) UpdateChannel.BETA else UpdateChannel.STABLE
            updater.check(currentVersion, channel, "")
                .onSuccess {
                    _available.value = it
                    _lastError.value = null
                }
                .onFailure { _lastError.value = it.message ?: "controllo non riuscito" }
            _checking.value = false
        }
    }

    fun install() {
        val update = _available.value ?: return
        scope.launch(Dispatchers.IO) {
            updater.install(update).collect { _install.value = it }
        }
    }

    /** "Non ora": la capsula sparisce, la voce in Impostazioni resta. */
    fun dismissCapsule() {
        _capsuleDismissed.value = true
    }
}
