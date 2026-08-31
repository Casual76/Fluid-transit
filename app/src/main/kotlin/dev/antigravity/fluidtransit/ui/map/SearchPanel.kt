package dev.antigravity.fluidtransit.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider

/**
 * Il pannello di ricerca: fermate e linee dal bundle, offline.
 *
 * In Fase 5 imparera' luoghi, indirizzi e itinerari senza cambiare forma.
 * Copre la mappa per intero — su un pannello cosi' il vetro non avrebbe
 * niente di utile da rifrangere e la leggibilita' della lista vince.
 */
@Composable
fun SearchPanel(
    index: SearchIndex?,
    initialQuery: String,
    onQueryChange: (String) -> Unit,
    onPick: (SearchIndex.Hit) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val results = remember(initialQuery, index) { index?.search(initialQuery).orEmpty() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Chiudi la ricerca",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                TextField(
                    value = initialQuery,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Fermata o linea…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus),
                )
                if (initialQuery.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Svuota",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            FluidListDivider()

            when {
                index == null -> FluidEmptyState(
                    title = "Un attimo",
                    detail = "Sto preparando l'indice di ricerca.",
                    modifier = Modifier.padding(20.dp),
                )

                initialQuery.length < 2 -> FluidEmptyState(
                    title = "Cerca in tutta la Toscana",
                    detail = "Scrivi il nome di una fermata o il numero di una linea.",
                    modifier = Modifier.padding(20.dp),
                )

                results.isEmpty() -> FluidEmptyState(
                    title = "Niente con questo nome",
                    detail = "Prova con meno lettere, o senza il nome della via.",
                    modifier = Modifier.padding(20.dp),
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(results.size) { i ->
                        val hit = results[i]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(hit) }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            when (hit) {
                                is SearchIndex.Hit.Route -> {
                                    Text(
                                        text = hit.title,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier
                                            .widthIn(min = 44.dp)
                                            .background(
                                                color = Color(0xFF000000 or hit.colorRgb.toLong()),
                                                shape = ContinuousCornerShape(FluidRadius.Small),
                                            )
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                    )
                                    Text(
                                        text = hit.destination,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                is SearchIndex.Hit.Stop -> {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = hit.title,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "Fermata",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        if (i < results.size - 1) FluidListDivider()
                    }
                }
            }
        }
    }
}

