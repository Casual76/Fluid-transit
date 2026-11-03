package dev.antigravity.fluidtransit.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.foundation.AppUpdateInstallState
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.antigravity.fluidengine.ui.fluid.FluidUpdateRelease
import dev.antigravity.fluidengine.ui.fluid.FluidUpdateUiState
import dev.antigravity.fluidengine.ui.fluid.toFluidUpdateRelease
import dev.antigravity.fluidengine.ui.fluid.toFluidUpdateState
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.data.update.UpdateFailure

/**
 * Gli aggiornamenti dal Pampa Store, come li legge la sezione dell'engine
 * (`fluidUpdateSection`): che versione hai, se ce n'e' una nuova, cosa cambia,
 * il canale. La capsula sulla mappa e' l'avviso; questa e' la stanza dove si
 * guarda con calma.
 *
 * Il disegno e' dell'engine. Qui c'e' solo il passaggio dagli stati di
 * [dev.antigravity.fluidtransit.data.update.UpdateHolder] a quelli della
 * sezione, che e' la parte con le decisioni.
 */
class UpdateSection(
    val state: FluidUpdateUiState,
    val lastCheckedAtMillis: Long?,
    val channel: UpdateChannel,
    val onChannelChange: (UpdateChannel) -> Unit,
    val onCheck: () -> Unit,
    val onInstall: () -> Unit,
)

@Composable
fun rememberUpdateSection(app: FluidTransitApp): UpdateSection {
    val updates = remember { app.updates }
    val available by updates.available.collectAsStateWithLifecycle()
    val install by updates.install.collectAsStateWithLifecycle()
    val checking by updates.checking.collectAsStateWithLifecycle()
    val error by updates.lastError.collectAsStateWithLifecycle()
    var beta by remember { mutableStateOf(updates.beta) }

    return UpdateSection(
        state = transitUpdateState(available, install, checking, error, neverChecked = updates.lastCheckedAtMillis == null),
        lastCheckedAtMillis = updates.lastCheckedAtMillis,
        channel = if (beta) UpdateChannel.BETA else UpdateChannel.STABLE,
        onChannelChange = {
            beta = it == UpdateChannel.BETA
            updates.beta = beta
        },
        onCheck = updates::check,
        onInstall = updates::install,
    )
}

/**
 * Da quello che sa [dev.antigravity.fluidtransit.data.update.UpdateHolder]
 * allo stato che la sezione disegna. L'ordine e' quello che la riga ha sempre
 * avuto:
 *
 * - un'installazione fallita ha la sua riga e si puo' riprovare (finiva nel
 *   ramo "in corso": titolo sbagliato, nessun tocco, e sotto spariva la riga
 *   "installa" — la prima volta succede sempre, perche' Android chiede prima
 *   di abilitare l'installazione da questa app);
 * - un'installazione in corso, con la sua barra;
 * - una versione nuova (anche mentre si ricontrolla: la domanda vecchia
 *   resta finche' non arriva la nuova);
 * - altrimenti il controllo: in corso, fallito, o "sei alla piu' recente" (se un
 *   controllo c'e' stato: prima non si sa niente, e dirlo sarebbe una promessa).
 *
 * Mai l'eccezione nuda: la frase in italiano sopra, e il testo tecnico sotto
 * per chi deve indagare ([UpdateFailure]). Il modello della sezione ha un
 * messaggio solo, quindi le due cose viaggiano insieme.
 */
internal fun transitUpdateState(
    available: AvailableAppUpdate?,
    install: AppUpdateInstallState?,
    checking: Boolean,
    error: String?,
    neverChecked: Boolean = false,
): FluidUpdateUiState {
    val release = available?.toFluidUpdateRelease()
    return when {
        install is AppUpdateInstallState.Error -> {
            val words = UpdateFailure.words(install.message, installing = true)
            FluidUpdateUiState.Failed(
                release = release ?: FluidUpdateRelease(version = ""),
                message = words.title + (words.technical?.let { "\n$it" } ?: ""),
            )
        }

        install != null && release != null -> install.toFluidUpdateState(release)

        release != null -> FluidUpdateUiState.Available(release)

        checking -> FluidUpdateUiState.Checking

        error != null -> {
            val words = UpdateFailure.words(error)
            FluidUpdateUiState.CheckFailed(words.title + (words.technical?.let { "\n$it" } ?: ""))
        }

        neverChecked -> FluidUpdateUiState.Idle

        else -> FluidUpdateUiState.UpToDate
    }
}
