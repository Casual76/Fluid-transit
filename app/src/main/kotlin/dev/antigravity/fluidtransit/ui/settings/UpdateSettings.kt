package dev.antigravity.fluidtransit.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.foundation.AppUpdateInstallState
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidtransit.BuildConfig
import dev.antigravity.fluidtransit.FluidTransitApp

/**
 * Gli aggiornamenti dal Pampa Store.
 *
 * Qui c'e' il quadro completo: che versione hai, se ce n'e' una nuova, cosa
 * cambia, e il canale. La capsula sulla mappa e' l'avviso; questa e' la
 * stanza dove si guarda con calma.
 */
@Composable
fun UpdateSettingsGroup(app: FluidTransitApp) {
    val updates = remember { app.updates }
    val available by updates.available.collectAsStateWithLifecycle()
    val install by updates.install.collectAsStateWithLifecycle()
    val checking by updates.checking.collectAsStateWithLifecycle()
    val error by updates.lastError.collectAsStateWithLifecycle()
    var beta by remember { mutableStateOf(updates.beta) }

    FluidListGroup {
        FluidListRow(
            title = "Versione",
            subtitle = BuildConfig.VERSION_NAME,
        )

        val update = available
        val progress = install
        when {
            progress != null && progress !is AppUpdateInstallState.Installed -> FluidListRow(
                title = "Aggiornamento in corso",
                subtitle = when (progress) {
                    is AppUpdateInstallState.Downloading ->
                        "Scaricato ${progress.downloadedBytes / (1024 * 1024)} MB " +
                            "di ${progress.totalBytes / (1024 * 1024)}"
                    is AppUpdateInstallState.Verifying -> progress.message
                    is AppUpdateInstallState.Installing -> progress.message
                    is AppUpdateInstallState.AwaitingUserAction -> progress.message
                    is AppUpdateInstallState.Error -> progress.message
                    else -> ""
                },
                meta = if (progress is AppUpdateInstallState.Downloading) {
                    "${(progress.progress * 100).toInt()}%"
                } else {
                    null
                },
            )

            update != null -> FluidListRow(
                title = "Aggiornamento disponibile",
                // Il changelog e' quello che l'utente legge per decidere: si
                // mostra, non si riassume in "miglioramenti vari".
                subtitle = "${update.version} · " +
                    update.changelog.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                meta = "installa",
                onClick = { updates.install() },
            )

            else -> FluidListRow(
                title = if (checking) "Sto controllando…" else "Cerca aggiornamenti",
                subtitle = when {
                    error != null -> "L'ultimo controllo non e' riuscito: $error"
                    checking -> "Un attimo"
                    else -> "Sei alla versione piu' recente del canale scelto"
                },
                onClick = { updates.check() },
            )
        }

        FluidListRow(
            title = "Versioni di prova",
            subtitle = "Ricevi anche le beta: escono prima e si rompono piu' spesso",
            badge = {
                FluidSwitch(
                    checked = beta,
                    onCheckedChange = {
                        beta = it
                        updates.beta = it
                    },
                )
            },
        )
    }
}
