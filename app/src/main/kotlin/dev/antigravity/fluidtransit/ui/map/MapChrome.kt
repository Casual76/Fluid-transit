package dev.antigravity.fluidtransit.ui.map

import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
    /**
     * Quota di larghezza. Non uguale per tutti: "Extraurbani" e' tre volte
     * "Tutti" e con quote pari mandava a capo l'ultima lettera — visto sul
     * device. "Tutti" cede lo spazio che non gli serve.
     */
    val weight: Float,
)

private val Chips = listOf(
    ChipSpec(CategoryFilter.ALL, "Tutti", Icons.Rounded.DirectionsBus, 0.78f),
    ChipSpec(CategoryFilter.URBAN, "Urbani", Icons.Rounded.LocationCity, 1.0f),
    ChipSpec(CategoryFilter.EXTRA, "Extraurbani", Icons.Rounded.Landscape, 1.42f),
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
                    .weight(chip.weight)
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
                    maxLines = 1,
                    softWrap = false,
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
    /**
     * Rotazione dell'icona in gradi. In modalita' bussola il tasto
     * posizione ruota col nord — e' l'unica bussola dell'app, quella di
     * MapLibre in alto e' spenta.
     */
    iconRotation: () -> Float = { 0f },
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
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { rotationZ = iconRotation() },
        )
    }
}


/**
 * La capsula del live degradato: compare SOLO quando i bus vivi mancano
 * davvero (deciso: silenzio finche' funziona). Un tocco la espande con la
 * spiegazione; i tecnicismi restano in Stato dei dati.
 */
@Composable
fun LiveDownCapsule(
    backdrop: GlassBackdropState,
    modifier: Modifier = Modifier,
) {
    var expanded by remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .glassSurface(
                state = backdrop,
                tint = GlassDefaults.floatingTint(),
                shape = dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape(
                    dev.antigravity.fluidengine.ui.fluid.FluidRadius.Card,
                ),
                edge = GlassEdge.None,
            )
            .clickable(
                interactionSource = remember2(),
                indication = null,
                role = Role.Button,
                onClick = { expanded = !expanded },
            )
            .animateContentSize()
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Bus live non disponibili",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (expanded) {
            Text(
                text = "Le posizioni non stanno arrivando: per ora valgono gli " +
                    "orari programmati. Dettagli in Impostazioni → Stato dei dati.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
