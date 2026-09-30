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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * "Ridisegnati": il segnale che una sessione Glance gia' aperta puo' ascoltare.
 *
 * In Glance 1.1 `provideGlance` gira una volta per sessione, e una sessione
 * resta viva una quarantina di secondi dopo il primo disegno. In quel tempo
 * `update()` e `updateAll()` non rilanciano `provideGlance`: rileggono solo lo
 * stato del widget, e un widget che ha letto tutto PRIMA di `provideContent`
 * (tavolozza, fermata, tabellone, routine) non si accorge di niente. Visto
 * leggendo il bytecode e ricostruendo le sequenze vere: apri l'app (il
 * disegno d'avvio parte dopo 4 secondi), vai su Oggi e cancelli la routine, e
 * la home continua a scrivere "Esci alle 07:25" per una routine che non
 * esiste piu' fino alla sveglia seguente.
 *
 * Chi vuole che i widget si rileggano incrementa questo contatore, e i
 * contenuti, che lo ascoltano dentro `provideContent`, ricaricano quello che
 * mostrano. La sessione gira nel processo dell'app, quindi un flusso in
 * memoria basta.
 */
object WidgetTick {
    private val _ticks = MutableStateFlow(0L)
    val ticks: StateFlow<Long> = _ticks

    fun bump() {
        _ticks.update { it + 1 }
    }
}

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

    /**
     * Fa rileggere i due widget, anche se hanno una sessione gia' aperta.
     *
     * Il contatore serve alle sessioni vive, l'`updateAll` a quelle che non ci
     * sono: tutti e due, perche' da fuori non si sa quale delle due cose sia
     * vera.
     */
    suspend fun updateAllWidgets(context: Context) {
        WidgetTick.bump()
        runCatching { StopWidget().updateAll(context) }
        runCatching { RoutineWidget().updateAll(context) }
    }

    suspend fun scheduleNext(context: Context) {
        val app = context.applicationContext as? FluidTransitApp ?: return
        val minutes = runCatching { nextWakeMinutes(app) }.getOrDefault(COLD_MINUTES)
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + minutes * 60_000
        alarms.set(AlarmManager.RTC, at, pending(context))
    }

    /**
     * Una sveglia fra poco, invece che fra minuti.
     *
     * Serve dopo la configurazione di un widget. Chi ha appena scelto la
     * fermata torna al lanciatore e il processo dell'app resta senza attivita'
     * ne' servizi: Android lo puo' chiudere subito, e una coroutine con un
     * ritardo dentro l'applicationScope non arriva mai a scattare. Una sveglia
     * invece sopravvive al processo, e la sua ricevente ha gia' il goAsync che
     * tiene in piedi il giro fino al disegno.
     *
     * Rimpiazza la sveglia programmata: la ricevente ne programma un'altra
     * appena finito, quindi non si perde niente.
     */
    fun refreshSoon(context: Context, afterMs: Long = SOON_MS) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.set(AlarmManager.RTC, System.currentTimeMillis() + afterMs, pending(context))
    }

    /** Abbastanza perche' il lanciatore abbia finito di agganciare il widget. */
    private const val SOON_MS = 2_000L

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
        var soonest: Long? = null
        for (id in ids) {
            val prefs = runCatching {
                getAppWidgetState(app, PreferencesGlanceStateDefinition, id)
            }.getOrNull() ?: continue
            val hashHex = prefs[KEY_STOP_HASH] ?: continue
            val hash = hashHex.toULongOrNull(16)?.toLong() ?: continue
            val stop = reader.findStopByIdHash(hash)
            if (stop < 0) continue
            // Il tabellone che il widget disegna, non una sua parte.
            //
            // Prima si chiedeva il prossimo passaggio alla SOLA banchina
            // stellata, mentre le righe del widget vengono da tutte le
            // banchine della fermata (`DepartureBoards.compute` le unisce):
            // con la banchina stellata a 55 minuti dal suo prossimo bus e
            // l'altro verso a 3, il widget dormiva 25 minuti con due righe
            // che dicevano "3 min" e "13 min". Chiedendo lo stesso scatto che
            // il disegno usera', la sveglia e le righe non possono litigare,
            // e i minuti contano col ritardo dal vivo gia' applicato.
            val prossima = runCatching {
                app.departureBoards
                    .snapshot(listOf(stop), limit = 3, horizonSeconds = 3 * 3600)
                    .rows
                    .firstOrNull { !it.canceled && !it.skipped }
            }.getOrNull() ?: continue
            val fra = prossima.effectiveEpoch - now.epochSecond
            soonest = minOf(soonest ?: fra, fra)
        }
        return wakeMinutes(soonest)
    }

    /**
     * Quanto dormire, dato quanto manca al prossimo passaggio (null: nessuno).
     *
     * Separata dal resto perche' e' la regola vera (cinque minuti se un bus
     * sta arrivando, altrimenti fino a mezz'ora prima) e deve potersi provare
     * senza un'app in piedi.
     */
    internal fun wakeMinutes(secondsToNext: Long?): Long {
        if (secondsToNext == null) return COLD_MINUTES
        val minutesToNext = secondsToNext / 60
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
                    runCatching { updateAllWidgets(app) }
                    runCatching { scheduleNext(app) }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
