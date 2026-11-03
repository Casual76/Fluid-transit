package dev.antigravity.fluidtransit.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidSkeleton

/**
 * Le sagome di un tabellone di partenze: pastiglia, destinazione,
 * provenienza, minuti. Le stesse misure della riga vera, cosi' quando i
 * numeri arrivano non si sposta niente.
 *
 * Il disegno di una sagoma, il respiro e il movimento ridotto sono di
 * `FluidSkeleton` dell'engine: qui resta solo la disposizione, che e' della
 * riga di una partenza e quindi non puo' stare nell'engine (la sua
 * `FluidSkeletonRow` ha una piastrella e due righe, non una pastiglia e un
 * numero).
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
                FluidSkeleton(modifier = Modifier.width(44.dp), height = 26.dp)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Larghezze diverse riga per riga: una colonna di
                    // rettangoli identici sembra una tabella, non un elenco
                    // di nomi di posti.
                    FluidSkeleton(
                        modifier = Modifier.fillMaxWidth(if (i % 2 == 0) 0.72f else 0.56f),
                        height = 15.dp,
                    )
                    FluidSkeleton(modifier = Modifier.fillMaxWidth(0.38f), height = 11.dp)
                }
                FluidSkeleton(modifier = Modifier.width(46.dp), height = 16.dp)
            }
        }
    }
}
