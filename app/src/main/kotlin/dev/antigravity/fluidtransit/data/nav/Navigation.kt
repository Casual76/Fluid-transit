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
    ) : NavLeg()
}

/** Cosa mostrare ADESSO: lo stato vivo che mini e notifica leggono. */
class NavState(
    val kind: String,
    val destName: String,
    val phase: String, // walk | wait | ride | arrived
    val headline: String, // "Scendi a TORRE GALLI"
    val detail: String, // "4 fermate · 12 min"
    val stopsRemaining: Int,
    val totalStops: Int,
    val etaEpoch: Long,
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

    internal fun publish(s: NavState?) {
        _state.value = s
    }

    fun start(context: Context, plan: NavPlan) {
        pendingPlan = plan
        context.startForegroundService(Intent(context, NavigationService::class.java))
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, NavigationService::class.java))
        _state.value = null
    }
}
