package dev.antigravity.fluidtransit.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidGrabber
import dev.antigravity.fluidengine.ui.fluid.FluidHairline
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassEdge
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Ftb
import java.time.Instant
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * La scheda di una fermata, in stile iOS come da riferimento Apple Maps:
 * un pannello **staccato dai bordi**, con angoli continui, in fluid glass —
 * non una ModalBottomSheet a tutta larghezza, che oltre a essere un'altra
 * lingua visiva vive in una finestra separata e non puo' campionare la
 * mappa che ha sotto.
 *
 * Ogni linea ha la pillola del SUO colore, lo stesso della tratta sulla
 * mappa: escono entrambi dal campo colorDisplay del bundle. In Fase 4 qui
 * arrivano i minuti veri dal realtime; in Fase 7 il pulsante "sono su
 * questo bus".
 */
@Composable
fun StopCard(
    reader: BundleReader,
    backdrop: GlassBackdropState,
    stopIdHashHex: String,
    fallbackName: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    class DepartureRow(
        val line: String,
        val colorRgb: Int,
        val destination: String,
        val time: String,
        val inMinutes: Long,
    )

    class Data(val name: String, val rows: List<DepartureRow>)

    val data by produceState<Data?>(initialValue = null, stopIdHashHex) {
        value = withContext(Dispatchers.Default) {
            val hash = stopIdHashHex.toULongOrNull(16)?.toLong()
                ?: return@withContext Data(fallbackName, emptyList())
            val stop = reader.findStopByIdHash(hash)
            if (stop < 0) return@withContext Data(fallbackName, emptyList())
            val now = Instant.now()
            val departures = reader.nextDepartures(stop, now, limit = 10, horizonSeconds = 2 * 3600)
            Data(
                name = reader.stopName(stop),
                rows = departures.map { d ->
                    val local = ZonedDateTime.ofInstant(d.instant, Ftb.ROME)
                    DepartureRow(
                        line = reader.routeShortName(d.routeIndex)
                            .ifEmpty { reader.routeLongName(d.routeIndex) },
                        colorRgb = reader.routeDisplayColor(d.routeIndex),
                        destination = reader.patternDestination(d.patternIndex),
                        time = "%02d:%02d".format(local.hour, local.minute),
                        inMinutes = (d.instant.epochSecond - now.epochSecond) / 60,
                    )
                },
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(
                state = backdrop,
                tint = GlassDefaults.floatingTint(),
                shape = ContinuousCornerShape(FluidRadius.Sheet),
                edge = GlassEdge.None,
                role = GlassRole.Modal,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            FluidGrabber()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = data?.name ?: fallbackName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Chiudi",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(40.dp)
                    .clickable(
                        interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onDismiss,
                    )
                    .padding(8.dp),
            )
        }

        val current = data
        when {
            current == null -> {
                Spacer(Modifier.height(20.dp))
                Row(modifier = Modifier.padding(horizontal = 20.dp)) { FluidSpinner() }
                Spacer(Modifier.height(24.dp))
            }

            current.rows.isEmpty() -> {
                FluidEmptyState(
                    title = "Nessun passaggio nelle prossime due ore",
                    detail = "Da questa fermata non parte niente a breve.",
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(16.dp))
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .padding(horizontal = 20.dp),
                ) {
                    items(current.rows.size) { i ->
                        val row = current.rows[i]
                        if (i > 0) FluidHairline()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = row.line,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                modifier = Modifier
                                    .widthIn(min = 44.dp)
                                    .background(
                                        color = Color(0xFF000000 or row.colorRgb.toLong()),
                                        shape = ContinuousCornerShape(FluidRadius.Small),
                                    )
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                            Text(
                                text = row.destination,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = if (row.inMinutes < 60) "${row.inMinutes} min" else row.time,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (row.inMinutes < 60) {
                                    Text(
                                        text = row.time,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    text = "Orari programmati: i minuti veri arrivano col tempo reale.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}
