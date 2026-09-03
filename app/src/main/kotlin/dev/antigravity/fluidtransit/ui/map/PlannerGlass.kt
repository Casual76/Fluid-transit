package dev.antigravity.fluidtransit.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidGlassIconButton
import dev.antigravity.fluidengine.ui.fluid.FluidHairline
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassEdge
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.glassSurface

/**
 * Il pianificatore: Da e A, uno sopra l'altro, al posto della barra di
 * ricerca.
 *
 * E' la forma scelta dall'utente ("due righe in cima"), ed e' anche l'unico
 * modo per far esistere una cosa che prima non c'era: **partire da un punto
 * diverso da dove sei**. Fino alla Fase 8 l'origine era sempre e solo il GPS
 * (o il centro della mappa quando il GPS mancava), e non c'era nessun posto
 * dove cambiarla.
 *
 * Toccare una riga apre la ricerca di sempre, che compila quella riga invece
 * di navigare: nessun secondo motore di ricerca, nessun secondo pannello.
 */
@Composable
fun PlannerGlass(
    backdrop: GlassBackdropState,
    from: PlaceRef?,
    to: PlaceRef?,
    timeLabel: String,
    onPickFrom: () -> Unit,
    onPickTo: () -> Unit,
    onSwap: () -> Unit,
    onTime: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(
                state = backdrop,
                tint = GlassDefaults.floatingTint(),
                shape = ContinuousCornerShape(FluidRadius.Sheet),
                edge = GlassEdge.None,
                role = GlassRole.Floating,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                PlannerField(
                    icon = { tint ->
                        Icon(
                            imageVector = Icons.Rounded.MyLocation,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    // Senza origine scelta si parte da dove sei: dirlo qui
                    // evita la domanda "da dove sta calcolando?".
                    text = from?.name ?: "La tua posizione",
                    placeholder = from == null,
                    onClick = onPickFrom,
                )
                FluidHairline(modifier = Modifier.padding(start = 52.dp, end = 16.dp))
                PlannerField(
                    icon = { tint ->
                        Icon(
                            imageVector = Icons.Rounded.Place,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    text = to?.name ?: "Dove vai?",
                    placeholder = to == null,
                    onClick = onPickTo,
                )
            }
            FluidGlassIconButton(onClick = onSwap, backdrop = backdrop) {
                Icon(
                    imageVector = Icons.Rounded.SwapVert,
                    contentDescription = "Scambia partenza e arrivo",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
        }

        FluidHairline(modifier = Modifier.padding(horizontal = 16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onTime,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Chiudi il pianificatore",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onClose,
                    ),
            )
        }
    }
}

@Composable
private fun PlannerField(
    icon: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
    text: String,
    placeholder: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        icon(MaterialTheme.colorScheme.primary)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (placeholder) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
