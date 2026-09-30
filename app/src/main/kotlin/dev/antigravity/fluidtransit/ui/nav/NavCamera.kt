package dev.antigravity.fluidtransit.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.antigravity.fluidtransit.data.nav.NavFocus
import dev.antigravity.fluidtransit.data.nav.NavState
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.ui.map.FollowMode
import dev.antigravity.fluidtransit.ui.map.TransitMapController
import kotlinx.coroutines.delay

/**
 * Come guarda la mappa, mentre si viaggia.
 *
 * Tre inquadrature, perche' sono tre momenti diversi:
 *
 * - **a piedi** verso la fermata: si segue la posizione, piatta e larga,
 *   con la card in fondo che spinge il puck in basso e mette la fermata in
 *   scena. La bussola della navigazione — 55 gradi, zoom 16.5 — mostra il
 *   marciapiede e nient'altro: una fermata a trecento metri resta fuori.
 * - **in attesa**: non si segue niente. Si inquadra un riquadro che tiene
 *   dentro te, il bus e la fermata, perche' l'unica domanda di quel momento
 *   e' "quanto manca" e la risposta si guarda, non si legge.
 * - **a bordo**: la bussola di prima, che li' e' giusta.
 *
 * L'inquadratura a due punti **non si puo'** fare col `LocationComponent`:
 * vuole `animateCamera`, e quello annulla il tracking. Per questo l'attesa
 * esce dall'inseguimento di proposito, invece di finirci per caso.
 */
@Composable
fun NavCamera(
    controller: TransitMapController,
    state: NavState?,
    focus: NavFocus?,
    myVehKey: Int?,
    locationGranted: Boolean,
    /** L'utente ha spanato la mappa: comanda lui finche' non tocca il tasto. */
    manuale: Boolean,
    onFollow: (FollowMode) -> Unit,
    /** La tappa a fuoco e' gia' fatta: si sta camminando verso la meta. */
    finished: Boolean = false,
) {
    LaunchedEffect(state?.phase, manuale, locationGranted, state != null) {
        val s = state
        if (s == null) {
            // Fuori dalla navigazione la mappa torna a fare quello che
            // faceva: il padding della card se ne va con lei.
            controller.setCameraPadding(0)
            return@LaunchedEffect
        }
        if (manuale) {
            onFollow(FollowMode.FREE)
            return@LaunchedEffect
        }
        onFollow(
            when {
                // Senza posizione l'inseguimento non fa niente, e lo fa in
                // silenzio: meglio il riquadro, che non ha bisogno di sapere
                // dove sei.
                !locationGranted -> FollowMode.FREE
                s.phase == "walk" -> FollowMode.NAV_CAMMINO
                s.phase == "ride" -> FollowMode.COMPASS
                else -> FollowMode.FREE
            },
        )
    }

    // L'attesa: il riquadro che tiene dentro me, il bus e la fermata.
    //
    // `locationGranted` sta fra le chiavi perche' decide `insegue` qui sotto:
    // senza, concedere il permesso a meta' attesa lasciava girare il
    // riquadro sopra l'inseguimento appena partito, e le due camere si
    // contendevano la mappa.
    LaunchedEffect(state?.phase, focus, myVehKey, manuale, locationGranted, finished) {
        val s = state
        val f = focus
        if (manuale || s == null || f == null) return@LaunchedEffect
        if (s.phase == "arrived" || s.phase == "lost") return@LaunchedEffect
        if (finished) {
            // L'ultima camminata: il bus e la fermata di salita non c'entrano
            // piu'. Si inquadrava lo stesso "fermata + bus", e il riquadro
            // inseguiva il mezzo appena lasciato. Con la posizione ci pensa
            // l'inseguimento; senza, si guarda dove si e' scesi.
            if (!locationGranted) controller.flyTo(f.alightLat, f.alightLon, SOLA_FERMATA_ZOOM)
            return@LaunchedEffect
        }
        // Il riquadro entra in scena quando l'inseguimento non puo' fare
        // niente. `applyFollow` senza posizione esce subito e in silenzio:
        // la mappa resta dove capita, che a bordo vuol dire spesso a
        // chilometri dal proprio autobus. L'attesa non insegue mai, perche'
        // li' servono due punti e non uno.
        val insegue = locationGranted && (s.phase == "walk" || s.phase == "ride")
        if (insegue) return@LaunchedEffect

        var busLat = Double.NaN
        var busLon = Double.NaN
        var ultimoVolo = 0L
        while (true) {
            val bus = myVehKey?.let { controller.busPosition(it) }
            val adesso = System.currentTimeMillis()
            // Senza un mezzo in scena non c'e' niente che si muova: si
            // inquadra una volta, e di nuovo solo se il mezzo e' appena
            // sparito. Prima "nessun bus" contava come "il bus si e' mosso",
            // e la camera rivolava sullo stesso riquadro ogni quindici
            // secondi, strappando la mappa a chi la stava guardando.
            val mosso = when {
                bus == null -> ultimoVolo == 0L || !busLat.isNaN()
                busLat.isNaN() -> true
                else -> BundleReader.haversine(busLat, busLon, bus[0], bus[1]) > SPOSTAMENTO_M
            }
            // Al massimo un volo ogni quindici secondi, e solo se il mezzo
            // si e' mosso davvero: una camera che si riaggiusta a ogni giro
            // e' il modo piu' rapido di far chiudere l'app in autobus.
            if (mosso && adesso - ultimoVolo >= RIPOSO_MS) {
                ultimoVolo = adesso
                busLat = bus?.get(0) ?: Double.NaN
                busLon = bus?.get(1) ?: Double.NaN
                inquadra(controller, f, bus, discesa = s.phase == "ride")
            }
            delay(3_000)
        }
    }
}

