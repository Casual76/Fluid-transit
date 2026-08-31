package dev.antigravity.fluidtransit.ui.map

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.VectorSource

/** Cosa disegnare: la selezione dei chip Tutti/Urbani/Extraurbani. */
enum class CategoryFilter { ALL, URBAN, EXTRA }

/** Come la camera segue l'utente. */
enum class FollowMode { FREE, FOLLOW, COMPASS }

class StopTap(val idHashHex: String, val name: String)

/**
 * Il ponte fra Compose e MapLibre: la vista si crea una volta, il controller
 * riceve i cambi di stato (modalita', filtro, follow) e li applica alla
 * mappa. Lo stile si ricarica solo quando cambia davvero (modalita' o tema):
 * tutto il resto e' proprieta' sui layer gia' montati.
 */
class TransitMapController(private val context: Context) {

    internal var map: MapLibreMap? = null
    private var currentStyleKey: String? = null
    private var overlayUrl: String? = null
    private var filter = CategoryFilter.ALL
    private var darkTheme = false
    private var locationEnabled = false
    var onStopTap: ((StopTap) -> Unit)? = null
    var onGesture: (() -> Unit)? = null

    /**
     * L'ultimo stato chiesto dalla UI. `getMapAsync` consegna la mappa dopo
     * che il primo `apply` e' gia' passato: senza questa memoria il primo
     * stile non arriverebbe mai e la mappa resterebbe un rettangolo vuoto
     * finche' l'utente non cambia qualcosa — trovato cosi' sul device.
     */
    private class Desired(
        val mode: MapCatalog.MapMode,
        val dark: Boolean,
        val overlayUrl: String?,
        val filter: CategoryFilter,
        val locationEnabled: Boolean,
        val follow: FollowMode,
    )

    private var desired: Desired? = null

