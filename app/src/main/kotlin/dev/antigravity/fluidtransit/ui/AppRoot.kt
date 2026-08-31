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
import dev.antigravity.fluidtransit.ui.map.MapScreen
import dev.antigravity.fluidtransit.ui.settings.SettingsTab
import dev.antigravity.fluidtransit.ui.today.TodayTab
import dev.antigravity.fluidtransit.ui.welcome.WelcomeScreen

private const val RouteMap = "map"
private const val RouteToday = "today"
private const val RouteFavorites = "favorites"
private const val RouteSettings = "settings"

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
    val chromeController = rememberFluidChromeController()
    val scrollToTop = remember { FluidScrollToTopBus() }
    val modalHost = rememberFluidGlassModalHostState()
    val notificationHost = rememberFluidNotificationHostState()
    val fallbackBackdrop = rememberGlassBackdrop()

    // La mappa e' un AndroidView e non passa dal registro dei FluidScreen:
    // il suo backdrop si crea qui e si consegna sia alla schermata (che lo
    // riempie) sia alla tab bar (che lo rifrange) quando la Mappa e' davanti.
    val mapBackdrop = rememberGlassBackdrop()
    val backdrop = if (route == RouteMap) {
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
                    when (route) {
                        RouteToday -> TodayTab()
                        RouteFavorites -> FavoritesTab()
                        RouteSettings -> SettingsTab(app)
                        else -> MapScreen(app, mapBackdrop)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(
                        horizontal = FluidTabBarDefaults.HorizontalMargin,
                        vertical = FluidTabBarDefaults.BottomMargin,
                    ),
            ) {
                FluidTabBar(
                    items = Tabs,
                    selectedRoute = route,
                    onSelect = { route = it.route },
                    onReselect = { scrollToTop.request() },
                    backdrop = backdrop,
                )
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
