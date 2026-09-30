package dev.antigravity.fluidtransit.data.nav

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Il modo viaggio a tre livelli, deciso in fase di piano e col default
 * scelto dall'utente: Bilanciato. Cambia il ritmo del realtime in
 * navigazione e se il GPS lavora di continuo (solo Preciso).
 */
enum class TravelMode(val pollSeconds: Int, val usesGps: Boolean, val label: String) {
    PRECISO(15, true, "Preciso"),
    BILANCIATO(30, false, "Bilanciato"),
    RISPARMIO(60, false, "Risparmio"),
}

class TravelModeStore(context: Context) {
    private val prefs = context.getSharedPreferences("travel-mode", Context.MODE_PRIVATE)

    var mode: TravelMode
        get() = runCatching { TravelMode.valueOf(prefs.getString("mode", null) ?: "") }
            .getOrDefault(TravelMode.BILANCIATO)
        set(value) {
            prefs.edit().putString("mode", value.name).apply()
        }
}

/**
 * Il piano che il servizio di navigazione segue: tappe gia' risolte in
 * numeri puri, cosi' il servizio non ha bisogno del motore — solo del
 * lettore per i nomi e dei ritardi live per correggere i tempi.
 */
class NavPlan(
    /** "journey" (viaggio calcolato) o "bus" (sono su questo bus). */
    val kind: String,
    val destName: String,
    val legs: List<NavLeg>,
    /**
     * Il bundle su cui questi numeri sono stati risolti.
     *
     * Le tappe qui dentro sono indici dentro un file, e il file si scambia
     * di notte: continuare a seguirli dopo lo scambio vuol dire annunciare
     * la fermata di un'altra linea con la faccia seria. Zero = non si sa,
     * e in quel caso non si controlla niente.
     */
    val buildId: Long = 0L,
)

sealed class NavLeg {
    /**
     * Una camminata. Porta anche DOVE si va, non solo per quanto: senza le
     * coordinate il servizio poteva dire "cammina per 4 minuti" e nient'altro
     * — mai quanto manca davvero, perche' non sapeva ne' dove sei tu ne' dove
     * e' la fermata.
     */
    class Walk(
        val seconds: Int,
        val toName: String,
        val startEpoch: Long,
        val toLat: Double = 0.0,
        val toLon: Double = 0.0,
    ) : NavLeg()
    class Ride(
        val trip: Int,
        val pattern: Int,
        val route: Int,
        val boardPosition: Int,
        val alightPosition: Int,
        val dayStartEpoch: Long,
        val dep0: Int,
        val profile: Int,
        val lineName: String,
        val alightName: String,
        /** I nomi delle fermate della corsa, da board ad alight compresi. */
        val stopNames: List<String>,
        /**
         * Gli hash che sopravvivono alla notte: del `trip_id` e delle due
         * fermate. Gli indici qui sopra valgono solo nel bundle
         * [NavPlan.buildId]; quando gli orari si scambiano a viaggio in corso
         * la tappa si ritrova da questi ([reboundTo]). Zero = piano fatto da
         * una versione che non li scriveva.
         */
        val tripHash: Long = 0L,
        val boardStopHash: Long = 0L,
        val alightStopHash: Long = 0L,
    ) : NavLeg() {
        /**
         * Una tappa gia' percorsa che negli orari nuovi non si ritrova.
         *
         * Resta nell'elenco — e' un pezzo di viaggio che hai fatto — ma senza
         * indici: quelli vecchi, letti nel bundle nuovo, sarebbero di un'altra
         * corsa, e il calcolo dello stato la prenderebbe per quella in corso.
         */
        val orphaned: Boolean get() = trip < 0

        internal fun orphan(): Ride = Ride(
            trip = -1, pattern = -1, route = -1,
            boardPosition = boardPosition, alightPosition = alightPosition,
            dayStartEpoch = dayStartEpoch, dep0 = dep0, profile = -1,
            lineName = lineName, alightName = alightName, stopNames = stopNames,
            tripHash = tripHash, boardStopHash = boardStopHash, alightStopHash = alightStopHash,
        )
    }
}

