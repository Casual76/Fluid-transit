package dev.antigravity.fluidtransit.ui.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.action.actionStartActivity
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
import dev.antigravity.fluidtransit.routing.DelayModel
import dev.antigravity.fluidtransit.routing.DepartureBoard
import dev.antigravity.fluidtransit.routing.DepartureText
import dev.antigravity.fluidtransit.routing.Ftb
import dev.antigravity.fluidtransit.routing.Times
import dev.antigravity.fluidtransit.ui.nav.Deeplink
import dev.antigravity.fluidtransit.ui.theme.TransitBrand
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Il tocco su un widget porta dove il widget guarda.
 *
 * Prima portava all'app e basta: toccavi "SODERINI · 12 fra 3 minuti" e ti
 * trovavi la Toscana intera a zoom 7,6, con la fermata da ricercare a mano.
 * Un widget che non sa aprire la cosa che mostra e' un cartello, non una
 * scorciatoia.
 */
private fun openLink(context: Context, link: String?): Intent =
    Intent(context, MainActivity::class.java).apply {
        if (link != null) {
            action = Intent.ACTION_VIEW
            data = Uri.parse(link)
        }
    }

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

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as FluidTransitApp
        val settings = app.settingsStore.current()
        val palette = engineWidgetPalette(context, settings, TransitBrand)

        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val stopHash = prefs[KEY_STOP_HASH]
        val stopName = prefs[KEY_STOP_NAME] ?: ""
        val board = loadBoard(app, stopHash)
        val rows = board?.rows

        provideContent {
            val layout = resolveEngineWidgetLayout(LocalSize.current, hasFooter = false)
            EngineWidgetSurface(
                palette = palette,
                layout = layout,
                onClick = actionStartActivity(
                    openLink(context, stopHash?.let { Deeplink.stop(it, stopName) }),
                ),
            ) {
                EngineWidgetHeader(
                    title = stopName.ifEmpty { "Fluid Transit" },
                    palette = palette,
                    layout = layout,
                    subtitle = when {
                        stopHash == null -> "Tocca per configurare"
                        rows == null -> "Orari in arrivo…"
                        // Un widget dice sempre di quando sono i suoi numeri:
                        // e' l'unico posto dove l'utente non puo' chiedere.
                        // Un widget dice sempre di quando sono i suoi
                        // numeri con le stesse parole del resto dell'app.
                        else -> "Prossimi passaggi · " + DepartureText.boardSource(board)
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
                                val phrase = DepartureText.phrase(r, board.computedAtEpoch)
                                EngineWidgetRow(
                                    title = "${r.line} → ${r.destination}",
                                    subtitle = phrase.support,
                                    palette = palette,
                                    layout = layout,
                                    tone = palette.primaryTone,
                                    trailing = phrase.headline,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Il tabellone della fermata del widget.
     *
     * Un widget non puo' iscriversi a un flusso — vive il tempo di
     * disegnarsi — quindi chiede uno scatto. Ma lo chiede alla stessa fonte
     * di tutte le schermate, con le stesse regole e le stesse parole: era
     * l'unica superficie che mostrava i minuti nudi, senza dire da dove
     * venissero se non nel titolo.
     */
    private suspend fun loadBoard(app: FluidTransitApp, stopHashHex: String?): DepartureBoard? {
        if (stopHashHex == null) return null
        val ready = withTimeoutOrNull(BUNDLE_WAIT_MS) {
            app.bundleManager.state.filterIsInstance<BundleManager.BundleState.Ready>().first()
        } ?: return null
        val hash = stopHashHex.toULongOrNull(16)?.toLong() ?: return null
        val stop = ready.reader.findStopByIdHash(hash)
        if (stop < 0) return null

        // I ritardi, che il widget prima non guardava affatto: mostrava gli
        // orari di tabella come se fossero certi, e per mezz'ora di fila.
        //
        // In parallelo, e con poco tempo. Questo codice gira anche dentro il
        // `goAsync` di una ricevente, che il sistema si aspetta finisca in
        // una decina di secondi: in fila, sei secondi di attesa del bundle
        // piu' quattro piu' quattro fanno quattordici, e un disegno che non
        // arriva in tempo lascia sulla home la schermata di prima. E' il
        // sospetto piu' probabile dietro al widget che restava a "Tocca per
        // configurare" dopo la configurazione.
        //
        // Se la rete non risponde in tempo si mostrano gli orari di tabella,
        // dicendolo: e' il comportamento giusto, non una rinuncia.
        runCatching {
            withTimeoutOrNull(REALTIME_WAIT_MS) {
                coroutineScope {
                    launch { runCatching { app.realtime.refreshDelays() } }
                    launch { runCatching { app.realtime.refreshPredictions() } }
                }
            }
        }
        return app.departureBoards.snapshot(listOf(stop), limit = 5)
    }

    private companion object {
        /** Quanto si aspetta il bundle: aprirlo e' una mmap, non un download. */
        const val BUNDLE_WAIT_MS = 5_000L

        /**
         * Quanto si aspettano i ritardi.
         *
         * Il totale con l'attesa del bundle deve stare sotto la decina di
         * secondi che il sistema concede a una ricevente.
         */
        const val REALTIME_WAIT_MS = 3_000L
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
                onClick = actionStartActivity(
                    openLink(context, todayRoutine?.let { Deeplink.journey(it.id) }),
                ),
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
