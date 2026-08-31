package dev.antigravity.fluidtransit.ui.favorites

import androidx.compose.runtime.Composable
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState

/**
 * I preferiti: fermate e linee salvate. Il salvataggio arriva con la scheda
 * fermata (Fase 3) e la persistenza Room (Fase 6); lo stato vuoto e' quello
 * definitivo, non un segnaposto.
 */
@Composable
fun FavoritesTab() {
    FluidScreen(title = "Preferiti") {
        item {
            FluidEmptyState(
                title = "Niente di salvato, per ora",
                detail = "Quando salvi una fermata o una linea, le ritrovi qui.",
            )
        }
    }
}