/**
 * Lo stesso piano, con gli indici del bundle [reader].
 *
 * Le tappe da [current] in poi devono ritrovarsi TUTTE: mezzo viaggio giusto e
 * mezzo sbagliato e' peggio di nessun viaggio, perche' la meta' sbagliata
 * annuncerebbe fermate di un'altra linea con la faccia seria. Null in quel
 * caso, e chi chiama lo deve dire. Le tappe prima di [current] sono gia'
 * percorse: se non si ritrovano restano come [NavLeg.Ride.orphaned].
 */
fun NavPlan.reboundTo(
    reader: dev.antigravity.fluidtransit.routing.BundleReader,
    current: Int,
): NavPlan? {
    if (buildId == reader.buildId) return this
    val nuove = legs.mapIndexed { i, leg ->
        if (leg !is NavLeg.Ride || leg.orphaned) return@mapIndexed leg
        val r = dev.antigravity.fluidtransit.routing.NavRebind.ride(
            reader = reader,
            tripHash = leg.tripHash,
            boardStopHash = leg.boardStopHash,
            alightStopHash = leg.alightStopHash,
            boardPosition = leg.boardPosition,
            alightPosition = leg.alightPosition,
        )
        when {
            r != null -> NavLeg.Ride(
                trip = r.trip,
                pattern = r.pattern,
                route = r.route,
                boardPosition = r.boardPosition,
                alightPosition = r.alightPosition,
                dayStartEpoch = leg.dayStartEpoch,
                dep0 = r.dep0,
                profile = r.profile,
                lineName = leg.lineName,
                alightName = leg.alightName,
                stopNames = (r.boardPosition..r.alightPosition).map {
                    reader.stopName(reader.patternStop(r.pattern, it))
                },
                tripHash = leg.tripHash,
                boardStopHash = leg.boardStopHash,
                alightStopHash = leg.alightStopHash,
            )

            i < current -> leg.orphan()
            else -> return null
        }
    }
    return NavPlan(kind = kind, destName = destName, legs = nuove, buildId = reader.buildId)
}

