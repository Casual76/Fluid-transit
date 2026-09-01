package dev.antigravity.fluidtransit.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.ui.theme.TransitBrand
import kotlinx.coroutines.launch

/**
 * La configurazione del widget fermata: scegli una delle tue fermate
 * preferite. Se non ne hai ancora, il widget te lo dice invece di aprire
 * mezza app dentro un dialogo.
 */
class StopWidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setResult(RESULT_CANCELED)

        val app = application as FluidTransitApp
        setContent {
            val settings by app.settingsStore.settings
                .collectAsStateWithLifecycle(initialValue = null)
            val s = settings ?: return@setContent
            FluidTheme(settings = s, brand = TransitBrand) {
                val stops = app.favorites.stops()
                FluidScreen(title = "Quale fermata?") {
                    if (stops.isEmpty()) {
                        item {
                            FluidEmptyState(
                                title = "Nessuna fermata preferita",
                                detail = "Stella una fermata nell'app: il widget " +
                                    "mostra le partenze di una delle tue.",
                            )
                        }
                    } else {
                        item {
                            FluidListGroup {
                                for (stop in stops) {
                                    FluidListRow(
                                        title = stop.name,
                                        subtitle = "Le prossime partenze sulla home",
                                        onClick = { pick(appWidgetId, stop) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun pick(appWidgetId: Int, stop: dev.antigravity.fluidtransit.data.favorites.Favorites.Stop) {
        lifecycleScope.launch {
            val manager = GlanceAppWidgetManager(this@StopWidgetConfigActivity)
            val glanceId = manager.getGlanceIdBy(appWidgetId)
            updateAppWidgetState(this@StopWidgetConfigActivity, glanceId) { prefs ->
                prefs[KEY_STOP_HASH] = stop.idHashHex
                prefs[KEY_STOP_NAME] = stop.name
            }
            StopWidget().update(this@StopWidgetConfigActivity, glanceId)
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            )
            finish()
        }
    }
}