/**
 * Il riquadro su fermata + mezzo + me, con quello che si sa davvero.
 *
 * @param discesa la fermata di riferimento e' quella dove scendo (a bordo)
 *   invece di quella dove salgo.
 */
private fun inquadra(
    controller: TransitMapController,
    f: NavFocus,
    bus: DoubleArray?,
    discesa: Boolean = false,
) {
    val rifLat = if (discesa) f.alightLat else f.boardLat
    val rifLon = if (discesa) f.alightLon else f.boardLon
    var minLat = rifLat
    var maxLat = rifLat
    var minLon = rifLon
    var maxLon = rifLon
    var punti = 1
    fun cresci(lat: Double, lon: Double) {
        if (lat < minLat) minLat = lat
        if (lat > maxLat) maxLat = lat
        if (lon < minLon) minLon = lon
        if (lon > maxLon) maxLon = lon
        punti++
    }
    bus?.let { cresci(it[0], it[1]) }
    controller.lastLocation()?.let { (lat, lon) -> cresci(lat, lon) }
    if (punti == 1) {
        if (discesa) {
            // A bordo, senza sapere ne' dove sei ne' dov'e' il mezzo: la
            // tratta intera dice piu' della sola fermata d'arrivo, che vista
            // da sola non dice da che parte si arriva.
            controller.flyToBounds(f.bounds[0], f.bounds[1], f.bounds[2], f.bounds[3])
            return
        }
        // Si sa solo dov'e' la fermata: un riquadro di un punto solo non e'
        // un riquadro, e MapLibre ci mette uno zoom da satellite.
        controller.flyTo(rifLat, rifLon, SOLA_FERMATA_ZOOM)
        return
    }
    controller.flyToBounds(minLat, minLon, maxLat, maxLon)
}

/** Sotto questo spostamento del mezzo l'inquadratura non cambia niente. */
private const val SPOSTAMENTO_M = 150.0

/** E comunque non piu' di un volo ogni quindici secondi. */
private const val RIPOSO_MS = 15_000L

private const val SOLA_FERMATA_ZOOM = 16.0