    fun bind(map: MapLibreMap) {
        this.map = map
        desired?.let { d ->
            apply(d.mode, d.dark, d.overlayUrl, d.filter, d.locationEnabled, d.follow)
        }
        map.uiSettings.isRotateGesturesEnabled = true
        map.uiSettings.isTiltGesturesEnabled = true
        // Il logo/attribution nativi restano: obbligo di licenza. Margine per
        // non finire sotto la tab bar ci pensa la UI sopra.
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                onGesture?.invoke()
            }
        }
        map.addOnMapClickListener { point ->
            val m = this.map ?: return@addOnMapClickListener false
            val screen = m.projection.toScreenLocation(point)
            val hits = m.queryRenderedFeatures(screen, MapCatalog.LAYER_FERMATE)
            val f = hits.firstOrNull() ?: return@addOnMapClickListener false
            val hash = f.getStringProperty("h") ?: return@addOnMapClickListener false
            onStopTap?.invoke(StopTap(hash, f.getStringProperty("n") ?: ""))
            true
        }
    }

    fun apply(
        mode: MapCatalog.MapMode,
        dark: Boolean,
        overlayUrl: String?,
        filter: CategoryFilter,
        locationEnabled: Boolean,
        follow: FollowMode,
    ) {
        desired = Desired(mode, dark, overlayUrl, filter, locationEnabled, follow)
        val m = map ?: return
        this.overlayUrl = overlayUrl
        this.filter = filter
        this.darkTheme = dark
        this.locationEnabled = locationEnabled
        val key = "$mode|$dark"
        if (key != currentStyleKey) {
            currentStyleKey = key
            val builder = MapCatalog.styleUri(mode, dark)
                ?.let { Style.Builder().fromUri(it) }
                ?: Style.Builder().fromJson(MapCatalog.styleJson(mode))
            m.setStyle(builder) { style ->
                hideBasemapTransitPois(style)
                addOverlay(style)
                applyFilter(style)
                enableLocationIfAllowed(style)
                applyFollow(follow)
            }
        } else {
            m.getStyle { style ->
                // L'overlay puo' arrivare dopo lo stile (l'indice si scarica
                // in sottofondo): l'aggiunta e' idempotente.
                addOverlay(style)
                applyFilter(style)
                enableLocationIfAllowed(style)
                applyFollow(follow)
            }
        }
    }

    /**
     * I POI-fermata della basemap si spengono: i nostri pallini sono legati
     * ai dati veri del bundle, i loro sono nodi OSM con id e posizioni
     * diverse — farli convivere significa doppi segnaposti sfalsati.
     * Il filtro si aggiunge a quello esistente del layer; se lo schema dello
     * stile cambiasse, il fallimento e' silenzioso e innocuo.
     */
    private fun hideBasemapTransitPois(style: Style) {
        for (layer in style.layers) {
            if (layer !is SymbolLayer) continue
            runCatching {
                if (layer.sourceLayer != "poi") return@runCatching
                val exclude = Expression.not(
                    Expression.`in`(
                        Expression.get("subclass"),
                        Expression.literal(arrayOf<Any>("bus_stop", "bus_station", "tram_stop")),
                    ),
                )
                val existing = layer.filter
                layer.setFilter(if (existing == null) exclude else Expression.all(existing, exclude))
            }
        }
    }

    private fun addOverlay(style: Style) {
        val url = overlayUrl ?: return
        if (style.getSource(MapCatalog.OVERLAY_SOURCE) != null) return
        style.addSource(VectorSource(MapCatalog.OVERLAY_SOURCE, "pmtiles://$url"))

        // Le tratte vanno sotto le etichette della basemap: si infilano sotto
        // il primo layer di simboli, cosi' i nomi delle strade restano sopra.
        val firstSymbol = style.layers.firstOrNull { it is SymbolLayer }?.id

        val linee = LineLayer(MapCatalog.LAYER_LINEE, MapCatalog.OVERLAY_SOURCE).apply {
            sourceLayer = "linee"
            minZoom = MapCatalog.LINEE_MIN_ZOOM
            setProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get("c"))),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(12.5f, 1.1f),
                        Expression.stop(14f, 2.2f),
                        Expression.stop(16f, 3.6f),
                        Expression.stop(18f, 6f),
                    ),
                ),
                // La transizione fluida chiesta: le tratte sfumano dentro.
                PropertyFactory.lineOpacity(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(MapCatalog.LINEE_MIN_ZOOM, 0f),
                        Expression.stop(13.2f, 0.85f),
                    ),
                ),
            )
        }
        if (firstSymbol != null) style.addLayerBelow(linee, firstSymbol) else style.addLayer(linee)

        val fermate = CircleLayer(MapCatalog.LAYER_FERMATE, MapCatalog.OVERLAY_SOURCE).apply {
            sourceLayer = "fermate"
            minZoom = MapCatalog.FERMATE_MIN_ZOOM
            setProperties(
                PropertyFactory.circleColor(if (darkTheme) "#1E1E24" else "#FFFFFF"),
                PropertyFactory.circleStrokeColor(if (darkTheme) "#B9B9C6" else "#4A4A55"),
                PropertyFactory.circleStrokeWidth(1.6f),
                PropertyFactory.circleRadius(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(MapCatalog.FERMATE_MIN_ZOOM, 2.2f),
                        Expression.stop(16f, 4.6f),
                        Expression.stop(18.5f, 7.5f),
                    ),
                ),
                PropertyFactory.circleOpacity(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(MapCatalog.FERMATE_MIN_ZOOM, 0f),
                        Expression.stop(14.4f, 1f),
                    ),
                ),
                PropertyFactory.circleStrokeOpacity(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(MapCatalog.FERMATE_MIN_ZOOM, 0f),
                        Expression.stop(14.4f, 1f),
                    ),
                ),
            )
        }
        style.addLayer(fermate)

        val nomi = SymbolLayer(MapCatalog.LAYER_FERMATE_NOMI, MapCatalog.OVERLAY_SOURCE).apply {
            sourceLayer = "fermate"
            minZoom = MapCatalog.NOMI_MIN_ZOOM
            setProperties(
                PropertyFactory.textField(Expression.get("n")),
                PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                PropertyFactory.textSize(11f),
                PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
                PropertyFactory.textAnchor("top"),
                PropertyFactory.textColor(if (darkTheme) "#E8E8F0" else "#2B2B33"),
                PropertyFactory.textHaloColor(if (darkTheme) "#101014" else "#FFFFFF"),
                PropertyFactory.textHaloWidth(1.4f),
                PropertyFactory.textOptional(true),
            )
        }
        style.addLayer(nomi)
    }

    private fun applyFilter(style: Style) {
        val expr = when (filter) {
            CategoryFilter.ALL -> Expression.literal(true)
            CategoryFilter.URBAN -> Expression.`in`(
                Expression.get("cat"),
                Expression.literal(arrayOf<Any>("u", "ue")),
            )
            CategoryFilter.EXTRA -> Expression.`in`(
                Expression.get("cat"),
                Expression.literal(arrayOf<Any>("e", "ue")),
            )
        }
        (style.getLayer(MapCatalog.LAYER_LINEE) as? LineLayer)?.setFilter(expr)
        (style.getLayer(MapCatalog.LAYER_FERMATE) as? CircleLayer)?.setFilter(expr)
        (style.getLayer(MapCatalog.LAYER_FERMATE_NOMI) as? SymbolLayer)?.setFilter(expr)
    }

    @SuppressLint("MissingPermission") // il chiamante passa locationEnabled solo col permesso
    private fun enableLocationIfAllowed(style: Style) {
        val m = map ?: return
        if (!locationEnabled) return
        val component = m.locationComponent
        if (!component.isLocationComponentActivated) {
            component.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .useDefaultLocationEngine(true)
                    .build(),
            )
        }
        component.isLocationComponentEnabled = true
        component.renderMode = RenderMode.COMPASS
    }

    @SuppressLint("MissingPermission")
    fun applyFollow(follow: FollowMode) {
        val m = map ?: return
        if (!locationEnabled) return
        val component = m.locationComponent
        if (!component.isLocationComponentActivated) return
        when (follow) {
            FollowMode.FREE -> component.cameraMode = CameraMode.NONE
            FollowMode.FOLLOW -> {
                component.cameraMode = CameraMode.TRACKING
                val flat = CameraPosition.Builder(m.cameraPosition).tilt(0.0).bearing(0.0).build()
                m.animateCamera(CameraUpdateFactory.newCameraPosition(flat), 500)
            }
            FollowMode.COMPASS -> {
                // La bussola decisa: heading-up, 3D con inclinazione fissa,
                // zoom ravvicinato. E' l'unico posto dove il 3D si accende.
                component.cameraMode = CameraMode.TRACKING_COMPASS
                val tilted = CameraPosition.Builder(m.cameraPosition)
                    .tilt(MapCatalog.NAV_TILT)
                    .zoom(maxOf(m.cameraPosition.zoom, MapCatalog.NAV_ZOOM))
                    .build()
                m.animateCamera(CameraUpdateFactory.newCameraPosition(tilted), 600)
            }
        }
    }

    fun flyTo(lat: Double, lon: Double, zoom: Double) {
        map?.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).build(),
            ),
            900,
        )
    }
}

