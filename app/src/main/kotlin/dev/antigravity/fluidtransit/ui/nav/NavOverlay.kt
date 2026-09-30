package dev.antigravity.fluidtransit.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidtransit.data.nav.NavFocus
import dev.antigravity.fluidtransit.data.nav.NavPlan
import dev.antigravity.fluidtransit.data.nav.NavState
import dev.antigravity.fluidtransit.ui.map.BottomGlassPanel
import dev.antigravity.fluidtransit.ui.map.PanelMaxWidth

/**
 * Il viaggio in corso, in fondo allo schermo.
 *
 * Nasce esteso: chi ha appena premuto Parti vuole vedere cosa fare, non una
 * riga sola. Si riduce a capsula per guardare la mappa, e torna su con un
 * tocco o con una trascinata.
 *
 * Non e' un caso di `Panel`: quello e' persistenza per stato che muore alla
 * rotazione, e la navigazione vive in un foreground service. E non e' una
 * rotta di `AppRoot`: rimonterebbe la MapView, che costa un secondo e tre
 * quarti e si porterebbe via la camera.
 */
@Composable
fun BoxScope.NavOverlay(
    state: NavState?,
    plan: NavPlan?,
    focus: NavFocus?,
    backdrop: GlassBackdropState,
    colorOf: (route: Int) -> Int,
    onStop: () -> Unit,
    /**
     * Aperta o ridotta a capsula. Lo stato vive in chi la chiama: questo
     * composable esce di scena quando si apre un pannello, e con lo stato
     * dentro la capsula ridotta tornava aperta da sola alla chiusura del
     * pannello.
     */
    esteso: Boolean,
    onEsteso: (Boolean) -> Unit,
    /** Quanto spazio sta prendendo in fondo: la camera lo usa per il padding. */
    onHeight: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Indietro riduce la card; non finisce il viaggio. Prima in navigazione
    // il tasto indietro non faceva niente del tutto.
    BackHandler(enabled = state != null && esteso) { onEsteso(false) }

    AnimatedVisibility(
        visible = state != null,
        enter = slideInVertically(initialOffsetY = { it / 3 }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it / 3 }) + fadeOut(),
        modifier = modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding(),
    ) {
        state?.let { s ->
            BottomGlassPanel(
                backdrop = backdrop,
                shape = if (esteso) {
                    ContinuousCornerShape(FluidRadius.Sheet)
                } else {
                    FluidCapsuleShape
                },
                // Ridotta, la capsula si trascina tutta come la tab bar che
                // sostituisce; estesa, il gesto vive nella maniglia.
                wholeSurfaceDrag = !esteso,
                showGrabber = esteso,
                // Il congedo NON scivola via: la superficie rimbalza al suo
                // posto mentre il contenuto cambia sotto. E' cosi' che
                // l'esteso "si chiude nella capsula".
                transformOnDismiss = true,
                onDragExpand = if (!esteso) ({ onEsteso(true) }) else null,
                // Trascinare giu' riduce, non termina. Il viaggio finisce
                // solo con la X.
                onDragDismiss = { onEsteso(false) },
                modifier = Modifier
                    .widthIn(max = PanelMaxWidth)
                    .padding(horizontal = FluidTabBarDefaults.HorizontalMargin)
                    .padding(bottom = FluidTabBarDefaults.BottomMargin)
                    .onSizeChanged { onHeight(it.height) },
            ) {
                NavCard(
                    state = s,
                    plan = plan,
                    focus = focus,
                    esteso = esteso,
                    colorOf = colorOf,
                    onToggle = { onEsteso(!esteso) },
                    onStop = onStop,
                )
            }
        }
    }
}
