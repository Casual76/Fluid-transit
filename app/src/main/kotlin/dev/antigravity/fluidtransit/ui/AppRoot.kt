package dev.antigravity.fluidtransit.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.FluidNotificationHost
import dev.antigravity.fluidengine.ui.fluid.FluidScrollToTopBus
import dev.antigravity.fluidengine.ui.fluid.FluidTabBar
import dev.antigravity.fluidengine.ui.fluid.FluidTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidTabItem
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidNotificationHostState
import dev.antigravity.fluidengine.ui.fluid.ProvideFluidChrome
import dev.antigravity.fluidengine.ui.fluid.fluidGlassModalObscured
import dev.antigravity.fluidengine.ui.fluid.rememberFluidChromeController
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberFluidNotificationHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState
import dev.antigravity.fluidtransit.ui.favorites.FavoritesTab
import dev.antigravity.fluidtransit.ui.map.MapIntent
import dev.antigravity.fluidtransit.ui.map.MapScreen
import dev.antigravity.fluidtransit.ui.nav.Deeplink
import dev.antigravity.fluidtransit.ui.settings.DataStatusScreen
import dev.antigravity.fluidtransit.ui.settings.SettingsTab
import dev.antigravity.fluidtransit.ui.today.TodayTab
import dev.antigravity.fluidtransit.ui.welcome.WelcomeScreen

private const val RouteMap = "map"
private const val RouteToday = "today"
private const val RouteFavorites = "favorites"
private const val RouteSettings = "settings"

/**
 * Lo stato dei dati: non e' una scheda, sta sopra a quella che c'e'.
 *
 * Era un `if` dentro Impostazioni, quindi non aveva nome e non si poteva
 * raggiungere da nessun'altra parte — ne' da un deep link, ne' dalla capsula
 * "Bus live non disponibili" che di quello parla.
 */
private const val RouteDataStatus = "data-status"

private val Tabs = listOf(
    FluidTabItem(route = RouteMap, label = "Mappa", icon = Icons.Rounded.Map),
    FluidTabItem(route = RouteToday, label = "Oggi", icon = Icons.Rounded.WbSunny),
    FluidTabItem(route = RouteFavorites, label = "Preferiti", icon = Icons.Rounded.Star),
    FluidTabItem(route = RouteSettings, label = "Impostazioni", icon = Icons.Rounded.Settings),
)

/**
 * La radice: benvenuto finche' il bundle non c'e', poi la shell.
 *
 * Il passaggio e' automatico - deciso cosi': appena gli orari sono pronti
 * l'app entra, senza chiedere altro. Il Crossfade evita lo stacco secco.
 */
@Composable
fun AppRoot(app: FluidTransitApp) {
    val bundleState by app.bundleManager.state.collectAsStateWithLifecycle()

    Crossfade(targetState = bundleState is BundleState.Ready, label = "root") { ready ->
        if (ready) AppShell(app) else WelcomeScreen(app.bundleManager, bundleState)
    }
}

/**
 * La shell dell'app: contenuto + tab bar in vetro, cablata come il sample
 * dell'engine — chrome, modali e notifiche fratelli del contenuto, tutti
 * sullo stesso backdrop attivo.
 */
