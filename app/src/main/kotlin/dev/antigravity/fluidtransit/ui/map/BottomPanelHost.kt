package dev.antigravity.fluidtransit.ui.map

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidGrabber
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassEdge
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * L'unico pannello dal basso della mappa: un pezzo di vetro staccato dai
 * bordi che ospita la scheda fermata, la scheda linea e il suo stato ridotto.
 *
 * E' UNO solo di proposito: il passaggio fermata->linea e' un cambio di
 * contenuto dentro la stessa superficie, e animateContentSize lo trasforma
 * con una molla invece di far sparire un pop-up e apparirne un altro.
 *
 * Il vetro campiona la mappa UNA volta (`sampleOnce`): sotto un pannello
 * fermo la mappa non cambia, e ricampionarla a ogni frame di scorrimento
 * era il lag segnalato alla prova sul device.
 *
 * Trascinare verso il basso lo liquida ([onDragDismiss]); il grabber e'
 * sempre zona di presa, e con [wholeSurfaceDrag] lo e' l'intera superficie
 * (per lo stato mini, che dentro non scorre).
 */
@Composable
fun BottomGlassPanel(
    backdrop: GlassBackdropState,
    onDragDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    wholeSurfaceDrag: Boolean = false,
    /** Lo stato mini non ha il grabber: e' gia' tutto zona di presa, e la
     *  striscia vuota sopra lo gonfiava — segnalato sul device. */
    showGrabber: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    fun Modifier.dragToDismiss(): Modifier = pointerInput(onDragDismiss) {
        detectVerticalDragGestures(
            onVerticalDrag = { _, dy ->
                scope.launch {
                    offsetY.snapTo((offsetY.value + dy).coerceAtLeast(0f))
                }
            },
            onDragEnd = {
                scope.launch {
                    if (offsetY.value > 42.dp.toPx()) {
                        // Scivola via, POI si congeda: l'animazione prima
                        // del cambio di stato e' quello che rende il gesto
                        // "ben fatto" invece di uno scatto.
                        offsetY.animateTo(size.height.toFloat() + 80f)
                        onDragDismiss()
                        offsetY.snapTo(0f)
                    } else {
                        offsetY.animateTo(
                            0f,
                            spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                    }
                }
            },
            onDragCancel = { scope.launch { offsetY.animateTo(0f) } },
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
            .glassSurface(
                state = backdrop,
                tint = GlassDefaults.floatingTint(),
                shape = ContinuousCornerShape(FluidRadius.Sheet),
                edge = GlassEdge.None,
                role = GlassRole.Modal,
                sampleOnce = true,
            )
            .then(if (wholeSurfaceDrag) Modifier.dragToDismiss() else Modifier)
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
    ) {
        if (showGrabber) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .then(if (wholeSurfaceDrag) Modifier else Modifier.dragToDismiss()),
                contentAlignment = Alignment.Center,
            ) {
                FluidGrabber()
            }
        }
        content()
    }
}
