package dev.antigravity.fluidtransit.ui.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidSectionTitle
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.ui.map.MapIntent

/**
 * I preferiti, a sezioni come deciso: i tuoi posti, le fermate stellate, le
 * linee stellate. Ogni riga porta sulla mappa — e' la' che vive tutto — e
 * la tenuta premuta offre la rimozione.
 */
@Composable
fun FavoritesTab(
    app: FluidTransitApp,
    onOpenOnMap: (MapIntent) -> Unit,
) {
    val favVersion by app.favorites.version.collectAsStateWithLifecycle()
    var localTick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    val places = remember(localTick) { app.savedPlaces.load() }
    val stops = remember(favVersion, localTick) { app.favorites.stops() }
    val routes = remember(favVersion, localTick) { app.favorites.routes() }

    FluidScreen(title = "Preferiti") {
        if (places.isEmpty() && stops.isEmpty() && routes.isEmpty()) {
            item {
                FluidEmptyState(
                    title = "Niente di salvato, per ora",
                    detail = "La stella nelle schede di fermate e linee, e il tasto " +
                        "Salva su un luogo, portano tutto qui.",
                )
            }
        }

        if (places.isNotEmpty()) {
            item { FluidSectionTitle(eyebrow = "Posti", title = "I tuoi posti") }
            item {
                FluidListGroup {
                    for (p in places) {
                        FluidListRow(
                            title = p.label,
                            subtitle = "Portami qui dalla mappa",
                            leading = {
                                Icon(
                                    imageVector = Icons.Rounded.Place,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            onClick = {
                                onOpenOnMap(MapIntent.Place(p.label, p.lat, p.lon, p.id))
                            },
                            contextActions = {
                                listOf(
                                    FluidContextAction(
                                        label = "Rimuovi",
                                        destructive = true,
                                        onClick = {
                                            app.savedPlaces.remove(p.id)
                                            localTick++
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }

        if (stops.isNotEmpty()) {
            item { FluidSectionTitle(eyebrow = "Fermate", title = "Le tue fermate") }
            item {
                FluidListGroup {
                    for (s in stops) {
                        FluidListRow(
                            title = s.name,
                            subtitle = "Prossimi passaggi sulla mappa",
                            leading = {
                                Icon(
                                    imageVector = Icons.Rounded.Star,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            onClick = { onOpenOnMap(MapIntent.Stop(s.idHashHex, s.name)) },
                            contextActions = {
                                listOf(
                                    FluidContextAction(
                                        label = "Togli dai preferiti",
                                        destructive = true,
                                        onClick = { app.favorites.toggleStop(s.idHashHex, s.name) },
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }

        if (routes.isNotEmpty()) {
            item { FluidSectionTitle(eyebrow = "Linee", title = "Le tue linee") }
            item {
                FluidListGroup {
                    for (r in routes) {
                        FluidListRow(
                            title = "Linea ${r.shortName}",
                            subtitle = "La tratta sulla mappa",
                            leading = {
                                androidx.compose.foundation.layout.Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .background(
                                            color = Color(0xFF000000 or r.colorRgb.toLong()),
                                            shape = CircleShape,
                                        ),
                                )
                            },
                            onClick = { onOpenOnMap(MapIntent.Route(r.idHashHex)) },
                            contextActions = {
                                listOf(
                                    FluidContextAction(
                                        label = "Togli dai preferiti",
                                        destructive = true,
                                        onClick = {
                                            app.favorites.toggleRoute(r.idHashHex, r.shortName, r.colorRgb)
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
