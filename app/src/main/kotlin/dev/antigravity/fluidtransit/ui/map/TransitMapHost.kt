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

/** Il tocco su un bus vivo: le chiavi bastano a risalire a corsa e linea. */
class BusTap(val vehKey: Int, val tripHashHex: String, val routeHashHex: String)

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
    var onBusTap: ((BusTap) -> Unit)? = null
    var onEmptyTap: (() -> Unit)? = null
    var onGesture: (() -> Unit)? = null

    private val busOverlay = BusOverlay()

    /** A camera ferma: lat, lon, zoom, bearing, tilt. Per sopravvivere alla rotazione. */
    var onCameraIdle: ((DoubleArray) -> Unit)? = null

    /** Il bearing continuo, per l'icona-bussola del tasto posizione. */
    var onBearing: ((Double) -> Unit)? = null

    /** Il centro attuale della camera: per le "fermate vicine" senza GPS. */
    fun cameraCenter(): Pair<Double, Double>? =
        map?.cameraPosition?.target?.let { it.latitude to it.longitude }

    /**
     * Quanto logo e attribuzione MapLibre devono alzarsi da fondo schermo
     * per non finire sotto la tab bar. In pixel, dalla UI che conosce i dp.
     */
    var chromeBottomPx: Int = 0
        set(value) {
            field = value
            map?.let { applyOrnamentMargins(it) }
        }

    private fun applyOrnamentMargins(m: MapLibreMap) {
        val side = (8 * context.resources.displayMetrics.density).toInt()
        m.uiSettings.setLogoMargins(side, 0, 0, chromeBottomPx)
        m.uiSettings.setAttributionMargins(side * 12, 0, 0, chromeBottomPx)
    }

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
        applyOrnamentMargins(map)
        // Niente bussola di MapLibre in alto: quando serve, e' l'icona del
        // tasto posizione a fare da bussola — deciso guardando la build.
        map.uiSettings.isCompassEnabled = false
        map.addOnCameraMoveListener { onBearing?.invoke(map.cameraPosition.bearing) }
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
        map.addOnCameraIdleListener {
            val p = map.cameraPosition
            val t = p.target ?: return@addOnCameraIdleListener
            onCameraIdle?.invoke(
                doubleArrayOf(t.latitude, t.longitude, p.zoom, p.bearing, p.tilt),
            )
        }
        map.addOnMapClickListener { point ->
            val m = this.map ?: return@addOnMapClickListener false
            val screen = m.projection.toScreenLocation(point)
            // La hitbox di un pallino da pochi dp e' impossibile da centrare
            // col dito: si interroga un quadrato da ~44 dp intorno al tocco.
            val pad = 22 * context.resources.displayMetrics.density
            val box = android.graphics.RectF(
                screen.x - pad, screen.y - pad, screen.x + pad, screen.y + pad,
            )
            // I bus hanno la precedenza sulle fermate: sono sopra, si
            // muovono, e il dito cerca loro.
            val busHit = m.queryRenderedFeatures(box, MapCatalog.LAYER_BUS).firstOrNull()
            if (busHit != null) {
                val vk = busHit.getNumberProperty("vk")?.toInt()
                if (vk != null) {
                    onBusTap?.invoke(
                        BusTap(
                            vehKey = vk,
                            tripHashHex = busHit.getStringProperty("th") ?: "0",
                            routeHashHex = busHit.getStringProperty("rh") ?: "0",
                        ),
                    )
                    return@addOnMapClickListener true
                }
            }
            val hits = m.queryRenderedFeatures(
                box,
                MapCatalog.LAYER_FERMATE,
                MapCatalog.LAYER_FERMATE_LINEA,
            )
            val f = hits.firstOrNull()
            val hash = f?.getStringProperty("h")
            if (hash != null) {
                onStopTap?.invoke(StopTap(hash, f.getStringProperty("n") ?: ""))
            } else {
                // Tocco sul vuoto: chi ha pannelli aperti li chiude, come su
                // ogni mappa che si rispetti.
                onEmptyTap?.invoke()
            }
            hash != null
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
                enableBuildings3d(style, mode)
                addOverlay(style)
                ensureBusLayer(style)
                applyFilter(style)
                enableLocationIfAllowed(style)
                applyFollow(follow)
            }
        } else {
            m.getStyle { style ->
                // L'overlay puo' arrivare dopo lo stile (l'indice si scarica
                // in sottofondo): l'aggiunta e' idempotente.
                addOverlay(style)
                ensureBusLayer(style)
                applyFilter(style)
                applyRouteMode(style)
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

    /**
     * Gli edifici estrusi della basemap: nello stile Liberty il layer
     * `building-3d` esiste gia' (con le altezze vere di OSM) ma parte
     * spento. In Stradale si accende — visibili anche dall'alto, come
     * chiesto — in Ibrida no: sopra una foto aerea sono volumi inventati,
     * e li' il layer nemmeno esiste.
     */
    private fun enableBuildings3d(style: Style, mode: MapCatalog.MapMode) {
        if (mode != MapCatalog.MapMode.STREETS) return
        runCatching {
            style.getLayer("building-3d")?.setProperties(
                PropertyFactory.visibility("visible"),
            )
        }
    }

    private fun addOverlay(style: Style) {
        val url = overlayUrl ?: return
        if (style.getSource(MapCatalog.OVERLAY_SOURCE) != null) return
        style.addSource(VectorSource(MapCatalog.OVERLAY_SOURCE, "pmtiles://$url"))

        // Le tratte vanno sotto le etichette della basemap: si infilano sotto
        // il primo layer di simboli, cosi' i nomi delle strade restano sopra.
        val firstSymbol = style.layers.firstOrNull { it is SymbolLayer }?.id

        // Due layer di tratte con soglie diverse: le extraurbane — lunghe,
        // da guardare da lontano — entrano in scena prima delle urbane.
        fun lineLayer(id: String, cat: String, minZ: Float, fullAt: Float) =
            LineLayer(id, MapCatalog.OVERLAY_SOURCE).apply {
                sourceLayer = "linee"
                minZoom = minZ
                setFilter(Expression.eq(Expression.get("cat"), Expression.literal(cat)))
                setProperties(
                    PropertyFactory.lineColor(Expression.toColor(Expression.get("c"))),
                    PropertyFactory.lineCap("round"),
                    PropertyFactory.lineJoin("round"),
                    PropertyFactory.lineWidth(
                        Expression.interpolate(
                            Expression.linear(), Expression.zoom(),
                            Expression.stop(minZ, 1.1f),
                            Expression.stop(14f, 2.2f),
                            Expression.stop(16f, 3.6f),
                            Expression.stop(18f, 6f),
                        ),
                    ),
                    // La transizione fluida chiesta: le tratte sfumano dentro.
                    PropertyFactory.lineOpacity(
                        Expression.interpolate(
                            Expression.linear(), Expression.zoom(),
                            Expression.stop(minZ, 0f),
                            Expression.stop(fullAt, 0.85f),
                        ),
                    ),
                )
            }

        val lineeExtra = lineLayer(
            MapCatalog.LAYER_LINEE_EXTRA, "e",
            MapCatalog.LINEE_EXTRA_MIN_ZOOM, 11.2f,
        )
        val lineeUrbane = lineLayer(
            MapCatalog.LAYER_LINEE_URBANE, "u",
            MapCatalog.LINEE_URBANE_MIN_ZOOM, 13.2f,
        )
        if (firstSymbol != null) {
            style.addLayerBelow(lineeExtra, firstSymbol)
            style.addLayerBelow(lineeUrbane, firstSymbol)
        } else {
            style.addLayer(lineeExtra)
            style.addLayer(lineeUrbane)
        }

        // La tratta selezionata: sopra le linee normali, visibile da lontano.
        val lineaSel = LineLayer(MapCatalog.LAYER_LINEA_SEL, MapCatalog.OVERLAY_SOURCE).apply {
            sourceLayer = "linee"
            minZoom = 6f
            setFilter(Expression.literal(false))
            setProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get("c"))),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(7f, 2.6f),
                        Expression.stop(12f, 4f),
                        Expression.stop(16f, 6.5f),
                    ),
                ),
                PropertyFactory.lineOpacity(0.95f),
            )
        }
        if (firstSymbol != null) style.addLayerBelow(lineaSel, firstSymbol) else style.addLayer(lineaSel)

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

        // Le fermate della linea selezionata: stessi vestiti, ma visibili da
        // qualunque zoom, perche' in modalita' linea si guarda l'intera tratta.
        val fermateLinea = CircleLayer(MapCatalog.LAYER_FERMATE_LINEA, MapCatalog.OVERLAY_SOURCE).apply {
            sourceLayer = "fermate"
            minZoom = 6f
            setFilter(Expression.literal(false))
            setProperties(
                PropertyFactory.circleColor(if (darkTheme) "#1E1E24" else "#FFFFFF"),
                PropertyFactory.circleStrokeColor(if (darkTheme) "#B9B9C6" else "#4A4A55"),
                PropertyFactory.circleStrokeWidth(1.8f),
                PropertyFactory.circleRadius(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(7f, 2.4f),
                        Expression.stop(12f, 3.6f),
                        Expression.stop(16f, 5.2f),
                    ),
                ),
            )
        }
        style.addLayer(fermateLinea)
        val fermateLineaNomi =
            SymbolLayer(MapCatalog.LAYER_FERMATE_LINEA_NOMI, MapCatalog.OVERLAY_SOURCE).apply {
                sourceLayer = "fermate"
                minZoom = 12.5f
                setFilter(Expression.literal(false))
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
        style.addLayer(fermateLineaNomi)
        applyRouteMode(style)
    }

    private fun applyFilter(style: Style) {
        // In modalita' linea comanda applyRouteMode: i chip riprendono il
        // controllo all'uscita.
        if (highlightedRoute != null) return
        // I due layer di tratte hanno gia' il filtro di categoria addosso:
        // il chip li accende e spegne per visibilita'.
        val showUrban = filter != CategoryFilter.EXTRA
        val showExtra = filter != CategoryFilter.URBAN
        style.getLayer(MapCatalog.LAYER_LINEE_URBANE)
            ?.setProperties(PropertyFactory.visibility(if (showUrban) "visible" else "none"))
        style.getLayer(MapCatalog.LAYER_LINEE_EXTRA)
            ?.setProperties(PropertyFactory.visibility(if (showExtra) "visible" else "none"))

        val stopExpr = when (filter) {
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
        (style.getLayer(MapCatalog.LAYER_FERMATE) as? CircleLayer)?.setFilter(stopExpr)
        (style.getLayer(MapCatalog.LAYER_FERMATE_NOMI) as? SymbolLayer)?.setFilter(stopExpr)
        applyBusFilter(style)
    }

    // ------------------------------------------------------------- bus vivi

    private fun ensureBusLayer(style: Style) {
        if (style.getSource(MapCatalog.BUS_SOURCE) != null) return
        style.addSource(org.maplibre.android.style.sources.GeoJsonSource(MapCatalog.BUS_SOURCE))
        val layer = SymbolLayer(MapCatalog.LAYER_BUS, MapCatalog.BUS_SOURCE).apply {
            minZoom = MapCatalog.BUS_MIN_ZOOM
            setProperties(
                // L'icona e' "bus-<forma>-<colore>": freccia se il feed da'
                // la direzione, pallino altrimenti, sempre del colore della
                // linea. Le bitmap si registrano al volo in BusIcons.
                PropertyFactory.iconImage(
                    Expression.concat(
                        Expression.literal("bus-"),
                        Expression.get("sh"),
                        Expression.literal("-"),
                        Expression.get("ci"),
                    ),
                ),
                PropertyFactory.iconRotate(Expression.toNumber(Expression.get("b"))),
                PropertyFactory.iconRotationAlignment("map"),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconSize(
                    Expression.product(
                        Expression.interpolate(
                            Expression.linear(), Expression.zoom(),
                            Expression.stop(MapCatalog.BUS_MIN_ZOOM, 0.6f),
                            Expression.stop(13f, 0.85f),
                            Expression.stop(16f, 1.05f),
                        ),
                        Expression.switchCase(
                            Expression.toBool(Expression.get("sel")),
                            Expression.literal(1.35f),
                            Expression.literal(1f),
                        ),
                    ),
                ),
                // Sfumano dentro come le tratte: niente pop-in.
                PropertyFactory.iconOpacity(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(MapCatalog.BUS_MIN_ZOOM, 0f),
                        Expression.stop(MapCatalog.BUS_MIN_ZOOM + 0.8f, 1f),
                    ),
                ),
            )
        }
        // In cima a tutto: i bus sono l'elemento vivo della mappa.
        style.addLayer(layer)
        applyBusFilter(style)
        pushBusFeatures(style)
    }

    private fun applyBusFilter(style: Style) {
        val layer = style.getLayer(MapCatalog.LAYER_BUS) as? SymbolLayer ?: return
        val rh = highlightedRoute
        val expr = when {
            // In modalita' linea si vedono solo i bus DELLA linea.
            rh != null -> Expression.eq(Expression.get("rh"), Expression.literal(rh))
            filter == CategoryFilter.URBAN ->
                Expression.eq(Expression.get("cat"), Expression.literal("u"))
            filter == CategoryFilter.EXTRA ->
                Expression.eq(Expression.get("cat"), Expression.literal("e"))
            else -> Expression.literal(true)
        }
        layer.setFilter(expr)
    }

    /** Il nuovo snapshot risolto: ogni bus riparte da dov'e' verso la nuova meta. */
    fun setBuses(list: List<BusRender>) {
        busOverlay.setTargets(list, android.os.SystemClock.elapsedRealtime())
        map?.getStyle { pushBusFeatures(it) }
    }

    fun setSelectedBus(vehKey: Int?) {
        busOverlay.selectedKey = vehKey
        map?.getStyle { pushBusFeatures(it) }
    }

    /**
     * Il ritmo giusto per il prossimo fotogramma: da lontano un pixel sono
     * decine di metri e 2 Hz bastano; da vicino il glide vuole 8 Hz.
     */
    fun busTickDelayMs(): Long {
        val zoom = map?.cameraPosition?.zoom ?: return 500L
        return if (zoom >= 13.0) 120L else 500L
    }

    /** Un fotogramma del glide. La UI lo chiama solo quando i bus si vedono. */
    fun tickBuses() {
        val m = map ?: return
        if (busOverlay.isEmpty) return
        if (m.cameraPosition.zoom < MapCatalog.BUS_MIN_ZOOM - 0.5) return
        val style = m.style ?: return
        pushBusFeatures(style)
    }

    private fun pushBusFeatures(style: Style) {
        if (!style.isFullyLoaded) return
        val source = style
            .getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>(MapCatalog.BUS_SOURCE)
            ?: return
        source.setGeoJson(
            busOverlay.features(
                android.os.SystemClock.elapsedRealtime(),
                style,
                context.resources.displayMetrics.density,
            ),
        )
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
        // Mai animateCamera mentre un tracking e' attivo: lo annulla, ed e'
        // il motivo per cui la bussola "funzionava" sul puck ma la camera
        // non girava (trovato sul device). Zoom e inclinazione, durante il
        // tracking, passano dalle API dedicate del LocationComponent.
        when (follow) {
            FollowMode.FREE -> component.cameraMode = CameraMode.NONE
            FollowMode.FOLLOW -> {
                // Vista dall'alto, nord in alto, che segue la posizione.
                component.cameraMode = CameraMode.TRACKING_GPS_NORTH
                component.tiltWhileTracking(0.0)
            }
            FollowMode.COMPASS -> {
                // Heading-up con inclinazione fissa e zoom ravvicinato.
                component.cameraMode = CameraMode.TRACKING_COMPASS
                component.tiltWhileTracking(MapCatalog.NAV_TILT)
                component.zoomWhileTracking(maxOf(m.cameraPosition.zoom, MapCatalog.NAV_ZOOM))
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

    /** Inquadra un riquadro geografico con un margine comodo. */
    fun flyToBounds(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) {
        val m = map ?: return
        val bounds = org.maplibre.android.geometry.LatLngBounds.Builder()
            .include(LatLng(minLat, minLon))
            .include(LatLng(maxLat, maxLon))
            .build()
        val pad = (72 * context.resources.displayMetrics.density).toInt()
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad), 900)
    }

    private var highlightedRoute: String? = null
    private var routeStopHashes: Array<String>? = null

    /**
     * La modalita' linea: si accende la tratta scelta e la mappa si pulisce —
     * le altre linee spariscono, le fermate normali pure, e al loro posto
     * compaiono le fermate DELLA linea, visibili anche da lontano. E' la
     * stessa meccanica che i bus live useranno in Fase 4.
     */
    fun enterRouteMode(routeIdHashHex: String, stopIdHashes: Array<String>) {
        highlightedRoute = routeIdHashHex
        routeStopHashes = stopIdHashes
        map?.getStyle { applyRouteMode(it) }
    }

    fun exitRouteMode() {
        highlightedRoute = null
        routeStopHashes = null
        map?.getStyle { applyRouteMode(it) }
    }

    val inRouteMode: Boolean get() = highlightedRoute != null

    private fun applyRouteMode(style: Style) {
        val rh = highlightedRoute
        val active = rh != null

        (style.getLayer(MapCatalog.LAYER_LINEA_SEL) as? LineLayer)?.setFilter(
            if (rh == null) {
                Expression.literal(false)
            } else {
                Expression.eq(Expression.get("rh"), Expression.literal(rh))
            },
        )

        // In modalita' linea il resto della rete si toglie di mezzo; il
        // filtro dei chip torna a comandare quando si esce.
        if (active) {
            for (id in arrayOf(MapCatalog.LAYER_LINEE_URBANE, MapCatalog.LAYER_LINEE_EXTRA)) {
                style.getLayer(id)?.setProperties(PropertyFactory.visibility("none"))
            }
        }
        for (id in arrayOf(MapCatalog.LAYER_FERMATE, MapCatalog.LAYER_FERMATE_NOMI)) {
            style.getLayer(id)?.setProperties(
                PropertyFactory.visibility(if (active) "none" else "visible"),
            )
        }
        if (!active) applyFilter(style)

        val stopFilter = routeStopHashes?.let { hashes ->
            Expression.`in`(Expression.get("h"), Expression.literal(hashes as Array<Any>))
        } ?: Expression.literal(false)
        (style.getLayer(MapCatalog.LAYER_FERMATE_LINEA) as? CircleLayer)?.setFilter(stopFilter)
        (style.getLayer(MapCatalog.LAYER_FERMATE_LINEA_NOMI) as? SymbolLayer)?.setFilter(stopFilter)
        applyBusFilter(style)
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
    initialCamera: DoubleArray? = null,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentController = rememberUpdatedState(controller)
    val holder = remember { arrayOfNulls<MapView>(1) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val start = initialCamera?.takeIf { it.size >= 5 }
            val options = MapLibreMapOptions.createFromAttributes(context)
                .textureMode(true)
                .camera(
                    CameraPosition.Builder()
                        .target(
                            LatLng(
                                start?.get(0) ?: MapCatalog.HOME_LAT,
                                start?.get(1) ?: MapCatalog.HOME_LON,
                            ),
                        )
                        .zoom(start?.get(2) ?: MapCatalog.HOME_ZOOM)
                        .bearing(start?.get(3) ?: 0.0)
                        .tilt(start?.get(4) ?: 0.0)
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
