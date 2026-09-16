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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
        idx to app.departureBoards.board(idx, limit = 3).collectAsStateWithLifecycle()
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
                androidx.compose.foundation.layout.Spacer(
                    androidx.compose.ui.Modifier
                        .height(12.dp),
                )
                dev.antigravity.fluidengine.ui.fluid.FluidButton(
                    text = "Apri la mappa",
                    onClick = { onOpenOnMap(MapIntent.Home) },
                    style = dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle.Filled,
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
                                // Una stella che punta a una fermata sparita
                                // dagli orari di oggi restava a "Leggo gli
                                // orari…" per sempre: il caso c'e' — succede
                                // a ogni cambio d'orario — ma la riga non lo
                                // sapeva distinguere dall'attesa vera.
                                reader != null && idx == null ->
                                    DepartureText.empty(
                                        DepartureText.Trouble.FERMATA_SCONOSCIUTA,
                                    ).short
                                board == null || board.computedAtEpoch == 0L -> "Leggo gli orari…"
                                board.rows.isEmpty() ->
                                    DepartureText.empty(DepartureText.trouble(board))
                                        .let { "${it.title} · ${it.short}" }
                                // I passaggi veri stanno nelle righe qui
                                // sotto, con la pastiglia della linea, i
                                // minuti grandi e la loro provenienza: qui
                                // sopra non serve ripeterla una seconda
                                // volta per tutta la fermata.
                                else -> ""
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
                        // I passaggi, con la stessa riga di tutta l'app.
                        //
                        // Erano una stringa sola dentro il sottotitolo — "6
                        // fra 1 min  ·  C4 fra 2 min" — su una scheda che per
                        // il resto restava vuota: la linea non aveva la sua
                        // pastiglia, i minuti non avevano il loro posto, e la
                        // differenza fra un numero che viene dal bus e uno di
                        // tabella si leggeva una volta sola per tutta la
                        // fermata invece che riga per riga.
                        if (board != null && board.rows.isNotEmpty()) {
                            for (d in board.rows) {
                                dev.antigravity.fluidengine.ui.theme.FluidListDivider()
                                dev.antigravity.fluidtransit.ui.common.DepartureRowUi(
                                    row = d,
                                    nowEpoch = board.computedAtEpoch,
                                    modifier = Modifier
                                        .clickable(
                                            interactionSource = remember {
                                                MutableInteractionSource()
                                            },
                                            indication = null,
                                            role = androidx.compose.ui.semantics.Role.Button,
                                            onClickLabel = "Apri la fermata ${s.name}",
                                            onClick = {
                                                onOpenOnMap(MapIntent.Stop(s.idHashHex, s.name))
                                            },
                                        )
                                        .padding(horizontal = 16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (routes.isNotEmpty()) {
            item { FluidSectionTitle(eyebrow = "Linee", title = "Le tue linee") }
            item {
                FluidListGroup {
                    for (r in routes) {
                        // Il nome della linea, non il suo numero ripetuto.
                        //
                        // La riga diceva "Linea 17" con accanto un pallino
                        // colorato muto: il colore senza il numero e il
                        // numero senza il colore. Adesso il numero sta nella
                        // sua pastiglia — come in tutto il resto dell'app — e
                        // il titolo puo' dire dove va quella linea, che e'
                        // l'unica cosa che uno non sa gia' guardando la
                        // pastiglia.
                        val nomeLungo = remember(r.idHashHex, reader) {
                            val h = r.idHashHex.toULongOrNull(16)?.toLong()
                            val idx = h?.let { reader?.findRouteByIdHash(it) } ?: -1
                            if (idx >= 0) reader?.routeLongName(idx)?.ifEmpty { null } else null
                        }
                        FluidListRow(
                            title = nomeLungo ?: "Linea ${r.shortName}",
                            subtitle = "La tratta sulla mappa",
                            leading = {
                                dev.antigravity.fluidtransit.ui.map.RoutePill(
                                    text = r.shortName,
                                    colorRgb = r.colorRgb,
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

        // Cosa manca, e come si aggiunge.
        //
        // Con una sola fermata stellata questa scheda era per tre quarti
        // vuota: le sezioni "Posti" e "Linee" semplicemente non comparivano,
        // e niente diceva che esistessero. Lo spiegone completo c'e' solo
        // quando non c'e' NIENTE, cioe' l'unico momento in cui non serve
        // piu': chi ha gia' salvato qualcosa ha capito il meccanismo, ma non
        // per forza sa che vale anche per i luoghi e per le linee.
        val mancanti = buildList {
            if (places.isEmpty()) {
                add("Un posto" to "Cercalo e usa Salva: casa, lavoro, la palestra")
            }
            if (stops.isEmpty()) {
                add("Una fermata" to "La stella nella scheda della fermata")
            }
            if (routes.isEmpty()) {
                add("Una linea" to "La stella nella scheda della linea")
            }
        }
        if (mancanti.isNotEmpty() && (places.isNotEmpty() || stops.isNotEmpty() ||
                routes.isNotEmpty())
        ) {
            item { FluidSectionTitle(eyebrow = "Altro", title = "Puoi salvare anche") }
            item {
                FluidListGroup {
                    for ((i, m) in mancanti.withIndex()) {
                        if (i > 0) dev.antigravity.fluidengine.ui.theme.FluidListDivider()
                        FluidListRow(title = m.first, subtitle = m.second)
                    }
                }
            }
        }
    }
}
