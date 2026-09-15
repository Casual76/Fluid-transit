package dev.antigravity.fluidtransit.ui.map

import androidx.compose.runtime.saveable.listSaver

/**
 * Cosa mostra il pannello dal basso della mappa.
 *
 * Uno stato solo, non nove superfici: il morphing del vetro e' un cambio di
 * contenuto dentro la stessa forma.
 */
internal sealed interface Panel {
    class Stop(val tap: StopTap) : Panel
    class RouteMini(val routeIndex: Int) : Panel
    class RouteFull(val routeIndex: Int) : Panel
    class TripMini(val ref: TripRef) : Panel
    class TripFull(val ref: TripRef) : Panel
    class Place(val ref: PlaceRef) : Panel
    data object Nearby : Panel
    class Journeys(val to: PlaceRef) : Panel
    class JourneyDetail(val to: PlaceRef, val index: Int) : Panel
}

/**
 * Un luogo, salvato.
 *
 * `savedId` e' un `Long?` e il contenitore non accetta nulli: -1 vuol dire
 * "non e' fra i preferiti", che e' l'unico caso in cui era nullo.
 */
internal fun placeOut(r: PlaceRef): List<Any> =
    listOf(r.name, r.context, r.lat, r.lon, r.savedId ?: -1L)

internal fun placeIn(l: List<Any?>, at: Int): PlaceRef = PlaceRef(
    name = l[at] as String,
    context = l[at + 1] as String,
    lat = l[at + 2] as Double,
    lon = l[at + 3] as Double,
    savedId = (l[at + 4] as Long).takeIf { it >= 0 },
)

internal val PlaceRefSaver = listSaver<PlaceRef?, Any>(
    save = { if (it == null) emptyList() else placeOut(it) },
    restore = { if (it.isEmpty()) null else placeIn(it, 0) },
)

/** Una coppia di coordinate, salvata. */
internal val LatLonSaver = listSaver<Pair<Double, Double>?, Any>(
    save = { if (it == null) emptyList() else listOf(it.first, it.second) },
    restore = { if (it.isEmpty()) null else (it[0] as Double) to (it[1] as Double) },
)

internal fun tripOut(r: TripRef): List<Any> =
    listOf(r.vehKey, r.tripHash, r.routeHash, r.tripIndex, r.routeIndex)

internal fun tripIn(l: List<Any?>, at: Int): TripRef = TripRef(
    vehKey = l[at] as Int,
    tripHash = l[at + 1] as Long,
    routeHash = l[at + 2] as Long,
    tripIndex = l[at + 3] as Int,
    routeIndex = l[at + 4] as Int,
)

/**
 * Il pannello aperto, salvato.
 *
 * Era un `remember` semplice, e si perdeva in tre modi che si vedono tutti:
 * ruotando il telefono, passando a un'altra scheda e tornando indietro, e
 * quando il sistema riprende l'app dopo averla sfrattata dalla memoria. Chi
 * apriva una fermata, dava un'occhiata a Oggi e tornava sulla mappa la
 * trovava vuota, con la fermata da ricercare di nuovo.
 *
 * Una lista vuota vuol dire "niente pannello": il contenitore la tratta come
 * "non salvare", e al ritorno si riparte dal valore iniziale, che e' null.
 *
 * Gli indici del bundle (`routeIndex`, `tripIndex`) valgono solo dentro il
 * bundle che li ha prodotti: il guardiano sul `buildId`, dentro la schermata,
 * butta via il pannello salvato se nel frattempo e' passato l'aggiornamento
 * notturno. Meglio la mappa nuda che la linea sbagliata.
 */
internal val PanelSaver = listSaver<Panel?, Any>(
    save = { p ->
        when (p) {
            null -> emptyList()
            is Panel.Stop -> listOf("stop", p.tap.idHashHex, p.tap.name)
            is Panel.RouteMini -> listOf("rmini", p.routeIndex)
            is Panel.RouteFull -> listOf("rfull", p.routeIndex)
            is Panel.TripMini -> listOf("tmini") + tripOut(p.ref)
            is Panel.TripFull -> listOf("tfull") + tripOut(p.ref)
            is Panel.Place -> listOf("place") + placeOut(p.ref)
            Panel.Nearby -> listOf("nearby")
            is Panel.Journeys -> listOf("journeys") + placeOut(p.to)
            is Panel.JourneyDetail -> listOf("jdetail", p.index) + placeOut(p.to)
        }
    },
    restore = { l ->
        when (l.firstOrNull()) {
            "stop" -> Panel.Stop(StopTap(l[1] as String, l[2] as String))
            "rmini" -> Panel.RouteMini(l[1] as Int)
            "rfull" -> Panel.RouteFull(l[1] as Int)
            "tmini" -> Panel.TripMini(tripIn(l, 1))
            "tfull" -> Panel.TripFull(tripIn(l, 1))
            "place" -> Panel.Place(placeIn(l, 1))
            "nearby" -> Panel.Nearby
            "journeys" -> Panel.Journeys(placeIn(l, 1))
            "jdetail" -> Panel.JourneyDetail(placeIn(l, 2), l[1] as Int)
            else -> null
        }
    },
)
