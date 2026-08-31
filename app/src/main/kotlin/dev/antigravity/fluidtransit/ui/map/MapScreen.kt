package dev.antigravity.fluidtransit.ui.map

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * La schermata mappa: nessun titolo, mappa a tutto schermo, chrome in vetro
 * sopra — barra di ricerca col microfono, chip dei filtri, cambio livello in
 * basso a sinistra sopra l'attribuzione, tasto posizione in basso a destra.
 * Tutto come da spec decisa con l'utente il 31/08.
 */
@Composable
fun MapScreen(app: FluidTransitApp, backdrop: GlassBackdropState) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val bundleState by app.bundleManager.state.collectAsStateWithLifecycle()
    val ready = bundleState as? BundleState.Ready

    var mode by rememberSaveable { mutableStateOf(MapCatalog.MapMode.STREETS) }
    var filter by rememberSaveable { mutableStateOf(CategoryFilter.ALL) }
    var follow by rememberSaveable { mutableStateOf(FollowMode.FREE) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedStop by remember { mutableStateOf<StopTap?>(null) }

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

    controller.onStopTap = { tap -> selectedStop = tap }
    controller.onGesture = { if (follow != FollowMode.FREE) follow = FollowMode.FREE }

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

    Box(modifier = Modifier.fillMaxSize()) {
        // La mappa e' la sorgente del vetro: tutto il chrome la rifrange.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassBackdropSource(backdrop),
        ) {
            TransitMap(controller = controller, modifier = Modifier.fillMaxSize())
        }

        // --- chrome in alto: ricerca + filtri ---------------------------
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 14.dp)
                .padding(top = 8.dp),
        ) {
            MapSearchBar(
                backdrop = backdrop,
                onTap = { searchOpen = true },
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
            )
            Spacer(Modifier.height(10.dp))
            CategoryChipsRow(
                backdrop = backdrop,
                selected = filter,
                onSelect = { filter = it },
            )
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
            selected = mode == MapCatalog.MapMode.HYBRID,
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
            selected = follow != FollowMode.FREE,
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

        // --- pannelli sopra tutto ----------------------------------------
        if (searchOpen) {
            BackHandler { searchOpen = false }
            SearchPanel(
                index = searchIndex,
                initialQuery = query,
                onQueryChange = { query = it },
                onPick = { hit ->
                    searchOpen = false
                    follow = FollowMode.FREE
                    when (hit) {
                        is SearchIndex.Hit.Stop -> controller.flyTo(hit.lat, hit.lon, 16.2)
                        is SearchIndex.Hit.Route -> controller.flyTo(hit.lat, hit.lon, 13.2)
                    }
                },
                onClose = { searchOpen = false },
            )
        }

        val reader = ready?.reader
        val tapped = selectedStop
        if (tapped != null && reader != null) {
            StopSheet(
                reader = reader,
                stopIdHashHex = tapped.idHashHex,
                fallbackName = tapped.name,
                onDismiss = { selectedStop = null },
            )
        }
    }
}
