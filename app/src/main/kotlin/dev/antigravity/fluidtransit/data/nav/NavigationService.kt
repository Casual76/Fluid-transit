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
import dev.antigravity.fluidtransit.routing.Times
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

    // Questi quattro li scrive il thread principale — `onStartCommand`, il
    // listener della posizione — e li legge il giro di calcolo, che gira sul
    // pool di sfondo. Senza `@Volatile` non c'e' barriera fra le due parti:
    // il giro puo' vedere un piano ancora a meta', o una posizione con le
    // coordinate di prima. Una posizione sbagliata qui non e' un dettaglio:
    // e' il numero da cui esce "Scendi alla prossima".
    @Volatile
    private var plan: NavPlan? = null

    /**
     * Chi decide quando vibrare. Si riazzera con un piano nuovo, e da sola
     * al cambio di tappa.
     */
    @Volatile
    private var alerts = NavAlerts(ALIGHT_RADIUS_M)

    /**
     * Per quale (bundle, tappa) il fuoco e' gia' stato calcolato.
     *
     * Il fuoco costa la decodifica della polilinea del pattern — qualche
     * migliaio di varint — e non cambia finche' non cambia la tappa. A ogni
     * giro sarebbe quattro volte al minuto per niente.
     */
    @Volatile
    private var focusKey: Pair<Long, Int>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as FluidTransitApp).navigation.publish(null)
            (application as FluidTransitApp).navigation.publishFocus(null)
            (application as FluidTransitApp).navigation.publishPlan(null)
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
            // Il giro del viaggio di prima si ferma PRIMA di cambiare piano.
            // Se era a meta' della sua attesa di rete, finiva il lavoro e
            // pubblicava lo stato, il fuoco e gli avvisi del viaggio vecchio
            // sopra quelli del nuovo.
            loopJob?.cancel()
            loopJob = null
            plan = incoming
            app.navigation.pendingPlan = null
            alerts = NavAlerts(ALIGHT_RADIUS_M)
            focusKey = null
            // L'ultimo stato e' quello del viaggio di PRIMA: se il primo giro
            // del nuovo trovava il bundle a meta' scambio, ripeteva le frasi
            // del viaggio precedente; e lo scambio degli orari lo usa per
            // sapere quale tappa e' in corso.
            lastState = null
            // Piano, fuoco e stato cambiano insieme: prima la card restava
            // sulle frasi del viaggio precedente finche' il primo giro del
            // nuovo non finiva di aspettare la rete.
            app.navigation.publish(preparing(incoming))
            app.navigation.publishFocus(null)
            app.navigation.publishPlan(incoming)
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

    /** Il giro di rete in corso, se c'e': vedi il ciclo. */
    private var netJob: kotlinx.coroutines.Job? = null

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

    @Synchronized
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

    @Synchronized
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
                val p0 = plan ?: break
                // Prima la rete, poi tutto il resto su UNA fotografia del
                // bundle. Lo scambio degli orari puo' arrivare proprio mentre
                // si aspetta la rete — basta aprire l'app sul bus — e
                // rileggere il bundle dopo voleva dire usare gli indici del
                // piano vecchio sul file nuovo: fermate di un'altra corsa, e
                // un "Scendi qui" in mezzo al viaggio.
                //
                // Tutto il corpo e' sotto rete, perche' lo scope non ha un
                // gestore di eccezioni e una qualunque eccezione portava giu'
                // il processo. Ma la cancellazione passa: `runCatching` la
                // inghiottiva, e un giro superato da un viaggio nuovo finiva
                // il suo lavoro e lo pubblicava sopra quello nuovo.
                // La rete in un giro suo, aspettato al massimo [NET_WAIT_MS].
                //
                // Le due chiamate hanno trenta secondi di timeout ciascuna, e
                // con la rete appesa — una galleria, una zona morta — il
                // ciclo restava fermo fino a un minuto: niente posizione
                // ricalcolata, niente "scendi qui", la card congelata proprio
                // mentre si viaggia. Un timeout attorno non basta, perche'
                // la chiamata bloccante non si interrompe e withContext ne
                // aspetta comunque la fine: il giro continua per conto suo,
                // e il ciclo va avanti con quello che c'e'. Uno solo per
                // volta: finche' il precedente e' in corso, si aspetta quello.
                val giro = netJob?.takeIf { it.isActive } ?: scope.launch {
                    try {
                        app.realtime.refreshVehicles()
                        app.realtime.refreshDelays()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Senza rete si calcola con quello che c'e'.
                    }
                }.also { netJob = it }
                kotlinx.coroutines.withTimeoutOrNull(NET_WAIT_MS) { giro.join() }
                ensureActive()
                if (plan !== p0) break
                val bundle = readerOrNull(app)
                // Gli orari si sono scambiati sotto i piedi? Il piano si
                // rilegge sul bundle nuovo, o — se la corsa non c'e' piu' — la
                // navigazione si ferma e lo dice.
                val p = try {
                    followSwap(app, p0, bundle)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    p0
                }
                if (p == null) {
                    val perso = lostState(p0)
                    lastState = perso
                    app.navigation.publish(perso)
                    app.navigation.publishFocus(null)
                    notify(buildNotification(perso))
                    maybeAlert(perso)
                    endOfTrip(p0)
                    break
                }
                val state = try {
                    computeState(app, p, bundle)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (state != null) {
                    ensureActive()
                    lastState = state
                    app.navigation.publish(state)
                    notify(buildNotification(state))
                    maybeAlert(state)
                    if (state.phase == "arrived") {
                        // Consumo minimo: la sessione resta, il lavoro si ferma.
                        endOfTrip(p)
                        break
                    }
                }
                delay(mode.pollSeconds * 1000L)
            }
        }
    }

    /**
     * A viaggio finito, o perso, il GPS si spegne.
     *
     * Il commento in testa alla classe lo prometteva ("niente poll, niente
     * GPS") e il ciclo si fermava, ma l'ascoltatore della posizione restava
     * registrato fino a Termina. Solo se nel frattempo non e' partito un altro
     * viaggio: quello ha appena acceso il suo.
     */
    private fun endOfTrip(p: NavPlan) {
        synchronized(this) {
            if (plan === p) stopLocation()
        }
    }

    /** Cosa dire fra la scelta del viaggio e il primo giro. */
    private fun preparing(p: NavPlan): NavState = NavState(
        kind = p.kind,
        destName = p.destName,
        phase = "wait",
        headline = "Preparo il viaggio…",
        detail = p.destName,
        stopsRemaining = 0,
        totalStops = 0,
        etaEpoch = 0,
    )

    /** L'ultimo stato buono: si ripropone quando per un giro manca il bundle. */
    @Volatile
    private var lastState: NavState? = null

    /**
     * Il piano, riletto sul bundle di adesso se gli orari si sono scambiati.
     *
     * Le tappe sono indici dentro un file, e il file si scambia di notte —
     * cioe', con i ritardi di GitHub, verso mezzogiorno. Seguire gli indici
     * vecchi nel bundle nuovo voleva dire annunciare le fermate di un'altra
     * corsa. Null quando la tappa in corso, o una delle prossime, negli
     * orari nuovi non si ritrova.
     */
    private fun followSwap(
        app: FluidTransitApp,
        p: NavPlan,
        r: dev.antigravity.fluidtransit.routing.BundleReader?,
    ): NavPlan? {
        if (r == null) return p
        if (p.buildId == 0L || p.buildId == r.buildId) return p
        val current = lastState?.legIndex?.coerceAtLeast(0) ?: 0
        val nuovo = p.reboundTo(r, current) ?: return null
        // Solo se nel frattempo non e' arrivato un altro viaggio: quello
        // nuovo ha il suo ciclo, e non va sovrascritto con questo.
        if (plan === p) {
            plan = nuovo
            focusKey = null
            app.navigation.publishPlan(nuovo)
        }
        return nuovo
    }

    /** Quando la corsa che seguivi negli orari nuovi non c'e' piu'. */
    private fun lostState(p: NavPlan): NavState = NavState(
        kind = p.kind,
        destName = p.destName,
        phase = "lost",
        headline = "Gli orari sono cambiati",
        detail = "la corsa che seguivi non c'e' piu' negli orari nuovi: cerca di nuovo il percorso",
        stopsRemaining = 0,
        totalStops = 0,
        etaEpoch = 0,
        legIndex = lastState?.legIndex ?: -1,
    )

    /**
     * Il fuoco della tappa [legIndex], se non e' gia' quello pubblicato.
     *
     * [legIndex] negativo non azzera niente: capita nell'ultima camminata,
     * dopo essere scesi, e spegnere il fuoco li' farebbe riapparire l'intera
     * rete addosso a chi sta facendo gli ultimi duecento metri.
     */
    private fun ensureFocus(
        app: FluidTransitApp,
        reader: dev.antigravity.fluidtransit.routing.BundleReader,
        p: NavPlan,
        legIndex: Int,
    ) {
        if (legIndex < 0) return
        val key = reader.buildId to legIndex
        if (focusKey == key) return
        focusKey = key
        app.navigation.publishFocus(buildNavFocus(reader, p, legIndex, app.stopGroups.value))
    }

    /** La prima tappa in vettura da [from] in poi, o -1 se non ce n'e' piu'. */
    private fun nextRide(p: NavPlan, from: Int): Int {
        for (i in from until p.legs.size) if (p.legs[i] is NavLeg.Ride) return i
        return -1
    }

    /** Perche' una corsa non si prende piu', gia' in parole. */
    private class Persa(val headline: String, val detail: String, val canceled: Boolean)

    /**
     * Il feed parla delle corse che viaggiano oggi, per indice. Un viaggio
     * pianificato per domani ha lo stesso indice di una corsa di oggi, e
     * leggerne il feed voleva dire "La 20 e' gia' passata" alle 08:10 per il
     * bus di domattina alle 07:40, o cancellata domani perche' oggi c'e'
     * sciopero.
     */
    private fun stessoGiorno(
        reader: dev.antigravity.fluidtransit.routing.BundleReader,
        leg: NavLeg.Ride,
        now: Long,
    ): Boolean = leg.dayStartEpoch ==
        dev.antigravity.fluidtransit.routing.TripProgress.serviceDayStart(reader, leg.trip, now)

    /**
     * La corsa [leg] si prende ancora? Null se si'. Cancellata o gia'
     * passata, secondo il feed: senza feed non si sa, e si tace.
     *
     * @param earliest da quando si puo' prendere la prossima: l'arrivo alla
     *   fermata per chi ci sta camminando.
     */
    private fun corsaPersa(
        app: FluidTransitApp,
        reader: dev.antigravity.fluidtransit.routing.BundleReader,
        leg: NavLeg.Ride,
        now: Long,
        earliest: Long,
        canceled: Set<Int>,
    ): Persa? {
        if (!stessoGiorno(reader, leg, now)) return null
        val live = app.departureBoards.live()
        val cancellata = canceled.contains(leg.trip)
        val passata = !cancellata && dev.antigravity.fluidtransit.routing.NavApproach.passed(
            live = live,
            trip = leg.trip,
            stopCount = reader.patternStopCount(leg.pattern),
            position = leg.boardPosition,
            nowEpoch = now,
        )
        if (!cancellata && !passata) return null
        return Persa(
            headline = if (cancellata) {
                dev.antigravity.fluidtransit.routing.DepartureText.canceledLine(leg.lineName)
            } else {
                dev.antigravity.fluidtransit.routing.DepartureText.passedLine(leg.lineName)
            },
            detail = dev.antigravity.fluidtransit.routing.DepartureText
                .afterMissed(prossimaUguale(reader, leg, live, now, earliest)),
            canceled = cancellata,
        )
    }

    /**
     * Quando passa davvero la prossima corsa uguale a [leg], dalla stessa
     * fermata, non prima di [earliest]. Null se nelle prossime due ore non
     * ce n'e'.
     *
     * Il tabellone si chiede intero, senza tetto: il filtro sulla corsa
     * uguale viene dopo, e con un tetto di quaranta righe a una fermata del
     * centro la 20 di fra mezz'ora cadeva fuori — e "non e' nelle prime
     * quaranta" diventava "cerca un altro percorso".
     *
     * Lo stesso pattern e la stessa posizione, non solo la stessa linea: una
     * corsa limitata, o quella del verso opposto, non porta alla mia
     * discesa. E dal tabellone, cioe' con gli stessi minuti che la fermata
     * mostra: una seconda regola per "quando passa" sarebbe un secondo orario
     * per lo stesso bus.
     */
    private fun prossimaUguale(
        reader: dev.antigravity.fluidtransit.routing.BundleReader,
        leg: NavLeg.Ride,
        live: dev.antigravity.fluidtransit.routing.LiveTimes,
        now: Long,
        earliest: Long,
    ): Long? {
        val stop = reader.patternStop(leg.pattern, leg.boardPosition)
        val board = dev.antigravity.fluidtransit.routing.Departures.build(
            reader,
            stop,
            Instant.ofEpochSecond(now),
            limit = Int.MAX_VALUE,
            live = live,
        )
        return board.rows.asSequence()
            .filter { it.tripIndex != leg.trip && it.patternIndex == leg.pattern }
            .filter { it.positionInPattern == leg.boardPosition }
            .filter { !it.canceled && !it.skipped && it.effectiveEpoch >= maxOf(now, earliest) }
            .minOfOrNull { it.effectiveEpoch }
    }

    /** Il bundle, se c'e' adesso. Durante lo scambio notturno non c'e'. */
    private fun readerOrNull(app: FluidTransitApp): dev.antigravity.fluidtransit.routing.BundleReader? =
        (
            app.bundleManager.state.value
                as? dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState.Ready
            )?.reader

    /**
     * Lo stato di adesso, calcolato su [bundle] — la stessa fotografia con cui
     * il piano e' stato riletto. Null = questo giro non pubblica niente.
     */
    private fun computeState(
        app: FluidTransitApp,
        p: NavPlan,
        bundle: dev.antigravity.fluidtransit.routing.BundleReader?,
    ): NavState? {
        // Un piano di un altro bundle non si calcola: i suoi indici sarebbero
        // di un'altra corsa. Il giro dopo `followSwap` lo rilegge.
        if (bundle != null && p.buildId != 0L && bundle.buildId != p.buildId) return null
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

        // Due conti diversi, e servono tutti e due. La barra conta sul viaggio
        // intero — con un cambio ripartiva da capo a meta' viaggio — mentre
        // "scendi alla prossima" e "preparati" contano sulla tappa. Prima
        // c'era un totale solo, del viaggio, diviso per le fermate mancanti
        // della tappa: al via di un viaggio a due tappe la barra segnava 25%.
        val primaDi = IntArray(p.legs.size)
        var journeyStops = 0
        for ((i, leg) in p.legs.withIndex()) {
            primaDi[i] = journeyStops
            if (leg is NavLeg.Ride) journeyStops += leg.alightPosition - leg.boardPosition
        }
        fun stopsOf(leg: NavLeg?): Int =
            if (leg is NavLeg.Ride) leg.alightPosition - leg.boardPosition else 0

        for ((legIndex, leg) in p.legs.withIndex()) {
            when (leg) {
                is NavLeg.Walk -> {
                    if (now < leg.startEpoch + leg.seconds) {
                        // Chi cammina verso la fermata vuole gia' vedere
                        // dove passa il bus: il fuoco punta alla prossima
                        // tappa in vettura, non a quella in corso.
                        val poi = nextRide(p, legIndex)
                        val r = bundle
                        if (r != null) ensureFocus(app, r, p, poi)
                        // Quale bus si andra' a prendere: la card lo dice gia'
                        // mentre si cammina, e la pastiglia colorata e' il
                        // modo in cui un bus si presenta dappertutto nell'app.
                        val corsa = p.legs.getOrNull(poi) as? NavLeg.Ride
                        val coloreCorsa = if (corsa != null && r != null) {
                            r.routeDisplayColor(corsa.route) and 0xFFFFFF
                        } else {
                            0
                        }
                        val meters = metersTo(leg.toLat, leg.toLon)
                        val quanto = if (meters >= 0) {
                            dev.antigravity.fluidtransit.routing.Words.distance(meters.toDouble()) + " · "
                        } else {
                            ""
                        }
                        val tappa = stopsOf(corsa)

                        // Il bus che si va a prendere si puo' perdere mentre
                        // si cammina: cancellato, o passato in anticipo.
                        // Visto sull'emulatore il 30/09: alle 13:58 il mezzo
                        // della 20 delle 14:00 era un chilometro oltre Piazza
                        // Dalmazia, la scia grigia lo mostrava, e la card
                        // diceva ancora "Cammina verso PIAZZA DALMAZIA".
                        // L'attesa se ne accorgeva; la camminata non guardava.
                        if (corsa != null && !corsa.orphaned && r != null) {
                            // La prossima uguale deve essere una che si fa in
                            // tempo a prendere: da quando si arriva alla
                            // fermata, non da adesso.
                            val arrivo = leg.startEpoch + leg.seconds
                            val persa = corsaPersa(app, r, corsa, now, arrivo, canceled)
                            if (persa != null) {
                                return NavState(
                                    kind = p.kind,
                                    destName = p.destName,
                                    phase = "walk",
                                    headline = persa.headline,
                                    // La fermata resta dove andare, se la
                                    // prossima e' uguale: quanto manca serve.
                                    detail = quanto + persa.detail,
                                    stopsRemaining = tappa,
                                    totalStops = tappa,
                                    etaEpoch = 0,
                                    journeyDone = primaDi[legIndex],
                                    journeyStops = journeyStops,
                                    metersToGo = meters,
                                    legIndex = legIndex,
                                    lineName = corsa.lineName,
                                    lineColorRgb = coloreCorsa,
                                    alightName = corsa.alightName,
                                    canceled = persa.canceled,
                                    missed = !persa.canceled,
                                )
                            }
                        }

                        // Camminare adesso e camminare fra tre ore sono due
                        // cose diverse, e si dicevano con le stesse parole.
                        //
                        // Il conto era "quanto manca alla fine della
                        // camminata", che mentre cammini e' giusto — sono i
                        // minuti che ti restano — ma prima di partire e' il
                        // tempo che manca alla partenza, e usciva etichettato
                        // "a piedi". Avviando alle 02:21 un viaggio che parte
                        // alle 05:27 si leggeva "190 min a piedi" per una
                        // camminata di quattro minuti.
                        if (now < leg.startEpoch) {
                            // "fra 0 min" e' la stessa frase vuota tolta
                            // dagli itinerari e dalla notifica dell'attesa:
                            // sotto il mezzo minuto la risposta e' "adesso".
                            // Anche la camminata puo' essere di pochi passi:
                            // "poi 0 min a piedi" e' la stessa frase vuota.
                            val cammino = Times.durationOrUnderMinute(leg.seconds)
                            val dettaglio = { t: Long ->
                                val mancano = (leg.startEpoch - t).toInt()
                                val fra = if (mancano <= Times.NOW_SECONDS) {
                                    "adesso"
                                } else {
                                    "fra " + Times.durationLabel(mancano)
                                }
                                // `quanto` finisce gia' col suo puntino e lo
                                // spazio, o e' vuoto: uno spazio in piu' qui
                                // dava " fra 9 min" senza GPS e "350 m .
                                // fra 9 min" con — visto sull'emulatore.
                                quanto + "$fra, poi $cammino a piedi fino a ${leg.toName}"
                            }
                            return NavState(
                                kind = p.kind,
                                destName = p.destName,
                                phase = "walk",
                                headline = "Parti alle ${Times.hhmm(leg.startEpoch)}",
                                detail = dettaglio(now),
                                detailAt = dettaglio,
                                stopsRemaining = tappa,
                                totalStops = tappa,
                                etaEpoch = 0,
                                journeyDone = primaDi[legIndex],
                                journeyStops = journeyStops,
                                metersToGo = meters,
                                legIndex = legIndex,
                                lineName = corsa?.lineName ?: "",
                                lineColorRgb = coloreCorsa,
                                alightName = corsa?.alightName ?: "",
                            )
                        }

                        val dettaglio = { t: Long ->
                            val restano = Times.durationOrUnderMinute(
                                (leg.startEpoch + leg.seconds - t).toInt(),
                            )
                            "$quanto$restano a piedi"
                        }
                        return NavState(
                            kind = p.kind,
                            destName = p.destName,
                            phase = "walk",
                            headline = "Cammina verso ${leg.toName}",
                            // Con la posizione si dice quanto manca DAVVERO,
                            // non quanto mancherebbe secondo il piano fatto
                            // dieci minuti fa.
                            detail = dettaglio(now),
                            detailAt = dettaglio,
                            stopsRemaining = tappa,
                            totalStops = tappa,
                            etaEpoch = 0,
                            journeyDone = primaDi[legIndex],
                            journeyStops = journeyStops,
                            metersToGo = meters,
                            legIndex = legIndex,
                            lineName = corsa?.lineName ?: "",
                            lineColorRgb = coloreCorsa,
                            alightName = corsa?.alightName ?: "",
                        )
                    }
                }

                is NavLeg.Ride -> {
                    // Una tappa gia' percorsa che negli orari nuovi non si
                    // ritrova: e' fatta, e i suoi indici non valgono piu'.
                    if (leg.orphaned) continue
                    val tappa = stopsOf(leg)
                    // Il bundle puo' mancare per un istante: lo swap
                    // notturno lo sostituisce sotto i piedi. Un `continue`
                    // qui saltava TUTTE le tratte e si finiva in fondo alla
                    // funzione, cioe' su "Arrivato": la navigazione
                    // annunciava l'arrivo e faceva vibrare "Scendi qui" nel
                    // mezzo del viaggio, poi il ciclo si chiudeva per sempre.
                    // Meglio tacere per un giro e riprovare dopo: ripubblicare
                    // l'ultimo stato rifaceva passare i suoi avvisi, e il
                    // "bus in arrivo" si confermava da solo.
                    val reader = bundle ?: return if (lastState != null) null else NavState(
                        kind = p.kind,
                        destName = p.destName,
                        phase = "wait",
                        headline = "Un momento…",
                        detail = "sto rileggendo gli orari",
                        stopsRemaining = tappa,
                        totalStops = tappa,
                        etaEpoch = 0,
                        journeyDone = primaDi[legIndex],
                        journeyStops = journeyStops,
                    )
                    ensureFocus(app, reader, p, legIndex)
                    // Lo stesso modello che usano le schede: il ritardo alla
                    // salita e quello alla discesa non sono lo stesso numero,
                    // perche' fra le due fermate il bus ne recupera un pezzo.
                    val stops = reader.patternStopCount(leg.pattern)
                    // La stessa fonte delle schede: prima qui c'era il solo
                    // modello dei ritardi, quindi la navigazione non vedeva
                    // le previsioni per fermata che il feed pubblica. Il bus
                    // che stai aspettando poteva dire due minuti diversi a
                    // seconda che guardassi la capsula o la sua fermata.
                    val live = app.departureBoards.live()
                    val boardAt = live.at(leg.trip, leg.boardPosition, stops, now)
                    val boardDelay = boardAt?.delaySeconds ?: 0
                    val alightDelay = live
                        .at(leg.trip, leg.alightPosition, stops, now)?.delaySeconds ?: 0
                    val boardTime = leg.dayStartEpoch + leg.dep0 +
                        reader.profileOffset(leg.profile, leg.boardPosition) + boardDelay
                    val alightTime = leg.dayStartEpoch + leg.dep0 +
                        reader.profileOffset(leg.profile, leg.alightPosition) + alightDelay
                    val colore = reader.routeDisplayColor(leg.route) and 0xFFFFFF
                    if (now < boardTime) {
                        // Il feed parla della corsa di oggi: per un viaggio
                        // di domani, lo stesso indice oggi e' un altro bus.
                        val oggi = stessoGiorno(reader, leg, now)
                        // Una corsa cancellata non arriva: aspettarla in
                        // silenzio e' la cosa peggiore che l'app possa fare.
                        // `canceledTrips` era gia' calcolato e non lo leggeva
                        // nessuno.
                        if (oggi && canceled.contains(leg.trip)) {
                            return NavState(
                                kind = p.kind,
                                destName = p.destName,
                                phase = "wait",
                                headline = dev.antigravity.fluidtransit.routing.DepartureText
                                    .canceledLine(leg.lineName),
                                detail = dev.antigravity.fluidtransit.routing.DepartureText
                                    .afterMissed(prossimaUguale(reader, leg, live, now, now)),
                                stopsRemaining = tappa,
                                totalStops = tappa,
                                etaEpoch = 0,
                                journeyDone = primaDi[legIndex],
                                journeyStops = journeyStops,
                                legIndex = legIndex,
                                lineName = leg.lineName,
                                lineColorRgb = colore,
                                alightName = leg.alightName,
                                canceled = true,
                            )
                        }
                        // Dov'e' il mezzo che sto aspettando, in fermate.
                        //
                        // "Parte tra 4 minuti" e' un numero che non si puo'
                        // controllare; "e' a tre fermate" si controlla
                        // guardando fuori. Quando il feed non segue la corsa
                        // restano le parole di prima: meglio dire meno che
                        // annunciare un autobus che potrebbe non essere mai
                        // partito.
                        val avvicinamento = dev.antigravity.fluidtransit.routing.NavApproach
                            .between(
                                live = if (oggi) live else null,
                                trip = leg.trip,
                                stopCount = stops,
                                toPosition = leg.boardPosition,
                                nowEpoch = now,
                                name = {
                                    reader.stopName(reader.patternStop(leg.pattern, it))
                                },
                                scheduledAt = { pos ->
                                    leg.dayStartEpoch + leg.dep0 +
                                        reader.profileOffset(leg.profile, pos)
                                },
                            )
                        // Il feed dice che se n'e' gia' andato: in anticipo, o
                        // assegnato male, ma da qui non passa piu'. Il conto
                        // delle fermate qui dava zero, cioe' "e' alla tua
                        // fermata", e l'avviso "il tuo bus sta arrivando" per
                        // un bus che era gia' oltre.
                        if (avvicinamento.passed) {
                            return NavState(
                                kind = p.kind,
                                destName = p.destName,
                                phase = "wait",
                                headline = dev.antigravity.fluidtransit.routing.DepartureText
                                    .passedLine(leg.lineName),
                                detail = dev.antigravity.fluidtransit.routing.DepartureText
                                    .afterMissed(prossimaUguale(reader, leg, live, now, now)),
                                stopsRemaining = tappa,
                                totalStops = tappa,
                                etaEpoch = 0,
                                journeyDone = primaDi[legIndex],
                                journeyStops = journeyStops,
                                legIndex = legIndex,
                                lineName = leg.lineName,
                                lineColorRgb = colore,
                                alightName = leg.alightName,
                                missed = true,
                            )
                        }
                        // Le parole del tabellone anche qui: "ritardo live"
                        // non voleva dire niente altrove.
                        val fonte = dev.antigravity.fluidtransit.routing.DepartureText
                            .source(boardAt?.certainty)?.let { " · $it" } ?: ""
                        // Arrotondato e non troncato-per-eccesso, e con un
                        // nome per lo zero: con il bus in arrivo fra pochi
                        // secondi qui usciva "parte tra 1 min", e con il bus
                        // gia' partito da un minuto "parte tra 0 min" — che e'
                        // la stessa frase vuota che gli itinerari avevano e
                        // che e' stata tolta li'.
                        val dettaglio = { t: Long -> attesa(boardTime - t) + fonte }
                        return NavState(
                            kind = p.kind,
                            destName = p.destName,
                            phase = "wait",
                            headline = if (avvicinamento.known) {
                                "La ${leg.lineName} ${dove(avvicinamento.stopsAway)}"
                            } else {
                                "Aspetta la ${leg.lineName}"
                            },
                            detail = dettaglio(now),
                            detailAt = dettaglio,
                            stopsRemaining = tappa,
                            totalStops = tappa,
                            etaEpoch = alightTime,
                            journeyDone = primaDi[legIndex],
                            journeyStops = journeyStops,
                            legIndex = legIndex,
                            lineName = leg.lineName,
                            lineColorRgb = colore,
                            alightName = leg.alightName,
                            busStopsAway = avvicinamento.stopsAway,
                            busEtaEpoch = if (avvicinamento.known) boardTime else 0L,
                            approach = avvicinamento.stops,
                            approachHidden = avvicinamento.hidden,
                            busCertainty = avvicinamento.certainty ?: boardAt?.certainty,
                        )
                    }
                    if (now < alightTime) {
                        // A bordo: la prossima fermata la dice la stessa regola
                        // della scheda della corsa. Qui si guardava solo
                        // l'orologio, e il feed che dichiarava servita una
                        // fermata non veniva ascoltato: su una corsa in
                        // anticipo la notifica annunciava una fermata che il
                        // bus si era gia' lasciato indietro, e contava una
                        // fermata di troppo da qui alla discesa.
                        val trovata = dev.antigravity.fluidtransit.routing.TripProgress
                            .nextPosition(live, leg.trip, stops, now) { pos ->
                                leg.dayStartEpoch + leg.dep0 +
                                    reader.profileOffset(leg.profile, pos)
                            }
                        val nextPos = if (trovata < 0) {
                            leg.alightPosition
                        } else {
                            minOf(maxOf(trovata, leg.boardPosition + 1), leg.alightPosition)
                        }
                        val remaining = leg.alightPosition - nextPos + 1
                        val restanti = dev.antigravity.fluidtransit.routing.NavApproach.between(
                            live = live,
                            trip = leg.trip,
                            stopCount = stops,
                            toPosition = leg.alightPosition,
                            nowEpoch = now,
                            requireVehicle = false,
                            name = { reader.stopName(reader.patternStop(leg.pattern, it)) },
                            scheduledAt = { pos ->
                                leg.dayStartEpoch + leg.dep0 +
                                    reader.profileOffset(leg.profile, pos)
                            },
                        )
                        val alightStop = reader.patternStop(leg.pattern, leg.alightPosition)
                        val metersToAlight = metersTo(
                            reader.stopLat(alightStop),
                            reader.stopLon(alightStop),
                        )
                        val dettaglio = { t: Long ->
                            buildString {
                                append(
                                    if (remaining == 1) {
                                        "alla PROSSIMA fermata"
                                    } else {
                                        dev.antigravity.fluidtransit.routing.Words
                                            .count(remaining, "fermata", "fermate") +
                                            " · " + Times.durationOrUnderMinute((alightTime - t).toInt())
                                    },
                                )
                                // Sotto il chilometro la distanza vera dice
                                // piu' del conteggio delle fermate: e' il
                                // momento in cui uno inizia a guardare fuori.
                                if (metersToAlight in 0..999) {
                                    append(" · ")
                                    append(
                                        dev.antigravity.fluidtransit.routing.Words
                                            .distance(metersToAlight.toDouble()),
                                    )
                                }
                            }
                        }
                        return NavState(
                            kind = p.kind,
                            destName = p.destName,
                            phase = "ride",
                            headline = "Scendi a ${leg.alightName}",
                            detail = dettaglio(now),
                            detailAt = dettaglio,
                            stopsRemaining = remaining,
                            totalStops = tappa,
                            etaEpoch = alightTime,
                            journeyDone = primaDi[legIndex] + (tappa - remaining).coerceAtLeast(0),
                            journeyStops = journeyStops,
                            metersToGo = metersToAlight,
                            rideRoute = leg.route,
                            legIndex = legIndex,
                            lineName = leg.lineName,
                            lineColorRgb = colore,
                            alightName = leg.alightName,
                            // A bordo il mezzo e' sotto i piedi: le fermate
                            // che mancano le sa l'orologio, e non serve un
                            // feed per contarle. E' la stessa regola con cui
                            // la scheda della corsa elenca le sue.
                            approach = restanti.stops,
                            approachHidden = restanti.hidden,
                            busEtaEpoch = alightTime,
                            busCertainty = restanti.certainty,
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
            // Una camminata di pochi secondi in fondo al piano e' la
            // fermata stessa: li' "Scendi qui" resta la frase giusta.
            arrivedOnFoot = (p.legs.lastOrNull() as? NavLeg.Walk)
                ?.let { it.seconds > Times.NOW_SECONDS } == true,
            stopsRemaining = 0,
            totalStops = 0,
            etaEpoch = now,
            journeyDone = journeyStops,
            journeyStops = journeyStops,
        )
    }

    /**
     * Il guscio degli avvisi: la regola sta in [NavAlerts], qui c'e' solo il
     * cassetto delle notifiche.
     *
     * `<= 1` e non `== 1` per la discesa: col poll a sessanta secondi del
     * modo Risparmio, e un bus che fa due fermate in quel minuto, il momento
     * esatto in cui ne resta una non veniva mai campionato — e l'avviso non
     * arrivava proprio. Quella regola, e tutte le altre, adesso vivono in
     * una classe che si puo' interrogare senza prendere un autobus.
     */
    private fun maybeAlert(s: NavState) {
        val avviso = alerts.next(s, Instant.now().epochSecond) ?: return
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(
            ALERT_ID,
            NotificationCompat.Builder(
                this,
                if (avviso.forte) ALERT_CHANNEL else CHANNEL,
            )
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle(avviso.titolo)
                .setContentText(avviso.testo)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun buildNotification(s: NavState?): Notification {
        // Riapre la navigazione, non "l'app": chi tocca la barra mentre e'
        // sul bus vuole tornare al viaggio in corso, e se era su Preferiti
        // prima di bloccare lo schermo ci tornava dentro.
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(
                    android.net.Uri.parse(
                        dev.antigravity.fluidtransit.ui.nav.Deeplink.nav(),
                    ),
                ),
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
        if (s != null && s.journeyStops > 0 && s.phase == "ride") {
            builder.setProgress(s.journeyStops, s.journeyDone, false)
        }
        // La Live Update di Android 16, quando c'e': la barra a segmenti con
        // il punto che avanza. Sotto, resta la ongoing qui sopra.
        if (Build.VERSION.SDK_INT >= 36 && s != null && s.journeyStops > 0) {
            runCatching {
                val style = Notification.ProgressStyle()
                    .setProgress((s.journeyDone * 100) / s.journeyStops)
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

    /** L'orologio e' quello del vocabolario, non una copia locale. */
    private fun hm(epoch: Long): String =
        dev.antigravity.fluidtransit.routing.Times.hhmm(epoch)

    private fun ensureChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Navigazione", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            // Il nome e' quello che si legge nelle impostazioni di Android.
            // Era "Scendi qui", ma sullo stesso canale passano anche il bus in
            // arrivo, la corsa cancellata e gli orari cambiati: chi lo
            // spegneva per non sentirsi dire di scendere perdeva anche quelli.
            NotificationChannel(
                ALERT_CHANNEL, "Avvisi del viaggio", NotificationManager.IMPORTANCE_HIGH,
            ).apply { enableVibration(true) },
        )
    }

    override fun onDestroy() {
        stopLocation()
        (application as FluidTransitApp).navigation.publish(null)
        (application as FluidTransitApp).navigation.publishFocus(null)
        (application as FluidTransitApp).navigation.publishPlan(null)
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

        /**
         * Quanto il ciclo aspetta la rete prima di ricalcolare con quello che
         * ha. Un giro buono sono qualche centinaio di millisecondi; sei
         * secondi lasciano passare la rete lenta senza fermare la card.
         */
        const val NET_WAIT_MS = 6_000L

        /** Ogni quanto e ogni quanti metri si chiede una posizione nuova. */
        const val LOCATION_INTERVAL_MS = 5_000L
        const val LOCATION_METERS = 10f

        /** Oltre due minuti, un fix non dice piu' dove sei. */
        const val FIX_MAX_AGE_NANOS = 120_000_000_000L

        /** Entro questo raggio dalla fermata di discesa, e' ora di alzarsi. */
        const val ALIGHT_RADIUS_M = 300

        /**
         * Dov'e' il mezzo, in parole.
         *
         * "A una fermata" e' tecnicamente giusto e non lo dice nessuno: chi
         * aspetta dice "e' alla fermata prima". E zero non e' "a 0 fermate":
         * e' il momento di alzarsi.
         */
        internal fun dove(stopsAway: Int): String = when {
            stopsAway <= 0 -> "e' alla tua fermata"
            stopsAway == 1 -> "e' alla fermata prima"
            else -> "e' a " + dev.antigravity.fluidtransit.routing.Words
                .count(stopsAway, "fermata", "fermate")
        }

        /**
         * "parte ora" / "parte tra 4 min" / "e' gia' partita".
         *
         * Il conto era `secondi / 60 + 1`, cioe' troncato e poi alzato di
         * uno: con il bus in arrivo fra dieci secondi diceva "parte tra 1
         * min", e con il bus partito da un minuto "parte tra 0 min" — la
         * stessa frase vuota che gli itinerari avevano e che li' e' gia'
         * stata tolta. Qui si arrotonda come ovunque nell'app, e i due
         * estremi hanno un nome invece di un numero.
         */
        internal fun attesa(seconds: Long): String = when {
            seconds < -dev.antigravity.fluidtransit.routing.Times.NOW_SECONDS ->
                "e' gia' partita"
            seconds <= dev.antigravity.fluidtransit.routing.Times.NOW_SECONDS -> "parte ora"
            else -> "parte tra " +
                dev.antigravity.fluidtransit.routing.Times.durationLabel(seconds.toInt())
        }
    }
}