@Composable
private fun AppShell(app: FluidTransitApp) {
    var route by rememberSaveable { mutableStateOf(RouteMap) }

    /** Una schermata sopra le schede, o niente. Oggi solo lo stato dei dati. */
    var above by rememberSaveable { mutableStateOf<String?>(null) }

    /**
     * Lo stato salvabile di ogni scheda, tenuto da parte mentre un'altra e'
     * davanti. Senza, il `when` qui sotto e' uno scambio secco: la scheda che
     * esce viene smontata e tutto il suo `rememberSaveable` sparisce. Sulla
     * mappa si vedeva bene — bastava un giro su Oggi e al ritorno la camera
     * era tornata al punto di partenza, col pannello chiuso, i filtri
     * azzerati e la ricerca svuotata.
     */
    val tabState = rememberSaveableStateHolder()

    // La modalita' linea della mappa prende il posto della tab bar: quando
    // il suo pannello ridotto e' giu', la barra si sfila con lui.
    var mapHidesTabBar by remember { mutableStateOf(false) }

    // Le richieste alla mappa dalle altre schede: si cambia scheda e la
    // mappa consuma l'intento appena pronta.
    var mapIntent by remember {
        mutableStateOf<dev.antigravity.fluidtransit.ui.map.MapIntent?>(null)
    }
    val openOnMap: (dev.antigravity.fluidtransit.ui.map.MapIntent) -> Unit = {
        mapIntent = it
        above = null
        route = RouteMap
    }

    // L'indirizzo con cui l'app e' stata aperta, tradotto in una schermata.
    // Si consuma qui e non in MainActivity perche' e' qui che si sa cosa
    // c'e' — le schede, la mappa, il pianificatore.
    val pendingLink by app.pendingLink.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(pendingLink) {
        when (val link = pendingLink) {
            null -> return@LaunchedEffect
            is Deeplink.Stop -> openOnMap(MapIntent.Stop(link.idHashHex, link.name))
            is Deeplink.Route -> openOnMap(MapIntent.Route(link.idHashHex))
            is Deeplink.Journey -> {
                // Il viaggio di una routine: la notifica dice "esci alle
                // 8:12", e toccarla deve mostrare *quel* viaggio, non la
                // mappa da cui ricostruirlo a mano.
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    app.routines.list().firstOrNull { it.id == link.routineId }
                }
                if (r != null) {
                    openOnMap(
                        MapIntent.Journey(
                            fromLat = r.fromLat,
                            fromLon = r.fromLon,
                            toLat = r.toLat,
                            toLon = r.toLon,
                            toName = r.toName.ifEmpty { r.label.ifEmpty { "Arrivo" } },
                        ),
                    )
                }
            }
            // La navigazione in corso vive sulla mappa e si mostra da se'
            // appena e' davanti: qui basta portarci.
            Deeplink.Nav -> {
                above = null
                route = RouteMap
            }
            Deeplink.Today -> {
                above = null
                route = RouteToday
            }
            Deeplink.DataStatus -> {
                above = RouteDataStatus
            }
        }
        app.pendingLink.value = null
    }
    /**
     * Indietro: una regola sola, sempre la stessa.
     *
     * Chiude cio' che sta sopra le schede; se non c'e' niente sopra, torna
     * alla Mappa; dalla Mappa esce. Prima usciva e basta — da Oggi, da
     * Preferiti, da Impostazioni: il tasto Indietro chiudeva l'app, e per
     * tornare alla mappa bisognava riaprirla.
     *
     * Non e' una cronologia delle schede visitate, ed e' deliberato: una
     * cronologia fa cose diverse a seconda di come sei arrivato, ed e'
     * esattamente il genere di cosa che si impara a non fidarsi. Qui la
     * frase e' una: Indietro riporta alla mappa, e dalla mappa si esce.
     *
     * I pannelli della mappa, la ricerca e il pianificatore registrano i
     * loro BackHandler piu' internamente e quindi vincono su questo finche'
     * sono aperti: Indietro li chiude uno alla volta, e solo quando non c'e'
     * piu' niente da chiudere arriva qui.
     */
    androidx.activity.compose.BackHandler(enabled = above != null || route != RouteMap) {
        if (above != null) above = null else route = RouteMap
    }

    val chromeController = rememberFluidChromeController()
    val scrollToTop = remember { FluidScrollToTopBus() }
    val modalHost = rememberFluidGlassModalHostState()
    val notificationHost = rememberFluidNotificationHostState()
    val fallbackBackdrop = rememberGlassBackdrop()

    // La mappa e' un AndroidView e non passa dal registro dei FluidScreen:
    // il suo backdrop si crea qui e si consegna sia alla schermata (che lo
    // riempie) sia alla tab bar (che lo rifrange) quando la Mappa e' davanti.
    val mapBackdrop = rememberGlassBackdrop()
    val backdrop = if (above == null && route == RouteMap) {
        mapBackdrop
    } else {
        chromeController.activeBackdrop.value ?: fallbackBackdrop
    }

    CompositionLocalProvider(
        LocalFluidGlassModalHostState provides modalHost,
        LocalFluidNotificationHostState provides notificationHost,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ProvideFluidChrome(
                controller = chromeController,
                bottomInset = FluidTabBarDefaults.ContentInset,
                scrollToTop = scrollToTop,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .fluidGlassModalObscured(),
                ) {
                    tabState.SaveableStateProvider(above ?: route) {
                        when {
                            above == RouteDataStatus ->
                                DataStatusScreen(app, onBack = { above = null })

                            route == RouteToday -> TodayTab(app, onOpenOnMap = openOnMap)
                            route == RouteFavorites -> FavoritesTab(app, onOpenOnMap = openOnMap)
                            route == RouteSettings ->
                                SettingsTab(app, onOpenDataStatus = { above = RouteDataStatus })

                            else -> MapScreen(
                                app,
                                mapBackdrop,
                                onTabBarHidden = { mapHidesTabBar = it },
                                intent = mapIntent,
                                onIntentConsumed = { mapIntent = null },
                            )
                        }
                    }
                }
            }

            // Piccolo scarto e dissolvenza, non lo scivolone intero: il mini
            // della linea siede nello stesso posto con la stessa altezza, e
            // questo incrocio morbido e' cio' che fa leggere il ritorno della
            // barra come una trasformazione della stessa capsula.
            androidx.compose.animation.AnimatedVisibility(
                visible = above == null && !(route == RouteMap && mapHidesTabBar),
                enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it / 3 }) +
                    androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it / 3 }) +
                    androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Box(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(
                            horizontal = FluidTabBarDefaults.HorizontalMargin,
                            vertical = FluidTabBarDefaults.BottomMargin,
                        ),
                ) {
                    FluidTabBar(
                        items = Tabs,
                        selectedRoute = route,
                        onSelect = {
                            above = null
                            route = it.route
                        },
                        onReselect = { scrollToTop.request() },
                        backdrop = backdrop,
                    )
                }
            }

            // Sopra la tab bar, alla radice: un pop-up su cui la capsula di
            // navigazione puo' galleggiare non e' un modale.
            FluidGlassModalHost(state = modalHost, backdrop = backdrop)
            FluidNotificationHost(
                state = notificationHost,
                backdrop = backdrop,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}
