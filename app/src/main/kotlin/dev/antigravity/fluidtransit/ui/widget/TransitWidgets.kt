package dev.antigravity.fluidtransit.ui.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.action.actionStartActivity
import androidx.glance.layout.height
import androidx.glance.layout.Spacer
import androidx.glance.GlanceModifier
import androidx.glance.state.PreferencesGlanceStateDefinition
import dev.antigravity.fluidengine.widget.EngineWidgetGroup
import dev.antigravity.fluidengine.widget.EngineWidgetHairline
import dev.antigravity.fluidengine.widget.EngineWidgetHeader
import dev.antigravity.fluidengine.widget.EngineWidgetRow
import dev.antigravity.fluidengine.widget.EngineWidgetSurface
import dev.antigravity.fluidengine.widget.engineWidgetPalette
import dev.antigravity.fluidengine.widget.resolveEngineWidgetLayout
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.MainActivity
import dev.antigravity.fluidtransit.data.bundle.BundleManager
import dev.antigravity.fluidtransit.routing.Ftb
import dev.antigravity.fluidtransit.ui.theme.TransitBrand
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * I due widget decisi (entrambi, per volonta' dell'utente): le partenze di
 * una fermata preferita e il consiglio della routine di oggi. Tutto il
 * vestito viene dal kit dell'engine — palette dagli STESSI settings del
 * tema, budget di layout, componenti — cosi' la home e l'app dicono la
 * stessa cosa con la stessa voce.
 */

val KEY_STOP_HASH = stringPreferencesKey("stopHash")
val KEY_STOP_NAME = stringPreferencesKey("stopName")

class StopWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StopWidget()
}

class RoutineWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RoutineWidget()
}

class StopWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    class Row(val line: String, val destination: String, val minutes: Long)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as FluidTransitApp
        val settings = app.settingsStore.current()
        val palette = engineWidgetPalette(context, settings, TransitBrand)

        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val stopHash = prefs[KEY_STOP_HASH]
        val stopName = prefs[KEY_STOP_NAME] ?: ""
        val rows = loadDepartures(app, stopHash)

        provideContent {
            val layout = resolveEngineWidgetLayout(LocalSize.current, hasFooter = false)
            EngineWidgetSurface(
                palette = palette,
                layout = layout,
                onClick = actionStartActivity<MainActivity>(),
            ) {
                EngineWidgetHeader(
                    title = stopName.ifEmpty { "Fluid Transit" },
                    palette = palette,
                    layout = layout,
                    subtitle = when {
                        stopHash == null -> "Tocca per configurare"
                        rows == null -> "Orari in arrivo…"
                        else -> "Prossimi passaggi"
                    },
                )
                Spacer(GlanceModifier.height(if (layout.compact) 6.dp else 8.dp))
                EngineWidgetGroup(palette) {
                    when {
                        stopHash == null -> EngineWidgetRow(
                            title = "Scegli una fermata preferita",
                            subtitle = "dalla configurazione del widget",
                            palette = palette,
                            layout = layout,
                        )

                        rows.isNullOrEmpty() -> EngineWidgetRow(
                            title = "Nessun passaggio a breve",
                            subtitle = "nelle prossime due ore",
                            palette = palette,
                            layout = layout,
                        )

                        else -> {
                            rows.take(layout.rowLimit).forEachIndexed { i, r ->
                                if (i > 0) EngineWidgetHairline(palette, layout)
                                EngineWidgetRow(
                                    title = "${r.line} → ${r.destination}",
                                    palette = palette,
                                    layout = layout,
                                    tone = palette.primaryTone,
                                    trailing = if (r.minutes < 1) "ora" else "${r.minutes} min",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadDepartures(app: FluidTransitApp, stopHashHex: String?): List<Row>? {
        if (stopHashHex == null) return null
        val ready = withTimeoutOrNull(6_000) {
            app.bundleManager.state.filterIsInstance<BundleManager.BundleState.Ready>().first()
        } ?: return null
        val reader = ready.reader
        val hash = stopHashHex.toULongOrNull(16)?.toLong() ?: return emptyList()
        val stop = reader.findStopByIdHash(hash)
        if (stop < 0) return emptyList()
        val now = Instant.now()
        return reader.nextDepartures(stop, now, limit = 5, horizonSeconds = 2 * 3600).map { d ->
            Row(
                line = reader.routeShortName(d.routeIndex).ifEmpty { reader.routeLongName(d.routeIndex) },
                destination = reader.patternDestination(d.patternIndex),
                minutes = (d.instant.epochSecond - now.epochSecond) / 60,
            )
        }
    }
}

class RoutineWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as FluidTransitApp
        val settings = app.settingsStore.current()
        val palette = engineWidgetPalette(context, settings, TransitBrand)

        val today = LocalDate.now(Ftb.ROME).dayOfWeek.value
        val routines = dev.antigravity.fluidtransit.data.routines.Routines(context).list()
        val todayRoutine = routines.firstOrNull { it.enabled && today in it.days }
        val adviceToday = todayRoutine != null && todayRoutine.lastAdviceEpoch > 0 &&
            Instant.ofEpochSecond(todayRoutine.lastAdviceEpoch).atZone(Ftb.ROME)
                .toLocalDate() == LocalDate.now(Ftb.ROME)

        provideContent {
            val layout = resolveEngineWidgetLayout(LocalSize.current, hasFooter = false)
            EngineWidgetSurface(
                palette = palette,
                layout = layout,
                onClick = actionStartActivity<MainActivity>(),
            ) {
                EngineWidgetHeader(
                    title = "La tua routine",
                    palette = palette,
                    layout = layout,
                    subtitle = todayRoutine?.label?.ifEmpty { todayRoutine.toName },
                )
                Spacer(GlanceModifier.height(if (layout.compact) 6.dp else 8.dp))
                EngineWidgetGroup(palette) {
                    when {
                        todayRoutine == null -> EngineWidgetRow(
                            title = "Oggi niente routine",
                            subtitle = "Le crei dal dettaglio di un viaggio",
                            palette = palette,
                            layout = layout,
                        )

                        adviceToday -> EngineWidgetRow(
                            title = todayRoutine.lastAdviceText.ifEmpty { "Calcolo in corso" },
                            subtitle = "coi ritardi live di adesso",
                            palette = palette,
                            layout = layout,
                            tone = palette.primaryTone,
                            trailing = ZonedDateTime.ofInstant(
                                Instant.ofEpochSecond(todayRoutine.lastAdviceEpoch), Ftb.ROME,
                            ).let { "%02d:%02d".format(it.hour, it.minute) },
                        )

                        else -> EngineWidgetRow(
                            title = "Il consiglio arriva da solo",
                            subtitle = "circa 45 minuti prima dell'orario",
                            palette = palette,
                            layout = layout,
                        )
                    }
                }
            }
        }
    }
}
