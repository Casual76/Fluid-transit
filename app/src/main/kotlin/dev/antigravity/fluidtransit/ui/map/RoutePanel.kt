package dev.antigravity.fluidtransit.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidHairline
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidtransit.routing.BundleReader
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Tutto quello che la scheda linea sa dire, calcolato dal bundle in un
 * passaggio su Dispatchers.Default. I blocchi sono quelli decisi: testata,
 * elenco fermate per direzione, prima/ultima corsa e frequenza di oggi.
 * Gli orari accanto alle fermate sono quelli della prossima corsa, corretti
 * col ritardo live quando il feed la sta seguendo.
 */
class RouteInfo(
    val routeIndex: Int,
    val shortName: String,
    val longName: String,
    val agency: String,
    val category: String, // "Urbano" | "Extraurbano"
    val colorRgb: Int,
    val directions: List<Direction>,
    val firstDepToday: String?,
    val lastDepToday: String?,
    val headwayMinutes: Int?,
) {
    class Direction(
        val headsign: String,
        val stops: List<StopRef>,
        val durationMinutes: Int,
        /** La corsa a cui si riferiscono gli orari accanto alle fermate. */
        val nextTripLive: Boolean = false,
    )

    class StopRef(
        val stopIndex: Int,
        val name: String,
        val idHashHex: String,
        val lat: Double,
        val lon: Double,
        /**
         * Quando ci passa la prossima corsa. Zero se oggi non ne resta
         * nessuna. Fino alla Fase 8 la scheda linea non mostrava NESSUN
         * orario: era la mappa della linea e basta, e per sapere quando
         * passa bisognava uscire e toccare la fermata.
         */
        val timeEpoch: Long = 0L,
    )

    companion object {
        fun build(
            reader: BundleReader,
            routeIndex: Int,
            now: Instant,
            delays: dev.antigravity.fluidtransit.routing.DelayModel? = null,
        ): RouteInfo {
            val patterns = reader.patternsOfRoute(routeIndex)
            val today = now.atZone(dev.antigravity.fluidtransit.routing.Ftb.ROME).toLocalDate()
            val dayIndex = ChronoUnit.DAYS.between(reader.feedStart, today).toInt()

            // Per direzione, il pattern con piu' corse rappresenta la linea.
            val directions = (0..1).mapNotNull { dir ->
                val best = patterns
                    .filter { reader.patternDirection(it) == dir }
                    .maxByOrNull { reader.patternTripCount(it) }
                    ?: return@mapNotNull null
                val n = reader.patternStopCount(best)

                // La prossima corsa di questa direzione: e' quella a cui si
                // riferiscono gli orari mostrati accanto alle fermate.
                val dayStart = dev.antigravity.fluidtransit.routing.Ftb
                    .serviceDayStart(today).epochSecond
                var nextTrip = -1
                var nextDep = Long.MAX_VALUE
                if (dayIndex >= 0 && dayIndex < reader.dayCount) {
                    val firstTrip = reader.patternFirstTrip(best)
                    for (t in firstTrip until firstTrip + reader.patternTripCount(best)) {
                        if (!reader.serviceActive(reader.tripService(t), dayIndex)) continue
                        val dep = dayStart + reader.tripDeparture0(t)
                        // Cinque minuti di tolleranza: una corsa appena
                        // partita e' ancora quella che interessa a chi sta
                        // guardando le fermate piu' avanti.
                        if (dep >= now.epochSecond - 300 && dep < nextDep) {
                            nextDep = dep
                            nextTrip = t
                        }
                    }
                }
                val offsets = if (nextTrip >= 0) {
                    dev.antigravity.fluidtransit.routing.StopTimes
                        .offsets(reader, best, reader.tripProfile(nextTrip))
                } else {
                    null
                }
                val tripLive = nextTrip >= 0 && delays?.current(nextTrip) != null

                val stops = (0 until n).map { i ->
                    val s = reader.patternStop(best, i)
                    StopRef(
                        timeEpoch = if (offsets != null) {
                            val live = delays?.at(nextTrip, i, n)
                            nextDep + offsets[i] + (live?.delaySeconds ?: 0)
                        } else {
                            0L
                        },
                        stopIndex = s,
                        name = reader.stopName(s),
                        idHashHex = java.lang.Long.toHexString(reader.stopIdHash(s)),
                        lat = reader.stopLat(s),
                        lon = reader.stopLon(s),
                    )
                }
                // La durata: il profilo di una corsa mediana del pattern.
                val mid = reader.patternFirstTrip(best) + reader.patternTripCount(best) / 2
                val duration = reader.profileOffset(reader.tripProfile(mid), n - 1) / 60
                Direction(
                    headsign = reader.patternDestination(best),
                    stops = stops,
                    durationMinutes = duration,
                    nextTripLive = tripLive,
                )
            }

            // Prima/ultima corsa su tutta la linea; la frequenza su UNA sola
            // direzione — sommare i due sensi dimezzerebbe l'intervallo vero
            // (trovato sul device: "ogni 5 min" per una linea da 10).
            var first = Int.MAX_VALUE
            var last = Int.MIN_VALUE
            val depsAroundNow = ArrayList<Int>()
            val dayStart = dev.antigravity.fluidtransit.routing.Ftb.serviceDayStart(today)
            val nowSec = (now.epochSecond - dayStart.epochSecond).toInt()
            val headwayDirection = patterns.firstOrNull()?.let { reader.patternDirection(it) } ?: 0
            for (p in patterns) {
                val firstTrip = reader.patternFirstTrip(p)
                val sameDirection = reader.patternDirection(p) == headwayDirection
                for (k in 0 until reader.patternTripCount(p)) {
                    val t = firstTrip + k
                    if (!reader.serviceActive(reader.tripService(t), dayIndex)) continue
                    val dep = reader.tripDeparture0(t)
                    if (dep < first) first = dep
                    if (dep > last) last = dep
                    if (sameDirection && dep in (nowSec - 5400)..(nowSec + 5400)) {
                        depsAroundNow.add(dep)
                    }
                }
            }
            val headway = if (depsAroundNow.size >= 3) {
                depsAroundNow.sort()
                val gaps = (1 until depsAroundNow.size)
                    .map { depsAroundNow[it] - depsAroundNow[it - 1] }
                    .filter { it > 0 }
                    .sorted()
                if (gaps.isEmpty()) null else (gaps[gaps.size / 2] / 60).coerceAtLeast(1)
            } else {
                null
            }

            /**
             * L'orologio di una corsa, con due cifre e senza bugie sulle
             * notturne: negli orari GTFS l'ultima corsa del feed finisce
             * alle 30:10, e stampata con un modulo 24 secco diventava
             * "6:10" — cioe' stamattina invece di stanotte.
             */
            fun fmt(sec: Int): String {
                val label = "%02d:%02d".format((sec / 3600) % 24, (sec % 3600) / 60)
                return if (sec >= 24 * 3600) "$label di notte" else label
            }

            return RouteInfo(
                routeIndex = routeIndex,
                shortName = reader.routeShortName(routeIndex)
                    .ifEmpty { reader.routeLongName(routeIndex) },
                longName = reader.routeLongName(routeIndex),
                agency = reader.routeAgency(routeIndex),
                category = if (reader.routeAgency(routeIndex).contains("extraurbano", ignoreCase = true)) {
                    "Extraurbano"
                } else {
                    "Urbano"
                },
                colorRgb = reader.routeDisplayColor(routeIndex),
                directions = directions,
                firstDepToday = if (first == Int.MAX_VALUE) null else fmt(first),
                lastDepToday = if (last == Int.MIN_VALUE) null else fmt(last),
                headwayMinutes = headway,
            )
        }
    }
}

