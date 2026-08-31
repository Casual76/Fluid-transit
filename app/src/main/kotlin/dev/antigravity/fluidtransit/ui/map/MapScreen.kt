package dev.antigravity.fluidtransit.ui.map

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocationSearching
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.luminance
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.navigationBarsPadding
import java.time.Instant
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * La schermata mappa: nessun titolo, mappa a tutto schermo, chrome in vetro
 * sopra — barra di ricerca col microfono, chip dei filtri, cambio livello in
 * basso a sinistra sopra l'attribuzione, tasto posizione in basso a destra.
 * Tutto come da spec decisa con l'utente il 31/08.
 */
/** Cosa mostra il pannello dal basso. Uno stato solo: il morphing e' un cambio di contenuto. */
private sealed interface Panel {
    class Stop(val tap: StopTap) : Panel
    class RouteMini(val routeIndex: Int) : Panel
    class RouteFull(val routeIndex: Int) : Panel
}

@Composable
fun MapScreen(
    app: FluidTransitApp,
    backdrop: GlassBackdropState,
    onTabBarHidden: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    // Lo scuro della mappa segue il tema DELL'APP, non quello di sistema:
    // chi forza "Scuro" dalle Impostazioni deve vedere anche la mappa scura.
    // La luminanza dello sfondo Material e' la verita' gia' risolta da
    // FluidTheme, qualunque sia la combinazione di impostazioni.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bundleState by app.bundleManager.state.collectAsStateWithLifecycle()
    val ready = bundleState as? BundleState.Ready

    var mode by rememberSaveable { mutableStateOf(MapCatalog.MapMode.STREETS) }
    var filter by rememberSaveable { mutableStateOf(CategoryFilter.ALL) }
    var follow by rememberSaveable { mutableStateOf(FollowMode.FREE) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var routeDirection by remember { mutableStateOf(0) }

    // La modalita' linea prende il posto della tab bar: la shell lo sa da qui.
    LaunchedEffect(panel) {
        onTabBarHidden(panel is Panel.RouteMini || panel is Panel.RouteFull)
    }

    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val controller = remember { TransitMapController(context) }
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        locationGranted = granted
        if (granted) follow = FollowMode.FOLLOW
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                query = spoken
                searchOpen = true
            }
        }
    }

    // L'indice di ricerca si costruisce una volta per bundle, fuori dal main.
    val searchIndex by produceState<SearchIndex?>(initialValue = null, ready?.buildId) {
        val reader = ready?.reader ?: return@produceState
        value = withContext(Dispatchers.Default) { SearchIndex.build(reader) }
    }

    fun exitRouteMode() {
        controller.exitRouteMode()
        panel = null
    }

    controller.onStopTap = { tap ->
        // Aprire una fermata chiude la modalita' linea: il pannello torna scheda fermata.
        controller.exitRouteMode()
        panel = Panel.Stop(tap)
    }
    controller.onEmptyTap = {
        // Il tocco a vuoto e' una delle tre uscite decise.
        exitRouteMode()
    }
    controller.onGesture = { if (follow != FollowMode.FREE) follow = FollowMode.FREE }

    // Il tap sulla pillola di una linea: la mappa si pulisce (resta la
    // tratta accesa e le SUE fermate), la camera inquadra tutto, e il
    // pannello si trasforma nello stato mini della scheda linea.
    // In Fase 4 la stessa meccanica rispondera' al tap su un bus live.
    fun showRoute(routeIndex: Int) {
        val reader = ready?.reader ?: return
        follow = FollowMode.FREE
        routeDirection = 0
        panel = Panel.RouteMini(routeIndex)
        scope.launch(Dispatchers.Default) {
            var minLat = 90.0
            var maxLat = -90.0
            var minLon = 180.0
            var maxLon = -180.0
            val hashes = LinkedHashSet<String>()
            for (p in reader.patternsOfRoute(routeIndex)) {
                val n = reader.patternStopCount(p)
                for (i in 0 until n) {
                    val s = reader.patternStop(p, i)
                    hashes.add(java.lang.Long.toHexString(reader.stopIdHash(s)))
                    val lat = reader.stopLat(s)
                    val lon = reader.stopLon(s)
                    if (lat < minLat) minLat = lat
                    if (lat > maxLat) maxLat = lat
                    if (lon < minLon) minLon = lon
                    if (lon > maxLon) maxLon = lon
                }
            }
            if (minLat > maxLat) return@launch
            val rh = java.lang.Long.toHexString(reader.routeIdHash(routeIndex))
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                controller.enterRouteMode(rh, hashes.toTypedArray())
                controller.flyToBounds(minLat, minLon, maxLat, maxLon)
            }
        }
    }

    // I dati della scheda linea, calcolati quando serve.
    val currentRouteIndex = when (val p = panel) {
        is Panel.RouteMini -> p.routeIndex
        is Panel.RouteFull -> p.routeIndex
        else -> null
    }
    val routeInfo by produceState<RouteInfo?>(initialValue = null, currentRouteIndex, ready?.buildId) {
        val reader = ready?.reader
        val idx = currentRouteIndex
        if (reader == null || idx == null) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.Default) { RouteInfo.build(reader, idx, Instant.now()) }
    }

    // Le ricerche recenti e i suggerimenti del pannello.
    val recentStore = remember { RecentSearches(context) }
    var recentsVersion by remember { mutableStateOf(0) }
    val recents = remember(recentsVersion) { recentStore.load() }
    val nearby by produceState(initialValue = emptyList<Suggestion>(), searchOpen, ready?.buildId) {
        val reader = ready?.reader
        if (!searchOpen || reader == null) {
            value = emptyList()
            return@produceState
        }
        val center = controller.cameraCenter() ?: return@produceState
        value = withContext(Dispatchers.Default) {
            reader.stopsNear(center.first, center.second, 700.0)
                .sortedBy {
                    dev.antigravity.fluidtransit.routing.BundleReader.haversine(
                        center.first, center.second, reader.stopLat(it), reader.stopLon(it),
                    )
                }
                .take(5)
                .map { s ->
                    Suggestion(
                        kind = "stop",
                        key = java.lang.Long.toHexString(reader.stopIdHash(s)),
                        title = reader.stopName(s),
                        subtitle = "Fermata",
                        colorRgb = 0,
                        lat = reader.stopLat(s),
                        lon = reader.stopLon(s),
                    )
                }
        }
    }

    fun pick(s: Suggestion) {
        searchOpen = false
        query = ""
        follow = FollowMode.FREE
        recentStore.add(
            RecentSearches.Entry(s.kind, s.key, s.title, s.subtitle, s.colorRgb, s.lat, s.lon),
        )
        recentsVersion++
        if (s.kind == "stop") {
            controller.exitRouteMode()
            controller.flyTo(s.lat, s.lon, 16.2)
            panel = Panel.Stop(StopTap(s.key, s.title))
        } else {
            // La chiave e' l'hash del route_id: stabile fra i bundle, al
            // contrario dell'indice che ogni notte cambia.
            val reader = ready?.reader
            val hash = s.key.toULongOrNull(16)?.toLong()
            if (reader != null && hash != null) {
                val idx = reader.findRouteByIdHash(hash)
                if (idx >= 0) showRoute(idx)
            }
        }
    }

    // Ogni cambio di stato scende nella mappa da un punto solo.
    LaunchedEffect(mode, dark, ready?.overlayUrl, filter, locationGranted, follow) {
        controller.apply(
            mode = mode,
            dark = dark,
            overlayUrl = ready?.overlayUrl,
            filter = filter,
            locationEnabled = locationGranted,
            follow = follow,
        )
    }

    // All'avvio, col permesso gia' in tasca, la mappa parte su di te: e' il
    // comportamento da app di navigazione che la spec chiede.
    LaunchedEffect(Unit) {
        if (locationGranted) follow = FollowMode.FOLLOW
    }

    // Logo e attribuzione MapLibre sopra la tab bar, non sotto.
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(Unit) {
        controller.chromeBottomPx = with(density) {
            (FluidTabBarDefaults.ContentInset + 6.dp).toPx()
        }.toInt()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // La mappa e' la sorgente del vetro: tutto il chrome la rifrange.
        // La camera sopravvive a rotazione e ritorno dall'ultima schermata.
        val savedCamera = rememberSaveable { mutableStateOf<DoubleArray?>(null) }
        controller.onCameraIdle = { savedCamera.value = it }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassBackdropSource(backdrop),
        ) {
            TransitMap(
                controller = controller,
                modifier = Modifier.fillMaxSize(),
                initialCamera = savedCamera.value,
            )
        }

        // --- chrome in alto: la barra che diventa pannello, e i filtri ---
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 14.dp)
                .padding(top = 8.dp),
        ) {
            if (searchOpen) {
                BackHandler {
                    searchOpen = false
                    query = ""
                }
            }
            val queryResults = remember(query, searchIndex) {
                if (query.length < 2) {
                    emptyList()
                } else {
                    searchIndex?.search(query).orEmpty().map { hit ->
                        when (hit) {
                            is SearchIndex.Hit.Stop -> Suggestion(
                                kind = "stop",
                                key = ready?.reader
                                    ?.let { java.lang.Long.toHexString(it.stopIdHash(hit.stopIndex)) }
                                    ?: "",
                                title = hit.title,
                                subtitle = "Fermata",
                                colorRgb = 0,
                                lat = hit.lat,
                                lon = hit.lon,
                            )

                            is SearchIndex.Hit.Route -> Suggestion(
                                kind = "route",
                                key = ready?.reader
                                    ?.let { java.lang.Long.toHexString(it.routeIdHash(hit.routeIndex)) }
                                    ?: "",
                                title = hit.title,
                                subtitle = hit.destination,
                                colorRgb = hit.colorRgb,
                                lat = hit.lat,
                                lon = hit.lon,
                            )
                        }
                    }
                }
            }
            SearchGlass(
                backdrop = backdrop,
                open = searchOpen,
                query = query,
                results = queryResults,
                recents = recents.filter { it.kind == "stop" }.map { it.toSuggestion() },
                nearby = nearby,
                recentLines = recents.filter { it.kind == "route" }.map { it.toSuggestion() },
                onOpen = { searchOpen = true },
                onClose = {
                    searchOpen = false
                    query = ""
                },
                onQueryChange = { query = it },
                onMic = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Che fermata o linea cerchi?")
                    }
                    runCatching { micLauncher.launch(intent) }
                },
                onPick = ::pick,
            )
            androidx.compose.animation.AnimatedVisibility(visible = !searchOpen) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    CategoryChipsRow(
                        backdrop = backdrop,
                        selected = filter,
                        onSelect = { filter = it },
                    )
                }
            }
        }

        // --- angoli bassi: livelli a sinistra, posizione a destra --------
        val bottomInset = FluidTabBarDefaults.ContentInset + 14.dp
        MapCornerButton(
            icon = Icons.Rounded.Layers,
            contentDescription = if (mode == MapCatalog.MapMode.STREETS) {
                "Passa alla vista ibrida"
            } else {
                "Passa alla vista stradale"
            },
            backdrop = backdrop,
            onClick = {
                mode = if (mode == MapCatalog.MapMode.STREETS) {
                    MapCatalog.MapMode.HYBRID
                } else {
                    MapCatalog.MapMode.STREETS
                }
            },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, bottom = bottomInset),
        )
        // In bussola l'icona del tasto GIRA col nord: e' l'unica bussola
        // dell'app (quella di MapLibre in alto e' spenta).
        var bearing by remember { mutableStateOf(0f) }
        controller.onBearing = { bearing = it.toFloat() }
        MapCornerButton(
            icon = when (follow) {
                FollowMode.FREE -> Icons.Rounded.LocationSearching
                FollowMode.FOLLOW -> Icons.Rounded.MyLocation
                FollowMode.COMPASS -> Icons.Rounded.Explore
            },
            contentDescription = when (follow) {
                FollowMode.FREE -> "Centrati sulla mia posizione"
                FollowMode.FOLLOW -> "Passa alla bussola"
                FollowMode.COMPASS -> "Torna alla vista normale"
            },
            backdrop = backdrop,
            iconRotation = { if (follow == FollowMode.COMPASS) -bearing else 0f },
            onClick = {
                if (!locationGranted) {
                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                } else {
                    follow = when (follow) {
                        FollowMode.FREE -> FollowMode.FOLLOW
                        FollowMode.FOLLOW -> FollowMode.COMPASS
                        FollowMode.COMPASS -> FollowMode.FOLLOW
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = bottomInset),
        )

        // --- l'unico pannello dal basso: fermata, linea, o linea ridotta ---
        // Il passaggio fra i tre e' un morphing della stessa superficie di
        // vetro; in modalita' linea il pannello prende il posto della tab bar.
        val reader = ready?.reader
        val inRoutePanel = panel is Panel.RouteMini || panel is Panel.RouteFull
        val bottomPad by androidx.compose.animation.core.animateDpAsState(
            targetValue = if (inRoutePanel) 10.dp else FluidTabBarDefaults.ContentInset + 10.dp,
            label = "panelBottomPad",
        )
        androidx.compose.animation.AnimatedVisibility(
            visible = panel != null && reader != null,
            enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it / 2 }) +
                androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it / 2 }) +
                androidx.compose.animation.fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        ) {
            val p = panel
            if (p != null && reader != null) {
                BackHandler {
                    when (p) {
                        is Panel.RouteFull -> panel = Panel.RouteMini(p.routeIndex)
                        else -> exitRouteMode()
                    }
                }
                BottomGlassPanel(
                    backdrop = backdrop,
                    wholeSurfaceDrag = p is Panel.RouteMini,
                    showGrabber = p !is Panel.RouteMini,
                    onDragDismiss = {
                        when (p) {
                            is Panel.RouteFull -> panel = Panel.RouteMini(p.routeIndex)
                            else -> exitRouteMode()
                        }
                    },
                    modifier = Modifier
                        .padding(horizontal = 14.dp)
                        .padding(bottom = bottomPad),
                ) {
                    androidx.compose.animation.AnimatedContent(
                        targetState = p,
                        transitionSpec = {
                            androidx.compose.animation.fadeIn() togetherWith
                                androidx.compose.animation.fadeOut()
                        },
                        contentKey = { state ->
                            when (state) {
                                is Panel.Stop -> "stop-${state.tap.idHashHex}"
                                is Panel.RouteMini -> "mini-${state.routeIndex}"
                                is Panel.RouteFull -> "full-${state.routeIndex}"
                            }
                        },
                        label = "panelContent",
                    ) { state ->
                        when (state) {
                            is Panel.Stop -> Column {
                                StopPanelContent(
                                    reader = reader,
                                    stopIdHashHex = state.tap.idHashHex,
                                    fallbackName = state.tap.name,
                                    onDismiss = { panel = null },
                                    onRouteTap = ::showRoute,
                                )
                            }

                            is Panel.RouteMini -> Column(
                                modifier = Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClickLabel = "Espandi la scheda della linea",
                                    onClick = { panel = Panel.RouteFull(state.routeIndex) },
                                ),
                            ) {
                                val info = routeInfo
                                if (info != null && info.routeIndex == state.routeIndex) {
                                    RouteMiniContent(info, routeDirection)
                                }
                            }

                            is Panel.RouteFull -> Column {
                                val info = routeInfo
                                if (info != null && info.routeIndex == state.routeIndex) {
                                    RouteFullContent(
                                        info = info,
                                        direction = routeDirection,
                                        onDirectionChange = { routeDirection = it },
                                        onStopTap = { stopRef ->
                                            controller.exitRouteMode()
                                            controller.flyTo(stopRef.lat, stopRef.lon, 16.2)
                                            panel = Panel.Stop(
                                                StopTap(stopRef.idHashHex, stopRef.name),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun RecentSearches.Entry.toSuggestion() =
    Suggestion(kind, key, title, subtitle, colorRgb, lat, lon)
