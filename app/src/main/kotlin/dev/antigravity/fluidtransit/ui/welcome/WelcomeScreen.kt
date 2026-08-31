package dev.antigravity.fluidtransit.ui.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidAmbientCanvas
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidtransit.data.bundle.BundleManager
import dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState

/**
 * Il primo avvio: l'app spiega cosa sta scaricando e aspetta gli orari.
 *
 * Decisioni gia' prese e tradotte qui: su Wi-Fi si scarica senza chiedere;
 * su rete a consumo si chiede prima, con "aspetta il Wi-Fi" come
 * alternativa; a download finito si entra da soli (il Crossfade sta in
 * AppRoot). Tono: dare del tu, diretto.
 */
@Composable
fun WelcomeScreen(manager: BundleManager, state: BundleState) {
    Box(modifier = Modifier.fillMaxSize()) {
        FluidAmbientCanvas(
            ambient = FluidAmbient(tone = FluidHeroTone.Primary, motif = FluidHeroMotif.Ripples),
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Fluid Transit",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Bus, tram e treni di tutta la Toscana",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(48.dp))

            when (state) {
                is BundleState.Missing, is BundleState.Downloading -> {
                    val progress = (state as? BundleState.Downloading)?.progress ?: 0f
                    if (progress > 0f) {
                        FluidProgressBar(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        FluidSpinner()
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Sto scaricando gli orari di tutta la regione.\nServe solo la prima volta, poi si aggiornano da soli.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                is BundleState.AskMetered -> {
                    Text(
                        text = "Sei su rete mobile.\nGli orari pesano circa ${state.bytes / (1024 * 1024)} MB: scarico ora o aspetto il Wi-Fi?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    FluidButton(
                        text = "Scarica ora",
                        onClick = manager::downloadOnMetered,
                        style = FluidButtonStyle.Filled,
                    )
                    Spacer(Modifier.height(12.dp))
                    FluidButton(
                        text = "Aspetta il Wi-Fi",
                        onClick = manager::waitForWifi,
                        style = FluidButtonStyle.Plain,
                    )
                }

                is BundleState.WaitingForWifi -> {
                    FluidSpinner()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Aspetto una rete Wi-Fi per scaricare gli orari.\nAppena c'e', parto da solo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    FluidButton(
                        text = "Scarica ora sulla rete mobile",
                        onClick = manager::downloadOnMetered,
                        style = FluidButtonStyle.Plain,
                    )
                }

                is BundleState.Failed -> {
                    Text(
                        text = "Non sono riuscito a scaricare gli orari.\n${state.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    FluidButton(
                        text = "Riprova",
                        onClick = manager::retry,
                        style = FluidButtonStyle.Filled,
                    )
                }

                is BundleState.Ready -> Unit // AppRoot ha gia' cambiato schermata
            }
        }
    }
}