/** La pillola della linea, identica ovunque. */
@Composable
fun RoutePill(text: String, colorRgb: Int, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        maxLines = 1,
        modifier = modifier
            .widthIn(min = 44.dp)
            .background(
                color = Color(0xFF000000 or colorRgb.toLong()),
                shape = ContinuousCornerShape(FluidRadius.Small),
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * Il pop-up ridotto che prende il posto della tab bar: STESSA altezza della
 * capsula di navigazione (cosi' il congedo si legge come la tab bar che
 * ritorna), pillola, capolinea, numero fermate e durata. Un tocco o un
 * trascinamento verso l'alto lo espandono; il resto dei gesti vive
 * nell'host.
 */
@Composable
fun RouteMiniContent(info: RouteInfo, direction: Int) {
    val dir = info.directions.getOrNull(direction) ?: info.directions.firstOrNull() ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(dev.antigravity.fluidengine.ui.fluid.FluidTabBarDefaults.Height)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RoutePill(info.shortName, info.colorRgb)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "→ ${dir.headsign}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${dir.stops.size} fermate · ~${dir.durationMinutes} min",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * La scheda linea espansa, coi blocchi decisi: testata, prima/ultima corsa
 * e frequenza di oggi, direzioni, elenco fermate col salto sulla mappa.
 */
@Composable
fun RouteFullContent(
    info: RouteInfo,
    direction: Int,
    onDirectionChange: (Int) -> Unit,
    onStopTap: (RouteInfo.StopRef) -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
) {
    val dir = info.directions.getOrNull(direction) ?: info.directions.firstOrNull() ?: return

    Column(modifier = Modifier.fillMaxWidth()) {
        // --- testata -----------------------------------------------------
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
                    text = dir.headsign,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${info.category} · ${info.agency}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // La stella dei preferiti, come nella scheda fermata.
            androidx.compose.material3.Icon(
                imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = if (isFavorite) "Togli dai preferiti" else "Salva nei preferiti",
                tint = if (isFavorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .size(38.dp)
                    .clickable(
                        interactionSource = androidx.compose.runtime.remember {
                            androidx.compose.foundation.interaction.MutableInteractionSource()
                        },
                        indication = null,
                        onClick = onToggleFavorite,
                    )
                    .padding(7.dp),
            )
        }

        // --- oggi: prima/ultima corsa e frequenza ------------------------
        if (info.firstDepToday != null && info.lastDepToday != null) {
            Text(
                text = buildString {
                    append("Oggi: prima ${info.firstDepToday}, ultima ${info.lastDepToday}")
                    if (info.headwayMinutes != null) {
                        append(" · circa ogni ${info.headwayMinutes} min a quest'ora")
                    }
                    val dir = info.directions.getOrNull(direction)
                    if (dir?.stops?.any { it.timeEpoch > 0 } == true) {
                        append("\nGli orari qui sotto sono della prossima corsa")
                        append(if (dir.nextTripLive) ", corretti col ritardo live." else ", previsti.")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        } else {
            Text(
                text = "Oggi questa linea non ha corse.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        // --- direzione ---------------------------------------------------
        if (info.directions.size > 1) {
            FluidSegmentedControl(
                options = info.directions.indices.toList(),
                selected = direction.coerceIn(info.directions.indices),
                onSelect = onDirectionChange,
                label = { i ->
                    info.directions[i].headsign.let {
                        if (it.length > 18) it.take(17) + "…" else it
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 6.dp),
            )
        }

        // --- fermate, col salto sulla mappa ------------------------------
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .padding(horizontal = 8.dp),
        ) {
            items(dir.stops.size) { i ->
                val stop = dir.stops[i]
                if (i > 0) FluidHairline(modifier = Modifier.padding(start = 44.dp, end = 12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onStopTap(stop) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Il pallino della fermata, sul filo della linea.
                    androidx.compose.foundation.layout.Box(
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
                    if (stop.timeEpoch > 0) {
                        // L'orario della prossima corsa. Verde solo se il
                        // feed la sta davvero seguendo: altrimenti e' un
                        // orario previsto e non deve fingere di essere altro.
                        Text(
                            text = dev.antigravity.fluidtransit.routing.Times.hhmm(stop.timeEpoch),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (dir.nextTripLive) {
                                liveGreen()
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    } else if (i == 0 || i == dir.stops.size - 1) {
                        Text(
                            text = if (i == 0) "Partenza" else "Capolinea",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
