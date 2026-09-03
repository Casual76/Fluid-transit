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
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
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
import dev.antigravity.fluidtransit.routing.DelayModel
import dev.antigravity.fluidtransit.routing.Ftb
import dev.antigravity.fluidtransit.routing.Times
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
 * stessa superficie. I minuti in verde sono quelli veri dal bus; il tasto
 * accanto vola sul mezzo che sta arrivando.
 */
@Composable
fun StopPanelContent(
    reader: BundleReader,
    stopIdHashHex: String,
    fallbackName: String,
    onDismiss: () -> Unit,
    onRouteTap: (routeIndex: Int) -> Unit,
    backdrop: dev.antigravity.fluidengine.ui.fluid.GlassBackdropState,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    delays: dev.antigravity.fluidtransit.routing.DelayModel? = null,
    delaysStamp: Long = 0L,
    canceledTrips: Set<Int> = emptySet(),
    liveVehicleTrips: Set<Int> = emptySet(),
    onFlyToBus: (tripIndex: Int) -> Unit = {},
    /** "Parti da qui": questa fermata come ORIGINE del pianificatore. */
    onStartHere: (() -> Unit)? = null,
) {
    class DepartureRow(
        val tripIndex: Int,
        val routeIndex: Int,
        val line: String,
        val colorRgb: Int,
        val destination: String,
        val scheduledEpoch: Long,
        val delaySeconds: Int?,
        val confidence: dev.antigravity.fluidtransit.routing.DelayModel.Confidence?,
    )

    class Data(val name: String, val nowEpoch: Long, val rows: List<DepartureRow>)

    // Il battito. Senza, i minuti restavano quelli del momento in cui la
    // scheda si era aperta: si vedeva "3 min" per tutto il tempo che la si
    // teneva aperta, e le corse passate non spariscono. Era la ragione
    // principale del "non sembrano davvero live".
    var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(stopIdHashHex) {
        while (true) {
            kotlinx.coroutines.delay(15_000)
            tick++
        }
    }

    val data by produceState<Data?>(initialValue = null, stopIdHashHex, tick, delaysStamp) {
        value = withContext(Dispatchers.Default) {
            val hash = stopIdHashHex.toULongOrNull(16)?.toLong()
                ?: return@withContext Data(fallbackName, 0L, emptyList())
            val stop = reader.findStopByIdHash(hash)
            if (stop < 0) return@withContext Data(fallbackName, 0L, emptyList())
            val now = Instant.now()
            val departures = reader.nextDepartures(stop, now, limit = 10, horizonSeconds = 2 * 3600)
            Data(
                name = reader.stopName(stop),
                nowEpoch = now.epochSecond,
                rows = departures.map { d ->
                    // Il ritardo di QUESTA fermata, non quello della corsa
                    // spalmato su tutto il percorso.
                    val live = delays?.at(
                        d.tripIndex,
                        d.positionInPattern,
                        reader.patternStopCount(d.patternIndex),
                    )
                    DepartureRow(
                        tripIndex = d.tripIndex,
                        routeIndex = d.routeIndex,
                        line = reader.routeShortName(d.routeIndex)
                            .ifEmpty { reader.routeLongName(d.routeIndex) },
                        colorRgb = reader.routeDisplayColor(d.routeIndex),
                        destination = reader.patternDestination(d.patternIndex),
                        scheduledEpoch = d.instant.epochSecond,
                        delaySeconds = live?.delaySeconds,
                        confidence = live?.confidence,
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
        // La stella dei preferiti: piena quando la fermata e' tua.
        Icon(
            imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            contentDescription = if (isFavorite) "Togli dai preferiti" else "Salva nei preferiti",
            tint = if (isFavorite) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .size(40.dp)
                .clickable(
                    interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onToggleFavorite,
                )
                .padding(8.dp),
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

    // "Parti da qui": la fermata aperta diventa l'origine del pianificatore.
    // E' una delle quattro strade decise per scegliere una partenza diversa
    // da dove sei.
    if (onStartHere != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 4.dp),
        ) {
            GlassActionButton(
                text = "Parti da qui",
                icon = Icons.Rounded.MyLocation,
                backdrop = backdrop,
                onClick = onStartHere,
                modifier = Modifier.weight(1f),
            )
        }
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
                    val now = current.nowEpoch
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
                        // Live vuol dire "il feed sta parlando di questa
                        // fermata". Una fermata che il bus ha gia' passato
                        // non e' live: prima diventava verde comunque.
                        val liveKind = row.confidence
                            ?.takeIf { it != DelayModel.Confidence.SERVED }
                        val delay = row.delaySeconds.takeIf { liveKind != null }
                        val canceled = row.tripIndex in canceledTrips
                        if (row.tripIndex in liveVehicleTrips && !canceled) {
                            // Il tasto del prossimo bus live — in vetro,
                            // vetro su vetro, come da regola: vola sul bus
                            // di QUESTA corsa e apre la sua scheda.
                            dev.antigravity.fluidengine.ui.fluid.FluidGlassIconButton(
                                onClick = { onFlyToBus(row.tripIndex) },
                                backdrop = backdrop,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DirectionsBus,
                                    contentDescription = "Vola sul bus della ${row.line}",
                                    tint = liveGreen(),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            when {
                                canceled -> Text(
                                    text = "Cancellata",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )

                                delay != null -> {
                                    val eff = row.scheduledEpoch + delay
                                    val minutes = Times.minutesUntil(now, eff)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        // Il pallino pulsa solo dove il feed
                                        // sta guardando adesso; piu' avanti
                                        // e' una proiezione nostra, e la
                                        // differenza si vede.
                                        if (liveKind == DelayModel.Confidence.OBSERVED) {
                                            LiveDot(liveGreen())
                                        }
                                        Text(
                                            text = if (minutes < 60) {
                                                Times.minutesLabel(now, eff)
                                            } else {
                                                Times.hhmm(eff)
                                            },
                                            style = MaterialTheme.typography.titleSmall,
                                            color = liveGreen(),
                                        )
                                    }
                                    Text(
                                        text = "previsto ${Times.hhmm(row.scheduledEpoch)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                else -> {
                                    val minutes = Times.minutesUntil(now, row.scheduledEpoch)
                                    Text(
                                        text = if (minutes < 60) {
                                            Times.minutesLabel(now, row.scheduledEpoch)
                                        } else {
                                            Times.hhmm(row.scheduledEpoch)
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    // Un numero nudo non dice se e' vero o
                                    // teorico: qui lo dice.
                                    Text(
                                        text = "previsto ${Times.hhmm(row.scheduledEpoch)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Text(
                text = if (current.rows.any { it.delaySeconds != null }) {
                    "In verde i minuti che vengono dal bus: col pallino quando il feed sta " +
                        "guardando questa fermata, senza quando li stiamo stimando piu' avanti."
                } else {
                    "Orari previsti da tabella: i minuti veri arrivano quando il bus " +
                        "e' in viaggio."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}
