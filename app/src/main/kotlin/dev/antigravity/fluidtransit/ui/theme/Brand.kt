package dev.antigravity.fluidtransit.ui.theme

import androidx.compose.ui.graphics.Color
import dev.antigravity.fluidengine.ui.theme.AccentPreset

/**
 * L'ametista di Fluid Transit.
 *
 * Due tagli dello stesso colore: piu' profondo sul tema chiaro (il chiaro
 * mangia saturazione), piu' luminoso sullo scuro dove puo' brillare senza
 * urlare. Il valore scuro e' lo stesso provato negli spike della mappa.
 */
val TransitBrand = AccentPreset(
    name = "fluidtransit",
    label = "Fluid Transit",
    light = Color(0xFF7C4DC4),
    dark = Color(0xFF9B6DD6),
)
