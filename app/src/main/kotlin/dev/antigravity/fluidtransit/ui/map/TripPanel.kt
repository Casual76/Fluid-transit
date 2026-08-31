package dev.antigravity.fluidtransit.ui.map

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidHairline
import dev.antigravity.fluidengine.ui.fluid.FluidTabBarDefaults
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Ftb
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Le chiavi di un bus toccato, come bastano a risalire a corsa e linea.
 * `tripIndex`/`routeIndex` valgono -1 quando il bundle non riconosce gli
 * hash — succede, le due generazioni di dati non sono sincronizzate.
 */
class TripRef(
    val vehKey: Int,
    val tripHash: Long,
    val routeHash: Long,
    val tripIndex: Int,
    val routeIndex: Int,
)

/**
 * Tutto quello che la scheda corsa sa dire. Come RouteInfo, si calcola in
 * un passaggio su Dispatchers.Default e si ricalcola quando arriva un
 * ritardo nuovo.
 */
class TripInfo(
    val ref: TripRef,
    val shortName: String,
    val colorRgb: Int,
    val headsign: String,
    val delaySec: Int?, // null = nessun trip-update per questa corsa
    val canceled: Boolean,
    val stops: List<NextStop>, // le prossime fermate, coi minuti gia' corretti
    val stopsTotal: Int,
) {
    class NextStop(
        val name: String,
        val idHashHex: String,
        val lat: Double,
        val lon: Double,
        val time: String,
        val minutes: Long,
        val isLast: Boolean,
    )

    companion object {
        fun build(
            reader: BundleReader,
            ref: TripRef,
            now: Instant,
            delaySec: Int?,
            canceled: Boolean,
        ): TripInfo {
            val shortName = if (ref.routeIndex >= 0) {
                reader.routeShortName(ref.routeIndex).ifEmpty { reader.routeLongName(ref.routeIndex) }
            } else {
                "?"
            }
            val color = if (ref.routeIndex >= 0) reader.routeDisplayColor(ref.routeIndex) else 0x8A8A93

            if (ref.tripIndex < 0) {
                return TripInfo(ref, shortName, color, "", delaySec, canceled, emptyList(), 0)
            }

            val pattern = reader.tripPattern(ref.tripIndex)
            val profile = reader.tripProfile(ref.tripIndex)
            val dep0 = reader.tripDeparture0(ref.tripIndex)
            val n = reader.patternStopCount(pattern)
            val today = now.atZone(Ftb.ROME).toLocalDate()

            // Il giorno di servizio della corsa IN CORSO: quasi sempre oggi,
            // ma una notturna dopo mezzanotte appartiene a ieri (le "25:30").
            var dayStartSec = 0L
            var found = false
            for (offset in 0 downTo -1) {
                val date = today.plusDays(offset.toLong())
                val dayIndex = ChronoUnit.DAYS.between(reader.feedStart, date).toInt()
                if (dayIndex < 0 || dayIndex >= reader.dayCount) continue
                if (!reader.serviceActive(reader.tripService(ref.tripIndex), dayIndex)) continue
                val start = Ftb.serviceDayStart(date).epochSecond + dep0
                // Corrente = partita da meno di 12 ore o in partenza entro 2.
                if (now.epochSecond in (start - 2 * 3600)..(start + 12 * 3600)) {
                    dayStartSec = Ftb.serviceDayStart(date).epochSecond
                    found = true
                    break
                }
            }
            if (!found) dayStartSec = Ftb.serviceDayStart(today).epochSecond

            val effDelay = delaySec ?: 0
            val stops = ArrayList<NextStop>(n)
            for (i in 0 until n) {
                val eff = dayStartSec + dep0 + reader.profileOffset(profile, i) + effDelay
                if (eff < now.epochSecond - 60) continue // gia' passata
                val s = reader.patternStop(pattern, i)
                val local = ZonedDateTime.ofInstant(Instant.ofEpochSecond(eff), Ftb.ROME)
                stops.add(
                    NextStop(
                        name = reader.stopName(s),
                        idHashHex = java.lang.Long.toHexString(reader.stopIdHash(s)),
                        lat = reader.stopLat(s),
                        lon = reader.stopLon(s),
                        time = "%02d:%02d".format(local.hour, local.minute),
                        minutes = (eff - now.epochSecond) / 60,
                        isLast = i == n - 1,
                    ),
                )
            }
            return TripInfo(
                ref = ref,
                shortName = shortName,
                colorRgb = color,
                headsign = reader.patternDestination(pattern),
                delaySec = delaySec,
                canceled = canceled,
                stops = stops,
                stopsTotal = n,
            )
        }
    }
}

