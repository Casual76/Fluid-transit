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
import androidx.lifecycle.repeatOnLifecycle
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
/**
 * Oltre questa eta' del feed i bus non si disegnano: meglio una mappa senza
 * mezzi per due secondi che mezzi dove non sono. Il tetto vero dell'origine
 * e' ~120 s, quindi tre minuti separano "normale" da "il proxy dormiva".
 */
private const val STALE_HIDE_SECONDS = 180L

/**
 * Quanto lontano deve stare la mappa da te perche' a decidere la vicinanza
 * dei risultati sia quello che guardi invece di dove sei. Sotto questa
 * soglia stai ancora girando per casa tua; sopra, stai esplorando altrove.
 */
private const val MAP_WINS_METERS = 20_000.0

private sealed interface Panel {
    class Stop(val tap: StopTap) : Panel
    class RouteMini(val routeIndex: Int) : Panel
    class RouteFull(val routeIndex: Int) : Panel
    class TripMini(val ref: TripRef) : Panel
    class TripFull(val ref: TripRef) : Panel
    class Place(val ref: PlaceRef) : Panel
    class Journeys(val to: PlaceRef) : Panel
    class JourneyDetail(val to: PlaceRef, val index: Int) : Panel
}

@androidx.compose.runtime.Composable
@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun MapScreen(
    app: FluidTransitApp,
    backdrop: GlassBackdropState,
    onTabBarHidden: (Boolean) -> Unit = {},
    intent: MapIntent? = null,
    onIntentConsumed: () -> Unit = {},
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

    // Lo zoom corrente (a camera ferma): decide se i bus vivi si scaricano.
    var cameraZoom by remember { mutableStateOf(MapCatalog.HOME_ZOOM) }

    // La modalita' linea (e la scheda corsa, che vive nello stesso posto)
    // prende il posto della tab bar: la shell lo sa da qui.
    var navActive by remember { mutableStateOf(false) }
    LaunchedEffect(panel, navActive) {
        onTabBarHidden(
            navActive ||
                panel is Panel.RouteMini || panel is Panel.RouteFull ||
                panel is Panel.TripMini || panel is Panel.TripFull,
        )
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

    // Il mic di sistema: il ripiego di sempre, quando il proxy vocale non puo'.
    fun launchSystemMic() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Che fermata, linea o posto cerchi?")
        }
        runCatching { micLauncher.launch(intent) }
    }

    // L'assistente esiste solo se c'e' una chiave verificata e l'interruttore
    // e' acceso: senza, il microfono resta quello di sistema, che trascrive e
    // basta — cioe' com'era prima della Fase 8.
    val assistantEnabled by app.assistant.enabled.collectAsStateWithLifecycle(initialValue = false)
    var assistantOpen by remember { mutableStateOf(false) }
    var assistantMode by remember {
        mutableStateOf(dev.antigravity.fluidtransit.ai.orchestrator.AskMode.VOICE)
    }
    var assistantQuestion by remember { mutableStateOf("") }

    fun openAssistant(
        mode: dev.antigravity.fluidtransit.ai.orchestrator.AskMode,
        question: String = "",
    ) {
        assistantMode = mode
        assistantQuestion = question
        assistantOpen = true
        searchOpen = false
        query = ""
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && assistantEnabled) {
            openAssistant(dev.antigravity.fluidtransit.ai.orchestrator.AskMode.VOICE)
        } else {
            launchSystemMic()
        }
    }

    // Il permesso notifiche (Android 13+): si chiede quando nasce la prima
    // routine, cioe' quando la notifica ha un motivo di esistere.
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    // L'indice di ricerca si costruisce una volta per bundle, fuori dal main.
    val searchIndex by produceState<SearchIndex?>(initialValue = null, ready?.buildId) {
        val reader = ready?.reader ?: return@produceState
        value = withContext(Dispatchers.Default) { SearchIndex.build(reader) }
    }

    // La geometria delle tratte, decodificata pigramente: e' quella che fa
    // correre i bus sulla strada invece di attraversare gli isolati.
    val pathCache = remember(ready?.buildId) {
        ready?.reader?.let { PathCache(it, app.applicationScope) }
    }
    LaunchedEffect(pathCache) { controller.setPathCache(pathCache) }

    // Quello che l'assistente non puo' sapere da solo: l'indice di ricerca,
    // dove sei e dove stai guardando. Glielo lascia qui la mappa.
    LaunchedEffect(searchIndex) { app.assistantBridge.searchIndex = searchIndex }
    LaunchedEffect(Unit) {
        app.assistantBridge.location = { controller.lastLocation() }
        app.assistantBridge.camera = { controller.cameraCenter() }
    }

    fun exitRouteMode() {
        controller.exitRouteMode()
        controller.setSelectedBus(null)
        controller.clearPlaceMarker()
        controller.clearJourney()
        panel = null
    }

    // Il colore d'accento per il segnaposto: lo stesso ametista del tema.
    val accentArgb = MaterialTheme.colorScheme.primary.let {
        android.graphics.Color.argb(255, (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt())
    }

    // Il pannello del luogo: dalla ricerca, da un posto salvato o dal
    // tieni-premuto sulla mappa. Marker + volo + pannello, come deciso.
    fun showPlace(ref: PlaceRef, fly: Boolean = true) {
        controller.exitRouteMode()
        controller.setSelectedBus(null)
        controller.clearJourney()
        follow = FollowMode.FREE
        controller.showPlaceMarker(ref.lat, ref.lon, accentArgb)
        if (fly) controller.flyTo(ref.lat, ref.lon, maxOf(15.2, cameraZoom))
        panel = Panel.Place(ref)
    }

    controller.onStopTap = { tap ->
        // Aprire una fermata chiude la modalita' linea: il pannello torna scheda fermata.
        controller.exitRouteMode()
        controller.setSelectedBus(null)
        panel = Panel.Stop(tap)
    }
    controller.onEmptyTap = {
        // Il tocco a vuoto e' una delle tre uscite decise.
        exitRouteMode()
    }
    controller.onMapLongPress = { lat, lon ->
        // Tieni premuto = un posto senza nome OSM, pronto da salvare.
        searchOpen = false
        showPlace(PlaceRef("Punto sulla mappa", "", lat, lon), fly = false)
    }
    controller.onGesture = { if (follow != FollowMode.FREE) follow = FollowMode.FREE }

    // Il tap sulla pillola di una linea: la mappa si pulisce (resta la
    // tratta accesa e le SUE fermate), la camera inquadra tutto, e il
    // pannello si trasforma nello stato mini della scheda linea.
    // La stessa meccanica risponde al tap su un bus vivo.
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

    // --- la navigazione a bordo (Fase 7) -----------------------------------
    val navState by app.navigation.state.collectAsStateWithLifecycle()
    LaunchedEffect(navState) { navActive = navState != null }
    // In navigazione la mappa passa da sola a 3D-bussola, come deciso in
    // Fase 2; e ne esce quando la navigazione finisce.
    LaunchedEffect(navState != null) {
        if (navState != null && locationGranted) follow = FollowMode.COMPASS
        if (navState == null && follow == FollowMode.COMPASS) follow = FollowMode.FOLLOW
    }

    // --- il tempo reale ---------------------------------------------------
    val rt = app.realtime
    val rtVehicles by rt.vehicles.collectAsStateWithLifecycle()
    val rtDelays by rt.delays.collectAsStateWithLifecycle()
    val rtStatus by rt.status.collectAsStateWithLifecycle()

    // Lo snapshot risolto contro il bundle: hash → indici → colori. Fuori
    // dal main, a ogni poll.
    val resolved by produceState<ResolvedRt?>(
        initialValue = null,
        rtVehicles, rtDelays, ready?.buildId,
    ) {
        val reader = ready?.reader
        val v = rtVehicles
        if (reader == null || v == null) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.Default) { resolveRt(reader, v, rtDelays) }
        value?.resolvedPercent?.let { rt.resolvedPercent.value = it }
    }

    // Il tocco su un bus: modalita' linea + scheda corsa, come deciso. La
    // tratta si accende, la mappa si pulisce, il bus resta evidenziato.
    fun showTrip(ref: TripRef, focus: Pair<Double, Double>?) {
        val reader = ready?.reader ?: return
        follow = FollowMode.FREE
        panel = Panel.TripMini(ref)
        controller.setSelectedBus(ref.vehKey)
        if (ref.routeIndex >= 0) {
            scope.launch(Dispatchers.Default) {
                val hashes = LinkedHashSet<String>()
                for (p in reader.patternsOfRoute(ref.routeIndex)) {
                    val n = reader.patternStopCount(p)
                    for (i in 0 until n) {
                        hashes.add(java.lang.Long.toHexString(reader.stopIdHash(reader.patternStop(p, i))))
                    }
                }
                val rh = java.lang.Long.toHexString(reader.routeIdHash(ref.routeIndex))
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    controller.enterRouteMode(rh, hashes.toTypedArray())
                    focus?.let { (la, lo) -> controller.flyTo(la, lo, maxOf(14.0, cameraZoom)) }
                }
            }
        } else {
            // Linea sconosciuta al bundle: niente da accendere, ma la
            // posizione live e la scheda minima ci sono lo stesso.
            controller.exitRouteMode()
            focus?.let { (la, lo) -> controller.flyTo(la, lo, maxOf(14.0, cameraZoom)) }
        }
    }

    controller.onBusTap = { tap ->
        val meta = resolved?.busMetaByKey?.get(tap.vehKey)
        val ref = if (meta != null) {
            TripRef(meta.vehKey, meta.tripHash, meta.routeHash, meta.tripIndex, meta.routeIndex)
        } else {
            TripRef(
                vehKey = tap.vehKey,
                tripHash = tap.tripHashHex.toULongOrNull(16)?.toLong() ?: 0L,
                routeHash = tap.routeHashHex.toULongOrNull(16)?.toLong() ?: 0L,
                tripIndex = -1,
                routeIndex = -1,
            )
        }
        // Niente volo: il bus e' gia' sotto il dito.
        showTrip(ref, focus = null)
    }

    // Le richieste dalle altre schede (Preferiti, Oggi): si consumano
    // appena bundle e mappa ci sono.
    LaunchedEffect(intent, ready?.buildId) {
        val reader = ready?.reader
        val i = intent
        if (i == null || reader == null) return@LaunchedEffect
        when (i) {
            is MapIntent.Stop -> {
                controller.exitRouteMode()
                controller.setSelectedBus(null)
                val hash = i.idHashHex.toULongOrNull(16)?.toLong()
                val stop = hash?.let { reader.findStopByIdHash(it) } ?: -1
                if (stop >= 0) controller.flyTo(reader.stopLat(stop), reader.stopLon(stop), 16.2)
                panel = Panel.Stop(StopTap(i.idHashHex, i.name))
            }

            is MapIntent.Route -> {
                val hash = i.idHashHex.toULongOrNull(16)?.toLong()
                val idx = hash?.let { reader.findRouteByIdHash(it) } ?: -1
                if (idx >= 0) showRoute(idx)
            }

            is MapIntent.Place -> {
                showPlace(PlaceRef(i.name, "", i.lat, i.lon, i.savedId))
            }
        }
        onIntentConsumed()
    }

    // I dati della scheda linea, calcolati quando serve.
    val currentRouteIndex = when (val p = panel) {
        is Panel.RouteMini -> p.routeIndex
        is Panel.RouteFull -> p.routeIndex
        else -> null
    }
    val routeInfo by produceState<RouteInfo?>(
        initialValue = null,
        currentRouteIndex, ready?.buildId,
        // Anche i ritardi: gli orari accanto alle fermate devono restare veri.
        rtDelays?.generatedAt,
    ) {
        val reader = ready?.reader
        val idx = currentRouteIndex
        if (reader == null || idx == null) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            RouteInfo.build(reader, idx, Instant.now(), app.delayModel)
        }
    }

    // I dati della scheda corsa: si ricalcolano anche quando arriva un
    // ritardo nuovo, cosi' i minuti delle fermate restano veri.
    val currentTripRef = when (val p = panel) {
        is Panel.TripMini -> p.ref
        is Panel.TripFull -> p.ref
        else -> null
    }
    // Il battito della scheda corsa: senza, le "prossime fermate" restavano
    // quelle del momento in cui si era aperta.
    var tripTick by remember { mutableStateOf(0) }
    LaunchedEffect(currentTripRef) {
        while (currentTripRef != null) {
            kotlinx.coroutines.delay(15_000)
            tripTick++
        }
    }
    val tripInfo by produceState<TripInfo?>(
        initialValue = null,
        currentTripRef, rtDelays, ready?.buildId, tripTick,
    ) {
        val reader = ready?.reader
        val ref = currentTripRef
        if (reader == null || ref == null) {
            value = null
            return@produceState
        }
        val d = rtDelays?.byTripHash?.get(ref.tripHash)
        value = withContext(Dispatchers.Default) {
            TripInfo.build(
                reader = reader,
                ref = ref,
                now = Instant.now(),
                delaySec = d?.takeIf { !it.noData }?.delaySec,
                canceled = d?.canceled == true,
                delays = app.delayModel,
            )
        }
    }

    // --- itinerari: origine, orario, calcolo -------------------------------
    val placesState by app.placesManager.state.collectAsStateWithLifecycle()
    var savedVersion by remember { mutableStateOf(0) }
    val savedSuggestions = remember(savedVersion) {
        app.savedPlaces.load().map {
            Suggestion("saved", it.id.toString(), it.label, "Il tuo posto", 0, it.lat, it.lon)
        }
    }

    // I posti salvati sulla mappa, con l'icona che dice cosa sono. Fino alla
    // Fase 8 si salvavano e sparivano: restavano nella lista dei Preferiti,
    // ma sulla mappa non c'era proprio niente.
    LaunchedEffect(savedVersion, accentArgb) {
        controller.setSavedPlaces(
            app.savedPlaces.load().map { SavedRender(it.id, it.label, it.lat, it.lon) },
            accentArgb and 0xFFFFFF,
        )
    }

    var journeyOrigin by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var journeyFromGps by remember { mutableStateOf(true) }
    var journeyTimeMode by rememberSaveable { mutableStateOf("now") } // now | depart | arrive
    var journeyTimeEpoch by rememberSaveable { mutableStateOf(0L) }
    var showTimeDialog by remember { mutableStateOf(false) }

    // Il pianificatore Da/A. `originRef` nullo vuol dire "da dove sei":
    // resta il caso normale, ma smette di essere l'unico possibile.
    var plannerOpen by remember { mutableStateOf(false) }
    var originRef by remember { mutableStateOf<PlaceRef?>(null) }
    var destRef by remember { mutableStateOf<PlaceRef?>(null) }

    /** Quale delle due righe sta compilando la ricerca: "from", "to", o niente. */
    var plannerField by remember { mutableStateOf<String?>(null) }

    /** Calcola, quando c'e' abbastanza per calcolare. */
    fun runPlanner() {
        val to = destRef ?: return
        val from = originRef
        if (from != null) {
            journeyFromGps = false
            journeyOrigin = from.lat to from.lon
        } else {
            // Dalla posizione GPS se c'e', dal centro mappa se no — e la
            // differenza si dichiara nel pannello, non si nasconde.
            val loc = controller.lastLocation()
            journeyFromGps = loc != null
            journeyOrigin = loc ?: controller.cameraCenter()
        }
        panel = Panel.Journeys(to)
    }

    /** "Portami qui" da un luogo: destinazione quella, partenza da dove sei. */
    fun goToPlace(ref: PlaceRef) {
        originRef = null
        destRef = ref
        journeyTimeMode = "now"
        plannerOpen = true
        runPlanner()
    }

    fun openPlanner() {
        searchOpen = false
        query = ""
        plannerField = null
        plannerOpen = true
    }

    // Le azioni dell'assistente le esegue la mappa, perche' e' l'unica che
    // puo': il modulo dell'assistente non sa niente di pannelli e di camera.
    LaunchedEffect(Unit) {
        app.assistantBridge.actions.collect { action ->
            val reader = ready?.reader
            when (action) {
                is dev.antigravity.fluidtransit.ai.tools.AssistantAction.ShowPlace -> {
                    assistantOpen = false
                    showPlace(
                        PlaceRef(
                            action.point.name, action.point.context,
                            action.point.lat, action.point.lon,
                        ),
                    )
                }

                is dev.antigravity.fluidtransit.ai.tools.AssistantAction.ShowStop -> {
                    assistantOpen = false
                    panel = Panel.Stop(StopTap(action.idHashHex, action.name))
                    val hash = action.idHashHex.toULongOrNull(16)?.toLong()
                    val s = if (reader != null && hash != null) reader.findStopByIdHash(hash) else -1
                    if (reader != null && s >= 0) {
                        controller.flyTo(reader.stopLat(s), reader.stopLon(s), 16.0)
                    }
                }

                is dev.antigravity.fluidtransit.ai.tools.AssistantAction.ShowRoute -> {
                    assistantOpen = false
                    showRoute(action.routeIndex)
                }

                is dev.antigravity.fluidtransit.ai.tools.AssistantAction.ShowJourneys -> {
                    originRef = action.from?.let {
                        PlaceRef(it.name, it.context, it.lat, it.lon)
                    }
                    destRef = PlaceRef(
                        action.to.name, action.to.context, action.to.lat, action.to.lon,
                    )
                    journeyTimeMode = when {
                        action.arriveByEpoch != null -> "arrive"
                        action.departAtEpoch != null -> "depart"
                        else -> "now"
                    }
                    journeyTimeEpoch = action.arriveByEpoch ?: action.departAtEpoch ?: 0L
                    plannerOpen = true
                    runPlanner()
                }

                is dev.antigravity.fluidtransit.ai.tools.AssistantAction.StartNavigation -> {
                    val origin = controller.lastLocation() ?: controller.cameraCenter()
                    if (reader != null && origin != null) {
                        val js = app.assistantBridge.plan(
                            origin.first, origin.second,
                            action.to.lat, action.to.lon, null, null,
                        )
                        val j = js.firstOrNull()
                        if (j != null) {
                            assistantOpen = false
                            app.navigation.start(context, buildNavPlan(reader, j, action.to.name))
                        }
                    }
                }

                is dev.antigravity.fluidtransit.ai.tools.AssistantAction.SavePlace -> {
                    app.savedPlaces.add(action.label, action.point.lat, action.point.lon)
                    savedVersion++
                }

                is dev.antigravity.fluidtransit.ai.tools.AssistantAction.StarStop ->
                    app.favorites.toggleStop(action.idHashHex, action.name)

                is dev.antigravity.fluidtransit.ai.tools.AssistantAction.StarRoute -> {
                    if (reader != null) {
                        app.favorites.toggleRoute(
                            java.lang.Long.toHexString(reader.routeIdHash(action.routeIndex)),
                            action.shortName,
                            reader.routeDisplayColor(action.routeIndex),
                        )
                    }
                }

                is dev.antigravity.fluidtransit.ai.tools.AssistantAction.CreateRoutine -> {
                    val origin = action.from
                        ?: controller.lastLocation()?.let {
                            dev.antigravity.fluidtransit.ai.tools.NamedPoint(
                                "La tua posizione", "", it.first, it.second,
                            )
                        }
                    if (origin != null) {
                        val routine = dev.antigravity.fluidtransit.data.routines.Routines.Routine(
                            id = System.currentTimeMillis(),
                            label = action.label,
                            fromLat = origin.lat,
                            fromLon = origin.lon,
                            toLat = action.to.lat,
                            toLon = action.to.lon,
                            toName = action.to.name,
                            days = action.days,
                            anchor = action.anchor,
                            anchorMinutes = action.anchorMinutes,
                            enabled = true,
                        )
                        app.routines.add(routine)
                        dev.antigravity.fluidtransit.data.routines.RoutineScheduler
                            .scheduleNextCompute(context, routine)
                    }
                }
            }
        }
    }

    val journeysTarget = when (val p = panel) {
        is Panel.Journeys -> p.to
        is Panel.JourneyDetail -> p.to
        else -> null
    }
    val journeys by produceState<List<UiJourney>?>(
        initialValue = null,
        journeysTarget, journeyTimeMode, journeyTimeEpoch, ready?.buildId,
    ) {
        val reader = ready?.reader
        val to = journeysTarget
        val from = journeyOrigin
        if (reader == null || to == null || from == null) {
            value = null
            return@produceState
        }
        value = null
        // Il realtime entra nel calcolo: ritardi e cancellazioni di ADESSO.
        val rtNow = resolved
        val liveData = if (rtNow != null) {
            dev.antigravity.fluidtransit.routing.Raptor.Realtime(rtNow.delayByTrip, rtNow.canceledTrips)
        } else {
            dev.antigravity.fluidtransit.routing.Raptor.Realtime.NONE
        }
        val raw = withContext(app.routingDispatcher) {
            val raptor = app.raptorFor(reader)
            val fromPlace = dev.antigravity.fluidtransit.routing.Raptor.Place(from.first, from.second)
            val toPlace = dev.antigravity.fluidtransit.routing.Raptor.Place(to.lat, to.lon)
            when (journeyTimeMode) {
                "arrive" -> raptor.planArriveBy(
                    fromPlace, toPlace, Instant.ofEpochSecond(journeyTimeEpoch), liveData,
                )

                "depart" -> raptor.plan(
                    fromPlace, toPlace, Instant.ofEpochSecond(journeyTimeEpoch), liveData,
                )

                else -> raptor.plan(fromPlace, toPlace, Instant.now(), liveData)
            }
        }
        value = withContext(Dispatchers.Default) {
            // Le corse davvero seguite dal feed: serve per distinguere
            // "monitorata e puntuale" da "non se ne sa niente".
            val liveTrips = rtNow?.delayByTrip?.keys.orEmpty()
            raw.map { UiJourney.of(reader, it, liveTrips) }
        }
    }

    // Il viaggio scelto si accende sulla mappa e la camera lo inquadra.
    LaunchedEffect(panel, journeys) {
        val p = panel
        val r2 = ready?.reader
        if (p is Panel.JourneyDetail && r2 != null) {
            val j = journeys?.getOrNull(p.index) ?: return@LaunchedEffect
            val (features, bbox) = withContext(Dispatchers.Default) {
                buildJourneyGeometry(r2, j.raw)
            }
            controller.showJourney(features)
            if (bbox[0] <= bbox[2]) controller.flyToBounds(bbox[0], bbox[1], bbox[2], bbox[3])
        } else {
            controller.clearJourney()
        }
    }

    // --- i cicli del realtime: vivono col ciclo di vita della schermata ----
    // Bus: solo quando lo zoom li rende visibili (o una scheda corsa e'
    // aperta). Il ritmo lo decide lo stato del client: 30 s dal proxy,
    // 3 min in diretta. Il tick a ~8 Hz fa scivolare i marker.
    val vehiclesActive = ready != null &&
        (
            cameraZoom >= MapCatalog.BUS_MIN_ZOOM - 0.6 ||
                panel is Panel.TripMini || panel is Panel.TripFull ||
                // In modalita' linea i bus della tratta si vedono da
                // qualunque zoom: il polling deve accompagnarli.
                panel is Panel.RouteMini || panel is Panel.RouteFull
            )
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    LaunchedEffect(vehiclesActive) {
        if (!vehiclesActive) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            launch {
                while (true) {
                    rt.refreshVehicles()
                    kotlinx.coroutines.delay(rt.vehiclesIntervalMs())
                }
            }
            launch {
                while (true) {
                    controller.tickBuses()
                    kotlinx.coroutines.delay(controller.busTickDelayMs())
                }
            }
        }
    }

    // Ritardi: solo con un pannello aperto — sono i pannelli a mostrarli.
    val delaysActive = ready != null && panel != null
    LaunchedEffect(delaysActive) {
        if (!delaysActive) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                rt.refreshDelays()
                kotlinx.coroutines.delay(30_000)
            }
        }
    }

    // Ogni snapshot risolto scende nella mappa: da li' parte il moto.
    //
    // Con una riserva, decisa con l'utente: se il proxy ha in pancia
    // posizioni troppo vecchie — apertura dopo qualche ora, misurate
    // ventisei minuti il 03/09 — NON si disegnano bus dove non sono. Si
    // aspetta il giro fresco, che arriva in un paio di secondi.
    LaunchedEffect(resolved, rtStatus) {
        // Anche l'assistente vuole sapere chi e' in strada adesso.
        app.assistantBridge.resolved = resolved
        val age = rtStatus.feedAgeSeconds
        val fresh = age == null || age <= STALE_HIDE_SECONDS
        controller.setBuses(if (fresh) resolved?.buses ?: emptyList() else emptyList())
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
        // I posti salvati non finiscono nei recenti: sono gia' sempre in cima.
        if (s.kind != "saved") {
            recentStore.add(
                RecentSearches.Entry(s.kind, s.key, s.title, s.subtitle, s.colorRgb, s.lat, s.lon),
            )
            recentsVersion++
        }
        if (s.kind == "place" || s.kind == "saved") {
            showPlace(
                PlaceRef(
                    name = s.title,
                    context = s.subtitle.takeIf { it != "Luogo" && it != "Il tuo posto" } ?: "",
                    lat = s.lat,
                    lon = s.lon,
                    savedId = if (s.kind == "saved") s.key.toLongOrNull() else null,
                ),
            )
            return
        }
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

    // Il selettore d'orario: "Parti alle / Arriva entro" col TimePicker.
    // Un orario gia' passato si legge come "domani a quest'ora".
    if (showTimeDialog) {
        val zone = dev.antigravity.fluidtransit.routing.Ftb.ROME
        val base = if (journeyTimeEpoch > 0) Instant.ofEpochSecond(journeyTimeEpoch) else Instant.now()
        val zdt = java.time.ZonedDateTime.ofInstant(base, zone)
        val timeState = androidx.compose.material3.rememberTimePickerState(
            initialHour = zdt.hour,
            initialMinute = zdt.minute,
            is24Hour = true,
        )
        var timeMode by remember { mutableStateOf(if (journeyTimeMode == "arrive") 1 else 0) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTimeDialog = false },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val chosen = java.time.ZonedDateTime.now(zone)
                        .withHour(timeState.hour)
                        .withMinute(timeState.minute)
                        .withSecond(0)
                    val instant = if (chosen.toInstant().isBefore(Instant.now().minusSeconds(60))) {
                        chosen.plusDays(1).toInstant()
                    } else {
                        chosen.toInstant()
                    }
                    journeyTimeEpoch = instant.epochSecond
                    journeyTimeMode = if (timeMode == 1) "arrive" else "depart"
                    showTimeDialog = false
                }) { androidx.compose.material3.Text("Fatto") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    journeyTimeMode = "now"
                    showTimeDialog = false
                }) { androidx.compose.material3.Text("Adesso") }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl(
                        options = listOf(0, 1),
                        selected = timeMode,
                        onSelect = { timeMode = it },
                        label = { if (it == 0) "Parti alle" else "Arriva entro" },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                    androidx.compose.material3.TimePicker(state = timeState)
                }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // La mappa e' la sorgente del vetro: tutto il chrome la rifrange.
        // La camera sopravvive a rotazione e ritorno dall'ultima schermata.
        val savedCamera = rememberSaveable { mutableStateOf<DoubleArray?>(null) }
        controller.onCameraIdle = {
            savedCamera.value = it
            cameraZoom = it[2]
        }
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
                    plannerField = null
                }
            } else if (plannerOpen) {
                BackHandler {
                    plannerOpen = false
                    plannerField = null
                    destRef = null
                    originRef = null
                    panel = null
                    controller.clearJourney()
                    controller.clearPlaceMarker()
                }
            }
            // Fermate e linee (indice in memoria) + lo stadio RAPIDO dei
            // luoghi (POI, vie, localita'), fuori dal main.
            // Da dove si pesa la vicinanza, come deciso: normalmente da dove
            // sei; ma se hai portato la mappa lontano da li', comanda quello
            // che stai guardando — cercare "via roma" mentre si esplora
            // Siena deve dare le vie senesi.
            fun searchReference(): Pair<Double, Double>? {
                val here = controller.lastLocation()
                val looking = controller.cameraCenter()
                if (here == null) return looking
                if (looking == null) return here
                val away = dev.antigravity.fluidtransit.routing.BundleReader
                    .haversine(here.first, here.second, looking.first, looking.second)
                return if (away > MAP_WINS_METERS) looking else here
            }

            val placesReady = placesState as? dev.antigravity.fluidtransit.data.places.PlacesManager.State.Ready
            val queryResults by produceState(
                initialValue = emptyList<Suggestion>(),
                query, searchIndex, placesReady,
            ) {
                if (query.length < 2) {
                    value = emptyList()
                    return@produceState
                }
                // Scrivere e' un gesto continuo: si aspetta la fine della
                // parola invece di riscandire l'indice a ogni lettera.
                kotlinx.coroutines.delay(160)
                val ref = searchReference()
                value = withContext(Dispatchers.Default) {
                    val rLat = ref?.first ?: Double.NaN
                    val rLon = ref?.second ?: Double.NaN
                    val transit = searchIndex?.search(query, 25, rLat, rLon).orEmpty().map { hit ->
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
                                score = hit.score,
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
                                score = hit.score,
                            )
                        }
                    }
                    val places = placesReady?.search?.fast(query, 14, rLat, rLon).orEmpty().map { h ->
                        Suggestion(
                            kind = "place",
                            key = "%.5f,%.5f".format(h.lat, h.lon),
                            title = h.name,
                            subtitle = h.context.ifEmpty { "Luogo" },
                            colorRgb = 0,
                            lat = h.lat,
                            lon = h.lon,
                            score = h.score,
                        )
                    }
                    // Una lista sola, ordinata per quanto c'entra: se scrivi
                    // "esselunga" viene su il supermercato, se scrivi "6"
                    // viene su la linea. L'icona di ogni riga dice cos'e'.
                    (transit + places).sortedByDescending { it.score }
                }
            }

            // Lo stadio LENTO: i civici. Parte dopo, con calma, e i suoi
            // risultati si AGGIUNGONO a quelli gia' mostrati — deciso cosi'.
            val civiciResults by produceState(
                initialValue = emptyList<Suggestion>(),
                query, placesReady,
            ) {
                value = emptyList()
                if (placesReady == null || query.length < 5 || !query.any { it.isDigit() }) {
                    return@produceState
                }
                kotlinx.coroutines.delay(350)
                val ref = searchReference()
                value = withContext(Dispatchers.Default) {
                    placesReady.search.civici(
                        query,
                        6,
                        ref?.first ?: Double.NaN,
                        ref?.second ?: Double.NaN,
                    ).map { h ->
                        Suggestion(
                            kind = "civic",
                            key = "%.5f,%.5f".format(h.lat, h.lon),
                            title = h.name,
                            subtitle = h.context.ifEmpty { "Indirizzo" },
                            colorRgb = 0,
                            lat = h.lat,
                            lon = h.lon,
                            score = h.score,
                        )
                    }
                }
            }
            if (plannerOpen && !searchOpen) {
                PlannerGlass(
                    backdrop = backdrop,
                    from = originRef,
                    to = destRef,
                    timeLabel = when (journeyTimeMode) {
                        "depart" -> "Parti alle ${hhmm(journeyTimeEpoch)}"
                        "arrive" -> "Arriva entro le ${hhmm(journeyTimeEpoch)}"
                        else -> "Parti ora"
                    },
                    onPickFrom = {
                        plannerField = "from"
                        query = ""
                        searchOpen = true
                    },
                    onPickTo = {
                        plannerField = "to"
                        query = ""
                        searchOpen = true
                    },
                    onSwap = {
                        // Scambiare con "la tua posizione" ha senso solo se
                        // quella posizione diventa un punto vero.
                        val here = originRef ?: controller.lastLocation()?.let {
                            PlaceRef("La tua posizione", "", it.first, it.second)
                        }
                        originRef = destRef
                        destRef = here
                        runPlanner()
                    },
                    onTime = { showTimeDialog = true },
                    onClose = {
                        plannerOpen = false
                        plannerField = null
                        destRef = null
                        originRef = null
                        panel = null
                        controller.clearJourney()
                        controller.clearPlaceMarker()
                    },
                )
            } else {
            SearchGlass(
                backdrop = backdrop,
                open = searchOpen,
                query = query,
                // I civici arrivano dopo, ma entrano nella stessa lista e si
                // ordinano insieme agli altri: stessa scala di pertinenza.
                results = (queryResults + civiciResults).sortedByDescending { it.score },
                saved = savedSuggestions,
                recents = recents.filter { it.kind == "stop" || it.kind == "place" }
                    .map { it.toSuggestion() },
                nearby = nearby,
                recentLines = recents.filter { it.kind == "route" }.map { it.toSuggestion() },
                onOpen = { searchOpen = true },
                onClose = {
                    searchOpen = false
                    query = ""
                    // Chiudere la ricerca mentre si compilava una riga del
                    // pianificatore torna al pianificatore, non lo abbandona.
                    plannerField = null
                },
                onQueryChange = { query = it },
                onMic = {
                    // Col microfono si entra in modalita' vocale
                    // dell'assistente, se c'e' una chiave; altrimenti resta
                    // il riconoscimento di sistema di sempre, che trascrive
                    // nella barra e basta.
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    when {
                        !assistantEnabled -> launchSystemMic()
                        !granted -> audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        else ->
                            openAssistant(dev.antigravity.fluidtransit.ai.orchestrator.AskMode.VOICE)
                    }
                },
                // Scrivendo, il tasto del mic diventa "chiedi all'IA": e' la
                // scelta dell'utente, e regge perche' una frase scritta e'
                // quasi sempre una domanda, non il nome di una fermata.
                onAsk = if (assistantEnabled) {
                    {
                        openAssistant(
                            dev.antigravity.fluidtransit.ai.orchestrator.AskMode.TEXT,
                            query,
                        )
                    }
                } else {
                    null
                },
                onPick = { s ->
                    val field = plannerField
                    if (field == null) {
                        pick(s)
                    } else {
                        // La ricerca sta compilando una riga del
                        // pianificatore, non portando da qualche parte.
                        val ref = PlaceRef(s.title, s.subtitle, s.lat, s.lon)
                        if (field == "from") originRef = ref else destRef = ref
                        plannerField = null
                        searchOpen = false
                        query = ""
                        plannerOpen = true
                        runPlanner()
                    }
                },
                onPlanRoute = { openPlanner() },
            )
            }
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

            // Il live degradato: silenzio finche' funziona, capsula discreta
            // quando i bus vivi mancano davvero — come deciso.
            val liveDegraded = vehiclesActive && !searchOpen && (
                rtStatus.source == dev.antigravity.fluidtransit.data.rt.RealtimeClient.Source.SCHEDULE_ONLY ||
                    (rtStatus.feedAgeSeconds ?: 0) >
                    dev.antigravity.fluidtransit.data.rt.RealtimeClient.STALE_SECONDS
                )
            androidx.compose.animation.AnimatedVisibility(visible = liveDegraded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(10.dp))
                    LiveDownCapsule(backdrop = backdrop)
                }
            }

            // L'aggiornamento disponibile, nella stessa forma dell'avviso del
            // live: e' un'app che non passa da uno store che aggiorna da solo,
            // quindi se non lo dice qui non lo dice nessuno.
            val update by app.updates.available.collectAsStateWithLifecycle()
            val updateHidden by app.updates.capsuleDismissed.collectAsStateWithLifecycle()
            androidx.compose.animation.AnimatedVisibility(visible = update != null && !updateHidden) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(10.dp))
                    update?.let { u ->
                        UpdateCapsule(
                            backdrop = backdrop,
                            version = u.version,
                            onInstall = { app.updates.install() },
                            onDismiss = { app.updates.dismissCapsule() },
                        )
                    }
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
        // dell'app (quella di MapLibre in alto e' spenta). La scrittura di
        // stato avviene SOLO in bussola: fuori, aggiornare a ogni frame di
        // pan era lavoro regalato al garbage collector.
        var bearing by remember { mutableStateOf(0f) }
        controller.onBearing = if (follow == FollowMode.COMPASS) {
            { bearing = it.toFloat() }
        } else {
            null
        }
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
        val inRoutePanel = panel is Panel.RouteMini || panel is Panel.RouteFull ||
            panel is Panel.TripMini || panel is Panel.TripFull
        // In modalita' linea il pannello siede ESATTAMENTE dove sedeva la
        // tab bar: stessi margini, e il mini anche la stessa altezza — cosi'
        // il rimbalzo del congedo si legge come la capsula che ritorna.
        val bottomPad by androidx.compose.animation.core.animateDpAsState(
            targetValue = if (inRoutePanel) {
                FluidTabBarDefaults.BottomMargin
            } else {
                FluidTabBarDefaults.ContentInset + 10.dp
            },
            label = "panelBottomPad",
        )
        androidx.compose.animation.AnimatedVisibility(
            visible = panel != null && reader != null,
            enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it / 3 }) +
                androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it / 3 }) +
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
                        is Panel.TripFull -> panel = Panel.TripMini(p.ref)
                        is Panel.JourneyDetail -> panel = Panel.Journeys(p.to)
                        is Panel.Journeys -> panel = Panel.Place(p.to)
                        else -> exitRouteMode()
                    }
                }
                val isMini = p is Panel.RouteMini || p is Panel.TripMini
                BottomGlassPanel(
                    backdrop = backdrop,
                    shape = if (isMini) {
                        dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
                    } else {
                        dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape(
                            dev.antigravity.fluidengine.ui.fluid.FluidRadius.Sheet,
                        )
                    },
                    wholeSurfaceDrag = isMini,
                    showGrabber = !isMini,
                    // L'esteso si RIDUCE nel mini e il mini TORNA tab bar:
                    // rimbalzo sul posto piu' trasformazione, mai lo
                    // scivola-via-e-riappari segnalato come "roba strana".
                    transformOnDismiss = p !is Panel.Stop && p !is Panel.Place,
                    onDragExpand = when (p) {
                        is Panel.RouteMini -> ({ panel = Panel.RouteFull(p.routeIndex) })
                        is Panel.TripMini -> ({ panel = Panel.TripFull(p.ref) })
                        else -> null
                    },
                    onDragDismiss = {
                        when (p) {
                            is Panel.RouteFull -> panel = Panel.RouteMini(p.routeIndex)
                            is Panel.TripFull -> panel = Panel.TripMini(p.ref)
                            is Panel.JourneyDetail -> panel = Panel.Journeys(p.to)
                            is Panel.Journeys -> panel = Panel.Place(p.to)
                            else -> exitRouteMode()
                        }
                    },
                    modifier = Modifier
                        .padding(horizontal = FluidTabBarDefaults.HorizontalMargin)
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
                                is Panel.TripMini -> "tmini-${state.ref.vehKey}"
                                is Panel.TripFull -> "tfull-${state.ref.vehKey}"
                                is Panel.Place -> "place-${state.ref.lat}-${state.ref.lon}"
                                is Panel.Journeys -> "journeys-${state.to.lat}"
                                is Panel.JourneyDetail -> "jdetail-${state.index}"
                            }
                        },
                        label = "panelContent",
                    ) { state ->
                        when (state) {
                            is Panel.Stop -> Column {
                                val favVersion by app.favorites.version.collectAsStateWithLifecycle()
                                val isFav = remember(favVersion, state.tap.idHashHex) {
                                    app.favorites.isStopFavorite(state.tap.idHashHex)
                                }
                                StopPanelContent(
                                    reader = reader,
                                    stopIdHashHex = state.tap.idHashHex,
                                    fallbackName = state.tap.name,
                                    onStartHere = {
                                        val hash = state.tap.idHashHex.toULongOrNull(16)?.toLong()
                                        val s = hash?.let { reader.findStopByIdHash(it) } ?: -1
                                        if (s >= 0) {
                                            originRef = PlaceRef(
                                                reader.stopName(s), "Fermata",
                                                reader.stopLat(s), reader.stopLon(s),
                                            )
                                            openPlanner()
                                            if (destRef != null) runPlanner()
                                        }
                                    },
                                    onDismiss = { panel = null },
                                    onRouteTap = ::showRoute,
                                    backdrop = backdrop,
                                    isFavorite = isFav,
                                    onToggleFavorite = {
                                        app.favorites.toggleStop(state.tap.idHashHex, state.tap.name)
                                    },
                                    delays = app.delayModel,
                                    delaysStamp = rtDelays?.generatedAt ?: 0L,
                                    canceledTrips = resolved?.canceledTrips ?: emptySet(),
                                    liveVehicleTrips = resolved?.vehicleByTrip?.keys ?: emptySet(),
                                    onFlyToBus = { tripIdx ->
                                        val meta = resolved?.vehicleByTrip?.get(tripIdx)
                                            ?.let { vk -> resolved?.busMetaByKey?.get(vk) }
                                        if (meta != null) {
                                            showTrip(
                                                TripRef(
                                                    meta.vehKey, meta.tripHash, meta.routeHash,
                                                    meta.tripIndex, meta.routeIndex,
                                                ),
                                                focus = meta.lat to meta.lon,
                                            )
                                        }
                                    },
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
                                    val routeHashHex = remember(state.routeIndex) {
                                        java.lang.Long.toHexString(reader.routeIdHash(state.routeIndex))
                                    }
                                    val favVersion by app.favorites.version.collectAsStateWithLifecycle()
                                    val isFav = remember(favVersion, routeHashHex) {
                                        app.favorites.isRouteFavorite(routeHashHex)
                                    }
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
                                        isFavorite = isFav,
                                        onToggleFavorite = {
                                            app.favorites.toggleRoute(
                                                routeHashHex, info.shortName, info.colorRgb,
                                            )
                                        },
                                    )
                                }
                            }

                            is Panel.TripMini -> Column(
                                modifier = Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClickLabel = "Espandi la scheda della corsa",
                                    onClick = { panel = Panel.TripFull(state.ref) },
                                ),
                            ) {
                                val info = tripInfo
                                if (info != null && info.ref.vehKey == state.ref.vehKey) {
                                    TripMiniContent(info)
                                }
                            }

                            is Panel.TripFull -> Column {
                                val info = tripInfo
                                if (info != null && info.ref.vehKey == state.ref.vehKey) {
                                    val meta = resolved?.busMetaByKey?.get(state.ref.vehKey)
                                    // La guardia GPS decisa in Fase 2: pulsante solo se la
                                    // posizione e' coerente col mezzo; GPS spento = via libera.
                                    val guard = if (meta == null || info.ref.tripIndex < 0) {
                                        null
                                    } else {
                                        val loc = controller.lastLocation()
                                        when {
                                            loc == null -> "ok"
                                            dev.antigravity.fluidtransit.routing.BundleReader.haversine(
                                                loc.first, loc.second, meta.lat, meta.lon,
                                            ) <= 300.0 -> "ok"

                                            else -> "far"
                                        }
                                    }
                                    TripFullContent(
                                        info = info,
                                        fixAgeSec = resolved?.busMetaByKey
                                            ?.get(state.ref.vehKey)?.fixAgeSec,
                                        onStopTap = { stop ->
                                            exitRouteMode()
                                            controller.flyTo(stop.lat, stop.lon, 16.2)
                                            panel = Panel.Stop(
                                                StopTap(stop.idHashHex, stop.name),
                                            )
                                        },
                                        backdrop = backdrop,
                                        boardGuard = guard,
                                        onBoardBus = {
                                            val delay = resolved?.delayByTrip
                                                ?.get(info.ref.tripIndex) ?: 0
                                            val plan = buildBusNavPlan(
                                                reader, info.ref.tripIndex, delay,
                                            )
                                            if (plan != null) {
                                                app.navigation.start(context, plan)
                                                panel = null
                                            }
                                        },
                                    )
                                }
                            }

                            is Panel.Place -> Column {
                                PlacePanelContent(
                                    ref = state.ref,
                                    backdrop = backdrop,
                                    onDismiss = { exitRouteMode() },
                                    onGo = { goToPlace(state.ref) },
                                    onStartHere = {
                                        // Questo punto diventa la partenza:
                                        // dal tieni-premuto, da un posto
                                        // salvato o da un risultato di
                                        // ricerca, indifferentemente.
                                        originRef = state.ref
                                        controller.clearPlaceMarker()
                                        openPlanner()
                                        if (destRef != null) runPlanner()
                                    },
                                    onSave = { label ->
                                        app.savedPlaces.add(label, state.ref.lat, state.ref.lon)
                                        savedVersion++
                                        val id = app.savedPlaces.load()
                                            .firstOrNull { it.label.equals(label.trim(), true) }?.id
                                        panel = Panel.Place(
                                            PlaceRef(label.trim(), state.ref.name, state.ref.lat, state.ref.lon, id),
                                        )
                                    },
                                    onRemoveSaved = state.ref.savedId?.let { id ->
                                        {
                                            app.savedPlaces.remove(id)
                                            savedVersion++
                                            exitRouteMode()
                                        }
                                    },
                                )
                            }

                            is Panel.Journeys -> Column {
                                JourneysContent(
                                    toName = state.to.name,
                                    journeys = journeys,
                                    fromLabel = if (journeyFromGps) {
                                        "Dalla tua posizione"
                                    } else {
                                        "Dal centro della mappa (GPS spento)"
                                    },
                                    timeLabel = when (journeyTimeMode) {
                                        "depart" -> "Parti alle ${hhmm(journeyTimeEpoch)}"
                                        "arrive" -> "Arrivi entro ${hhmm(journeyTimeEpoch)}"
                                        else -> "Parti ora"
                                    },
                                    backdrop = backdrop,
                                    onTimeTap = { showTimeDialog = true },
                                    onPick = { i -> panel = Panel.JourneyDetail(state.to, i) },
                                    onDismiss = { panel = Panel.Place(state.to) },
                                )
                            }

                            is Panel.JourneyDetail -> Column {
                                val j = journeys?.getOrNull(state.index)
                                if (j != null) {
                                    JourneyDetailContent(
                                        j = j,
                                        toName = state.to.name,
                                        onDismiss = { panel = Panel.Journeys(state.to) },
                                        backdrop = backdrop,
                                        onStart = {
                                            val plan = buildNavPlan(reader, j.raw, state.to.name)
                                            app.navigation.start(context, plan)
                                            panel = null
                                            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                                                ContextCompat.checkSelfPermission(
                                                    context,
                                                    Manifest.permission.POST_NOTIFICATIONS,
                                                ) != PackageManager.PERMISSION_GRANTED
                                            ) {
                                                notifPermissionLauncher.launch(
                                                    Manifest.permission.POST_NOTIFICATIONS,
                                                )
                                            }
                                        },
                                        onCreateRoutine = { days, anchor, minutes ->
                                            val from = journeyOrigin
                                            if (from != null) {
                                                val routine =
                                                    dev.antigravity.fluidtransit.data.routines.Routines.Routine(
                                                        id = System.currentTimeMillis(),
                                                        label = "→ ${state.to.name}",
                                                        fromLat = from.first,
                                                        fromLon = from.second,
                                                        toLat = state.to.lat,
                                                        toLon = state.to.lon,
                                                        toName = state.to.name,
                                                        days = days,
                                                        anchor = anchor,
                                                        anchorMinutes = minutes,
                                                        enabled = true,
                                                    )
                                                app.routines.add(routine)
                                                dev.antigravity.fluidtransit.data.routines.RoutineScheduler
                                                    .scheduleNextCompute(context, routine)
                                                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                                                    ContextCompat.checkSelfPermission(
                                                        context,
                                                        Manifest.permission.POST_NOTIFICATIONS,
                                                    ) != PackageManager.PERMISSION_GRANTED
                                                ) {
                                                    notifPermissionLauncher.launch(
                                                        Manifest.permission.POST_NOTIFICATIONS,
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- il mini di navigazione: al posto della tab bar mentre viaggi --
        androidx.compose.animation.AnimatedVisibility(
            visible = navState != null && panel == null,
            enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it / 3 }) +
                androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it / 3 }) +
                androidx.compose.animation.fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        ) {
            navState?.let { s ->
                BottomGlassPanel(
                    backdrop = backdrop,
                    shape = dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape,
                    wholeSurfaceDrag = false,
                    showGrabber = false,
                    onDragDismiss = { },
                    modifier = Modifier
                        .padding(horizontal = FluidTabBarDefaults.HorizontalMargin)
                        .padding(bottom = FluidTabBarDefaults.BottomMargin),
                ) {
                    NavMiniContent(
                        state = s,
                        onStop = { app.navigation.stop(context) },
                    )
                }
            }
        }

        // --- l'assistente: un pannello appoggiato in basso, non un dialogo.
        // La mappa resta viva sopra e sotto, e mentre lui cerca una linea si
        // vedono i bus muoversi — che e' il momento in cui uno vuole
        // guardarli.
        if (assistantOpen) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = FluidTabBarDefaults.HorizontalMargin)
                    .padding(bottom = FluidTabBarDefaults.BottomMargin),
            ) {
                dev.antigravity.fluidtransit.ui.assistant.AssistantOverlay(
                    app = app,
                    backdrop = backdrop,
                    startMode = assistantMode,
                    initialQuestion = assistantQuestion,
                    onPlace = { name ->
                        // Il chip apre il posto con la stessa ricerca della
                        // barra: un solo criterio, una sola risposta.
                        val hit = app.assistantBridge.findStops(name, 1).firstOrNull()
                        if (hit != null) {
                            assistantOpen = false
                            panel = Panel.Stop(StopTap(hit.idHashHex, hit.name))
                            controller.flyTo(hit.lat, hit.lon, 16.0)
                        } else {
                            query = name
                            assistantOpen = false
                            searchOpen = true
                        }
                    },
                    onClose = { assistantOpen = false },
                )
            }
            BackHandler { assistantOpen = false }
        }

    }
}

private fun RecentSearches.Entry.toSuggestion() =
    Suggestion(kind, key, title, subtitle, colorRgb, lat, lon)

private fun hhmm(epochSecond: Long): String {
    if (epochSecond <= 0) return "—"
    val z = java.time.ZonedDateTime.ofInstant(
        Instant.ofEpochSecond(epochSecond),
        dev.antigravity.fluidtransit.routing.Ftb.ROME,
    )
    return "%02d:%02d".format(z.hour, z.minute)
}
