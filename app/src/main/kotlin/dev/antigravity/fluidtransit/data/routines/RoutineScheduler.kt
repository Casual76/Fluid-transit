package dev.antigravity.fluidtransit.data.routines

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.data.bundle.BundleManager
import dev.antigravity.fluidtransit.routing.Ftb
import dev.antigravity.fluidtransit.routing.Raptor
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.glance.appwidget.updateAll

/**
 * Le sveglie delle routine, come da piano: `setExactAndAllowWhileIdle` (il
 * WorkManager ha un pavimento di 15 minuti, troppo grosso per "esci fra X"),
 * un giro di calcolo ~45 minuti prima dell'ancora e due rifiniture mentre
 * l'uscita si avvicina — coi ritardi live dentro a ogni giro.
 */
object RoutineScheduler {

    const val CHANNEL_ID = "routine"
    private const val ACTION = "dev.antigravity.fluidtransit.ROUTINE_ALARM"
    private const val COMPUTE_LEAD_MINUTES = 45L

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Routine: quando uscire",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Ti dice quando uscire per prendere il bus delle tue routine"
            },
        )
    }

    fun rescheduleAll(context: Context) {
        val store = Routines(context)
        for (r in store.list()) {
            if (r.enabled) scheduleNextCompute(context, r) else cancel(context, r.id)
        }
    }

    /** La prossima occorrenza: oggi se la finestra non e' passata, se no il prossimo giorno buono. */
    fun scheduleNextCompute(context: Context, r: Routines.Routine) {
        if (r.days.isEmpty()) return
        val zone = Ftb.ROME
        val now = ZonedDateTime.now(zone)
        for (offset in 0..7) {
            val day = now.toLocalDate().plusDays(offset.toLong())
            if (day.dayOfWeek.value !in r.days) continue
            val anchor = day.atStartOfDay(zone).plusMinutes(r.anchorMinutes.toLong())
            val computeAt = anchor.minusMinutes(COMPUTE_LEAD_MINUTES)
            val at = when {
                offset == 0 && now.isAfter(anchor) -> continue // oggi e' andata
                offset == 0 && now.isAfter(computeAt) -> now.plusSeconds(5) // siamo gia' in finestra
                else -> computeAt
            }
            setAlarm(context, r.id, "compute", at.toInstant().toEpochMilli())
            return
        }
    }

    fun cancel(context: Context, id: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pending(context, id, "compute"))
        am.cancel(pending(context, id, "refine"))
    }

    internal fun setAlarm(context: Context, id: Long, phase: String, atMillis: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        runCatching {
            am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, atMillis, pending(context, id, phase),
            )
        }.onFailure {
            // Senza il permesso delle sveglie esatte: meglio in ritardo che mai.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending(context, id, phase))
        }
    }

    private fun pending(context: Context, id: Long, phase: String): PendingIntent {
        val intent = Intent(context, RoutineReceiver::class.java)
            .setAction(ACTION)
            .putExtra("id", id)
            .putExtra("phase", phase)
        // requestCode distinto per (routine, fase): compute e refine convivono.
        val code = (id * 2 + if (phase == "compute") 0 else 1).toInt()
        return PendingIntent.getBroadcast(
            context, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Il giro di calcolo: bundle + ritardi live + RAPTOR, poi la notifica
     * "esci alle" e le rifiniture man mano che l'uscita si avvicina.
     */
    fun runComputation(context: Context, id: Long, phase: String, whenDone: () -> Unit) {
        val app = context.applicationContext as FluidTransitApp
        app.applicationScope.launch(Dispatchers.IO) {
            try {
                computeAndNotify(app, id, phase)
            } finally {
                whenDone()
            }
        }
    }

    private suspend fun computeAndNotify(app: FluidTransitApp, id: Long, phase: String) {
        val store = Routines(app)
        val r = store.list().firstOrNull { it.id == id } ?: return
        if (!r.enabled) return

        val ready = withTimeoutOrNull(30_000) {
            app.bundleManager.state.filterIsInstance<BundleManager.BundleState.Ready>().first()
        } ?: return
        val reader = ready.reader

        // I ritardi di ADESSO: un giro di realtime prima del calcolo.
        runCatching {
            app.realtime.refreshVehicles()
            app.realtime.refreshDelays()
        }
        val resolvedDelays = runCatching {
            val v = app.realtime.vehicles.value
            if (v != null) {
                dev.antigravity.fluidtransit.ui.map.resolveRt(reader, v, app.realtime.delays.value)
            } else {
                null
            }
        }.getOrNull()
        val live = if (resolvedDelays != null) {
            Raptor.Realtime(resolvedDelays.delayByTrip, resolvedDelays.canceledTrips)
        } else {
            Raptor.Realtime.NONE
        }

        val zone = Ftb.ROME
        val anchor = LocalDate.now(zone).atStartOfDay(zone)
            .plusMinutes(r.anchorMinutes.toLong()).toInstant()
        val from = Raptor.Place(r.fromLat, r.fromLon)
        val to = Raptor.Place(r.toLat, r.toLon)

        val journey = kotlinx.coroutines.withContext(app.routingDispatcher) {
            val raptor = app.raptorFor(reader)
            if (r.anchor == "depart") {
                raptor.plan(from, to, anchor, live).firstOrNull { !it.isWalkOnly }
            } else {
                raptor.planArriveBy(from, to, anchor, live)
                    .filter { !it.isWalkOnly }
                    .maxByOrNull { it.departure }
            }
        }

        val nm = app.getSystemService(NotificationManager::class.java)
        if (journey == null) {
            store.update(id) {
                Routines.Routine(
                    it.id, it.label, it.fromLat, it.fromLon, it.toLat, it.toLon, it.toName,
                    it.days, it.anchor, it.anchorMinutes, it.enabled,
                    lastAdviceEpoch = 0,
                    lastAdviceText = "Oggi nessun bus utile",
                )
            }
            scheduleNextCompute(app, r)
            return
        }

        val leave = journey.departure
        val firstRide = journey.legs.filterIsInstance<Raptor.Leg.Ride>().firstOrNull()
        val rideText = firstRide?.let {
            val line = reader.routeShortName(it.route).ifEmpty { reader.routeLongName(it.route) }
            "linea $line alle ${hm(it.departure)} da ${reader.stopName(it.boardStop)}"
        } ?: "a piedi"
        val minutes = (leave.epochSecond - Instant.now().epochSecond) / 60
        val advice = "Esci alle ${hm(leave)} — $rideText"

        store.update(id) {
            Routines.Routine(
                it.id, it.label, it.fromLat, it.fromLon, it.toLat, it.toLon, it.toName,
                it.days, it.anchor, it.anchorMinutes, it.enabled,
                lastAdviceEpoch = leave.epochSecond,
                lastAdviceText = advice,
            )
        }

        val title = when {
            minutes <= 1 -> "Esci ora — ${r.label.ifEmpty { r.toName }}"
            else -> "Esci tra $minutes min — ${r.label.ifEmpty { r.toName }}"
        }
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(title)
            .setContentText(advice)
            .setStyle(NotificationCompat.BigTextStyle().bigText(advice))
            .setAutoCancel(true)
            .setOnlyAlertOnce(phase == "refine")
            .build()
        runCatching { nm.notify(id.toInt(), notification) }

        // Le rifiniture: si ricalcola avvicinandosi all'uscita, coi ritardi
        // freschi. Dopo l'ultima, si arma il prossimo giorno buono.
        runCatching {
            dev.antigravity.fluidtransit.ui.widget.RoutineWidget().updateAll(app)
        }
        val nowMs = System.currentTimeMillis()
        val refineAt = listOf(
            leave.toEpochMilli() - 12 * 60_000,
            leave.toEpochMilli() - 2 * 60_000,
        ).firstOrNull { it > nowMs + 30_000 }
        if (refineAt != null) {
            setAlarm(app, id, "refine", refineAt)
        } else {
            scheduleNextCompute(app, r)
        }
    }

    private fun hm(i: Instant): String = ZonedDateTime.ofInstant(i, Ftb.ROME)
        .let { "%02d:%02d".format(it.hour, it.minute) }
}

/** La sveglia di una routine: calcola e notifica, poi riarma. */
class RoutineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        if (id < 0) return
        val phase = intent.getStringExtra("phase") ?: "compute"
        val pending = goAsync()
        RoutineScheduler.runComputation(context, id, phase) { pending.finish() }
    }
}

/** Al riavvio le sveglie non esistono piu': si riarmano tutte. */
class RoutineBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            RoutineScheduler.rescheduleAll(context)
        }
    }
}
