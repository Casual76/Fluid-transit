package dev.antigravity.fluidtransit.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy

/**
 * La forma di quello che sta arrivando, mentre arriva.
 *
 * Dove l'app aspetta dei dati mostrava una rotellina in mezzo al vuoto:
 * corretta, ma dice solo "sto facendo qualcosa", e per i dieci secondi che
 * ci mettono gli avvisi a scaricarsi la schermata resta una pagina bianca
 * con un puntino che gira. Le sagome invece dicono anche COSA sta per
 * comparire, e quando compare non salta niente, perche' occupava gia' quello
 * spazio.
 *
 * Il respiro e' un'opacita' che va e viene, non una banda che scorre: e' la
 * stessa quiete del resto del design system, e sotto la politica di
 * movimento ridotto si spegne e resta una sagoma ferma.
 *
 * Sta nell'app e non nell'engine perche' l'engine si aggiorna a versioni, e
 * questa e' nata mentre si guardava una schermata precisa. Se sopravvive a
 * qualche giro, il suo posto e' li'.
 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    radius: Dp = FluidRadius.Small,
) {
    val respiro = LocalFluidMotionPolicy.current.allowDecorativeMotion
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.13f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FluidMotion.EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .height(height)
            .background(
                color = MaterialTheme.colorScheme.onSurface
                    .copy(alpha = if (respiro) alpha else 0.09f),
                shape = ContinuousCornerShape(radius),
            ),
    )
}

/**
 * Le sagome di un tabellone di partenze: pastiglia, destinazione,
 * provenienza, minuti. Le stesse misure della riga vera, cosi' quando i
 * numeri arrivano non si sposta niente.
 */
@Composable
fun DepartureSkeleton(
    rows: Int = 3,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        repeat(rows) { i ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SkeletonBlock(modifier = Modifier.width(44.dp), height = 26.dp)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Larghezze diverse riga per riga: una colonna di
                    // rettangoli identici sembra una tabella, non un elenco
                    // di nomi di posti.
                    SkeletonBlock(
                        modifier = Modifier.fillMaxWidth(if (i % 2 == 0) 0.72f else 0.56f),
                        height = 15.dp,
                    )
                    SkeletonBlock(modifier = Modifier.fillMaxWidth(0.38f), height = 11.dp)
                }
                SkeletonBlock(modifier = Modifier.width(46.dp), height = 16.dp)
            }
        }
    }
}

/**
 * Le sagome di una scheda di testo — un avviso di servizio, una scheda
 * lunga: un titolo e qualche riga.
 */
@Composable
fun CardSkeleton(
    lines: Int = 3,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        SkeletonBlock(modifier = Modifier.fillMaxWidth(0.55f), height = 18.dp)
        repeat(lines) { i ->
            SkeletonBlock(
                modifier = Modifier.fillMaxWidth(if (i == lines - 1) 0.64f else 1f),
                height = 13.dp,
            )
        }
    }
}
