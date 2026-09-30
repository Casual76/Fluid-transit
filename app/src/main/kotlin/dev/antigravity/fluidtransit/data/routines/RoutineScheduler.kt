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

    /**
     * La prossima sveglia, decisa da [RoutineTiming.next].
     *
     * @param doneDay il giorno appena chiuso, che non si riprende.
     */
    fun scheduleNextCompute(context: Context, r: Routines.Routine, doneDay: LocalDate? = null) {
        val alarm = RoutineTiming.next(r, Instant.now().epochSecond, doneDay) ?: return
        setAlarm(context, r.id, alarm.phase, alarm.atEpoch * 1000, alarm.day)
    }

    /**
     * Un giro che non e' riuscito si riprova fra un minuto, finche' l'ora
     * della routine non e' passata. Prima si richiamava la pianificazione,
     * che dentro la finestra rispondeva "fra cinque secondi": con gli orari
     * non ancora pronti, o un'eccezione che si ripeteva, erano cinque
     * secondi di attesa e un giro, all'infinito.
     */
    private fun retryLater(context: Context, r: Routines.Routine, phase: String, day: LocalDate) {
        val anchor = Routines.anchorEpoch(day, r.anchorMinutes)
        val now = Instant.now().epochSecond
        if (now + RETRY_SECONDS < anchor) {
            setAlarm(context, r.id, phase, (now + RETRY_SECONDS) * 1000, day)
        } else {
            scheduleNextCompute(context, r, doneDay = day)
        }
    }

    private const val RETRY_SECONDS = 60L

    fun cancel(context: Context, id: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pending(context, id, RoutineTiming.COMPUTE))
        am.cancel(pending(context, id, RoutineTiming.REFINE))
    }

    internal fun setAlarm(context: Context, id: Long, phase: String, atMillis: Long, day: LocalDate) {
        val am = context.getSystemService(AlarmManager::class.java)
        runCatching {
            am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, atMillis, pending(context, id, phase, day),
            )
        }.onFailure {
            // Senza il permesso delle sveglie esatte: meglio in ritardo che mai.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending(context, id, phase, day))
        }
    }

    private fun pending(context: Context, id: Long, phase: String, day: LocalDate? = null): PendingIntent {
        val intent = Intent(context, RoutineReceiver::class.java)
            .setAction(ACTION)
            .putExtra("id", id)
            .putExtra("phase", phase)
        // Il giorno della routine viaggia con la sveglia: quella delle 23:35
        // e' della routine di domani alle 00:20, e il giro deve saperlo.
        if (day != null) intent.putExtra("day", day.toEpochDay())
        // requestCode per (routine, posto): la pianificazione e l'avviso
        // stanno nello stesso posto — la prima arma il secondo — e le
        // rifiniture nel loro. Gli stessi numeri di prima, cosi' le sveglie
        // gia' armate da una versione vecchia si possono ancora cancellare.
        val code = (id * 2 + if (phase == RoutineTiming.REFINE) 1 else 0).toInt()
        return PendingIntent.getBroadcast(
            context, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Il giro di calcolo: bundle + ritardi live + RAPTOR, poi la notifica
     * "esci alle" e le rifiniture man mano che l'uscita si avvicina.
     */
    fun runComputation(context: Context, id: Long, phase: String, day: LocalDate, whenDone: () -> Unit) {
        val app = context.applicationContext as FluidTransitApp
        app.applicationScope.launch(Dispatchers.IO) {
            try {
                computeAndNotify(app, id, phase, day)
            } catch (e: Exception) {
                // C'era solo try/finally. Un'eccezione qui dentro — il
                // lettore del bundle chiuso sotto i piedi dallo scambio
                // notturno, la rete che salta — non fermava la routine:
                // saliva su uno scope senza gestore e portava giu' il
                // processo. E la sveglia successiva non veniva armata da
                // nessuno, quindi la routine moriva li'.
                runCatching {
                    Routines(app).list().firstOrNull { it.id == id && it.enabled }
                        ?.let { retryLater(app, it, phase, day) }
                }
            } finally {
                whenDone()
            }
        }
    }

    private suspend fun computeAndNotify(app: FluidTransitApp, id: Long, phase: String, day: LocalDate) {
        val store = Routines(app)
        val r = store.list().firstOrNull { it.id == id } ?: return
        if (!r.enabled) return

        // L'attesa e' corta di proposito: questo giro nasce da un
        // BroadcastReceiver, che il sistema non lascia lavorare a lungo.
        // Trenta secondi erano una richiesta di essere uccisi.
        val ready = withTimeoutOrNull(BUNDLE_WAIT_MS) {
            app.bundleManager.state.filterIsInstance<BundleManager.BundleState.Ready>().first()
        } ?: run {
            // E soprattutto: si RIARMA. Prima si usciva e basta, e quella
            // routine non si sarebbe piu' fatta viva — nessun errore, nessun
            // avviso, semplicemente non suonava mai piu'.
            retryLater(app, r, phase, day)
            return
        }
        val reader = ready.reader

        // I ritardi di ADESSO: un giro di realtime prima del calcolo.
        runCatching {
            app.realtime.refreshVehicles()
            app.realtime.refreshDelays()
            app.realtime.refreshPredictions()
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
            Raptor.Realtime(
                resolvedDelays.delayByTrip,
                resolvedDelays.canceledTrips,
                java.time.Instant.now().epochSecond,
                // Le previsioni fermata per fermata, come il tabellone, il
                // pianificatore e l'assistente. Senza, "Esci alle" si
                // calcolava su un ritardo solo per tutta la corsa, e poteva
                // dire un minuto diverso dalla scheda dello stesso bus.
                live = app.departureBoards.live(),
            )
        } else {
            Raptor.Realtime.NONE
        }

        val anchor = java.time.Instant.ofEpochSecond(Routines.anchorEpoch(day, r.anchorMinutes))
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
            scheduleNextCompute(app, r, doneDay = day)
            return
        }

        val leave = journey.departure
        val firstRide = journey.legs.filterIsInstance<Raptor.Leg.Ride>().firstOrNull()
        val rideText = firstRide?.let {
            val line = reader.routeShortName(it.route).ifEmpty { reader.routeLongName(it.route) }
            "linea $line alle ${hm(it.departure)} da ${reader.stopName(it.boardStop)}"
        } ?: "a piedi"
        // Arrotondato come ovunque nell'app, non troncato: la stessa uscita
        // diceva "tra 12 min" qui e "13 min" nella scheda del viaggio,
        // perche' questa riga buttava via i secondi invece di arrotondarli.
        val secondsToLeave = (leave.epochSecond - Instant.now().epochSecond).toInt()
        val minutes = dev.antigravity.fluidtransit.routing.Times.toMinutes(secondsToLeave)
        val advice = "Esci alle ${hm(leave)} — $rideText"

        store.update(id) {
            Routines.Routine(
                it.id, it.label, it.fromLat, it.fromLon, it.toLat, it.toLon, it.toName,
                it.days, it.anchor, it.anchorMinutes, it.enabled,
                lastAdviceEpoch = leave.epochSecond,
                lastAdviceText = advice,
            )
        }

        // La pianificazione di un "arriva entro": l'ora di uscita adesso si
        // sa, e l'avviso si arma tre quarti d'ora prima. Niente notifica: e'
        // presto, e il consiglio lo mostrano gia' Oggi e il widget.
        if (phase == RoutineTiming.PLAN) {
            runCatching { dev.antigravity.fluidtransit.ui.widget.RoutineWidget().updateAll(app) }
            val alertAt = maxOf(
                leave.epochSecond - RoutineTiming.COMPUTE_LEAD_SECONDS,
                Instant.now().epochSecond + RoutineTiming.NOW_DELAY_SECONDS,
            )
            setAlarm(app, id, RoutineTiming.COMPUTE, alertAt * 1000, day)
            return
        }

        val title = when {
            minutes <= 1 -> "Esci ora — ${r.label.ifEmpty { r.toName }}"
            // Con le parole delle durate del resto dell'app: il primo giro
            // parte quarantacinque minuti prima, e piu' in la' di un'ora
            // "Esci tra 72 min" e' un numero da dividere.
            else -> "Esci tra " +
                dev.antigravity.fluidtransit.routing.Times.durationLabel(secondsToLeave) +
                " — ${r.label.ifEmpty { r.toName }}"
        }
        // Toccarla apre *quel* viaggio. Fino a ieri questa notifica non
        // aveva contentIntent: toccarla non faceva assolutamente niente, e
        // quel nulla e' peggio di un errore — sembra che l'app si sia rotta.
        val open = PendingIntent.getActivity(
            app, id.toInt(),
            Intent(app, dev.antigravity.fluidtransit.MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(
                    android.net.Uri.parse(
                        dev.antigravity.fluidtransit.ui.nav.Deeplink.journey(id),
                    ),
                ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(title)
            .setContentText(advice)
            .setStyle(NotificationCompat.BigTextStyle().bigText(advice))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(phase == RoutineTiming.REFINE)
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
            setAlarm(app, id, RoutineTiming.REFINE, refineAt, day)
        } else {
            // Oggi e' fatta: il prossimo giorno buono, non "fra cinque
            // secondi" perche' l'ora della routine non e' ancora passata.
            scheduleNextCompute(app, r, doneDay = day)
        }
    }

    /** Quanto si aspetta il bundle dentro il giro di una sveglia. */
    private const val BUNDLE_WAIT_MS = 8_000L

    /** L'orologio e' quello di tutta l'app: qui era riscritto in casa. */
    private fun hm(i: Instant): String =
        dev.antigravity.fluidtransit.routing.Times.hhmm(i.epochSecond)
}

/** La sveglia di una routine: calcola e notifica, poi riarma. */
class RoutineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        if (id < 0) return
        val phase = intent.getStringExtra("phase") ?: RoutineTiming.COMPUTE
        // Le sveglie armate da una versione vecchia non hanno il giorno:
        // per quelle vale oggi, com'era.
        val day = if (intent.hasExtra("day")) {
            LocalDate.ofEpochDay(intent.getLongExtra("day", 0))
        } else {
            LocalDate.now(Ftb.ROME)
        }
        val pending = goAsync()
        RoutineScheduler.runComputation(context, id, phase, day) { pending.finish() }
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