/** Cosa mostrare ADESSO: lo stato vivo che mini e notifica leggono. */
class NavState(
    val kind: String,
    val destName: String,
    val phase: String, // walk | wait | ride | arrived | lost
    val headline: String, // "Scendi a TORRE GALLI"
    val detail: String, // "4 fermate · 12 min"
    /**
     * Le fermate della TAPPA in corso: quante ne mancano e quante sono. E' su
     * questi che si decide "scendi alla prossima" e "preparati".
     */
    val stopsRemaining: Int,
    val totalStops: Int,
    val etaEpoch: Long,
    /**
     * Quanto del viaggio INTERO e' fatto, in fermate: le tappe gia' percorse
     * piu' il pezzo di questa. Lo leggono la barra della card e quella della
     * notifica.
     *
     * Prima la barra divideva le fermate mancanti della tappa per quelle del
     * viaggio intero, e su un viaggio con un cambio partiva dal 25% appena
     * saliti sul primo bus.
     */
    val journeyDone: Int = 0,
    val journeyStops: Int = 0,
    /** Metri che mancano al punto di questa fase. -1 se la posizione non si sa. */
    val metersToGo: Int = -1,
    /**
     * La linea su cui sei adesso, come indice del bundle. -1 quando non sei
     * a bordo.
     *
     * Serve alla mappa per accendersi da sola sulla tratta giusta appena
     * sali: il percorso del bus e i mezzi vivi con la loro direzione ci sono
     * gia', mancava solo qualcuno che dicesse quale linea guardare.
     */
    val rideRoute: Int = -1,
    /**
     * Quale tappa del piano e' in corso.
     *
     * Gli avvisi si riarmano quando cambia: senza, in un viaggio con un
     * cambio il secondo "il tuo bus sta arrivando" non sarebbe arrivato
     * mai, perche' i segni di "gia' avvisato" si azzeravano solo alla
     * partenza.
     */
    val legIndex: Int = -1,
    /** La linea di questa tappa, anche mentre la si aspetta. */
    val lineName: String = "",
    val lineColorRgb: Int = 0,
    val alightName: String = "",
    /**
     * Quante fermate mancano al MIO mezzo per arrivare da me. -1 = non si
     * sa, che non e' zero: zero vuol dire che e' alla tua fermata.
     *
     * Ha senso solo mentre si aspetta. A bordo il mezzo e' sotto i piedi e
     * quello che manca lo dice [stopsRemaining].
     */
    val busStopsAway: Int = -1,
    /** Quando il mezzo arriva al punto di questa fase. 0 = non si sa. */
    val busEtaEpoch: Long = 0,
    /** I pallini della barra di avvicinamento, gia' in parole. */
    val approach: List<dev.antigravity.fluidtransit.routing.NavApproach.Stop> = emptyList(),
    /** Quante fermate la barra ha dovuto nascondere per starci. */
    val approachHidden: Int = 0,
    /** Da dove viene il numero: decide se i pallini sono pieni o vuoti. */
    val busCertainty: dev.antigravity.fluidtransit.routing.Certainty? = null,
    /**
     * La stessa riga di [detail], scritta per un istante qualunque.
     *
     * Il servizio gira ogni 15-60 secondi a seconda del modo viaggio, e la
     * card leggeva il [detail] di quel giro: "parte tra 4 min" restava li'
     * fino al giro dopo, cioe' fino a un minuto intero in Risparmio. Qui la
     * frase e' la stessa regola, e la card la riscrive col battito comune di
     * `UiClock` come fanno le altre schermate. Null quando la riga non
     * dipende dall'orologio.
     */
    val detailAt: ((nowEpoch: Long) -> String)? = null,
    /**
     * Il feed dichiara cancellata la corsa che sto aspettando.
     *
     * Era gia' riconosciuta e finiva solo dentro una frase: nessun avviso,
     * nessuna alternativa. Chi non guardava lo schermo aspettava un autobus
     * che non sarebbe mai arrivato.
     */
    val canceled: Boolean = false,
    /**
     * Arrivato a piedi, dopo l'ultima camminata. L'avviso dell'arrivo non
     * dice "Scendi qui" a chi e' gia' sceso da un pezzo.
     */
    val arrivedOnFoot: Boolean = false,
)

/**
 * Il ponte fra UI e servizio: il piano in consegna (gli Intent non portano
 * oggetti cosi') e lo stato vivo da osservare.
 */
class NavigationHolder {

    /** Il piano che il servizio deve raccogliere all'avvio. */
    @Volatile
    var pendingPlan: NavPlan? = null

    private val _state = MutableStateFlow<NavState?>(null)
    val state: StateFlow<NavState?> = _state

    /**
     * Cosa accendere sulla mappa, adesso.
     *
     * Sta accanto allo stato e non dentro perche' i due cambiano a ritmi
     * diversi: lo stato si ricalcola a ogni giro (fino a quattro volte al
     * minuto), il fuoco una volta per tappa.
     */
    private val _focus = MutableStateFlow<NavFocus?>(null)
    val focus: StateFlow<NavFocus?> = _focus

    /**
     * Il piano che si sta seguendo.
     *
     * Lo stato dice cosa fare ADESSO; il piano dice il viaggio intero, che
     * e' quello che si vuole vedere quando si apre la card per capire
     * quanto manca e quanti cambi ci sono.
     */
    private val _plan = MutableStateFlow<NavPlan?>(null)
    val plan: StateFlow<NavPlan?> = _plan

    internal fun publish(s: NavState?) {
        _state.value = s
    }

    internal fun publishFocus(f: NavFocus?) {
        _focus.value = f
    }

    internal fun publishPlan(p: NavPlan?) {
        _plan.value = p
    }

    fun start(context: Context, plan: NavPlan) {
        pendingPlan = plan
        _plan.value = plan
        context.startForegroundService(Intent(context, NavigationService::class.java))
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, NavigationService::class.java))
        _state.value = null
        _focus.value = null
        _plan.value = null
    }
}
