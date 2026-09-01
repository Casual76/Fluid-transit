package dev.antigravity.fluidtransit.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidSectionTitle
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState
import dev.antigravity.fluidtransit.data.routines.RoutineScheduler
import dev.antigravity.fluidtransit.data.routines.Routines
import dev.antigravity.fluidtransit.data.rt.RtVehicles
import dev.antigravity.fluidtransit.ui.map.MapIntent
import dev.antigravity.fluidtransit.ui.map.resolveRt
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import dev.antigravity.fluidtransit.routing.Ftb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * La scheda Oggi, com'e' stata decisa: le prossime partenze (live) dalle
 * fermate preferite, le routine di oggi col loro stato, gli avvisi che
 * toccano le tue linee. E' "la mia giornata coi bus", non un'altra mappa.
 */
@Composable
fun TodayTab(
    app: FluidTransitApp,
    onOpenOnMap: (MapIntent) -> Unit,
) {
    val bundleState by app.bundleManager.state.collectAsStateWithLifecycle()
    val ready = bundleState as? BundleState.Ready
    val favVersion by app.favorites.version.collectAsStateWithLifecycle()
    val routinesVersion by app.routines.version.collectAsStateWithLifecycle()
    val routines = remember(routinesVersion) { app.routines.list() }
    val favStops = remember(favVersion) { app.favorites.stops() }
    val favRoutes = remember(favVersion) { app.favorites.routes() }

    // Il live anche qui: finche' la scheda e' davanti, un giro di ritardi
    // ogni 30 secondi — gli stessi flussi della mappa, nessun doppione.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(ready?.buildId) {
        if (ready == null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            app.realtime.refreshVehicles()
            while (true) {
                app.realtime.refreshDelays()
                delay(30_000)
            }
        }
    }
    val rtDelays by app.realtime.delays.collectAsStateWithLifecycle()

    class DepRow(
        val stopName: String,
        val stopHash: String,
        val line: String,
        val colorRgb: Int,
        val destination: String,
        val minutes: Long,
        val live: Boolean,
    )

    val departures by produceState<List<DepRow>?>(
        initialValue = null,
        ready?.buildId, favVersion, rtDelays,
    ) {
        val reader = ready?.reader
        if (reader == null || favStops.isEmpty()) {
            value = emptyList()
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            val delayByTrip = rtDelays?.let { d ->
                resolveRt(reader, RtVehicles(0, 0, emptyList()), d).delayByTrip
            }.orEmpty()
            val now = Instant.now()
            favStops.take(5).flatMap { fav ->
                val hash = fav.idHashHex.toULongOrNull(16)?.toLong()
                    ?: return@flatMap emptyList<DepRow>()
                val stop = reader.findStopByIdHash(hash)
                if (stop < 0) return@flatMap emptyList<DepRow>()
                reader.nextDepartures(stop, now, limit = 3, horizonSeconds = 2 * 3600).map { d ->
                    val delaySec = delayByTrip[d.tripIndex]
                    val eff = d.instant.epochSecond + (delaySec ?: 0)
                    DepRow(
                        stopName = reader.stopName(stop),
                        stopHash = fav.idHashHex,
                        line = reader.routeShortName(d.routeIndex)
                            .ifEmpty { reader.routeLongName(d.routeIndex) },
                        colorRgb = reader.routeDisplayColor(d.routeIndex),
                        destination = reader.patternDestination(d.patternIndex),
                        minutes = ((eff - now.epochSecond) / 60).coerceAtLeast(0),
                        live = delaySec != null,
                    )
                }
            }
        }
    }

    // Gli avvisi delle TUE linee (piu' quelli di rete, che riguardano tutti).
    val alerts by produceState(
        initialValue = emptyList<dev.antigravity.fluidtransit.data.rt.GtfsRtLite.RtAlert>(),
        favVersion,
    ) {
        val mine = favRoutes.mapNotNull { it.idHashHex.toULongOrNull(16)?.toLong() }.toSet()
        val all = app.realtime.fetchAlerts()
        val now = Instant.now().epochSecond
        value = all.filter { a ->
            val activeNow = (a.startEpoch == 0L || a.startEpoch <= now) &&
                (a.endEpoch == 0L || a.endEpoch >= now)
            activeNow && (a.routeHashes.isEmpty() || a.routeHashes.any { it in mine })
        }.take(6)
    }

    val today = LocalDate.now(Ftb.ROME).dayOfWeek.value

    FluidScreen(title = "Oggi") {
        // --- le partenze dai preferiti ---------------------------------
        if (favStops.isEmpty() && routines.isEmpty()) {
            item {
                FluidEmptyState(
                    title = "Oggi si costruisce dai preferiti",
                    detail = "Stella una fermata o crea una routine da un viaggio: " +
                        "questa scheda diventa la tua giornata coi bus.",
                )
            }
        }

        val deps = departures
        if (favStops.isNotEmpty()) {
            item { FluidSectionTitle(eyebrow = "Adesso", title = "Dalle tue fermate") }
            item {
                FluidListGroup {
                    if (deps == null) {
                        FluidListRow(title = "Un attimo…", subtitle = "Leggo gli orari")
                    } else if (deps.isEmpty()) {
                        FluidListRow(
                            title = "Nessun passaggio a breve",
                            subtitle = "Dalle tue fermate non parte niente nelle prossime due ore",
                        )
                    } else {
                        for (d in deps) {
                            FluidListRow(
                                eyebrow = d.stopName,
                                title = "${d.line} → ${d.destination}",
                                subtitle = if (d.live) "stima live" else "orario programmato",
                                meta = if (d.minutes < 1) "ora" else "${d.minutes} min",
                                leading = {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .background(
                                                color = Color(0xFF000000 or d.colorRgb.toLong()),
                                                shape = CircleShape,
                                            ),
                                    )
                                },
                                onClick = { onOpenOnMap(MapIntent.Stop(d.stopHash, d.stopName)) },
                            )
                        }
                    }
                }
            }
        }

        // --- le routine --------------------------------------------------
        if (routines.isNotEmpty()) {
            item { FluidSectionTitle(eyebrow = "Routine", title = "Le tue routine") }
            item {
                FluidListGroup {
                    for (r in routines) {
                        val isToday = today in r.days
                        val adviceToday = r.lastAdviceEpoch > 0 &&
                            Instant.ofEpochSecond(r.lastAdviceEpoch).atZone(Ftb.ROME)
                                .toLocalDate() == LocalDate.now(Ftb.ROME)
                        FluidListRow(
                            title = r.label.ifEmpty { "→ ${r.toName}" },
                            subtitle = buildString {
                                append(daysShort(r.days))
                                append(" · ")
                                append(if (r.anchor == "arrive") "entro le " else "parti alle ")
                                append("%02d:%02d".format(r.anchorMinutes / 60, r.anchorMinutes % 60))
                                if (isToday && adviceToday && r.lastAdviceText.isNotEmpty()) {
                                    append("\n")
                                    append(r.lastAdviceText)
                                }
                            },
                            meta = when {
                                !r.enabled -> "in pausa"
                                isToday -> "oggi"
                                else -> "attiva"
                            },
                            onClick = {
                                app.routines.update(r.id) {
                                    Routines.Routine(
                                        it.id, it.label, it.fromLat, it.fromLon, it.toLat,
                                        it.toLon, it.toName, it.days, it.anchor,
                                        it.anchorMinutes, !it.enabled,
                                        it.lastAdviceEpoch, it.lastAdviceText,
                                    )
                                }
                                val updated = app.routines.list().first { it.id == r.id }
                                if (updated.enabled) {
                                    RoutineScheduler.scheduleNextCompute(app, updated)
                                } else {
                                    RoutineScheduler.cancel(app, r.id)
                                }
                            },
                            contextActions = {
                                listOf(
                                    FluidContextAction(
                                        label = "Elimina la routine",
                                        destructive = true,
                                        onClick = {
                                            RoutineScheduler.cancel(app, r.id)
                                            app.routines.remove(r.id)
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        } else if (favStops.isNotEmpty()) {
            item {
                FluidListGroup {
                    FluidListRow(
                        title = "Nessuna routine, per ora",
                        subtitle = "Nel dettaglio di un viaggio trovi \"Rendine una routine\": " +
                            "l'app calcolera' da sola quando devi uscire",
                    )
                }
            }
        }

        // --- gli avvisi --------------------------------------------------
        if (alerts.isNotEmpty()) {
            item { FluidSectionTitle(eyebrow = "Avvisi", title = "Sulle tue linee") }
            item {
                FluidListGroup {
                    for (a in alerts) {
                        FluidListRow(
                            title = a.header.ifEmpty { "Avviso di servizio" },
                            subtitle = a.description.take(220),
                        )
                    }
                }
            }
        }
    }
}

private fun daysShort(days: Set<Int>): String {
    if (days.size == 7) return "Tutti i giorni"
    if (days == setOf(1, 2, 3, 4, 5)) return "Lun–Ven"
    val names = listOf("Lun", "Mar", "Mer", "Gio", "Ven", "Sab", "Dom")
    return days.sorted().joinToString(" ") { names[it - 1] }
}