/**
 * La MapView dentro Compose, con il ciclo di vita completo — compresi
 * onLowMemory e onSaveInstanceState, che allo spike mancavano.
 *
 * `textureMode` e' acceso: una SurfaceView vive fuori dalla gerarchia che il
 * vetro dell'engine registra, e il glass sopra la mappa campionerebbe il
 * nulla. La TextureView costa qualcosa in piu' e ripaga con il backdrop vero.
 */
@Composable
fun TransitMap(
    controller: TransitMapController,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentController = rememberUpdatedState(controller)
    val holder = remember { arrayOfNulls<MapView>(1) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val options = MapLibreMapOptions.createFromAttributes(context)
                .textureMode(true)
                .camera(
                    CameraPosition.Builder()
                        .target(LatLng(MapCatalog.HOME_LAT, MapCatalog.HOME_LON))
                        .zoom(MapCatalog.HOME_ZOOM)
                        .build(),
                )
            MapView(context, options).apply {
                holder[0] = this
                onCreate(null)
                getMapAsync { map -> currentController.value.bind(map) }
            }
        },
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val view = holder[0] ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                Lifecycle.Event.ON_DESTROY -> view.onDestroy()
                else -> Unit
            }
        }
        val memoryCallbacks = object : android.content.ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit
            override fun onLowMemory() {
                holder[0]?.onLowMemory()
            }

            override fun onTrimMemory(level: Int) {
                if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    holder[0]?.onLowMemory()
                }
            }
        }
        val appContext = holder[0]?.context?.applicationContext
        appContext?.registerComponentCallbacks(memoryCallbacks)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            appContext?.unregisterComponentCallbacks(memoryCallbacks)
            holder[0]?.onDestroy()
            holder[0] = null
        }
    }
}
