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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
    /**
     * Da dove si parte quando nessuno l'ha scelto.
     *
     * La riga diceva sempre "La tua posizione", anche col GPS spento, mentre
     * il pannello dei risultati sotto diceva correttamente "Dal centro della
     * mappa". Due frasi diverse sullo stesso fatto, nella stessa schermata, a
     * dieci centimetri di distanza.
     */
    defaultFrom: String,
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
                    text = from?.name ?: defaultFrom,
                    placeholder = from == null,
                    spoken = plannerFieldSpoken("Partenza", from?.name ?: defaultFrom),
                    onClickLabel = "Scegli la partenza",
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
                    // Vuota NON legge "Dove vai?": e' un invito, non un valore,
                    // e "Destinazione: Dove vai?" si legge come se fosse il
                    // nome del posto.
                    spoken = plannerFieldSpoken("Destinazione", to?.name),
                    onClickLabel = "Scegli la destinazione",
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
                    .minimumInteractiveComponentSize()
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

/**
 * Cosa legge un lettore di schermo su una riga Da o A.
 *
 * Le due righe si distinguevano solo per l'icona, che non dice niente a
 * nessun lettore, e per il segnaposto ("La tua posizione", "Dove vai?"):
 * scelto un posto, il segnaposto spariva e TalkBack leggeva "Piazza
 * Dalmazia, pulsante" e "Careggi, pulsante", senza dire quale fosse la
 * partenza — e il tasto "Scambia" li' sotto rendeva la cosa ancora piu'
 * confusa. Chi non ha ancora scelto (nome vuoto) sente "da scegliere",
 * non l'invito scritto sullo schermo.
 */
internal fun plannerFieldSpoken(role: String, name: String?): String =
    if (name.isNullOrBlank()) "$role: da scegliere" else "$role: $name"

@Composable
private fun PlannerField(
    icon: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
    text: String,
    placeholder: Boolean,
    /** La riga a voce: ruolo e valore, vedi [plannerFieldSpoken]. */
    spoken: String,
    /** Cosa fa il tocco, per chi non vede dove sta toccando. */
    onClickLabel: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = onClickLabel,
                role = Role.Button,
                onClick = onClick,
            )
            // Dopo il clickable, che cosi' conserva il tocco: la descrizione
            // sostituisce il solo testo del nome, che da solo non diceva se
            // questa riga e' la partenza o l'arrivo.
            .clearAndSetSemantics { contentDescription = spoken }
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