/** Il verde del "live", leggibile su entrambi i temi. */
@Composable
fun liveGreen(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Color(0xFF53D28C)
    } else {
        Color(0xFF128A45)
    }

/** Il pallino pulsante accanto ai minuti veri: la convenzione decisa. */
@Composable
fun LiveDot(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "live")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "liveAlpha",
    )
    Box(
        modifier = modifier
            .size(7.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(color, CircleShape),
    )
}

/** La frase del ritardo, nel tono diretto dell'app. */
fun delayLabel(delaySec: Int?, canceled: Boolean): String = when {
    canceled -> "Corsa cancellata"
    delaySec == null -> "Orario programmato"
    delaySec > 90 -> "+${(delaySec + 30) / 60} min di ritardo"
    delaySec < -90 -> "${(-delaySec + 30) / 60} min in anticipo"
    else -> "In orario"
}

/**
 * Il mini della corsa: prende il posto della tab bar come quello della
 * linea — stessa altezza, stessa capsula — ma parla del bus vivo.
 */
@Composable
fun TripMiniContent(info: TripInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(FluidTabBarDefaults.Height)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RoutePill(info.shortName, info.colorRgb)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (info.headsign.isNotEmpty()) "→ ${info.headsign}" else "Bus in servizio",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val red = MaterialTheme.colorScheme.error
                if (info.delaySec != null && !info.canceled) LiveDot(liveGreen())
                Text(
                    text = buildString {
                        append(delayLabel(info.delaySec, info.canceled))
                        if (info.stops.isNotEmpty()) append(" · ${info.stops.size} fermate rimaste")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        info.canceled -> red
                        info.delaySec != null -> liveGreen()
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * La scheda corsa espansa: testata, stato live, prossime fermate coi minuti
 * gia' corretti dal ritardo. In Fase 7 qui sotto arrivera' il tasto in
 * vetro "sono su questo bus" con la guardia GPS.
 */
@Composable
fun TripFullContent(
    info: TripInfo,
    fixAgeSec: Int?,
    onStopTap: (TripInfo.NextStop) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoutePill(info.shortName, info.colorRgb)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (info.headsign.isNotEmpty()) info.headsign else "Bus in servizio",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (fixAgeSec != null && fixAgeSec >= 0) {
                        "Posizione live · aggiornata ${fixAgeSec}s fa"
                    } else {
                        "Posizione live"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (info.delaySec != null && !info.canceled) LiveDot(liveGreen())
            Text(
                text = delayLabel(info.delaySec, info.canceled),
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    info.canceled -> MaterialTheme.colorScheme.error
                    info.delaySec != null -> liveGreen()
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        if (info.ref.tripIndex < 0) {
            Text(
                text = "Questa corsa non e' negli orari di oggi: la posizione resta " +
                    "live, ma fermate e minuti non si possono calcolare.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
                    .fadeVerticalEdges()
                    .padding(horizontal = 8.dp),
            ) {
                items(info.stops.size) { i ->
                    val stop = info.stops[i]
                    if (i > 0) FluidHairline(modifier = Modifier.padding(start = 44.dp, end = 12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onStopTap(stop) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(
                                    color = Color(0xFF000000 or info.colorRgb.toLong()),
                                    shape = CircleShape,
                                ),
                        )
                        Text(
                            text = stop.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (stop.isLast) {
                            Text(
                                text = "Capolinea",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = if (stop.minutes < 60) "${stop.minutes} min" else stop.time,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (info.delaySec != null) {
                                    liveGreen()
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            if (stop.minutes < 60) {
                                Text(
                                    text = stop.time,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
