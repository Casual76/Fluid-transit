package dev.antigravity.fluidtransit.ui.map

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidGrabber
import dev.antigravity.fluidengine.ui.fluid.FluidLoadingBlock
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassEdge
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Il vetro che aspetta, invece del vetro vuoto.
 *
 * Le schede linea e corsa si calcolano fuori dal thread della UI, e finche'
 * non arrivano il pannello si apriva VUOTO: un rettangolo di vetro grande
 * come il contenuto che non c'e' ancora, che cresce di scatto quando arriva.
 * Per chi guarda non e' "sto caricando", e' "si e' rotto qualcosa" — ed e'
 * uno dei posti in cui l'app sembrava comportarsi in modo diverso ogni volta,
 * perche' la durata di quel vuoto dipende da quanto e' grande la linea.
 *
 * `FluidLoadingBlock` esisteva nell'engine e non lo usava nessuno.
 */
@Composable
internal fun PanelLoading(what: String, compact: Boolean = false) {
    if (compact) {
        // Il mini e' alto quanto una tab bar: un blocco da 180 dp lo
        // gonfierebbe, e il gonfiore e' proprio la cosa da evitare.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FluidSpinner()
            Text(
                text = what,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        FluidLoadingBlock(minHeight = 160.dp)
    }
}

/**
 * Quanto puo' essere largo un pannello.
 *
 * In verticale su un telefono non cambia niente: lo schermo e' piu' stretto
 * di cosi'. In orizzontale invece il pannello arrivava da bordo a bordo, e
 * una riga larga duemila pixel mette il numero della linea a sinistra e i
 * suoi minuti a duemila pixel di distanza — tecnicamente leggibile, di fatto
 * da inseguire con gli occhi.
 */
internal val PanelMaxWidth = 520.dp

/**
 * Quanto puo' essere alto l'elenco dentro un pannello.
 *
 * Le altezze erano in dp fissi — 340, 380, 400, 480 — tarate sull'altezza di
 * un telefono in verticale. Girato l'apparecchio, lo schermo e' alto quanto
 * il solo elenco: il pannello si prendeva tutto, la tab bar gli galleggiava
 * sopra le righe e sotto non restava mappa. Lo stesso succede su uno schermo
 * piccolo anche in verticale.
 *
 * La regola: l'elenco prende quello che avanza dopo aver messo da parte
 * tutto cio' che non e' elenco, e mai piu' dell'altezza pensata per quel
 * pannello. Su un telefono in verticale avanza sempre abbastanza, quindi
 * non cambia niente; e' in orizzontale che il minimo morde.
 *
 * Una riserva in dp e non una percentuale, perche' cio' che sta intorno
 * all'elenco — testata, tasto, riga della provenienza, margini, tab bar —
 * ha una sua altezza fissa, che non si rimpicciolisce con lo schermo.
 */
@Composable
internal fun panelListMax(preferred: Dp, reserve: Dp = AROUND_THE_LIST): Dp {
    val density = LocalDensity.current
    val window = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    // La tastiera mangia lo schermo come qualunque altra cosa.
    //
    // Senza sottrarla, in orizzontale con la tastiera aperta l'elenco della
    // ricerca chiedeva 121 dp di un'area che ne aveva sessanta: risultato,
    // pannello vuoto. Chiedere meno di quello che c'e' e' l'unico modo di
    // mostrare qualcosa.
    val ime = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    return listMax(preferred, window, ime, reserve)
}

/**
 * Il conto, senza Compose intorno.
 *
 * Sta fuori dal composable per un motivo solo: qui si puo' interrogare. Le
 * misure che decidono se un elenco si vede o no — lo schermo, la tastiera,
 * quello che sta intorno — sono numeri, e i due difetti che questa funzione
 * ha gia' avuto erano errori di aritmetica, non di Compose: una volta il
 * minimo che non c'era, una volta la tastiera sottratta due volte.
 *
 * **La tastiera si sottrae UNA volta sola, qui.** Chi usa il risultato non
 * deve aggiungere un `imePadding()` sulla stessa scatola: sarebbe toglierla
 * di nuovo, dall'interno.
 */
internal fun listMax(
    preferred: Dp,
    window: Dp,
    ime: Dp,
    reserve: Dp,
    floor: Dp = LIST_FLOOR,
): Dp {
    val disponibile = maxOf(window - ime, 0.dp)
    return minOf(preferred, maxOf(disponibile - reserve, minOf(floor, disponibile)))
}

/**
 * Quanto si mette da parte per cio' che non e' l'elenco.
 *
 * Misurato sulla scheda fermata, che e' la piu' carica: testata 64,
 * "Parti da qui" 56, la riga della provenienza in fondo 36, i margini del
 * vetro 24, la tab bar col suo scarto 86, la barra di stato 24.
 */
private val AROUND_THE_LIST = 290.dp

/**
 * La riserva della barra di ricerca.
 *
 * Molto piu' piccola perche' sotto l'elenco non c'e' niente: quando la
 * ricerca e' aperta la tab bar non si vede e il piede non esiste. Sopra ci
 * sono solo la barra di stato, la capsula e i suoi margini.
 */
internal val AROUND_THE_SEARCH = 110.dp

/** Sotto questa altezza l'elenco non e' piu' un elenco: si sacrifica altro. */
internal val LIST_FLOOR = 120.dp

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
        // Quanto in alto puo' arrivare la superficie.
        //
        // Era 64 PIXEL fissi, contro una soglia di espansione di 36 dp: su
        // uno schermo da 420 dpi quei 36 dp sono 94 pixel, cioe' oltre il
        // tetto. Il gesto "trascina su per espandere" non poteva riuscire su
        // nessun telefono moderno — restava vivo solo a densita' 1, che non
        // esiste piu'. Adesso il tetto si calcola DALLA soglia, cosi' i due
        // numeri non possono piu' allontanarsi.
        val ceiling = expandAt * 1.8f
        detectVerticalDragGestures(
            onVerticalDrag = { _, dy ->
                scope.launch {
                    // Verso l'alto si va poco e con resistenza: e' un gesto
                    // di intenzione, non uno spostamento.
                    val next = offsetY.value + if (offsetY.value + dy < 0) dy / 2.5f else dy
                    offsetY.snapTo(next.coerceAtLeast(ceiling))
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
