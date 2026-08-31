package dev.antigravity.fluidtransit.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidGlassIconButton
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassEdge
import dev.antigravity.fluidengine.ui.fluid.glassSurface

// La barra di ricerca vive in SearchGlass.kt: e' la stessa superficie che si
// estende nel pannello, non un componente separato.

@Composable
private fun remember2() = androidx.compose.runtime.remember { MutableInteractionSource() }

private class ChipSpec(
    val filter: CategoryFilter,
    val label: String,
    val icon: ImageVector,
)

private val Chips = listOf(
    ChipSpec(CategoryFilter.ALL, "Tutti", Icons.Rounded.DirectionsBus),
    ChipSpec(CategoryFilter.URBAN, "Urbani", Icons.Rounded.LocationCity),
    ChipSpec(CategoryFilter.EXTRA, "Extraurbani", Icons.Rounded.Landscape),
)

/**
 * I filtri per categoria sotto la barra.
 *
 * La regola, arrivata guardando la prima build: **la fila non e' mai piu'
 * corta dello schermo**. Ogni chip prende una quota uguale della larghezza
 * (peso, non misura fissa) senza crescere in altezza ne' in corpo del testo,
 * cosi' su qualunque schermo la fila arriva esattamente al margine.
 * Quando i chip diventeranno troppi per starci (Tram, Treni), scatteranno
 * scorrimento e degradazione delle etichette come da spec.
 */
@Composable
fun CategoryChipsRow(
    backdrop: GlassBackdropState,
    selected: CategoryFilter,
    onSelect: (CategoryFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (chip in Chips) {
            val isSelected = chip.filter == selected
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .glassSurface(
                        state = backdrop,
                        tint = GlassDefaults.floatingTint(),
                        shape = FluidCapsuleShape,
                        edge = GlassEdge.None,
                    )
                    .clickable(
                        interactionSource = remember2(),
                        indication = null,
                        role = Role.Button,
                        onClick = { onSelect(chip.filter) },
                    )
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                Icon(
                    imageVector = chip.icon,
                    contentDescription = null,
                    tint = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = chip.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

/** Un tasto tondo in vetro agli angoli della mappa. */
@Composable
fun MapCornerButton(
    icon: ImageVector,
    contentDescription: String,
    backdrop: GlassBackdropState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    FluidGlassIconButton(
        onClick = onClick,
        backdrop = backdrop,
        selected = selected,
        modifier = modifier,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.size(22.dp),
        )
    }
}

