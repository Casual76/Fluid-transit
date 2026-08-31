package dev.antigravity.fluidtransit.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidHairline
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Ftb
import java.time.Instant
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sfuma il contenuto ai bordi verticali dello scorrimento: senza, le righe
 * si troncano di netto contro il pannello — il "taglio brutto" segnalato
 * alla prima prova.
 */
fun Modifier.fadeVerticalEdges(edge: androidx.compose.ui.unit.Dp = 16.dp): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = edge.toPx()
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to Color.Transparent, 1f to Color.Black, endY = h,
            ),
            size = androidx.compose.ui.geometry.Size(size.width, h),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to Color.Black, 1f to Color.Transparent,
                startY = size.height - h, endY = size.height,
            ),
            topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h),
            size = androidx.compose.ui.geometry.Size(size.width, h),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }

/**
 * Il contenuto della scheda fermata: prossimi passaggi dal bundle, ogni
 * linea con la pillola del SUO colore — lo stesso della tratta sulla mappa.
 * Il vetro, il grabber e i gesti vivono in [BottomGlassPanel]: questo e'
 * solo il dentro, cosi' il passaggio a scheda linea e' un morphing della
 * stessa superficie. In Fase 4 qui arrivano i minuti veri e il tasto (in
 * vetro, come tutti i tasti nei pannelli) del prossimo bus live.
 */
@Composable
fun StopPanelContent(
    reader: BundleReader,
    stopIdHashHex: String,
    fallbackName: String,
    onDismiss: () -> Unit,
    onRouteTap: (routeIndex: Int) -> Unit,
) {
    class DepartureRow(
        val routeIndex: Int,
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
                        routeIndex = d.routeIndex,
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp),
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
                    .fadeVerticalEdges()
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
                        // Il tocco sulla pillola trasforma la scheda in
                        // scheda linea, con la tratta accesa sulla mappa.
                        RoutePill(
                            text = row.line,
                            colorRgb = row.colorRgb,
                            modifier = Modifier.clickable(
                                interactionSource = androidx.compose.runtime.remember {
                                    MutableInteractionSource()
                                },
                                indication = null,
                                role = Role.Button,
                                onClickLabel = "Mostra la linea ${row.line}",
                                onClick = { onRouteTap(row.routeIndex) },
                            ),
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
