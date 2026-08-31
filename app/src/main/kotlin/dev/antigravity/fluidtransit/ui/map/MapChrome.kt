package dev.antigravity.fluidtransit.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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

/**
 * La barra di ricerca: a tutta larghezza, in vetro, col microfono a destra
 * dentro la barra — come deciso. In Fase 3 il tocco apre il pannello di
 * ricerca su fermate e linee; il mic detta col riconoscimento di sistema.
 */
@Composable
fun MapSearchBar(
    backdrop: GlassBackdropState,
    onTap: () -> Unit,
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
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
                onClickLabel = "Cerca fermate e linee",
                onClick = onTap,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Cerca fermate e linee",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = "Cerca con la voce",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(40.dp)
                .clickable(
                    interactionSource = remember2(),
                    indication = null,
                    role = Role.Button,
                    onClick = onMic,
                )
                .padding(8.dp),
        )
    }
}

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
 * I filtri per categoria sotto la barra, scorrevoli se mai non ci stessero.
 *
 * La spec prevede la degradazione delle etichette (testo solo sul
 * selezionato, poi solo icone) quando i chip saranno di piu': con tre voci
 * icona+testo entrano su qualunque schermo, quindi quella logica arrivera'
 * insieme a Tram e Treni, quando servira' davvero.
 */
@Composable
fun CategoryChipsRow(
    backdrop: GlassBackdropState,
    selected: CategoryFilter,
    onSelect: (CategoryFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (chip in Chips) {
            val isSelected = chip.filter == selected
            Row(
                modifier = Modifier
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
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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

/** Il contenitore che manca a [Box]: un modifier condiviso dai due angoli bassi. */
@Composable
fun BoxScopePlaceholder() = Unit
