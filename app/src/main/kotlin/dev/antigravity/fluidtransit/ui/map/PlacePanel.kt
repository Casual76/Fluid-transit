package dev.antigravity.fluidtransit.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassEdge
import dev.antigravity.fluidengine.ui.fluid.glassSurface

/** Un luogo scelto: dalla ricerca, da un posto salvato o dal tieni-premuto. */
class PlaceRef(
    val name: String,
    val context: String,
    val lat: Double,
    val lon: Double,
    val savedId: Long? = null,
)

/** Un bottone di testo in vetro: vetro su vetro, come da regola dei pannelli. */
@Composable
fun GlassActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    backdrop: GlassBackdropState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    Row(
        modifier = modifier
            .glassSurface(
                state = backdrop,
                tint = GlassDefaults.floatingTint(),
                shape = FluidCapsuleShape,
                edge = GlassEdge.None,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (emphasized) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (emphasized) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/**
 * Il pannello del luogo: nome, dove sta, e i due gesti decisi — "Portami
 * qui" che avvia gli itinerari e "Salva" che lo mette fra i tuoi posti.
 * Il salvataggio non apre dialoghi: il pannello si trasforma nel modulo,
 * come ogni altro passaggio di stato di questa superficie.
 */
@Composable
fun PlacePanelContent(
    ref: PlaceRef,
    backdrop: GlassBackdropState,
    onDismiss: () -> Unit,
    onGo: () -> Unit,
    onSave: (label: String) -> Unit,
    onRemoveSaved: (() -> Unit)? = null,
    /** "Parti da qui": mette questo punto come ORIGINE del pianificatore. */
    onStartHere: (() -> Unit)? = null,
) {
    var saving by remember { mutableStateOf(false) }
    var customLabel by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = ref.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (ref.context.isNotEmpty()) {
                Text(
                    text = ref.context,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "Chiudi",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(40.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onDismiss,
                )
                .padding(8.dp),
        )
    }

    if (!saving) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassActionButton(
                text = "Portami qui",
                icon = Icons.Rounded.Directions,
                backdrop = backdrop,
                onClick = onGo,
                emphasized = true,
                modifier = Modifier.weight(1f),
            )
            if (onRemoveSaved != null) {
                GlassActionButton(
                    text = "Rimuovi",
                    icon = Icons.Rounded.Star,
                    backdrop = backdrop,
                    onClick = onRemoveSaved,
                )
            } else {
                GlassActionButton(
                    text = "Salva",
                    icon = Icons.Rounded.Star,
                    backdrop = backdrop,
                    onClick = { saving = true },
                )
            }
        }
        // Su una riga sua: e' l'azione meno frequente delle due, ma e'
        // l'unico modo per far partire un viaggio da qui invece che da dove
        // sei — cosa che fino alla Fase 8 non si poteva fare affatto.
        if (onStartHere != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 14.dp),
            ) {
                GlassActionButton(
                    text = "Parti da qui",
                    icon = Icons.Rounded.MyLocation,
                    backdrop = backdrop,
                    onClick = onStartHere,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else {
        // --- il modulo del salvataggio: etichetta pronta o nome libero ----
        Text(
            text = "Salva come",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (label in listOf("Casa", "Lavoro", "Scuola")) {
                GlassActionButton(
                    text = label,
                    icon = null,
                    backdrop = backdrop,
                    onClick = { onSave(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .glassSurface(
                        state = backdrop,
                        tint = GlassDefaults.floatingTint(),
                        shape = FluidCapsuleShape,
                        edge = GlassEdge.None,
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = customLabel,
                    onValueChange = { customLabel = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        if (customLabel.isEmpty()) {
                            Text(
                                text = "Oppure un nome tuo…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    },
                )
            }
            GlassActionButton(
                text = "Salva",
                icon = null,
                backdrop = backdrop,
                emphasized = true,
                onClick = { if (customLabel.isNotBlank()) onSave(customLabel) },
            )
        }
    }
    Spacer(Modifier.height(6.dp))
}
