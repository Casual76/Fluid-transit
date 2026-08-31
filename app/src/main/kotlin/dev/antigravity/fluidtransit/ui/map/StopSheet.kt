package dev.antigravity.fluidtransit.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidHairline
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidSheet
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidtransit.routing.BundleReader
import java.time.Instant
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * La scheda di una fermata: i prossimi passaggi dal bundle, ogni linea con
 * la pillola del SUO colore — lo stesso della tratta sulla mappa, perche'
 * escono entrambi dal campo colorDisplay del bundle.
 *
 * In Fase 4 qui arrivano i minuti veri dal realtime; in Fase 7 il pulsante
 * "sono su questo bus". La struttura e' gia' quella.
 */
@androidx.compose.runtime.Composable
@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun StopSheet(
    reader: BundleReader,
    stopIdHashHex: String,
    fallbackName: String,
    onDismiss: () -> Unit,
) {
    class Row(
        val line: String,
        val colorRgb: Int,
        val destination: String,
        val time: String,
        val inMinutes: Long,
    )

    class Data(val name: String, val rows: List<Row>?)

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
                    val local = ZonedDateTime.ofInstant(d.instant, dev.antigravity.fluidtransit.routing.Ftb.ROME)
                    Row(
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

    FluidSheet(
        onDismissRequest = onDismiss,
        title = data?.name ?: fallbackName,
    ) {
        val current = data
        when {
            current == null -> {
                Spacer(Modifier.height(24.dp))
                FluidSpinner(modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(24.dp))
            }

            current.rows.isNullOrEmpty() -> {
                FluidEmptyState(
                    title = "Nessun passaggio nelle prossime due ore",
                    detail = "Da questa fermata non parte niente a breve. Gli orari valgono fino al ${reader.feedEnd}.",
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(16.dp))
            }

            else -> {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    current.rows.forEachIndexed { i, row ->
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
                    text = "Orari programmati: i minuti veri arrivano col tempo reale, in Fase 4.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}
