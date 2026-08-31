package dev.antigravity.fluidtransit.ui.map

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
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
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * L'unico pannello dal basso della mappa: un pezzo di vetro staccato dai
 * bordi che ospita la scheda fermata, la scheda linea e il suo stato mini.
 *
 * E' UNO solo di proposito: ogni passaggio di stato e' un cambio di
 * contenuto della stessa superficie, trasformata da animateContentSize —
 * mai un pop-up che muore e uno che nasce.
 *
 * Il vetro campiona la mappa una volta quando il pannello e' fermo (il
 * ricampionamento a ogni frame era il lag segnalato), ma torna VIVO durante
 * il trascinamento: un pannello che si muove con addosso il riflesso
 * congelato della vecchia posizione era il primo dei difetti segnalati.
 *
 * I gesti: trascinare giu' congeda ([onDragDismiss]); dove ha senso,
 * trascinare su espande ([onDragExpand]). Con [transformOnDismiss] il
 * congedo NON scivola via: il pannello rimbalza al suo posto mentre lo
 * stato cambia sotto — e' cosi' che l'esteso "si chiude nel mini" e il
 * mini "torna tab bar", invece della roba strana riapri-e-richiudi.
 */
@Composable
fun BottomGlassPanel(
    backdrop: GlassBackdropState,
    onDragDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = ContinuousCornerShape(FluidRadius.Sheet),
    wholeSurfaceDrag: Boolean = false,
    showGrabber: Boolean = true,
    transformOnDismiss: Boolean = false,
    onDragExpand: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val offsetY = remember { Animatable(0f) }
    // Booleano derivato, non lettura diretta: cosi' il vetro si ricompone
    // solo quando il pannello passa da fermo a in-moto e ritorno, non a
    // ogni frame del trascinamento.
    val resting by remember {
        androidx.compose.runtime.derivedStateOf { offsetY.value == 0f }
    }
    val scope = rememberCoroutineScope()
    val currentDismiss by rememberUpdatedState(onDragDismiss)
    val currentExpand by rememberUpdatedState(onDragExpand)
    val currentTransform by rememberUpdatedState(transformOnDismiss)

    fun Modifier.panelDrag(): Modifier = pointerInput(Unit) {
        val dismissAt = 42.dp.toPx()
        val expandAt = -36.dp.toPx()
        detectVerticalDragGestures(
            onVerticalDrag = { _, dy ->
                scope.launch {
                    // Verso l'alto si va poco e con resistenza: e' un gesto
                    // di intenzione, non uno spostamento.
                    val next = offsetY.value + if (offsetY.value + dy < 0) dy / 2.5f else dy
                    offsetY.snapTo(next.coerceAtLeast(-64f))
                }
            },
            onDragEnd = {
                scope.launch {
                    val settle = spring<Float>(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    )
                    when {
                        offsetY.value <= expandAt && currentExpand != null -> {
                            currentExpand?.invoke()
                            offsetY.animateTo(0f, settle)
                        }

                        offsetY.value > dismissAt -> {
                            if (currentTransform) {
                                // Il rimbalzo E la trasformazione insieme:
                                // lo stato cambia subito, la molla riporta
                                // la superficie mentre il contenuto muta.
                                currentDismiss()
                                offsetY.animateTo(0f, settle)
                            } else {
                                offsetY.animateTo(size.height.toFloat() + 80f)
                                currentDismiss()
                                offsetY.snapTo(0f)
                            }
                        }

                        else -> offsetY.animateTo(0f, settle)
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
                shape = shape,
                edge = GlassEdge.None,
                role = GlassRole.Modal,
                // Fermo: una cattura sola (era il lag). In movimento: vivo,
                // o il riflesso resta congelato alla vecchia posizione.
                sampleOnce = resting,
            )
            .then(if (wholeSurfaceDrag) Modifier.panelDrag() else Modifier)
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
                    .then(if (wholeSurfaceDrag) Modifier else Modifier.panelDrag()),
                contentAlignment = Alignment.Center,
            ) {
                FluidGrabber()
            }
        }
        content()
    }
}
