package dev.antigravity.fluidtransit.data.routines

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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

    /**
     * L'archivio di TUTTA l'app, non una copia: `version` e' un flusso per
     * istanza, e lo scheduler scriveva su un `Routines(app)` suo. La scheda
     * Oggi ascolta quello dell'app, quindi il consiglio calcolato dalla
     * sveglia delle 06:45 finiva sul file ma la riga in Oggi restava com'era
     * (e il widget si ridisegnava solo perche' lo si chiamava a mano).
     * Condiviso, ogni scrittura fa scattare Oggi e il widget.
     */
    private fun storeOf(context: Context): Routines =
        (context.applicationContext as? FluidTransitApp)?.routines ?: Routines(context)

    /**
     * Riarma TUTTO, senza chiedersi se c'e' gia': per il riavvio del
     * telefono, quando di sveglie non ne esiste piu' nessuna.
     */
    fun rescheduleAll(context: Context) {
        for (r in storeOf(context).list()) {
            // Col riavvio la tendina e' vuota: il "gia' avvisato" ricordato
            // sarebbe falso, e la prima rifinitura non ripubblicherebbe.
            runCatching { alarmPrefs(context).edit().remove(notifiedKey(r.id)).apply() }
            if (r.enabled) scheduleNextCompute(context, r) else cancel(context, r.id)
        }
    }

    /**
     * Riarma solo cio' che serve: per l'avvio del processo.
     *
     * Prima `onCreate` riarmava tutto senza guardare, e quando era proprio la
     * sveglia a far nascere il processo — alle 07:15, "parti alle 08:00" —
     * `RoutineTiming.next` vedeva "dentro la finestra, niente consiglio
     * ancora" e ne armava un'altra a cinque secondi. Il giro vero era ancora
     * a meta' fra bundle, rete e RAPTOR: la seconda partenza rifaceva tutto e
     * "Esci tra 45 min" suonava due volte. Per un "arriva entro" la stessa
     * sveglia in piu' era una rifinitura, che ripubblica la notifica anche
     * dopo che l'avevi tolta. Vedi [RoutineTiming.atStart].
     */
    fun rescheduleMissing(context: Context) {
        val now = Instant.now().epochSecond
        for (r in storeOf(context).list()) {
            if (!r.enabled) {
                cancel(context, r.id)
                continue
            }
            val armed = armedOf(context, r.id)
            when (RoutineTiming.atStart(armed, now)) {
                RoutineTiming.StartAction.LEAVE -> Unit
                RoutineTiming.StartAction.REARM_NEXT -> scheduleNextCompute(context, r)
                RoutineTiming.StartAction.REARM_SAME ->
                    setAlarm(
                        context, r.id, armed!!.phase,
                        RoutineTiming.rearmAt(armed, now) * 1000, armed.day!!,
                    )
            }
        }
    }

    /**
     * Il ricevitore segna lo scatto PRIMA di avviare il giro: da qui si
     * misura se un giro e' "ancora in corso". L'ora programmata non basta,
     * perche' una sveglia in ritardo (Doze, inesatta su Android 12 senza
     * permesso) nasce quando quell'ora e' gia' lontana.
     */
    internal fun markFired(context: Context, id: Long, phase: String, day: LocalDate) {
        val now = Instant.now().epochSecond
        runCatching {
            val at = armedOf(context, id)?.takeIf { it.phase == phase }?.atEpoch ?: now
            alarmPrefs(context).edit()
                .putString(id.toString(), RoutineTiming.Armed(phase, at, day, now).format())
                .apply()
        }
    }

    private fun notifiedKey(id: Long) = "notified:$id"

    /** L'avviso di questa occorrenza e' stato pubblicato (e da allora non riavviato il telefono)? */
    private fun wasNotified(context: Context, id: Long, day: LocalDate): Boolean =
        runCatching { alarmPrefs(context).getLong(notifiedKey(id), Long.MIN_VALUE) == day.toEpochDay() }
            .getOrDefault(false)

    private fun markNotified(context: Context, id: Long, day: LocalDate) {
        runCatching { alarmPrefs(context).edit().putLong(notifiedKey(id), day.toEpochDay()).apply() }
    }

    /**
     * Pausa e riattivazione, per Oggi e per l'assistente. Stava in Oggi, e
     * l'assistente cambiava solo il flag: l'avviso restava in tendina, la
     * sveglia gia' armata suonava a vuoto senza riarmarsi, e "riattivala" non
     * armava niente.
     */
    fun setEnabled(context: Context, id: Long, enabled: Boolean) {
        val store = storeOf(context)
        store.update(id) { it.withEnabled(enabled) }
        val updated = store.list().firstOrNull { it.id == id } ?: return
        if (updated.enabled) scheduleNextCompute(context, updated) else cancel(context, id)
    }

    private fun alarmPrefs(context: Context) =
        context.getSharedPreferences("routine_alarms", Context.MODE_PRIVATE)

    private fun armedOf(context: Context, id: Long): RoutineTiming.Armed? =
        runCatching { RoutineTiming.Armed.parse(alarmPrefs(context).getString(id.toString(), null)) }
            .getOrNull()

    /**
     * La prossima sveglia, decisa da [RoutineTiming.next].
     *
     * @param doneDay il giorno appena chiuso, che non si riprende.
     */
    fun scheduleNextCompute(context: Context, r: Routines.Routine, doneDay: LocalDate? = null) {
        val alarm = RoutineTiming.next(r, Instant.now().epochSecond, doneDay)
        if (alarm == null) {
            runCatching { alarmPrefs(context).edit().remove(r.id.toString()).apply() }
            return
        }
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
        runCatching {
            alarmPrefs(context).edit().remove(id.toString()).remove(notifiedKey(id)).apply()
        }
        // Anche l'avviso gia' in tendina: una routine messa in pausa o
        // eliminata lasciava "Esci tra 25 min" sullo schermo, e toccarlo
        // (per una routine cancellata) non faceva niente.
        runCatching {
            context.getSystemService(NotificationManager::class.java).cancel(id.toInt())
        }
    }

    internal fun setAlarm(context: Context, id: Long, phase: String, atMillis: Long, day: LocalDate) {
        val am = context.getSystemService(AlarmManager::class.java)
        // Ricordare cosa si e' armato e per quando: senza, all'avvio del
        // processo non si distingue "sveglia che sta per suonare" da "sveglia
        // appena suonata che ha fatto nascere il processo".
        runCatching {
            alarmPrefs(context).edit()
                .putString(id.toString(), RoutineTiming.Armed(phase, atMillis / 1000, day).format())
                .apply()
        }
        // Da Android 12 la sveglia esatta e' un permesso che puo' mancare:
        // si chiede PRIMA, invece di aspettare la SecurityException. Il
        // manifest dichiara SCHEDULE_EXACT_ALARM fino ad Android 12L (che li'
        // e' concesso di base) e USE_EXACT_ALARM dal 13.
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        val exact = canExact && runCatching {
            am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, atMillis, pending(context, id, phase, day),
            )
        }.isSuccess
        if (!exact) {
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
                    storeOf(app).list().firstOrNull { it.id == id && it.enabled }
                        ?.let { retryLater(app, it, phase, day) }
                }
            } finally {
                whenDone()
            }
        }
    }

    private suspend fun computeAndNotify(app: FluidTransitApp, id: Long, phase: String, day: LocalDate) {
        val store = storeOf(app)
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
                // Il numero unico per corsa solo se e' ancora buono: vedi delaysFresh.
                if (app.realtime.delaysFresh()) resolvedDelays.delayByTrip else emptyMap(),
                if (app.realtime.delaysFresh()) resolvedDelays.canceledTrips else emptySet(),
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
                // Mai dal passato: una sveglia in ritardo (Android 12 senza
                // sveglie esatte, il telefono in Doze) pianificava dall'ora
                // della routine e riproponeva il bus gia' partito, con
                // "Esci ora" a bus andato. L'arrivo ha gia' il suo
                // `notBefore`.
                raptor.plan(from, to, maxOf(anchor, Instant.now()), live).firstOrNull { !it.isWalkOnly }
            } else {
                raptor.planArriveBy(from, to, anchor, live, notBefore = Instant.now())
                    .filter { !it.isWalkOnly }
                    .maxByOrNull { it.departure }
            }
        }

        val nm = app.getSystemService(NotificationManager::class.java)
        if (journey == null) {
            // Il consiglio che c'era, se c'era: lo si legge PRIMA di
            // azzerarlo. Appartiene a questa occorrenza e non e' gia' quello
            // di ieri (l'uscita cade nelle ore attorno all'ancora).
            val anchorEpoch = Routines.anchorEpoch(day, r.anchorMinutes)
            val consigliatoPrima = r.lastAdviceEpoch.takeIf {
                RoutineTiming.adviceBelongsTo(it, anchorEpoch)
            }
            store.update(id) {
                Routines.Routine(
                    it.id, it.label, it.fromLat, it.fromLon, it.toLat, it.toLon, it.toName,
                    it.days, it.anchor, it.anchorMinutes, it.enabled,
                    lastAdviceEpoch = 0,
                    lastAdviceText = RoutineText.NESSUN_BUS,
                    lastComputeEpoch = Instant.now().epochSecond,
                )
            }
            // Il widget si ridisegna anche qui: in mattinata di sciopero
            // diceva "Il consiglio arriva da solo" a chi il consiglio l'aveva
            // gia' avuto, e cioe' che non c'e'. (Ora scatta anche dal
            // `version` condiviso, ma non c'e' motivo di dipenderne.)
            runCatching { dev.antigravity.fluidtransit.ui.widget.RoutineWidget().updateAll(app) }
            // Il bus di cui la notifica parlava non c'e' piu': lo si DICE.
            // Prima il giro finiva in silenzio e l'"Esci tra 25 min" restava
            // in tendina per un bus cancellato. Dalla PLAN niente: non
            // aveva notificato niente, e non c'e' nulla da ritrattare.
            if (consigliatoPrima != null && phase != RoutineTiming.PLAN) {
                val title = RoutineText.busGoneTitle(r.label.ifEmpty { r.toName })
                val text = RoutineText.busGone(consigliatoPrima)
                val n = NotificationCompat.Builder(app, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_directions)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(openIntent(app, id, day))
                    .setAutoCancel(true)
                    .build()
                runCatching { nm.notify(id.toInt(), n) }
            }
            scheduleNextCompute(app, r, doneDay = day)
            return
        }

        val leave = journey.departure
        val firstRide = journey.legs.filterIsInstance<Raptor.Leg.Ride>().firstOrNull()
        // La frase la costruisce RoutineText.advice, la stessa funzione che il
        // widget usa (con parseAdvice) per rileggerla: scritta qui a mano
        // divergeva in silenzio dal suo lettore.
        val line = firstRide?.let {
            reader.routeShortName(it.route).ifEmpty { reader.routeLongName(it.route) }
        }
        // Arrotondato come ovunque nell'app, non troncato: la stessa uscita
        // diceva "tra 12 min" qui e "13 min" nella scheda del viaggio,
        // perche' questa riga buttava via i secondi invece di arrotondarli.
        val secondsToLeave = (leave.epochSecond - Instant.now().epochSecond).toInt()
        val minutes = dev.antigravity.fluidtransit.routing.Times.toMinutes(secondsToLeave)
        val advice = RoutineText.advice(
            leaveHm = hm(leave),
            line = line,
            busHm = firstRide?.let { hm(it.departure) },
            boardStop = firstRide?.let { reader.stopName(it.boardStop) },
        )

        store.update(id) {
            Routines.Routine(
                it.id, it.label, it.fromLat, it.fromLon, it.toLat, it.toLon, it.toName,
                it.days, it.anchor, it.anchorMinutes, it.enabled,
                lastAdviceEpoch = leave.epochSecond,
                lastAdviceText = advice,
                lastComputeEpoch = Instant.now().epochSecond,
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
        val open = openIntent(app, id, day)
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(title)
            .setContentText(advice)
            .setStyle(NotificationCompat.BigTextStyle().bigText(advice))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(phase == RoutineTiming.REFINE)
            .build()
        // Una rifinitura aggiorna l'avviso che c'e', non lo resuscita: chi
        // l'ha tolto dalla tendina ha detto che non lo vuole, e ripubblicarlo
        // "solo per aggiornarlo" lo faceva risuonare (`onlyAlertOnce` non vale
        // per una notifica che non esiste piu').
        val ancoraInTendina = runCatching {
            nm.activeNotifications.any { it.id == id.toInt() }
        }.getOrDefault(true)
        // ...ma solo se l'ha tolto qualcuno. Se la tendina e' vuota perche' il
        // telefono si e' riavviato, o il processo e' morto fra il salvataggio
        // e la notifica, l'avviso di questa occorrenza non e' mai stato
        // pubblicato e la rifinitura e' l'unica che puo' farlo.
        if (phase != RoutineTiming.REFINE || ancoraInTendina || !wasNotified(app, id, day)) {
            runCatching { nm.notify(id.toInt(), notification) }
                .onSuccess { markNotified(app, id, day) }
        }

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

    /**
     * Toccarla apre *quel* viaggio. Fino a ieri questa notifica non aveva
     * contentIntent: toccarla non faceva assolutamente niente, e quel nulla
     * e' peggio di un errore — sembra che l'app si sia rotta.
     *
     * Il giorno viaggia nell'indirizzo: la rifinitura a "esci fra 2 min" di
     * una "parti alle 07:25" puo' cadere alle 07:27, dopo l'ancora, e senza
     * il giorno l'app cercava "la prossima occorrenza" e apriva i viaggi di
     * domani invece del bus della notifica.
     */
    private fun openIntent(app: FluidTransitApp, id: Long, day: LocalDate): PendingIntent =
        PendingIntent.getActivity(
            app, id.toInt(),
            Intent(app, dev.antigravity.fluidtransit.MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(
                    android.net.Uri.parse(
                        dev.antigravity.fluidtransit.ui.nav.Deeplink.journey(id, day.toEpochDay()),
                    ),
                ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

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
        RoutineScheduler.markFired(context, id, phase, day)
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
