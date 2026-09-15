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
import androidx.glance.appwidget.updateAll

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
                // Il nome che vale e' quello degli orari, qui come nelle
                // schede e nel widget: quello salvato accanto alla stella e'
                // un ripiego, e resta com'era il giorno in cui si e' messa.
                val bundle by app.bundleManager.state.collectAsStateWithLifecycle()
                val reader = (
                    bundle
                        as? dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState.Ready
                    )?.reader
                fun nomeDi(stop: dev.antigravity.fluidtransit.data.favorites.Favorites.Stop): String =
                    reader?.let { r ->
                        stop.idHashHex.toULongOrNull(16)?.toLong()
                            ?.let { r.findStopByIdHash(it) }
                            ?.takeIf { it >= 0 }
                            ?.let { r.stopName(it) }
                    }?.ifEmpty { null } ?: stop.name
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
                                        title = nomeDi(stop),
                                        subtitle = "Le prossime partenze sulla home",
                                        onClick = { pick(appWidgetId, stop, nomeDi(stop)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun pick(
        appWidgetId: Int,
        stop: dev.antigravity.fluidtransit.data.favorites.Favorites.Stop,
        nome: String,
    ) {
        lifecycleScope.launch {
            val manager = GlanceAppWidgetManager(this@StopWidgetConfigActivity)
            val glanceId = manager.getGlanceIdBy(appWidgetId)
            updateAppWidgetState(this@StopWidgetConfigActivity, glanceId) { prefs ->
                prefs[KEY_STOP_HASH] = stop.idHashHex
                // Si salva il nome buono, cosi' anche il ripiego — quello
                // che il widget usa prima che gli orari siano pronti — parte
                // gia' giusto.
                prefs[KEY_STOP_NAME] = nome
            }
            StopWidget().update(this@StopWidgetConfigActivity, glanceId)
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            )

            // E ancora, un attimo dopo, con una sveglia.
            //
            // L'aggiornamento qui sopra da solo non basta: misurato
            // sull'emulatore, il widget puo' restare a "Tocca per configurare"
            // pur avendo la fermata gia' scritta nel suo stato. Il lanciatore
            // finisce di agganciarlo DOPO che questa Activity ha chiuso.
            //
            // Una sveglia e non una coroutine con un ritardo: chiuso questo
            // schermo il processo resta senza attivita' ne' servizi, e Android
            // lo puo' chiudere prima che il ritardo scada. Una sveglia
            // sopravvive al processo, e la sua ricevente ha gia' il goAsync
            // che tiene in piedi il giro fino al disegno.
            //
            // Onesta': non ho potuto verificare che questo CHIUDA il problema,
            // solo che e' il meccanismo giusto per provarci. Il difetto resta
            // scritto in APERTO.md con quello che e' stato escluso.
            WidgetRefresher.refreshSoon(applicationContext)
            finish()
        }
    }
}
