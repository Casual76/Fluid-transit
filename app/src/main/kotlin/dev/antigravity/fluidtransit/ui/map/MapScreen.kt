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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.rememberUpdatedState
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
/**
 * Oltre questa eta' del feed i bus non si disegnano: meglio una mappa senza
 * mezzi per due secondi che mezzi dove non sono. Il tetto vero dell'origine
 * e' ~120 s, quindi tre minuti separano "normale" da "il proxy dormiva".
 */
private const val STALE_HIDE_SECONDS = 180L

/**
 * Sotto questa distanza partenza e arrivo sono lo stesso posto.
 *
 * Sessanta metri: la larghezza di un incrocio. Chi deve fare sessanta metri
 * li fa a piedi senza chiederlo a un'app, e un viaggio da qui a qui e' la
 * risposta giusta a una domanda che nessuno ha fatto.
 */
private const val SAME_PLACE_M = 60.0

@androidx.compose.runtime.Composable
@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun MapScreen(
    app: FluidTransitApp,
    backdrop: GlassBackdropState,
    onTabBarHidden: (Boolean) -> Unit = {},
    intent: MapIntent? = null,
    onIntentConsumed: () -> Unit = {},
    onOpenDataStatus: () -> Unit = {},
    onOpenAlerts: () -> Unit = {},
) {
    val context = LocalContext.current
    // Lo scuro della mappa segue il tema DELL'APP, non quello di sistema:
    // chi forza "Scuro" dalle Impostazioni deve vedere anche la mappa scura.
    // La luminanza dello sfondo Material e' la verita' gia' risolta da
    // FluidTheme, qualunque sia la combinazione di impostazioni.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bundleState by app.bundleManager.state.collectAsStateWithLifecycle()
    val ready = bundleState as? BundleState.Ready

    // Le scelte fatte sulla mappa si ritrovano.
    //
    // La vista e il filtro stavano solo nello stato salvabile: sopravvivevano
    // a una rotazione e non alla chiusura dell'app. Chi metteva il filtro su
    // "Urbani" o passava al satellite li ritrovava come prima il giorno dopo.
    // Sono piccole, ma sono della stessa famiglia del "si comporta in modo
    // diverso ogni volta": una scelta fatta apposta che sparisce da sola. E
    // ricordarle non nasconde niente, perche' i chip e il tasto dicono sempre
    // in che stato sono.
    val mapPrefs = remember(context) { MapPrefs(context) }
    var mode by rememberSaveable { mutableStateOf(mapPrefs.mode) }
    var filter by rememberSaveable { mutableStateOf(mapPrefs.filter) }
    var follow by rememberSaveable { mutableStateOf(FollowMode.FREE) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var panel by rememberSaveable(stateSaver = PanelSaver) {
        mutableStateOf<Panel?>(null)
    }
    var routeDirection by rememberSaveable { mutableStateOf(0) }

    /**
     * Il guardiano del bundle.
     *
     * Un pannello salvato porta dentro degli indici, e gli indici valgono
     * solo nel bundle che li ha prodotti. Se l'app e' stata sfrattata dalla
     * memoria e nel frattempo e' passato l'aggiornamento notturno, quegli
     * indici puntano a una linea diversa: il pannello si butta, e si riparte
     * dalla mappa. Nel caso normale — rotazione, cambio di scheda — il
     * bundle e' lo stesso e non succede niente.
     */
    var panelBuild by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(ready?.buildId) {
        val id = ready?.buildId ?: return@LaunchedEffect
        if (panelBuild != 0L && panelBuild != id) panel = null
        panelBuild = id
    }

    // Lo zoom corrente (a camera ferma): decide se i bus vivi si scaricano.
    //
    // Salvabile, non solo `remember`: e' proprio questo valore che riaccende
    // il polling, e tornandoci sopra da un'altra scheda ripartiva da
    // HOME_ZOOM (7.6), sotto la soglia. Si tornava sulla mappa e il live era
    // spento finche' non si zoomava di nuovo a mano.
    var cameraZoom by rememberSaveable { mutableStateOf(MapCatalog.HOME_ZOOM) }

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

    // Il permesso negato per sempre non deve diventare un tasto morto.
    //
    // Toccando il mirino, se il permesso e' stato negato due volte, Android
    // non mostra piu' nessuna finestra: la richiesta torna indietro negata
    // all'istante e il tasto non faceva niente. Un tasto che non fa niente
    // e' peggio di un tasto che manca, perche' insegna a non fidarsi anche
    // degli altri — e questo e' il tasto con cui si chiede "dove sono".
    //
    // Dopo la finestra, negato piu' "non mostrare la spiegazione" vuol dire
    // esattamente questo, ed e' l'unico momento in cui quella combinazione
    // non e' ambigua: prima della prima richiesta e' falsa anche per chi non
    // ha mai deciso niente.
    val notifications = dev.antigravity.fluidengine.ui.fluid.LocalFluidNotificationHostState.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        locationGranted = granted
        if (granted) {
            follow = FollowMode.FOLLOW
        } else {
            val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>()
                .firstOrNull()
            val perSempre = activity != null &&
                !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.ACCESS_FINE_LOCATION,
                )
            if (perSempre && activity != null) {
                scope.launch {
                    notifications?.show(
                        dev.antigravity.fluidengine.ui.fluid.FluidNotification(
                            id = "posizione-negata",
                            title = "Il permesso di posizione e' negato",
                            message = "Android non lo chiede piu'. Ti porto dov'e' " +
                                "l'interruttore: senza, la mappa usa il centro di " +
                                "quello che stai guardando.",
                            tone = dev.antigravity.fluidengine.ui.fluid
                                .FluidNotificationTone.Warning,
                        ),
                    )
                }
                runCatching {
                    activity.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                }
            }
        }
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
    // Aspetta i gruppi di banchine: senza, uscirebbero le righe doppie che
    // l'indice esiste per evitare.
    val stopGroups by app.stopGroups.collectAsStateWithLifecycle()

    // L'indice lo costruisce l'Application: qui si guarda e basta. Prima lo
    // costruiva questa schermata e lo prestava all'assistente, che quindi
    // senza mappa aperta cercava dentro il niente.
    val searchIndex by app.searchIndex.collectAsStateWithLifecycle()

    // La geometria delle tratte, decodificata pigramente: e' quella che fa
    // correre i bus sulla strada invece di attraversare gli isolati.
    val pathCache = remember(ready?.buildId) {
        ready?.reader?.let { PathCache(it, app.applicationScope) }
    }
    LaunchedEffect(pathCache) { controller.setPathCache(pathCache) }

    // Quello che l'assistente non puo' sapere da solo: dove sei e dove stai
    // guardando. Il resto adesso se lo prende da se'.
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
    val online by app.online.collectAsStateWithLifecycle()

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
        // Il reader puo' chiudersi sotto: lo scambio notturno del bundle
        // succede mentre l'app e' aperta, e questo calcolo gira fuori dal
        // thread della UI. Un'eccezione qui dentro portava giu' l'app; adesso
        // resta lo scheletro, e la chiave del produceState (il buildId) fa
        // ripartire il calcolo col bundle nuovo.
        value = withContext(Dispatchers.Default) {
            runCatching {
                RouteInfo.build(reader, idx, Instant.now(), app.departureBoards.live())
            }.getOrNull()
        }
    }

    // Gli avvisi delle linee che passano dalla fermata aperta.
    //
    // Il feed della Regione non nomina mai le fermate negli avvisi —
    // contati sul feed vero: 746 riferimenti, tutti a linee, zero a
    // fermate — quindi "questa fermata e' spostata" non si puo' sapere. Le
    // linee si', ed e' quello che serve a chi sta li' ad aspettare.
    val currentStopHash = (panel as? Panel.Stop)?.tap?.idHashHex
    val avvisiDiFermata by produceState(
        initialValue = emptyList<String>(),
        currentStopHash, ready?.buildId,
    ) {
        val reader = ready?.reader
        val hex = currentStopHash
        if (reader == null || hex == null) {
            value = emptyList()
            return@produceState
        }
        val stop = hex.toULongOrNull(16)?.toLong()?.let { reader.findStopByIdHash(it) } ?: -1
        if (stop < 0) {
            value = emptyList()
            return@produceState
        }
        val linee = HashMap<Long, String>()
        for (pattern in reader.patternsAtStop(stop)) {
            val route = reader.patternRoute(pattern)
            if (route >= 0) {
                linee[reader.routeIdHash(route)] = reader.routeShortName(route)
                    .ifEmpty { reader.routeLongName(route) }
            }
        }
        val tutti = runCatching { app.realtime.fetchAlerts() }.getOrDefault(emptyList())
        val adesso = java.time.Instant.now().epochSecond
        value = tutti
            .filter { a ->
                a.routeHashes.any { it in linee.keys } &&
                    dev.antigravity.fluidtransit.routing.AlertText
                        .active(a.startEpoch, a.endEpoch, adesso)
            }
            // Il piu' recente per primo: su una fermata di stazione ce ne
            // sono venti in corso, e due sole stanno in cima. Fra un avviso
            // cominciato l'anno scorso e uno di ieri, quello che una persona
            // non sa ancora e' il secondo.
            .sortedByDescending { it.startEpoch }
            .map { a ->
                val quali = a.routeHashes.mapNotNull { linee[it] }.distinct().take(3)
                val testo = a.header.ifEmpty {
                    dev.antigravity.fluidtransit.routing.AlertText.body(a.description).take(80)
                }
                if (quali.isEmpty()) testo else quali.joinToString(", ") + " · " + testo
            }
    }

    // Gli avvisi della corsa aperta: sono quelli della sua linea.
    //
    // Chi ha in mano la scheda di un bus vivo o ci e' sopra o lo aspetta, e
    // in tutt'e due i casi una deviazione in corso e' la cosa che cambia i
    // suoi piani. La scheda gliela nascondeva.
    val currentTripRoute = (panel as? Panel.TripMini)?.ref?.routeHash
        ?: (panel as? Panel.TripFull)?.ref?.routeHash
    val avvisiDiCorsa by produceState(initialValue = emptyList<String>(), currentTripRoute) {
        val hash = currentTripRoute
        if (hash == null || hash == 0L) {
            value = emptyList()
            return@produceState
        }
        val tutti = runCatching { app.realtime.fetchAlerts() }.getOrDefault(emptyList())
        val adesso = java.time.Instant.now().epochSecond
        value = tutti
            .filter { a ->
                a.routeHashes.contains(hash) &&
                    dev.antigravity.fluidtransit.routing.AlertText
                        .active(a.startEpoch, a.endEpoch, adesso)
            }
            .sortedByDescending { it.startEpoch }
            .map { a ->
                a.header.ifEmpty {
                    dev.antigravity.fluidtransit.routing.AlertText.body(a.description).take(90)
                }
            }
    }

    // Gli avvisi di servizio della linea aperta.
    //
    // L'app li aveva e non li diceva dove servono: aprendo la 12 mentre e'
    // deviata, il pannello raccontava orari e fermate come se niente fosse.
    // Un avviso di servizio e' l'unica cosa che puo' rendere sbagliato tutto
    // il resto di quel pannello, e saperlo dopo non serve.
    //
    // Si chiedono solo quando una scheda linea e' davvero aperta, e il
    // recupero ha cinque minuti di cache: aprirne una seconda non ricarica
    // niente.
    val avvisiDiLinea by produceState(
        initialValue = emptyList<String>(),
        currentRouteIndex, ready?.buildId,
    ) {
        val reader = ready?.reader
        val idx = currentRouteIndex
        if (reader == null || idx == null) {
            value = emptyList()
            return@produceState
        }
        val hash = runCatching { reader.routeIdHash(idx) }.getOrNull()
        val tutti = runCatching { app.realtime.fetchAlerts() }.getOrDefault(emptyList())
        val adesso = java.time.Instant.now().epochSecond
        value = tutti
            .filter { a ->
                hash != null && a.routeHashes.contains(hash) &&
                    dev.antigravity.fluidtransit.routing.AlertText
                        .active(a.startEpoch, a.endEpoch, adesso)
            }
            // Il piu' recente per primo, come sulla fermata.
            .sortedByDescending { it.startEpoch }
            .map { a ->
                a.header.ifEmpty {
                    dev.antigravity.fluidtransit.routing.AlertText.body(a.description).take(90)
                }
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
        // Come la scheda linea: il bundle puo' cambiare mentre si calcola.
        value = withContext(Dispatchers.Default) {
            runCatching {
                TripInfo.build(
                    reader = reader,
                    ref = ref,
                    now = Instant.now(),
                    delaySec = d?.takeIf { !it.noData }?.delaySec,
                    canceled = d?.canceled == true,
                    live = app.departureBoards.live(),
                )
            }.getOrNull()
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

    // Da dove parte il viaggio, in coordinate. Salvabile come il pannello:
    // senza, un pannello dei viaggi ripristinato non aveva piu' una partenza
    // e restava a "Cerco i prossimi viaggi..." per sempre.
    var journeyOrigin by rememberSaveable(stateSaver = LatLonSaver) {
        mutableStateOf<Pair<Double, Double>?>(null)
    }

    /**
     * Da dove parte il viaggio, detto a parole.
     *
     * Erano due soli casi, un booleano: la tua posizione oppure il centro
     * della mappa. Ma una partenza scelta — dal pianificatore, dall'assistente,
     * dalla notifica di una routine — non e' ne' l'una ne' l'altro, e finiva
     * etichettata "Dal centro della mappa (GPS spento)" mentre il calcolo
     * partiva, correttamente, dal punto scelto. Il numero era giusto e la
     * frase era falsa, che e' il modo peggiore di sbagliare.
     */
    var journeyFrom by rememberSaveable { mutableStateOf("Dalla tua posizione") }
    var journeyTimeMode by rememberSaveable { mutableStateOf("now") } // now | depart | arrive
    var journeyTimeEpoch by rememberSaveable { mutableStateOf(0L) }
    var showTimeDialog by remember { mutableStateOf(false) }

    // Il pianificatore Da/A. `originRef` nullo vuol dire "da dove sei":
    // resta il caso normale, ma smette di essere l'unico possibile.
    var plannerOpen by rememberSaveable { mutableStateOf(false) }
    var originRef by rememberSaveable(stateSaver = PlaceRefSaver) {
        mutableStateOf<PlaceRef?>(null)
    }
    var destRef by rememberSaveable(stateSaver = PlaceRefSaver) {
        mutableStateOf<PlaceRef?>(null)
    }

    /** Quale delle due righe sta compilando la ricerca: "from", "to", o niente. */
    var plannerField by rememberSaveable { mutableStateOf<String?>(null) }

    // "Perche' questo numero": la riga toccata e il rettangolo da cui il
    // pop-up nasce. Non e' salvabile di proposito — e' una risposta a un
    // tocco, non uno stato in cui si resta.
    var whyRow by remember {
        mutableStateOf<dev.antigravity.fluidtransit.routing.NextDeparture?>(null)
    }
    var whyOrigin by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var whyAt by remember { mutableStateOf(0L) }

    /** Calcola, quando c'e' abbastanza per calcolare. */
    fun runPlanner() {
        val to = destRef ?: return
        val from = originRef
        if (from != null) {
            journeyFrom = "Da ${from.name}"
            journeyOrigin = from.lat to from.lon
        } else {
            // Dalla posizione GPS se c'e', dal centro mappa se no — e la
            // differenza si dichiara nel pannello, non si nasconde.
            val loc = controller.lastLocation()
            journeyFrom = if (loc != null) {
                "Dalla tua posizione"
            } else {
                "Dal centro della mappa (GPS spento)"
            }
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

    /**
     * Il pianificatore, aperto sulla domanda che si stava per fare.
     *
     * Apriva due righe vuote, e per cominciare a scrivere ne serviva una
     * terza di tocchi: barra, "calcola un percorso", riga "A". Ma chi apre il
     * pianificatore sa gia' dove vuole andare — e' il "da dove" che quasi
     * sempre e' scontato, perche' e' dove sei. Quindi si va dritti a scegliere
     * la destinazione, con la tastiera gia' su; la riga "Da" resta li' sotto
     * per quando non e' scontata.
     */
    fun openPlanner() {
        query = ""
        plannerOpen = true
        plannerField = "to"
        searchOpen = true
    }

    // Le richieste da fuori: da un'altra scheda (Preferiti, Oggi) o da fuori
    // dall'app (un widget, una notifica, un deep link). Si consumano appena
    // bundle e mappa ci sono — chi arriva da una notifica con l'app spenta
    // aspetta qui il caricamento degli orari, invece di trovare la mappa
    // generica e doversi cercare la fermata a mano.
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

            is MapIntent.Journey -> {
                originRef = if (i.fromLat != null && i.fromLon != null) {
                    // La routine tiene le coordinate della partenza ma non il
                    // suo nome: meglio dire cos'e' che inventarle un posto.
                    PlaceRef("partenza abituale", "", i.fromLat, i.fromLon)
                } else {
                    null
                }
                destRef = PlaceRef(i.toName, "", i.toLat, i.toLon)
                journeyTimeMode = "now"
                plannerOpen = true
                runPlanner()
            }
        }
        onIntentConsumed()
    }

    // Le azioni dell'assistente le esegue la mappa, perche' e' l'unica che
    // puo': il modulo dell'assistente non sa niente di pannelli e di camera.
    // Il corpo si rilegge a ogni composizione, il collector no.
    //
    // `LaunchedEffect(Unit)` non riparte mai — e deve restare cosi', o
    // riavviare il collector perderebbe le azioni in coda — ma per questo si
    // portava dietro per sempre lo stato della PRIMA composizione: il bundle
    // di allora, e le funzioni locali che lo usano. Dopo lo scambio notturno
    // quel reader e' chiuso, e le azioni dell'assistente ci lavoravano sopra.
    //
    // rememberUpdatedState tiene ferma la sottoscrizione e fresco il corpo.
    val handleAction by rememberUpdatedState<
        suspend (dev.antigravity.fluidtransit.ai.tools.AssistantAction) -> Unit,
        > { action ->
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

                // Le altre azioni (togliere una stella, spegnere una routine, fermare la
                // navigazione) non hanno bisogno della mappa: le esegue il ponte da se', anche
                // quando a chiedere e' un assistente esterno e questa schermata non esiste.
                else -> Unit
            }
    }
    LaunchedEffect(Unit) {
        app.assistantBridge.actions.collect { handleAction(it) }
    }

    val journeysTarget = when (val p = panel) {
        is Panel.Journeys -> p.to
        is Panel.JourneyDetail -> p.to
        else -> null
    }
    // Il calcolo e' fallito, che e' diverso da "non c'e' niente".
    var journeysFailed by remember { mutableStateOf(false) }
    val journeys by produceState<List<UiJourney>?>(
        initialValue = null,
        // La PARTENZA fra le chiavi, che e' dove mancava.
        //
        // Il calcolo la leggeva ma non ci si riavviava sopra: si cambiava la
        // riga "Da" nel pianificatore, l'intestazione diceva il posto nuovo —
        // quella si aggiorna da un'altra parte — e sotto restavano i viaggi
        // calcolati dal posto vecchio. Anche il tasto che scambia partenza e
        // arrivo non cambiava niente. Un pianificatore che risponde alla
        // domanda di prima e sembra aver risposto a quella nuova e' il modo
        // piu' diretto di far perdere fiducia a chi lo usa.
        journeysTarget, journeyOrigin, journeyTimeMode, journeyTimeEpoch, ready?.buildId,
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
            dev.antigravity.fluidtransit.routing.Raptor.Realtime(
                rtNow.delayByTrip,
                rtNow.canceledTrips,
                java.time.Instant.now().epochSecond,
                // Le stesse previsioni che usa il tabellone della fermata.
                //
                // Senza, il motore applicava a tutta la corsa il primo
                // ritardo dichiarato, e il tabellone la previsione della
                // fermata giusta: sul feed delle 07:30 le due cose
                // divergevano in media di 78 secondi, oltre il minuto su un
                // terzo delle corse. Stesso bus, stessa fermata, due orari.
                live = app.departureBoards.live(),
            )
        } else {
            dev.antigravity.fluidtransit.routing.Raptor.Realtime.NONE
        }
        journeysFailed = false
        val raw = withContext(app.routingDispatcher) {
            val raptor = app.raptorFor(reader)
            val fromPlace = dev.antigravity.fluidtransit.routing.Raptor.Place(from.first, from.second)
            val toPlace = dev.antigravity.fluidtransit.routing.Raptor.Place(to.lat, to.lon)
            runCatching {
            when (journeyTimeMode) {
                "arrive" -> raptor.planArriveBy(
                    fromPlace, toPlace, Instant.ofEpochSecond(journeyTimeEpoch), liveData,
                )

                "depart" -> raptor.plan(
                    fromPlace, toPlace, Instant.ofEpochSecond(journeyTimeEpoch), liveData,
                )

                else -> raptor.plan(fromPlace, toPlace, Instant.now(), liveData)
            }
            }.getOrNull()
        }
        // Un calcolo fallito non e' "nessun viaggio": il primo dice che non
        // abbiamo saputo rispondere, il secondo che la risposta e' no. Erano
        // la stessa schermata — "Nessun viaggio trovato, prova a cambiare
        // orario" — e cambiare orario non serviva a niente.
        if (raw == null) {
            journeysFailed = true
            value = emptyList()
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            // Le corse davvero seguite dal feed: serve per distinguere
            // "monitorata e puntuale" da "non se ne sa niente".
            val liveTrips = rtNow?.delayByTrip?.keys.orEmpty()
            runCatching { raw.map { UiJourney.of(reader, it, liveTrips) } }
                .getOrElse {
                    journeysFailed = true
                    emptyList()
                }
        }
    }

    // Gli avvisi delle linee del viaggio aperto.
    //
    // Il motore calcola sul percorso di tabella: se una delle linee oggi e'
    // deviata, il viaggio proposto puo' non esistere come e' scritto.
    val currentJourneyRoutes = (panel as? Panel.JourneyDetail)
        ?.let { journeys?.getOrNull(it.index) }
        ?.raw?.legs
        ?.filterIsInstance<dev.antigravity.fluidtransit.routing.Raptor.Leg.Ride>()
        ?.map { it.route }
        ?.distinct()
    val avvisiDiViaggio by produceState(
        initialValue = emptyList<String>(),
        currentJourneyRoutes, ready?.buildId,
    ) {
        val reader = ready?.reader
        val routes = currentJourneyRoutes
        if (reader == null || routes.isNullOrEmpty()) {
            value = emptyList()
            return@produceState
        }
        val linee = routes.associate { r ->
            reader.routeIdHash(r) to reader.routeShortName(r).ifEmpty { reader.routeLongName(r) }
        }
        val tutti = runCatching { app.realtime.fetchAlerts() }.getOrDefault(emptyList())
        val adesso = java.time.Instant.now().epochSecond
        value = tutti
            .filter { a ->
                a.routeHashes.any { it in linee.keys } &&
                    dev.antigravity.fluidtransit.routing.AlertText
                        .active(a.startEpoch, a.endEpoch, adesso)
            }
            .sortedByDescending { it.startEpoch }
            .map { a ->
                val quali = a.routeHashes.mapNotNull { linee[it] }.distinct().take(3)
                val testo = a.header.ifEmpty {
                    dev.antigravity.fluidtransit.routing.AlertText.body(a.description).take(80)
                }
                if (quali.isEmpty()) testo else quali.joinToString(", ") + " · " + testo
            }
    }

    // Il viaggio scelto si accende sulla mappa mentre lo stai SCEGLIENDO, e
    // la camera lo inquadra.
    //
    // Mentre lo stai facendo, no: durante la navigazione il percorso non si
    // disegna. La camminata era una retta tratteggiata fra due punti — che
    // taglia palazzi, fiumi e ferrovie — e mostrarla mentre uno cammina
    // davvero e' peggio che non mostrare niente: si vede dove sei, e basta.
    // Il percorso del bus arriva dopo, quando sali, dalla modalita' linea.
    LaunchedEffect(panel, journeys, navState) {
        val p = panel
        val r2 = ready?.reader
        if (p is Panel.JourneyDetail && r2 != null) {
            val j = journeys?.getOrNull(p.index) ?: return@LaunchedEffect
            val (shape, bbox) = withContext(Dispatchers.Default) {
                buildJourneyGeometry(r2, j.raw)
            }
            controller.showJourney(shape)
            if (bbox[0] <= bbox[2]) controller.flyToBounds(bbox[0], bbox[1], bbox[2], bbox[3])
        } else {
            controller.clearJourney()
        }
    }

    // Sali sul bus e la mappa ci si mette da sola: la tratta si accende, le
    // sue fermate compaiono a qualunque zoom, e i mezzi vivi corrono sopra
    // con la loro direzione. E' la stessa modalita' linea che si ottiene
    // toccando un bus, solo che qui non serve toccare niente.
    //
    // Si spegne quando scendi, e SOLO se e' stata accesa da qui: se nel
    // frattempo hai aperto tu una scheda linea, quella resta com'e'.
    var navLitRoute by remember { mutableStateOf(-1) }
    LaunchedEffect(navState?.phase, navState?.rideRoute) {
        val s = navState
        val reader = ready?.reader
        val route = if (s != null && s.phase == "ride") s.rideRoute else -1
        if (route == navLitRoute) return@LaunchedEffect

        if (route >= 0 && reader != null) {
            navLitRoute = route
            withContext(Dispatchers.Default) {
                val hashes = LinkedHashSet<String>()
                for (pat in reader.patternsOfRoute(route)) {
                    val n = reader.patternStopCount(pat)
                    for (i in 0 until n) {
                        hashes.add(
                            java.lang.Long.toHexString(
                                reader.stopIdHash(reader.patternStop(pat, i)),
                            ),
                        )
                    }
                }
                val rh = java.lang.Long.toHexString(reader.routeIdHash(route))
                withContext(Dispatchers.Main) {
                    controller.enterRouteMode(rh, hashes.toTypedArray())
                }
            }
        } else if (navLitRoute >= 0) {
            navLitRoute = -1
            // Solo se non c'e' una scheda linea o corsa aperta: quella l'ha
            // voluta l'utente e non la si spegne alle sue spalle.
            if (panel !is Panel.RouteMini && panel !is Panel.RouteFull &&
                panel !is Panel.TripMini && panel !is Panel.TripFull
            ) {
                controller.exitRouteMode()
            }
        }
    }

    // Un primo giro appena il bundle e' pronto, senza aspettare lo zoom.
    //
    // Il polling CONTINUO resta legato allo zoom, che e' giusto: i mezzi si
    // disegnano da 10.2 in su e tenerli aggiornati sotto sarebbe traffico
    // buttato. Ma il PRIMO dato deve esistere lo stesso — lo leggono la
    // scheda Oggi, i Preferiti, il widget e l'assistente, che non zoomano
    // niente — e quando poi si zooma i bus sono gia' in scena invece di
    // comparire mezzo minuto dopo. E' la meta' dell'impressione che il live
    // "si accenda solo dopo l'apertura".
    LaunchedEffect(ready?.buildId) {
        if (ready == null) return@LaunchedEffect
        runCatching { rt.refreshVehicles() }
        // E i ritardi, subito: sono l'unica cosa che distingue un tabellone
        // vero da uno di tabella, e finora partivano solo quando si apriva un
        // pannello. Chi apriva l'app, toccava una fermata e leggeva il primo
        // fotogramma vedeva "orario da tabella" per un paio di secondi, cioe'
        // esattamente il momento in cui si fa l'idea che il live non ci sia.
        runCatching { rt.refreshDelays() }
        runCatching { rt.refreshPredictions() }
    }

    // --- i cicli del realtime: vivono col ciclo di vita della schermata ----
    // Bus: solo quando lo zoom li rende visibili (o una scheda corsa e'
    // aperta). Il ritmo lo decide lo stato del client: 30 s dal proxy,
    // 3 min in diretta. Il tick a ~8 Hz fa scivolare i marker.
    //
    // Derivato e non letto dritto: `cameraZoom` cambia a ogni pizzicata, e
    // leggerlo qui dentro voleva dire ricomporre una schermata da duemila
    // righe ogni volta che la mappa si ferma. Quello che interessa e' il
    // BOOLEANO, che cambia una volta ogni tanto; derivedStateOf lascia
    // passare solo quello.
    val vehiclesActive by remember {
        androidx.compose.runtime.derivedStateOf {
            ready != null &&
                (
                    cameraZoom >= MapCatalog.BUS_MIN_ZOOM - 0.6 ||
                        panel is Panel.TripMini || panel is Panel.TripFull ||
                        // In modalita' linea i bus della tratta si vedono da
                        // qualunque zoom: il polling deve accompagnarli.
                        panel is Panel.RouteMini || panel is Panel.RouteFull
                    )
        }
    }
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
                rt.refreshPredictions()
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
        val age = rtStatus.feedAgeSeconds
        val fresh = age == null || age <= STALE_HIDE_SECONDS
        controller.setBuses(if (fresh) resolved?.buses ?: emptyList() else emptyList())
    }

    // Cosa passa qui intorno. Il come sta in NearbyPanel.kt, insieme al
    // pannello che lo mostra: la schermata chiede il tabellone e basta.
    val nearbyBoard = rememberNearbyBoard(
        app = app,
        reader = ready?.reader,
        buildId = ready?.buildId,
        stopGroups = stopGroups,
        follow = follow,
        here = { controller.lastLocation() ?: controller.cameraCenter() },
    )

    // Le ricerche recenti e i suggerimenti del pannello.
    //
    // Da dove si misura: dove sei, e se non lo sappiamo il centro della
    // mappa. E' lo stesso riferimento della ricerca e di "qui intorno":
    // tre elenchi che si vedono insieme non possono misurare da tre posti.
    val dovePerLaDistanza = dev.antigravity.fluidtransit.routing.Reference.point(
        controller.lastLocation(), controller.cameraCenter(),
    )
    val recentStore = remember { RecentSearches(context) }
    var recentsVersion by remember { mutableStateOf(0) }
    val recents = remember(recentsVersion) { recentStore.load() }
    val nearby by produceState(initialValue = emptyList<Suggestion>(), searchOpen, ready?.buildId) {
        val reader = ready?.reader
        if (!searchOpen || reader == null) {
            value = emptyList()
            return@produceState
        }
        val center = dev.antigravity.fluidtransit.routing.Reference.point(
            controller.lastLocation(), controller.cameraCenter(),
        ) ?: return@produceState
        value = withContext(Dispatchers.Default) {
            // `stopsNear` esce gia' dalla piu' vicina: prendere le prime
            // cinque vuol dire prendere le cinque piu' vicine.
            reader.stopsNear(center.first, center.second, 700.0)
                .take(5)
                .map { s ->
                    Suggestion(
                        kind = "stop",
                        key = java.lang.Long.toHexString(reader.stopIdHash(s)),
                        title = reader.stopName(s),
                        // Con la distanza, come i recenti e i risultati: e'
                        // l'unico elenco dei tre che non ce l'aveva, ed e'
                        // quello ordinato PER distanza — quindi l'unica cosa
                        // che diceva sul suo ordine bisognava indovinarla.
                        subtitle = "Fermata · a " +
                            dev.antigravity.fluidtransit.routing.Words.distance(
                                dev.antigravity.fluidtransit.routing.BundleReader.haversine(
                                    center.first, center.second,
                                    reader.stopLat(s), reader.stopLon(s),
                                ),
                            ),
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
        // Dove porta un suggerimento sta in `SuggestionTarget`, con il
        // perche' e il suo test: la riserva e' il luogo, non la linea.
        when (targetOf(s.kind)) {
            SuggestionTarget.STOP -> {
                controller.exitRouteMode()
                controller.flyTo(s.lat, s.lon, 16.2)
                panel = Panel.Stop(StopTap(s.key, s.title))
            }

            SuggestionTarget.ROUTE -> {
                // La chiave e' l'hash del route_id: stabile fra i bundle, al
                // contrario dell'indice che ogni notte cambia.
                val reader = ready?.reader
                val hash = s.key.toULongOrNull(16)?.toLong()
                if (reader != null && hash != null) {
                    val idx = reader.findRouteByIdHash(hash)
                    if (idx >= 0) showRoute(idx)
                }
            }

            SuggestionTarget.PLACE -> showPlace(
                PlaceRef(
                    name = s.title,
                    context = s.subtitle
                        .takeIf { it != "Luogo" && it != "Il tuo posto" && it != "Indirizzo" }
                        ?: "",
                    lat = s.lat,
                    lon = s.lon,
                    savedId = if (s.kind == "saved") s.key.toLongOrNull() else null,
                ),
            )
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
            // Anche sul disco, per la prossima apertura: senza posizione la
            // mappa ripartiva dalla Toscana intera, che e' una vista da cui
            // non si fa niente.
            mapPrefs.camera = it
        }
        // Letta SENZA osservarla.
        //
        // Serve una volta sola, per dire alla mappa da dove partire. Ma un
        // `DoubleArray` nuovo a ogni fermata della camera non e' mai uguale
        // al precedente, quindi ogni pan — anche senza cambiare zoom —
        // ricomponeva tutto quello che l'aveva letta, cioe' questa schermata
        // intera. Leggerla fuori dall'osservazione la rende quello che e':
        // un valore iniziale, non uno stato.
        val cameraDiPartenza = androidx.compose.runtime.snapshots.Snapshot
            .withoutReadObservation { savedCamera.value }
            ?: remember { mapPrefs.camera }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassBackdropSource(backdrop),
        ) {
            TransitMap(
                controller = controller,
                modifier = Modifier.fillMaxSize(),
                initialCamera = cameraDiPartenza,
            )
        }

        // --- chrome in alto: la barra che diventa pannello, e i filtri ---
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .widthIn(max = PanelMaxWidth)
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
            // I risultati della ricerca. Il come sta in SearchResults.kt.
            val risultati = rememberSearchResults(
                query = query,
                searchIndex = searchIndex,
                places = placesState,
                reader = ready?.reader,
                // Da dove si pesa la vicinanza: la regola sta in
                // `Reference`, ed e' la stessa dei recenti, delle fermate
                // vicine e dell'assistente.
                reference = {
                    dev.antigravity.fluidtransit.routing.Reference.point(
                        controller.lastLocation(), controller.cameraCenter(),
                    )
                },
            )

            if (plannerOpen && !searchOpen) {
                PlannerGlass(
                    backdrop = backdrop,
                    from = originRef,
                    to = destRef,
                    defaultFrom = if (locationGranted && controller.lastLocation() != null) {
                        "La tua posizione"
                    } else {
                        "Il centro della mappa"
                    },
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
                placesReady = placesState is
                    dev.antigravity.fluidtransit.data.places.PlacesManager.State.Ready,
                // I civici arrivano dopo, ma entrano nella stessa lista e si
                // ordinano insieme agli altri: stessa scala di pertinenza.
                results = risultati,
                saved = savedSuggestions,
                // Tutto tranne le linee, che hanno la loro fila.
                //
                // Il filtro nominava "stop" e "place", quindi i civici — che
                // si salvavano regolarmente fra i recenti — non si vedevano
                // MAI: cercare "via Bolognese 12" e ricercarla il giorno dopo
                // erano due ricerche identiche e complete.
                recents = recents.filter { it.kind != "route" }
                    .map { it.toSuggestion(dovePerLaDistanza) },
                nearby = nearby,
                recentLines = recents.filter { it.kind == "route" }
                    .map { it.toSuggestion(dovePerLaDistanza) },
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
                        //
                        // Ma il posto scelto entra lo stesso nei recenti: era
                        // l'unico modo di sceglierne uno senza che l'app se lo
                        // ricordasse, e cosi' il viaggio di ieri andava
                        // ricercato per intero anche se era lo stesso di oggi.
                        if (s.kind != "saved") {
                            recentStore.add(
                                RecentSearches.Entry(
                                    s.kind, s.key, s.title, s.subtitle,
                                    s.colorRgb, s.lat, s.lon,
                                ),
                            )
                            recentsVersion++
                        }
                        val ref = PlaceRef(s.title, s.subtitle, s.lat, s.lon)
                        if (field == "from") originRef = ref else destRef = ref
                        plannerField = null
                        searchOpen = false
                        query = ""
                        plannerOpen = true
                        runPlanner()
                    }
                },
                // Mentre si compila una riga del pianificatore, l'ingresso al
                // pianificatore non ha piu' senso: ci siamo dentro.
                onPlanRoute = if (plannerField == null) ({ openPlanner() }) else null,
                onClearRecents = {
                    recentStore.clear()
                    recentsVersion++
                },
                hint = when (plannerField) {
                    "to" -> "Dove vuoi andare?"
                    "from" -> "Da dove parti?"
                    else -> "Fermata, linea o luogo…"
                },
            )
            }
            androidx.compose.animation.AnimatedVisibility(visible = !searchOpen) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    CategoryChipsRow(
                        backdrop = backdrop,
                        selected = filter,
                        onSelect = {
                            filter = it
                            mapPrefs.filter = it
                        },
                    )
                }
            }

            // Il live degradato: silenzio finche' funziona, capsula discreta
            // quando manca davvero qualcosa — come deciso.
            //
            // Adesso comprende anche la strada diretta, che prima taceva: li'
            // i bus si muovono ma i ritardi non si scaricano, quindi OGNI
            // riga dell'app dice "orario da tabella". Sembrava che i mezzi
            // fossero tutti puntuali; invece non ne sapevamo niente.
            val liveDegraded = vehiclesActive && !searchOpen && (
                rtStatus.source != dev.antigravity.fluidtransit.data.rt.RealtimeClient.Source.PROXY ||
                    (rtStatus.feedAgeSeconds ?: 0) >
                    dev.antigravity.fluidtransit.data.rt.RealtimeClient.STALE_SECONDS
                )
            androidx.compose.animation.AnimatedVisibility(visible = liveDegraded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(10.dp))
                    LiveDownCapsule(
                        backdrop = backdrop,
                        status = rtStatus,
                        onOpenDataStatus = onOpenDataStatus,
                        offline = !online,
                    )
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
                mapPrefs.mode = mode
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
        // Cosa passa qui intorno, dove la tab bar lascia spazio: si legge
        // senza toccare niente, e toccandola si apre tutto.
        androidx.compose.animation.AnimatedVisibility(
            // Da lontano "qui intorno" non vuol dire niente.
            //
            // Al primo avvio, senza permesso della posizione, la mappa si
            // apre su tutta la Toscana: il centro cade in campagna fra
            // Siena e Colle, e la capsula mostrava le partenze di un paese
            // a caso come se fossero le tue. Sotto lo zoom in cui si
            // distinguono le strade, l'unica risposta onesta e' non
            // rispondere: c'e' il mirino, ed e' li' accanto.
            visible = panel == null && !searchOpen && !plannerOpen && !navActive &&
                reader != null && nearbyBoard.computedAtEpoch != 0L &&
                cameraZoom >= MapCatalog.NEARBY_MIN_ZOOM,
            enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it / 3 }) +
                androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it / 3 }) +
                androidx.compose.animation.fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        ) {
            NearbyCapsule(
                board = nearbyBoard,
                backdrop = backdrop,
                onClick = { panel = Panel.Nearby },
                modifier = Modifier
                    .padding(horizontal = FluidTabBarDefaults.HorizontalMargin)
                    .padding(bottom = FluidTabBarDefaults.ContentInset + 10.dp),
            )
        }

        // "Perche' questo numero", dichiarato qui e disegnato alla radice: si
        // apre SUL numero toccato, non al centro dello schermo.
        dev.antigravity.fluidtransit.ui.common.WhyThisNumberPortal(
            row = whyRow,
            nowEpoch = whyAt,
            origin = { whyOrigin },
            onDismiss = { whyRow = null },
            onOpenDataStatus = onOpenDataStatus,
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
                        .widthIn(max = PanelMaxWidth)
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
                                is Panel.Nearby -> "nearby"
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
                                    app = app,
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
                                    alerts = avvisiDiFermata,
                                    onOpenAlerts = onOpenAlerts,
                                    onWhyTap = { r, rect ->
                                        whyRow = r
                                        whyOrigin = rect
                                        whyAt = java.time.Instant.now().epochSecond
                                    },
                                    backdrop = backdrop,
                                    isFavorite = isFav,
                                    onToggleFavorite = {
                                        app.favorites.toggleStop(state.tap.idHashHex, state.tap.name)
                                    },
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
                                } else {
                                    PanelLoading("Leggo la linea\u2026", compact = true)
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
                                        onDismiss = ::exitRouteMode,
                                        alerts = avvisiDiLinea,
                                        onOpenAlerts = onOpenAlerts,
                                    )
                                } else {
                                    PanelLoading("Leggo la linea\u2026")
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
                                } else {
                                    PanelLoading("Leggo la corsa\u2026", compact = true)
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
                                                app.departureBoards.live(),
                                            )
                                            if (plan != null) {
                                                app.navigation.start(context, plan)
                                                panel = null
                                            } else {
                                                // Il piano non si costruisce quando la
                                                // corsa non ha piu' un "dopo" dove
                                                // scendere. Il tasto pero' c'era lo
                                                // stesso, e il tocco non faceva niente
                                                // — lo stesso difetto del mirino della
                                                // posizione, sullo stesso schermo.
                                                scope.launch {
                                                    notifications?.show(
                                                        dev.antigravity.fluidengine.ui.fluid
                                                            .FluidNotification(
                                                                id = "corsa-finita",
                                                                title = "Questa corsa e' finita",
                                                                message = "L'ultima fermata e' gia' " +
                                                                    "passata: non c'e' piu' un pezzo " +
                                                                    "di viaggio da seguire.",
                                                                tone = dev.antigravity.fluidengine
                                                                    .ui.fluid
                                                                    .FluidNotificationTone.Info,
                                                            ),
                                                    )
                                                }
                                            }
                                        },
                                        onDismiss = ::exitRouteMode,
                                        alerts = avvisiDiCorsa,
                                        onOpenAlerts = onOpenAlerts,
                                    )
                                } else {
                                    PanelLoading("Leggo la corsa\u2026")
                                }
                            }

                            is Panel.Nearby -> Column {
                                NearbyPanelContent(
                                    board = nearbyBoard,
                                    onDismiss = { panel = null },
                                    onRouteTap = ::showRoute,
                                    // Da dove si guarda: dov'e' la persona,
                                    // o il centro della mappa se il GPS e'
                                    // spento. La stessa regola della ricerca.
                                    distanceOf = { stop ->
                                        val r = ready?.reader
                                        val da = controller.lastLocation()
                                            ?: controller.cameraCenter()
                                        if (r == null || da == null || stop < 0) {
                                            null
                                        } else {
                                            dev.antigravity.fluidtransit.routing.BundleReader
                                                .haversine(
                                                    da.first, da.second,
                                                    r.stopLat(stop), r.stopLon(stop),
                                                )
                                        }
                                    },
                                    onWhyTap = { r, rect ->
                                        whyRow = r
                                        whyOrigin = rect
                                        whyAt = java.time.Instant.now().epochSecond
                                    },
                                    onStopTap = { stopIndex ->
                                        // Dalla riga si va alla fermata: e' la
                                        // continuazione naturale di "cosa passa
                                        // qui intorno" quando una delle risposte
                                        // interessa davvero.
                                        panel = Panel.Stop(
                                            StopTap(
                                                java.lang.Long.toHexString(
                                                    reader.stopIdHash(stopIndex),
                                                ),
                                                reader.stopName(stopIndex),
                                            ),
                                        )
                                        controller.flyTo(
                                            reader.stopLat(stopIndex),
                                            reader.stopLon(stopIndex),
                                            16.0,
                                        )
                                    },
                                )
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
                                    failed = journeysFailed,
                                    samePlace = journeyOrigin?.let { (lat, lon) ->
                                        dev.antigravity.fluidtransit.routing.BundleReader
                                            .haversine(lat, lon, state.to.lat, state.to.lon) <
                                            SAME_PLACE_M
                                    } ?: false,
                                    fromLabel = journeyFrom,
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
                                        alerts = avvisiDiViaggio,
                                        onOpenAlerts = onOpenAlerts,
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

/**
 * Un recente, con la distanza di ADESSO.
 *
 * Il sottotitolo si salva insieme alla ricerca, e per le fermate contiene la
 * distanza: quindi restava quella di quando l'avevi cercata. Sul telefono,
 * nello stesso istante: "STAZIONE PIAZZA ADUA · Fermata · a 0 m" fra i
 * recenti — misurata mesi fa con la mappa centrata li' sopra — e la stessa
 * fermata a un chilometro e mezzo nell'elenco sotto. La distanza e' l'unica
 * cosa che un recente non puo' ricordare, perche' e' l'unica che dipende da
 * dove sei adesso.
 */
private fun RecentSearches.Entry.toSuggestion(riferimento: Pair<Double, Double>?): Suggestion {
    val sub = if (kind == "stop") {
        riferimento?.let { (la, lo) ->
            "Fermata · a " + dev.antigravity.fluidtransit.routing.Words.distance(
                dev.antigravity.fluidtransit.routing.BundleReader.haversine(la, lo, lat, lon),
            )
        } ?: "Fermata"
    } else {
        subtitle
    }
    return Suggestion(kind, key, title, sub, colorRgb, lat, lon)
}

private fun hhmm(epochSecond: Long): String {
    if (epochSecond <= 0) return "—"
    val z = java.time.ZonedDateTime.ofInstant(
        Instant.ofEpochSecond(epochSecond),
        dev.antigravity.fluidtransit.routing.Ftb.ROME,
    )
    return "%02d:%02d".format(z.hour, z.minute)
}
