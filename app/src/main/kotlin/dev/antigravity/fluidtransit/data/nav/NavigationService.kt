package dev.antigravity.fluidtransit.data.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.MainActivity
import dev.antigravity.fluidtransit.routing.Ftb
import java.time.Instant
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Il foreground service della navigazione (Fase 7).
 *
 * Il tipo di servizio segue il modo viaggio, come impone Android 14+:
 * `location` SOLO in Preciso, `dataSync` negli altri — dichiararli
 * entrambi nel manifest e sceglierne uno all'avvio e' il compromesso
 * previsto dal piano.
 *
 * Il progresso e' orari + ritardi live (il GPS raffina in Preciso, quando
 * si potra' provare sul device). Gli avvisi decisi: PENULTIMA fermata con
 * suono e vibrazione, richiamo alla discesa. A viaggio finito — scelta
 * esplicita dell'utente — NON si chiude: si entra in consumo minimo
 * (niente poll, niente GPS) e si resta finche' non tocchi Termina.
 */
class NavigationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var plan: NavPlan? = null
    private var alertedPenultimate = false
    private var alertedArrival = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as FluidTransitApp).navigation.publish(null)
            stopSelf()
            return START_NOT_STICKY
        }
        val app = application as FluidTransitApp
        val incoming = app.navigation.pendingPlan
        if (incoming == null && plan == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (incoming != null) {
            plan = incoming
            app.navigation.pendingPlan = null
            alertedPenultimate = false
            alertedArrival = false
        }

        ensureChannels()
        val mode = TravelModeStore(this).mode
        startInForeground(mode)
        loop(app, mode)
        return START_STICKY
    }

    private fun startInForeground(mode: TravelMode) {
        val type = if (mode.usesGps) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        startForeground(NOTIFICATION_ID, buildNotification(null), type)
    }

    private var loopStarted = false

    private fun loop(app: FluidTransitApp, mode: TravelMode) {
        if (loopStarted) return
        loopStarted = true
        scope.launch {
            while (true) {
                val p = plan ?: break
                // I ritardi freschi correggono i tempi a ogni giro.
                runCatching {
                    app.realtime.refreshVehicles()
                    app.realtime.refreshDelays()
                }
                val state = computeState(app, p)
                app.navigation.publish(state)
                notify(buildNotification(state))
                maybeAlert(state)
                if (state.phase == "arrived") {
                    // Consumo minimo: la sessione resta, il lavoro si ferma.
                    break
                }
                delay(mode.pollSeconds * 1000L)
            }
        }
    }

    private fun computeState(app: FluidTransitApp, p: NavPlan): NavState {
        val now = Instant.now().epochSecond
        val delays = runCatching {
            val ready = app.bundleManager.state.value
                as? dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState.Ready
            val v = app.realtime.vehicles.value
            if (ready != null && v != null) {
                dev.antigravity.fluidtransit.ui.map.resolveRt(
                    ready.reader, v, app.realtime.delays.value,
                ).delayByTrip
            } else {
                emptyMap()
            }
        }.getOrDefault(emptyMap())

        var totalStops = 0
        for (leg in p.legs) if (leg is NavLeg.Ride) totalStops += leg.alightPosition - leg.boardPosition

        for (leg in p.legs) {
            when (leg) {
                is NavLeg.Walk -> {
                    if (now < leg.startEpoch + leg.seconds) {
                        return NavState(
                            kind = p.kind,
                            destName = p.destName,
                            phase = "walk",
                            headline = "Cammina verso ${leg.toName}",
                            detail = "${((leg.startEpoch + leg.seconds - now) / 60 + 1)} min a piedi",
                            stopsRemaining = totalStops,
                            totalStops = totalStops,
                            etaEpoch = 0,
                        )
                    }
                }

                is NavLeg.Ride -> {
                    val app2 = application as FluidTransitApp
                    val ready = app2.bundleManager.state.value
                        as? dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState.Ready
                    val reader = ready?.reader ?: continue
                    val delay = delays[leg.trip] ?: 0
                    val boardTime = leg.dayStartEpoch + leg.dep0 +
                        reader.profileOffset(leg.profile, leg.boardPosition) + delay
                    val alightTime = leg.dayStartEpoch + leg.dep0 +
                        reader.profileOffset(leg.profile, leg.alightPosition) + delay
                    if (now < boardTime) {
                        return NavState(
                            kind = p.kind,
                            destName = p.destName,
                            phase = "wait",
                            headline = "Aspetta la ${leg.lineName}",
                            detail = "parte tra ${((boardTime - now) / 60 + 1)} min" +
                                if (delay != 0) " · ritardo live" else "",
                            stopsRemaining = totalStops,
                            totalStops = totalStops,
                            etaEpoch = alightTime,
                        )
                    }
                    if (now < alightTime) {
                        // A bordo: la prossima fermata e' la prima col tempo davanti.
                        var nextPos = leg.alightPosition
                        for (pos in leg.boardPosition + 1..leg.alightPosition) {
                            val t = leg.dayStartEpoch + leg.dep0 +
                                reader.profileOffset(leg.profile, pos) + delay
                            if (t > now) {
                                nextPos = pos
                                break
                            }
                        }
                        val remaining = leg.alightPosition - nextPos + 1
                        return NavState(
                            kind = p.kind,
                            destName = p.destName,
                            phase = "ride",
                            headline = "Scendi a ${leg.alightName}",
                            detail = if (remaining == 1) {
                                "alla PROSSIMA fermata"
                            } else {
                                "$remaining fermate · ${((alightTime - now) / 60 + 1)} min"
                            },
                            stopsRemaining = remaining,
                            totalStops = totalStops,
                            etaEpoch = alightTime,
                        )
                    }
                }
            }
        }
        return NavState(
            kind = p.kind,
            destName = p.destName,
            phase = "arrived",
            headline = "Arrivato",
            detail = p.destName,
            stopsRemaining = 0,
            totalStops = totalStops,
            etaEpoch = now,
        )
    }

    private fun maybeAlert(s: NavState) {
        val nm = getSystemService(NotificationManager::class.java)
        if (s.phase == "ride" && s.stopsRemaining == 1 && !alertedPenultimate) {
            alertedPenultimate = true
            nm.notify(
                ALERT_ID,
                NotificationCompat.Builder(this, ALERT_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_map)
                    .setContentTitle("Scendi alla prossima")
                    .setContentText(s.headline)
                    .setAutoCancel(true)
                    .build(),
            )
        }
        if (s.phase == "arrived" && !alertedArrival) {
            alertedArrival = true
            nm.notify(
                ALERT_ID,
                NotificationCompat.Builder(this, ALERT_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_map)
                    .setContentTitle("Scendi qui")
                    .setContentText("Sei a ${s.destName}")
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    private fun buildNotification(s: NavState?): Notification {
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(s?.headline ?: "Navigazione")
            .setContentText(
                s?.let {
                    "${it.detail}" + if (it.etaEpoch > 0 && it.phase != "arrived") {
                        " · arrivo ${hm(it.etaEpoch)}"
                    } else {
                        ""
                    }
                } ?: "Preparo il viaggio…",
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Termina", stop)
        if (s != null && s.totalStops > 0 && s.phase == "ride") {
            builder.setProgress(s.totalStops, s.totalStops - s.stopsRemaining, false)
        }
        // La Live Update di Android 16, quando c'e': la barra a segmenti con
        // il punto che avanza. Sotto, resta la ongoing qui sopra.
        if (Build.VERSION.SDK_INT >= 36 && s != null && s.totalStops > 0) {
            runCatching {
                val style = Notification.ProgressStyle()
                    .setProgress(((s.totalStops - s.stopsRemaining) * 100) / s.totalStops)
                val native = Notification.Builder.recoverBuilder(this, builder.build())
                native.setStyle(style)
                return native.build()
            }
        }
        return builder.build()
    }

    private fun notify(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    private fun hm(epoch: Long): String = ZonedDateTime
        .ofInstant(Instant.ofEpochSecond(epoch), Ftb.ROME)
        .let { "%02d:%02d".format(it.hour, it.minute) }

    private fun ensureChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Navigazione", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL, "Scendi qui", NotificationManager.IMPORTANCE_HIGH,
            ).apply { enableVibration(true) },
        )
    }

    override fun onDestroy() {
        (application as FluidTransitApp).navigation.publish(null)
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // L'app scacciata dalle recenti non ferma il viaggio: e' il punto
        // del foreground service.
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        const val CHANNEL = "navigation"
        const val ALERT_CHANNEL = "nav-alert"
        const val NOTIFICATION_ID = 100
        const val ALERT_ID = 101
        const val ACTION_STOP = "dev.antigravity.fluidtransit.NAV_STOP"
    }
}
