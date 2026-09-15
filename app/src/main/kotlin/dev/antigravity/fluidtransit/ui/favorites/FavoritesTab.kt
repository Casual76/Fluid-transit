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
import dev.antigravity.fluidtransit.routing.DepartureText
import dev.antigravity.fluidtransit.ui.map.MapIntent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

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

    // I prossimi passaggi delle fermate stellate. Chi mette la stella a una
    // fermata si aspetta di vederci gli orari: fino alla Fase 8 qui c'era
    // scritto solo "Prossimi passaggi sulla mappa", cioe' un rimando.
    //
    // E fino alla Fase 9 c'era un calcolo tutto suo, con un battito da trenta
    // secondi che pero' non chiedeva mai i ritardi al proxy: ricalcolava
    // all'infinito sugli stessi dati fermi. Adesso e' lo stesso tabellone di
    // tutte le altre schermate, quindi gli stessi numeri nello stesso istante.
    val bundleState by app.bundleManager.state.collectAsStateWithLifecycle()
    val reader = (bundleState as? dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState.Ready)
        ?.reader
    // Fermata stellata -> indice nel bundle, per HASH e non per posizione.
    //
    // Prima erano due liste parallele costruite con `mapNotNull`, che pero'
    // salta: bastava una fermata stellata sparita dal feed — succede a ogni
    // cambio d'orario — perche' la seconda lista si accorciasse e ogni riga
    // successiva si prendesse gli orari della fermata dopo. Una fermata che
    // mostra i passaggi di un'altra e' il peggior modo di sbagliare, perche'
    // sembra funzionare.
    val indexByHash = remember(favVersion, localTick, reader) {
        val r = reader ?: return@remember emptyMap<String, Int>()
        stops.mapNotNull { s ->
            s.idHashHex.toULongOrNull(16)?.toLong()
                ?.let { r.findStopByIdHash(it) }
                ?.takeIf { it >= 0 }
                ?.let { s.idHashHex to it }
        }.toMap()
    }
    val boards = indexByHash.values.map { idx ->
        idx to app.departureBoards.board(idx, limit = 2).collectAsStateWithLifecycle()
    }
    val byStop = boards.associate { (idx, state) -> idx to state.value }

    // Tira giu' per aggiornare.
    //
    // I numeri si rinfrescano da soli ogni trenta secondi, quindi questo
    // gesto quasi non cambia niente — ed e' proprio per questo che serve. Una
    // lista di orari che non si lascia tirare sembra ferma, e uno resta li' a
    // chiedersi se stia guardando dati vivi. Qui la risposta e' un gesto.
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val refresh: () -> Unit = {
        if (!refreshing) {
            refreshing = true
            scope.launch {
                runCatching { app.realtime.refreshVehicles() }
                runCatching { app.realtime.refreshDelays() }
                runCatching { app.realtime.refreshPredictions() }
                app.bundleManager.refreshOnForeground()
                // Mezzo secondo di cortesia: un aggiornamento che sparisce
                // prima di essere visto non e' una risposta.
                kotlinx.coroutines.delay(500)
                refreshing = false
            }
        }
    }

    FluidScreen(
        title = "Preferiti",
        isRefreshing = refreshing,
        onRefresh = refresh,
    ) {
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
                        val idx = indexByHash[s.idHashHex]
                        val board = idx?.let { byStop[it] }
                        FluidListRow(
                            // Il nome che vale e' quello del bundle: quello
                            // salvato accanto alla stella e' un ripiego per
                            // quando gli orari non ci sono ancora, e puo'
                            // essere piu' vecchio. Misurato su questo
                            // telefono: la stella diceva "SODERINI" e la
                            // fermata si chiama "SODERINI TORRINO SANTA
                            // ROSA". Due nomi per la stessa cosa, a seconda
                            // di dove la guardi.
                            title = idx?.let { reader?.stopName(it) }?.ifEmpty { null } ?: s.name,
                            // Tre stati distinti, e prima ce n'erano due: la
                            // riga partiva da "Nessun passaggio" e lo diceva
                            // per il primo fotogramma anche quando il bus
                            // stava arrivando. Dire una cosa falsa mentre si
                            // carica e' peggio che non dire niente.
                            subtitle = when {
                                board == null || board.computedAtEpoch == 0L -> "Leggo gli orari…"
                                board.rows.isEmpty() -> "Nessun passaggio nelle prossime due ore"
                                else -> board.rows.joinToString(" · ") {
                                    DepartureText.compact(it, board.computedAtEpoch)
                                } + " · " + DepartureText.boardSource(board)
                            },
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
