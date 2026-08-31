package dev.antigravity.fluidtransit.ui.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState

/**
 * SEGNAPOSTO di Fase 2: la mappa vera (MapLibre, stile Apple Maps, bus vivi)
 * arriva in Fase 3 e questa schermata sparisce per intero.
 *
 * Nel frattempo mostra i numeri veri del bundle appena scaricato: serve a
 * verificare sul telefono che il download, lo swap e il lettore mmap
 * funzionino — che e' l'unico compito dello scheletro.
 */
@Composable
fun MapTab(app: FluidTransitApp) {
    val state by app.bundleManager.state.collectAsStateWithLifecycle()
    val ready = state as? BundleState.Ready

    FluidScreen(title = "Mappa") {
        item {
            FluidCard {
                Text(
                    text = "La mappa arriva con la Fase 3",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Qui ci saranno le fermate, le linee e i bus in tempo reale.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ready != null) {
                    val r = ready.reader
                    Text(
                        text = "Intanto gli orari ci sono gia': " +
                            "%,d fermate, %,d linee e %,d corse di tutta la Toscana, validi dal %s al %s."
                                .format(r.stopCount, r.routeCount, r.tripCount, r.feedStart, r.feedEnd)
                                .replace(',', '.'),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
