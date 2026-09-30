package dev.antigravity.fluidtransit.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.foundation.AppUpdateInstallState
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidtransit.BuildConfig
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.data.update.UpdateFailure

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
            // L'installazione fallita ha la sua riga, e si puo' riprovare.
            // Finiva nel ramo "in corso": titolo sbagliato, nessun tocco, e
            // sotto spariva la riga "installa" — la prima volta succede
            // sempre, perche' Android chiede prima di abilitare
            // l'installazione da questa app.
            progress is AppUpdateInstallState.Error -> {
                val parole = UpdateFailure.words(progress.message, installing = true)
                FluidListRow(
                    title = "Installazione non riuscita",
                    subtitle = parole.title + (parole.technical?.let { "\n$it" } ?: ""),
                    meta = "riprova",
                    onClick = { updates.install() },
                )
            }

            progress != null && progress !is AppUpdateInstallState.Installed -> FluidListRow(
                title = "Aggiornamento in corso",
                subtitle = when (progress) {
                    is AppUpdateInstallState.Downloading ->
                        "Scaricato ${progress.downloadedBytes / (1024 * 1024)} MB " +
                            "di ${progress.totalBytes / (1024 * 1024)}"
                    is AppUpdateInstallState.Verifying -> progress.message
                    is AppUpdateInstallState.Installing -> progress.message
                    is AppUpdateInstallState.AwaitingUserAction -> progress.message
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
                    // Mai l'eccezione nuda: la frase in italiano sopra, e il
                    // testo tecnico sotto per chi deve indagare.
                    error != null -> UpdateFailure.words(error).let { p ->
                        p.title + (p.technical?.let { "\n$it" } ?: "")
                    }
                    checking -> "Un attimo"
                    else -> "Sei alla versione piu' recente del canale scelto"
                },
                onClick = { updates.check() },
            )
        }

        // Il nome sta anche sull'interruttore: per TalkBack e' un nodo a se'
        // e senza nome leggeva solo "Interruttore, attivo" (vedi "Colori dal
        // telefono" in SettingsTab).
        val titoloBeta = "Versioni di prova"
        FluidListRow(
            title = titoloBeta,
            subtitle = "Ricevi anche le beta: escono prima e si rompono piu' spesso",
            badge = {
                FluidSwitch(
                    checked = beta,
                    onCheckedChange = {
                        beta = it
                        updates.beta = it
                    },
                    modifier = Modifier.semantics { contentDescription = titoloBeta },
                )
            },
        )
    }
}
