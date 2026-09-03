package dev.antigravity.fluidtransit.ui.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.data.bundle.BundleManager
import java.time.Instant
import kotlinx.coroutines.launch

/**
 * La sveglia dei widget.
 *
 * Android non concede a `updatePeriodMillis` niente di piu' fitto di mezz'ora,
 * e mezz'ora su un conteggio di minuti vuol dire numeri sbagliati di
 * mezz'ora: e' il difetto che l'utente avrebbe visto per primo, perche' il
 * widget e' il posto dove si guarda senza aprire l'app.
 *
 * La regola decisa con l'utente ("sveglia quando serve"): si rinfresca ogni
 * cinque minuti SOLO quando c'e' un passaggio entro la mezz'ora, e il resto
 * del tempo si dorme fino a poco prima del prossimo. Alarm inesatti: qui non
 * si sta perdendo un autobus, si sta aggiornando un numero, e la batteria
 * ringrazia.
 */
object WidgetRefresher {

    private const val ACTION = "dev.antigravity.fluidtransit.WIDGET_REFRESH"

    /** Ogni quanto si rinfresca quando c'e' qualcosa in arrivo. */
    private const val HOT_MINUTES = 5L

    /** Quanto prima di un passaggio si comincia a stare svegli. */
    private const val WARMUP_MINUTES = 30L

    /** Il sonno massimo: anche senza passaggi si controlla ogni tanto. */
    private const val COLD_MINUTES = 30L

    suspend fun scheduleNext(context: Context) {
        val app = context.applicationContext as? FluidTransitApp ?: return
        val minutes = runCatching { nextWakeMinutes(app) }.getOrDefault(COLD_MINUTES)
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + minutes * 60_000
        alarms.set(AlarmManager.RTC, at, pending(context))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context))
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, Receiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Quanto dormire: cinque minuti se un bus sta arrivando, altrimenti
     * fino a mezz'ora prima del prossimo passaggio.
     */
    private suspend fun nextWakeMinutes(app: FluidTransitApp): Long {
        val reader = (app.bundleManager.state.value as? BundleManager.BundleState.Ready)?.reader
            ?: return COLD_MINUTES
        val manager = GlanceAppWidgetManager(app)
        val ids = manager.getGlanceIds(StopWidget::class.java)
        if (ids.isEmpty()) return COLD_MINUTES

        val now = Instant.now()
        var soonest = Long.MAX_VALUE
        for (id in ids) {
            val prefs = runCatching {
                getAppWidgetState(app, PreferencesGlanceStateDefinition, id)
            }.getOrNull() ?: continue
            val hashHex = prefs[KEY_STOP_HASH] ?: continue
            val hash = hashHex.toULongOrNull(16)?.toLong() ?: continue
            val stop = reader.findStopByIdHash(hash)
            if (stop < 0) continue
            val next = reader
                .nextDepartures(stop, now, limit = 1, horizonSeconds = 3 * 3600)
                .firstOrNull() ?: continue
            soonest = minOf(soonest, next.instant.epochSecond - now.epochSecond)
        }
        if (soonest == Long.MAX_VALUE) return COLD_MINUTES
        val minutesToNext = soonest / 60
        return when {
            minutesToNext <= WARMUP_MINUTES -> HOT_MINUTES
            else -> (minutesToNext - WARMUP_MINUTES).coerceIn(HOT_MINUTES, COLD_MINUTES)
        }
    }

    /** La sveglia: rinfresca e si riprogramma. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val app = context.applicationContext as? FluidTransitApp ?: return
            // goAsync: senza, il processo puo' morire prima che il widget
            // sia stato ridisegnato, e la sveglia sarebbe servita a niente.
            val pending = goAsync()
            app.applicationScope.launch {
                try {
                    runCatching { StopWidget().updateAll(app) }
                    runCatching { RoutineWidget().updateAll(app) }
                    runCatching { scheduleNext(app) }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
