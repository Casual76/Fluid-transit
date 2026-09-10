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
        startLocation(mode)
        loop(app, mode)
        return START_STICKY
    }

    private fun startInForeground(mode: TravelMode) {
        // Il tipo segue il PERMESSO EFFETTIVO, non il modo scelto.
        //
        // Da Android 14 dichiarare un foreground service di tipo `location`
        // senza avere il permesso di posizione e' una SecurityException:
        // bastava scegliere il modo Preciso in Impostazioni e negare il
        // permesso perche' l'app morisse all'avvio del viaggio. E il tipo
        // `location` era comunque una dichiarazione a vuoto, visto che il
        // servizio la posizione non la legge.
        val granted = android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasLocation =
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == granted ||
                checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == granted
        val type = if (mode.usesGps && hasLocation) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        runCatching {
            startForeground(NOTIFICATION_ID, buildNotification(null), type)
        }.onFailure {
            // Ultima rete: senza foreground il sistema ferma il servizio, ma
            // portarsi dietro l'app in un crash sarebbe peggio.
            runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(null),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            }
        }
    }

    /**
     * Il giro in corso. Un Job, non un flag.
     *
     * Il ciclo esce da se' quando il viaggio finisce, e il servizio resta
     * vivo per scelta esplicita: con un flag che nessuno riazzerava, il
     * SECONDO viaggio della sessione non ripartiva piu'. `onStartCommand`
     * arrivava sulla stessa istanza, trovava il flag alzato, non rilanciava
     * niente — e la notifica restava ferma sull'ultima frase del viaggio
     * precedente, senza che niente lo facesse capire.
     */
    private var loopJob: kotlinx.coroutines.Job? = null

    // --- dove sei ---------------------------------------------------------
    //
    // Il modo Preciso prometteva il GPS dal giorno in cui e' nato — il tipo
    // di foreground service lo dichiarava perfino al sistema — ma nessuna
    // riga leggeva la posizione: la navigazione decideva ogni cosa
    // dall'orologio. "Cammina verso TORRE GALLI" senza mai dire quanto
    // manca, e la discesa annunciata su una tabella oraria invece che su
    // dove sei davvero.
    //
    // Qui si usa LocationManager e non FusedLocationProvider: quest'ultimo
    // arriva con Play Services, che l'app non ha, e per "quanti metri mancano
    // a quella fermata" il provider di sistema basta e avanza.

    @Volatile
    private var here: android.location.Location? = null

    private var locationListener: android.location.LocationListener? = null

    private fun startLocation(mode: TravelMode) {
        if (!mode.usesGps || locationListener != null) return
        val granted = android.content.pm.PackageManager.PERMISSION_GRANTED
        val fine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == granted
        val coarse =
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == granted
        if (!fine && !coarse) return
        val lm = getSystemService(android.location.LocationManager::class.java) ?: return
        val provider = when {
            fine && runCatching {
                lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
            }.getOrDefault(false) -> android.location.LocationManager.GPS_PROVIDER

            runCatching {
                lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false) -> android.location.LocationManager.NETWORK_PROVIDER

            else -> return
        }
        val listener = android.location.LocationListener { loc -> here = loc }
        runCatching {
            lm.requestLocationUpdates(
                provider,
                LOCATION_INTERVAL_MS,
                LOCATION_METERS,
                listener,
                android.os.Looper.getMainLooper(),
            )
            // Il primo dato senza aspettare il primo fix: se c'e' qualcosa di
            // recente in cassa, vale gia'.
            here = lm.getLastKnownLocation(provider)
            locationListener = listener
        }
    }

    private fun stopLocation() {
        val l = locationListener ?: return
        locationListener = null
        here = null
        runCatching {
            getSystemService(android.location.LocationManager::class.java)?.removeUpdates(l)
        }
    }

    /**
     * Metri fra dove siamo e un punto, -1 se non si sa.
     *
     * Un fix vecchio non dice piu' dove sei: meglio non dire niente che dire
     * una distanza presa da dove eri dieci minuti fa.
     */
    private fun metersTo(lat: Double, lon: Double): Int {
        if (lat == 0.0 && lon == 0.0) return -1
        val p = here ?: return -1
        val ageNanos = android.os.SystemClock.elapsedRealtimeNanos() - p.elapsedRealtimeNanos
        if (ageNanos > FIX_MAX_AGE_NANOS) return -1
        return dev.antigravity.fluidtransit.routing.BundleReader
            .haversine(p.latitude, p.longitude, lat, lon)
            .toInt()
    }

    private fun loop(app: FluidTransitApp, mode: TravelMode) {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (true) {
                val p = plan ?: break
                // Tutto il corpo sotto rete. Lo scope non ha un gestore di
                // eccezioni, quindi una qualunque eccezione qui dentro non
                // fermava il viaggio: portava giu' il processo.
                val state = runCatching {
                    // I ritardi freschi correggono i tempi a ogni giro.
                    runCatching {
                        app.realtime.refreshVehicles()
                        app.realtime.refreshDelays()
                    }
                    computeState(app, p)
                }.getOrNull()
                if (state != null) {
                    lastState = state
                    app.navigation.publish(state)
                    notify(buildNotification(state))
                    maybeAlert(state)
                    if (state.phase == "arrived") {
                        // Consumo minimo: la sessione resta, il lavoro si ferma.
                        break
                    }
                }
                delay(mode.pollSeconds * 1000L)
            }
        }
    }

    /** L'ultimo stato buono: si ripropone quando per un giro manca il bundle. */
    private var lastState: NavState? = null

    private fun computeState(app: FluidTransitApp, p: NavPlan): NavState {
        val now = Instant.now().epochSecond
        // I ritardi arrivano dal modello dell'applicazione, gia' alimentato
        // una volta sola da FluidTransitApp.
        //
        // Prima qui si ricostruiva l'INTERO snapshot risolto — ottocento
        // BusRender e tre mappe, ogni quindici secondi — per estrarne due
        // campi. E, cosa peggiore, si usava un ritardo piatto: la navigazione
        // annunciava un orario e la scheda della stessa corsa ne mostrava un
        // altro, perche' i pannelli passano da DelayModel e sanno che il
        // ritardo si consuma strada facendo.
        val canceled = app.canceledTrips.value

        // Tutte le tratte, non solo quella in corso: con un cambio, la barra
        // ripartiva da capo a meta' viaggio o restava indietro per sempre.
        var totalStops = 0
        for (leg in p.legs) if (leg is NavLeg.Ride) totalStops += leg.alightPosition - leg.boardPosition

        for (leg in p.legs) {
            when (leg) {
                is NavLeg.Walk -> {
                    if (now < leg.startEpoch + leg.seconds) {
                        val meters = metersTo(leg.toLat, leg.toLon)
                        val minutes = (leg.startEpoch + leg.seconds - now) / 60 + 1
                        return NavState(
                            kind = p.kind,
                            destName = p.destName,
                            phase = "walk",
                            headline = "Cammina verso ${leg.toName}",
                            // Con la posizione si dice quanto manca DAVVERO,
                            // non quanto mancherebbe secondo il piano fatto
                            // dieci minuti fa.
                            detail = if (meters >= 0) {
                                "$meters m · $minutes min a piedi"
                            } else {
                                "$minutes min a piedi"
                            },
                            stopsRemaining = totalStops,
                            totalStops = totalStops,
                            etaEpoch = 0,
                            metersToGo = meters,
                        )
                    }
                }

                is NavLeg.Ride -> {
                    val app2 = application as FluidTransitApp
                    val ready = app2.bundleManager.state.value
                        as? dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState.Ready
                    // Il bundle puo' mancare per un istante: lo swap
                    // notturno lo sostituisce sotto i piedi. Un `continue`
                    // qui saltava TUTTE le tratte e si finiva in fondo alla
                    // funzione, cioe' su "Arrivato": la navigazione
                    // annunciava l'arrivo e faceva vibrare "Scendi qui" nel
                    // mezzo del viaggio, poi il ciclo si chiudeva per sempre.
                    // Meglio ripetere l'ultima cosa vera e riprovare dopo.
                    val reader = ready?.reader ?: return lastState ?: NavState(
                        kind = p.kind,
                        destName = p.destName,
                        phase = "wait",
                        headline = "Un momento…",
                        detail = "sto rileggendo gli orari",
                        stopsRemaining = totalStops,
                        totalStops = totalStops,
                        etaEpoch = 0,
                    )
                    // Lo stesso modello che usano le schede: il ritardo alla
                    // salita e quello alla discesa non sono lo stesso numero,
                    // perche' fra le due fermate il bus ne recupera un pezzo.
                    val stops = reader.patternStopCount(leg.pattern)
                    val boardDelay = app.delayModel
                        .at(leg.trip, leg.boardPosition, stops, now)?.delaySeconds ?: 0
                    val alightDelay = app.delayModel
                        .at(leg.trip, leg.alightPosition, stops, now)?.delaySeconds ?: 0
                    val delay = boardDelay
                    val boardTime = leg.dayStartEpoch + leg.dep0 +
                        reader.profileOffset(leg.profile, leg.boardPosition) + boardDelay
                    val alightTime = leg.dayStartEpoch + leg.dep0 +
                        reader.profileOffset(leg.profile, leg.alightPosition) + alightDelay
                    if (now < boardTime) {
                        // Una corsa cancellata non arriva: aspettarla in
                        // silenzio e' la cosa peggiore che l'app possa fare.
                        // `canceledTrips` era gia' calcolato e non lo leggeva
                        // nessuno.
                        if (canceled.contains(leg.trip)) {
                            return NavState(
                                kind = p.kind,
                                destName = p.destName,
                                phase = "wait",
                                headline = "La ${leg.lineName} e' stata cancellata",
                                detail = "cerca un altro percorso",
                                stopsRemaining = totalStops,
                                totalStops = totalStops,
                                etaEpoch = 0,
                            )
                        }
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
                            val posDelay = app.delayModel
                                .at(leg.trip, pos, stops, now)?.delaySeconds ?: 0
                            val t = leg.dayStartEpoch + leg.dep0 +
                                reader.profileOffset(leg.profile, pos) + posDelay
                            if (t > now) {
                                nextPos = pos
                                break
                            }
                        }
                        val remaining = leg.alightPosition - nextPos + 1
                        val alightStop = reader.patternStop(leg.pattern, leg.alightPosition)
                        val metersToAlight = metersTo(
                            reader.stopLat(alightStop),
                            reader.stopLon(alightStop),
                        )
                        return NavState(
                            kind = p.kind,
                            destName = p.destName,
                            phase = "ride",
                            headline = "Scendi a ${leg.alightName}",
                            detail = buildString {
                                append(
                                    if (remaining == 1) {
                                        "alla PROSSIMA fermata"
                                    } else {
                                        "$remaining fermate · ${((alightTime - now) / 60 + 1)} min"
                                    },
                                )
                                // Sotto il chilometro la distanza vera dice
                                // piu' del conteggio delle fermate: e' il
                                // momento in cui uno inizia a guardare fuori.
                                if (metersToAlight in 0..999) append(" · $metersToAlight m")
                            },
                            stopsRemaining = remaining,
                            totalStops = totalStops,
                            etaEpoch = alightTime,
                            metersToGo = metersToAlight,
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
        // `<= 1`, non `== 1`: con il poll a sessanta secondi del modo
        // Risparmio, e un bus che fa due fermate in quel minuto, il momento
        // esatto in cui ne resta una non veniva mai campionato — e l'avviso
        // di scendere semplicemente non arrivava.
        // O manca una fermata secondo gli orari, O sei fisicamente vicino:
        // basta uno dei due. Con soli gli orari, un bus in ritardo faceva
        // scattare l'avviso tardi — a volte dopo che eri gia' passato.
        val closeEnough = s.metersToGo in 0..ALIGHT_RADIUS_M
        if (s.phase == "ride" && (s.stopsRemaining <= 1 || closeEnough) && !alertedPenultimate) {
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
        stopLocation()
        (application as FluidTransitApp).navigation.publish(null)
        // L'avviso di discesa e' una notifica a parte, e restava nel cassetto
        // a viaggio finito: un "Scendi qui" fermo li' il giorno dopo.
        runCatching { getSystemService(NotificationManager::class.java).cancel(ALERT_ID) }
        loopJob = null
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

        /** Ogni quanto e ogni quanti metri si chiede una posizione nuova. */
        const val LOCATION_INTERVAL_MS = 5_000L
        const val LOCATION_METERS = 10f

        /** Oltre due minuti, un fix non dice piu' dove sei. */
        const val FIX_MAX_AGE_NANOS = 120_000_000_000L

        /** Entro questo raggio dalla fermata di discesa, e' ora di alzarsi. */
        const val ALIGHT_RADIUS_M = 300
    }
}
