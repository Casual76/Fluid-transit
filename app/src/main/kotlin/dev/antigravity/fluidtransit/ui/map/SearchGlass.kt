package dev.antigravity.fluidtransit.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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

/** Un suggerimento nel pannello: cosa mostra e dove porta. */
class Suggestion(
    val kind: String, // "stop" | "route"
    val key: String,
    val title: String,
    val subtitle: String,
    val colorRgb: Int,
    val lat: Double,
    val lon: Double,
)

/**
 * La barra di ricerca che diventa il proprio pannello.
 *
 * Chiusa e' una capsula di vetro; toccata, **si estende verso il basso**
 * nello stesso pezzo di vetro — niente nuova pagina, niente stacco: la
 * bocciatura della prima versione ("pagina piatta, senza animazione, senza
 * vetro") e' il motivo per cui questo file esiste. Dentro, prima di
 * digitare: ricerche recenti, fermate vicine, linee recenti. Digitando, i
 * risultati.
 */
@Composable
fun SearchGlass(
    backdrop: GlassBackdropState,
    open: Boolean,
    query: String,
    results: List<Suggestion>,
    saved: List<Suggestion>,
    recents: List<Suggestion>,
    nearby: List<Suggestion>,
    recentLines: List<Suggestion>,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onQueryChange: (String) -> Unit,
    onMic: () -> Unit,
    onPick: (Suggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Chiusa: capsula (26 = meta' dei 52 di altezza). Aperta: pannello con
    // gli angoli continui di un foglio. Lo stesso vetro, due momenti.
    val radius = if (open) FluidRadius.Sheet else 26.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(
                state = backdrop,
                tint = GlassDefaults.floatingTint(),
                shape = ContinuousCornerShape(radius),
                edge = GlassEdge.None,
                role = if (open) GlassRole.Modal else GlassRole.Floating,
            )
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
    ) {
        // --- la riga della barra, sempre presente -------------------------
        val focus = remember { FocusRequester() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = { if (!open) onOpen() },
                )
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (open) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.Search,
                contentDescription = if (open) "Chiudi la ricerca" else null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(28.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = open,
                        onClick = onClose,
                    ),
            )
            Spacer(Modifier.width(10.dp))
            if (open) {
                LaunchedEffect(Unit) { focus.requestFocus() }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                text = "Fermata, linea o luogo…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        inner()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus),
                )
            } else {
                Text(
                    text = "Cerca fermate, linee e luoghi",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            FluidGlassIconButton(
                onClick = onMic,
                backdrop = backdrop,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = "Cerca con la voce",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // --- il pannello che scende ---------------------------------------
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .imePadding()
                    .padding(bottom = 10.dp),
            ) {
                if (query.length >= 2) {
                    if (results.isEmpty()) {
                        item {
                            Text(
                                text = "Niente con questo nome. Prova con meno lettere.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            )
                        }
                    } else {
                        items(results.size) { i -> SuggestionRow(results[i], onPick, divider = i > 0) }
                    }
                } else {
                    if (saved.isNotEmpty()) {
                        item { SectionLabel("I tuoi posti", Icons.Rounded.Star) }
                        items(saved.size) { i -> SuggestionRow(saved[i], onPick, divider = i > 0) }
                    }
                    if (recents.isNotEmpty()) {
                        item { SectionLabel("Recenti", Icons.Rounded.History) }
                        items(recents.size) { i -> SuggestionRow(recents[i], onPick, divider = i > 0) }
                    }
                    if (nearby.isNotEmpty()) {
                        item { SectionLabel("Fermate vicine", Icons.Rounded.NearMe) }
                        items(nearby.size) { i -> SuggestionRow(nearby[i], onPick, divider = i > 0) }
                    }
                    if (recentLines.isNotEmpty()) {
                        item { SectionLabel("Linee recenti", Icons.Rounded.History) }
                        items(recentLines.size) { i ->
                            SuggestionRow(recentLines[i], onPick, divider = i > 0)
                        }
                    }
                    if (recents.isEmpty() && nearby.isEmpty() && recentLines.isEmpty()) {
                        item {
                            Text(
                                text = "Scrivi il nome di una fermata o il numero di una linea.\nQuello che cerchi restera' qui per la prossima volta.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SuggestionRow(s: Suggestion, onPick: (Suggestion) -> Unit, divider: Boolean) {
    if (divider) FluidHairline(modifier = Modifier.padding(horizontal = 20.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(s) }
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (s.kind == "route") {
            Text(
                text = s.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .widthIn(min = 44.dp)
                    .background(
                        color = Color(0xFF000000 or s.colorRgb.toLong()),
                        shape = ContinuousCornerShape(FluidRadius.Small),
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
            Text(
                text = s.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = s.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (s.subtitle.isNotEmpty()) {
                    Text(
                        text = s.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
