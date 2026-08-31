package dev.antigravity.fluidtransit.spike.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory

/** Cosa la mappa deve mostrare. Tutto ciò che la UI può cambiare sta qui. */
data class MapOptions(
    val mode: MapCatalog.MapMode = MapCatalog.MapMode.MINIMAL,
    val buildings3d: Boolean = false,
    /** Inclinazione della camera in gradi. 0 è la mappa vista dall'alto. */
    val pitch: Double = 0.0,
    val rotationEnabled: Boolean = true,
)

/**
 * Una richiesta di spostare la camera.
 *
 * Il `nonce` c'è perché la stessa destinazione può essere richiesta due volte
 * di fila — premere due volte "Firenze" dopo aver trascinato la mappa deve
 * riportarci lì — e senza un valore che cambia l'effetto non si riattiverebbe.
 * Serve tale e quale in Fase 3 per "centra sulla fermata".
 */
data class MapFocus(val target: LatLng, val zoom: Double, val nonce: Int)

/**
 * La mappa.
 *
 * ⚠️ **BANCO DI PROVA, NON FUNZIONALITÀ.** Regge le modalità abbastanza da
 * poterle guardare, e nient'altro. Non ci sono la posizione dell'utente, le
 * fermate, le linee, i bus, la gestione degli errori di rete, il
 * comportamento offline, il ripristino dello stato: tutto questo è Fase 3.
 *
 * Il ciclo di vita e la forma delle opzioni si portano avanti; le scelte su
 * cosa la mappa debba mostrare e come vada guidata no — vanno chieste.
 *
 * Il ciclo di vita della `MapView` è manuale perché `MapView` è una View di
 * Android e non sa niente di Compose: saltarne anche solo `onStop` lascia il
 * renderer e il thread delle tile in funzione con la app in secondo piano,
 * che su una app di trasporto — dove si sta a lungo con lo schermo spento in
 * tasca — si sente sulla batteria.
 */
@Composable
fun FluidMapView(
    options: MapOptions,
    modifier: Modifier = Modifier,
    initialTarget: LatLng = FIRENZE,
    initialZoom: Double = 14.0,
    focus: MapFocus? = null,
    onStyleLoaded: (Style) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember { MapView(context).apply { onCreate(null) } }
    val mapHolder = remember { arrayOfNulls<MapLibreMap>(1) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    // Lo stile si ricarica solo quando cambia modalità. La camera sopravvive
    // al cambio: MapLibre non la tocca, ed è ciò che fa sembrare il passaggio
    // fra stradale e satellite un cambio di pelle e non un salto altrove.
    LaunchedEffect(options.mode) {
        val map = awaitMap(mapView, mapHolder)
        map.uiSettings.isTiltGesturesEnabled = true
        map.uiSettings.isRotateGesturesEnabled = options.rotationEnabled
        if (map.cameraPosition.zoom < 1.0) {
            map.cameraPosition = CameraPosition.Builder()
                .target(initialTarget)
                .zoom(initialZoom)
                .build()
        }
        val builder = MapCatalog.styleUri(options.mode)
            ?.let { Style.Builder().fromUri(it) }
            ?: Style.Builder().fromJson(MapCatalog.styleJson(options.mode))
        map.setStyle(builder) { style ->
            applyBuildings3d(style, options.buildings3d)
            onStyleLoaded(style)
        }
    }

    LaunchedEffect(options.buildings3d) {
        val map = awaitMap(mapView, mapHolder)
        map.style?.let { applyBuildings3d(it, options.buildings3d) }
    }

    LaunchedEffect(options.pitch) {
        val map = awaitMap(mapView, mapHolder)
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(map.cameraPosition).tilt(options.pitch).build()
            ),
            300,
        )
    }

    LaunchedEffect(options.rotationEnabled) {
        awaitMap(mapView, mapHolder).uiSettings.isRotateGesturesEnabled = options.rotationEnabled
    }

    LaunchedEffect(focus) {
        if (focus == null) return@LaunchedEffect
        val map = awaitMap(mapView, mapHolder)
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(map.cameraPosition)
                    .target(focus.target)
                    .zoom(focus.zoom)
                    .build()
            ),
            600,
        )
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/**
 * `getMapAsync` consegna la mappa una volta sola e su callback; da una
 * coroutine serve poterla aspettare più volte senza registrarne una nuova
 * ogni volta.
 */
private suspend fun awaitMap(mapView: MapView, holder: Array<MapLibreMap?>): MapLibreMap {
    holder[0]?.let { return it }
    return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        mapView.getMapAsync { map ->
            holder[0] = map
            if (continuation.isActive) continuation.resumeWith(Result.success(map))
        }
    }
}

/**
 * Accende o spegne gli edifici in volume.
 *
 * Gli stili pronti di OpenFreeMap un layer estruso ce l'hanno già: va acceso,
 * non aggiunto. Nella modalità ibrida invece la foto aerea non ne ha uno, ma
 * la sorgente vettoriale c'è, quindi il layer lo creiamo noi. Nel satellite
 * puro non c'è vettoriale affatto e non si può fare niente: l'interruttore
 * resta senza effetto, e la UI lo dice invece di lasciar credere che sia
 * rotto.
 *
 * ⚠️ Il ramo dell'ibrida esiste solo perché questo è un banco di prova.
 * **Nell'app quella combinazione non va offerta**: i volumi coprono la foto
 * aerea e non abbiamo texture da metterci sopra. Il codice qui sotto la
 * rende soltanto meno brutta abbassando l'opacità, che è una toppa, non una
 * soluzione.
 */
private fun applyBuildings3d(style: Style, enabled: Boolean) {
    val visibility = PropertyFactory.visibility(if (enabled) Property.VISIBLE else Property.NONE)

    style.getLayer(MapCatalog.OFM_BUILDING_3D_LAYER)?.let {
        it.setProperties(visibility)
        return
    }
    style.getLayer(MapCatalog.OWN_BUILDING_3D_LAYER)?.let {
        it.setProperties(visibility)
        return
    }
    if (!enabled) return
    if (style.getSource(MapCatalog.VECTOR_SOURCE) == null) return

    // Sopra una foto aerea i volumi vanno tenuti trasparenti. Pieni coprono
    // l'ortofoto e il risultato è il peggiore dei due mondi: si perde la foto
    // e in cambio si ottengono scatoloni di un colore inventato, perché senza
    // fotogrammetria non abbiamo alcuna texture da metterci sopra.
    val overImagery = style.getSource(MapCatalog.RASTER_SOURCE) != null

    style.addLayer(
        FillExtrusionLayer(MapCatalog.OWN_BUILDING_3D_LAYER, MapCatalog.VECTOR_SOURCE).apply {
            sourceLayer = MapCatalog.BUILDING_SOURCE_LAYER
            minZoom = MapCatalog.BUILDING_MIN_ZOOM
            setProperties(
                // `render_height` è calcolato da OpenFreeMap dalle altezze OSM
                // e, dove mancano, dal numero di piani. In centro è affidabile,
                // in periferia molti edifici finiscono a un'altezza di comodo:
                // il 3D è una lettura del territorio, non un rilievo.
                PropertyFactory.fillExtrusionHeight(Expression.get("render_height")),
                PropertyFactory.fillExtrusionBase(Expression.get("render_min_height")),
                PropertyFactory.fillExtrusionColor(
                    android.graphics.Color.parseColor(if (overImagery) "#C9BFAF" else "#D8CFC2")
                ),
                PropertyFactory.fillExtrusionOpacity(if (overImagery) 0.35f else 0.85f),
            )
        }
    )
}

val FIRENZE = LatLng(43.7731, 11.2560)
