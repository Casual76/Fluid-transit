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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidHairline
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Ftb
import dev.antigravity.fluidtransit.routing.Raptor
import java.time.Instant
import java.time.ZonedDateTime

/** Un viaggio gia' tradotto in stringhe: la UI non tocca il reader. */
class UiJourney(
    val depTime: String,
    val arrTime: String,
    val durationMin: Long,
    val transfers: Int,
    val walkMin: Int,
    val walkOnly: Boolean,
    val hasLive: Boolean,
    val pills: List<Pair<String, Int>>, // (nome linea, colore)
    val legs: List<UiLeg>,
    val raw: Raptor.Journey,
) {
    companion object {
        fun of(reader: BundleReader, j: Raptor.Journey): UiJourney {
            val legs = j.legs.map { leg ->
                when (leg) {
                    is Raptor.Leg.Walk -> UiLeg.Walk(
                        minutes = (leg.seconds + 30) / 60,
                        toName = if (leg.toStop >= 0) reader.stopName(leg.toStop) else "destinazione",
                        depTime = hm(leg.departure),
                    )

                    is Raptor.Leg.Ride -> UiLeg.Ride(
                        line = reader.routeShortName(leg.route)
                            .ifEmpty { reader.routeLongName(leg.route) },
                        colorRgb = reader.routeDisplayColor(leg.route),
                        headsign = reader.patternDestination(leg.pattern),
                        boardName = reader.stopName(leg.boardStop),
                        alightName = reader.stopName(leg.alightStop),
                        depTime = hm(leg.departure),
                        arrTime = hm(leg.arrival),
                        stops = leg.alightPosition - leg.boardPosition,
                        delaySeconds = leg.delaySeconds,
                    )
                }
            }
            return UiJourney(
                depTime = hm(j.departure),
                arrTime = hm(j.arrival),
                durationMin = (j.durationSeconds + 30) / 60,
                transfers = j.transfers,
                walkMin = (j.walkSeconds + 30) / 60,
                walkOnly = j.isWalkOnly,
                hasLive = j.legs.any { it is Raptor.Leg.Ride && it.delaySeconds != 0 },
                pills = j.legs.filterIsInstance<Raptor.Leg.Ride>().map { r ->
                    reader.routeShortName(r.route).ifEmpty { reader.routeLongName(r.route) } to
                        reader.routeDisplayColor(r.route)
                },
                legs = legs,
                raw = j,
            )
        }

        private fun hm(i: Instant): String = ZonedDateTime.ofInstant(i, Ftb.ROME)
            .let { "%02d:%02d".format(it.hour, it.minute) }
    }
}

sealed class UiLeg {
    class Walk(val minutes: Int, val toName: String, val depTime: String) : UiLeg()
    class Ride(
        val line: String,
        val colorRgb: Int,
        val headsign: String,
        val boardName: String,
        val alightName: String,
        val depTime: String,
        val arrTime: String,
        val stops: Int,
        val delaySeconds: Int,
    ) : UiLeg()
}

/**
 * Le soluzioni di viaggio nel pannello dal basso, come deciso: orari,
 * durata, la sequenza delle pillole colorate, i minuti live. Un tocco apre
 * il dettaglio nella stessa superficie.
 */
@Composable
fun JourneysContent(
    toName: String,
    journeys: List<UiJourney>?,
    fromLabel: String,
    timeLabel: String,
    backdrop: GlassBackdropState,
    onTimeTap: () -> Unit,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "→ $toName",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = fromLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "Chiudi",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(40.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onDismiss,
                )
                .padding(8.dp),
        )
    }

    Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        GlassActionButton(
            text = timeLabel,
            icon = Icons.Rounded.Schedule,
            backdrop = backdrop,
            onClick = onTimeTap,
        )
    }

    when {
        journeys == null -> {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FluidSpinner()
                Text(
                    text = "Cerco i prossimi viaggi…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        journeys.isEmpty() -> {
            FluidEmptyState(
                title = "Nessun viaggio trovato",
                detail = "In questa finestra il bus non ci arriva. Prova a cambiare orario.",
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(12.dp))
        }

        else -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .fadeVerticalEdges()
                    .padding(horizontal = 20.dp),
            ) {
                items(journeys.size) { i ->
                    if (i > 0) FluidHairline()
                    JourneyRow(journeys[i]) { onPick(i) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun JourneyRow(j: UiJourney, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "${j.depTime} → ${j.arrTime}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (j.hasLive) LiveDot(liveGreen())
            }
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.DirectionsWalk,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                if (j.walkOnly) {
                    Text(
                        text = "solo a piedi",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    for ((line, color) in j.pills) {
                        RoutePill(text = line, colorRgb = color)
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${j.durationMin} min",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = when {
                    j.walkOnly -> "${j.walkMin} min a piedi"
                    j.transfers == 0 -> "diretto"
                    j.transfers == 1 -> "1 cambio"
                    else -> "${j.transfers} cambi"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Il dettaglio tappa per tappa, col percorso gia' acceso sulla mappa. */
@Composable
fun JourneyDetailContent(
    j: UiJourney,
    toName: String,
    onDismiss: () -> Unit,
    backdrop: GlassBackdropState? = null,
    /** (giorni 1-7, "arrive"|"depart", minuti dalla mezzanotte). */
    onCreateRoutine: ((Set<Int>, String, Int) -> Unit)? = null,
    onStart: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "${j.depTime} → ${j.arrTime}",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (j.hasLive) LiveDot(liveGreen())
            }
            Text(
                text = "${j.durationMin} min · ${
                    if (j.transfers == 1) "1 cambio" else "${j.transfers} cambi"
                } · ${j.walkMin} min a piedi · → $toName",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "Chiudi",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(40.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onDismiss,
                )
                .padding(8.dp),
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 380.dp)
            .fadeVerticalEdges()
            .padding(horizontal = 20.dp),
    ) {
        items(j.legs.size) { i ->
            if (i > 0) FluidHairline(modifier = Modifier.padding(start = 32.dp))
            when (val leg = j.legs[i]) {
                is UiLeg.Walk -> Row(
                    modifier = Modifier.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.DirectionsWalk,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "Cammina ${leg.minutes} min fino a ${leg.toName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = leg.depTime,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is UiLeg.Ride -> Row(
                    modifier = Modifier.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .size(12.dp)
                            .background(
                                color = Color(0xFF000000 or leg.colorRgb.toLong()),
                                shape = CircleShape,
                            ),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RoutePill(text = leg.line, colorRgb = leg.colorRgb)
                            Text(
                                text = "→ ${leg.headsign}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${leg.depTime}  ${leg.boardName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (leg.stops == 1) "1 fermata" else "${leg.stops} fermate",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                        Text(
                            text = "${leg.arrTime}  ${leg.alightName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (leg.delaySeconds != 0) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier.padding(top = 2.dp),
                            ) {
                                LiveDot(liveGreen())
                                Text(
                                    text = delayLabel(leg.delaySeconds, canceled = false),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = liveGreen(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // --- "Avvia": la navigazione a bordo (Fase 7) -------------------------
    if (backdrop != null && onStart != null && !j.walkOnly) {
        Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            GlassActionButton(
                text = "Avvia il viaggio",
                icon = Icons.AutoMirrored.Rounded.DirectionsWalk,
                backdrop = backdrop,
                emphasized = true,
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // --- "Rendine una routine": il percorso naturale deciso ---------------
    if (backdrop != null && onCreateRoutine != null && !j.walkOnly) {
        RoutineForm(j = j, backdrop = backdrop, onCreateRoutine = onCreateRoutine)
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun RoutineForm(
    j: UiJourney,
    backdrop: GlassBackdropState,
    onCreateRoutine: (Set<Int>, String, Int) -> Unit,
) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var created by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var days by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(setOf(1, 2, 3, 4, 5))
    }
    var anchor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("arrive") }

    if (created) {
        Text(
            text = "Routine creata: la trovi nella scheda Oggi. Nei giorni scelti " +
                "ti diro' io quando uscire.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
        return
    }

    if (!open) {
        Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            GlassActionButton(
                text = "Rendine una routine",
                icon = Icons.Rounded.Schedule,
                backdrop = backdrop,
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }

    // I giorni: sette lettere, tocco per accendere.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val letters = listOf("L", "M", "M", "G", "V", "S", "D")
        for (d in 1..7) {
            val on = d in days
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = { days = if (on) days - d else days + d },
                    )
                    .background(
                        color = if (on) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        },
                        shape = CircleShape,
                    )
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = letters[d - 1],
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) Color.White else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl(
        options = listOf("arrive", "depart"),
        selected = anchor,
        onSelect = { anchor = it },
        label = { if (it == "arrive") "Arriva entro ${j.arrTime}" else "Parti alle ${j.depTime}" },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    )

    Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        GlassActionButton(
            text = "Crea la routine",
            icon = null,
            backdrop = backdrop,
            emphasized = true,
            onClick = {
                if (days.isEmpty()) return@GlassActionButton
                val time = if (anchor == "arrive") j.arrTime else j.depTime
                val minutes = time.split(':').let { it[0].toInt() * 60 + it[1].toInt() }
                // "Arriva entro" si arrotonda in su ai 5 minuti: un margine
                // onesto, non una promessa al secondo.
                val anchorMinutes = if (anchor == "arrive") ((minutes + 4) / 5) * 5 else minutes
                onCreateRoutine(days, anchor, anchorMinutes)
                created = true
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * La geometria del viaggio per la mappa: corse ritagliate dalla polilinea
 * del pattern (v4; senza sezione si ripiega sulle fermate), camminate in
 * linea retta tratteggiata. Ritorna anche il riquadro da inquadrare.
 */
fun buildJourneyGeometry(
    reader: BundleReader,
    j: Raptor.Journey,
): Pair<org.maplibre.geojson.FeatureCollection, DoubleArray> {
    var minLat = 90.0
    var maxLat = -90.0
    var minLon = 180.0
    var maxLon = -180.0

    fun grow(lat: Double, lon: Double) {
        if (lat < minLat) minLat = lat
        if (lat > maxLat) maxLat = lat
        if (lon < minLon) minLon = lon
        if (lon > maxLon) maxLon = lon
    }

    val features = ArrayList<org.maplibre.geojson.Feature>(j.legs.size)
    for (leg in j.legs) {
        when (leg) {
            is Raptor.Leg.Walk -> {
                grow(leg.fromLat, leg.fromLon)
                grow(leg.toLat, leg.toLon)
                val f = org.maplibre.geojson.Feature.fromGeometry(
                    org.maplibre.geojson.LineString.fromLngLats(
                        listOf(
                            org.maplibre.geojson.Point.fromLngLat(leg.fromLon, leg.fromLat),
                            org.maplibre.geojson.Point.fromLngLat(leg.toLon, leg.toLat),
                        ),
                    ),
                )
                f.addStringProperty("t", "w")
                features.add(f)
            }

            is Raptor.Leg.Ride -> {
                val points = ArrayList<org.maplibre.geojson.Point>(64)
                val poly = reader.patternPolyline(leg.pattern)
                if (poly != null) {
                    val a = reader.patternStopVertex(leg.pattern, leg.boardPosition)
                        .coerceIn(0, poly.size - 1)
                    val b = reader.patternStopVertex(leg.pattern, leg.alightPosition)
                        .coerceIn(0, poly.size - 1)
                    for (v in minOf(a, b)..maxOf(a, b)) {
                        grow(poly.lat[v], poly.lon[v])
                        points.add(org.maplibre.geojson.Point.fromLngLat(poly.lon[v], poly.lat[v]))
                    }
                } else {
                    for (pos in leg.boardPosition..leg.alightPosition) {
                        val s = reader.patternStop(leg.pattern, pos)
                        grow(reader.stopLat(s), reader.stopLon(s))
                        points.add(
                            org.maplibre.geojson.Point.fromLngLat(reader.stopLon(s), reader.stopLat(s)),
                        )
                    }
                }
                if (points.size >= 2) {
                    val f = org.maplibre.geojson.Feature.fromGeometry(
                        org.maplibre.geojson.LineString.fromLngLats(points),
                    )
                    f.addStringProperty("t", "r")
                    f.addStringProperty(
                        "c",
                        "#%06x".format(reader.routeDisplayColor(leg.route) and 0xFFFFFF),
                    )
                    features.add(f)
                }
            }
        }
    }
    return org.maplibre.geojson.FeatureCollection.fromFeatures(features) to
        doubleArrayOf(minLat, minLon, maxLat, maxLon)
}

/**
 * Il piano che il servizio di navigazione segue, precalcolato dal viaggio:
 * tappe in numeri puri, cosi' il servizio corregge i tempi coi ritardi
 * senza rifare il calcolo.
 */
fun buildNavPlan(
    reader: BundleReader,
    j: Raptor.Journey,
    destName: String,
): dev.antigravity.fluidtransit.data.nav.NavPlan {
    val legs = j.legs.map { leg ->
        when (leg) {
            is Raptor.Leg.Walk -> dev.antigravity.fluidtransit.data.nav.NavLeg.Walk(
                seconds = leg.seconds,
                toName = if (leg.toStop >= 0) reader.stopName(leg.toStop) else destName,
                startEpoch = leg.departure.epochSecond,
            )

            is Raptor.Leg.Ride -> {
                val profile = reader.tripProfile(leg.trip)
                val dep0 = reader.tripDeparture0(leg.trip)
                val dayStart = leg.departure.epochSecond - dep0 -
                    reader.profileOffset(profile, leg.boardPosition) - leg.delaySeconds
                dev.antigravity.fluidtransit.data.nav.NavLeg.Ride(
                    trip = leg.trip,
                    pattern = leg.pattern,
                    route = leg.route,
                    boardPosition = leg.boardPosition,
                    alightPosition = leg.alightPosition,
                    dayStartEpoch = dayStart,
                    dep0 = dep0,
                    profile = profile,
                    lineName = reader.routeShortName(leg.route)
                        .ifEmpty { reader.routeLongName(leg.route) },
                    alightName = reader.stopName(leg.alightStop),
                    stopNames = (leg.boardPosition..leg.alightPosition).map {
                        reader.stopName(reader.patternStop(leg.pattern, it))
                    },
                )
            }
        }
    }
    return dev.antigravity.fluidtransit.data.nav.NavPlan("journey", destName, legs)
}

/**
 * Il mini di navigazione: prende il posto della tab bar mentre si viaggia.
 * "Scendi a X · 4 fermate · 12 min", e Termina sempre a portata.
 */
@Composable
fun NavMiniContent(
    state: dev.antigravity.fluidtransit.data.nav.NavState,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(dev.antigravity.fluidengine.ui.fluid.FluidTabBarDefaults.Height)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.phase == "ride") LiveDot(liveGreen())
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.headline,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.detail,
                style = MaterialTheme.typography.labelMedium,
                color = if (state.phase == "ride") liveGreen() else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        androidx.compose.material3.Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "Termina la navigazione",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(40.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onStop,
                )
                .padding(8.dp),
        )
    }
}

/**
 * Il piano per "sono su questo bus": una sola tappa in vettura, dalla
 * prossima fermata al capolinea. Il giorno di servizio si risolve come
 * nella scheda corsa (una notturna dopo mezzanotte appartiene a ieri).
 */
fun buildBusNavPlan(
    reader: BundleReader,
    tripIndex: Int,
    delaySec: Int,
): dev.antigravity.fluidtransit.data.nav.NavPlan? {
    val pattern = reader.tripPattern(tripIndex)
    val profile = reader.tripProfile(tripIndex)
    val dep0 = reader.tripDeparture0(tripIndex)
    val n = reader.patternStopCount(pattern)
    val now = Instant.now()
    val today = now.atZone(Ftb.ROME).toLocalDate()

    var dayStart = Ftb.serviceDayStart(today).epochSecond
    for (offset in 0 downTo -1) {
        val date = today.plusDays(offset.toLong())
        val start = Ftb.serviceDayStart(date).epochSecond + dep0
        if (now.epochSecond in (start - 2 * 3600)..(start + 12 * 3600)) {
            dayStart = Ftb.serviceDayStart(date).epochSecond
            break
        }
    }

    var boardPos = 0
    for (pos in 0 until n) {
        val t = dayStart + dep0 + reader.profileOffset(profile, pos) + delaySec
        if (t > now.epochSecond) {
            boardPos = (pos - 1).coerceAtLeast(0)
            break
        }
    }
    if (boardPos >= n - 1) return null // corsa gia' finita

    val route = reader.patternRoute(pattern)
    val destName = reader.patternDestination(pattern)
    return dev.antigravity.fluidtransit.data.nav.NavPlan(
        kind = "bus",
        destName = destName,
        legs = listOf(
            dev.antigravity.fluidtransit.data.nav.NavLeg.Ride(
                trip = tripIndex,
                pattern = pattern,
                route = route,
                boardPosition = boardPos,
                alightPosition = n - 1,
                dayStartEpoch = dayStart,
                dep0 = dep0,
                profile = profile,
                lineName = reader.routeShortName(route).ifEmpty { reader.routeLongName(route) },
                alightName = destName,
                stopNames = (boardPos until n).map {
                    reader.stopName(reader.patternStop(pattern, it))
                },
            ),
        ),
    )
}
