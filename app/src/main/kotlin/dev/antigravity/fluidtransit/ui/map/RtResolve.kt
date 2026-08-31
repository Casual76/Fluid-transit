package dev.antigravity.fluidtransit.ui.map

import dev.antigravity.fluidtransit.data.rt.RtDelays
import dev.antigravity.fluidtransit.data.rt.RtVehicles
import dev.antigravity.fluidtransit.routing.BundleReader

/**
 * Lo snapshot realtime risolto contro il bundle: hash → indici, indici →
 * colori e categorie. Si calcola su Dispatchers.Default a ogni poll; tutto
 * cio' che la UI e la mappa toccano viene da qui, mai dai byte grezzi.
 */
class ResolvedRt(
    val buses: List<BusRender>,
    /** vehKey → i dettagli che servono al tap e al volo sul bus. */
    val busMetaByKey: Map<Int, BusMeta>,
    /** tripIndex → ritardo in secondi, dal feed trip-updates. */
    val delayByTrip: Map<Int, Int>,
    val canceledTrips: Set<Int>,
    /** tripIndex → vehKey del bus vivo che sta facendo quella corsa. */
    val vehicleByTrip: Map<Int, Int>,
    /** Percentuale di trip_id del feed riconosciuti nel bundle: diagnostica. */
    val resolvedPercent: Int?,
)

class BusMeta(
    val vehKey: Int,
    val tripHash: Long,
    val routeHash: Long,
    val tripIndex: Int,
    val routeIndex: Int,
    val lat: Double,
    val lon: Double,
    val fixAgeSec: Int,
)

/** Il grigio dei bus di cui il bundle non sa niente. */
private const val UNKNOWN_COLOR = 0x8A8A93

fun resolveRt(reader: BundleReader, vehicles: RtVehicles, delays: RtDelays?): ResolvedRt {
    var withTripId = 0
    var resolved = 0

    fun resolveTrip(tripHash: Long, routeHash: Long, direction: Int, startTime: Int): Int {
        if (tripHash != 0L) {
            withTripId++
            val direct = reader.findTripByIdHash(tripHash)
            if (direct >= 0) {
                resolved++
                return direct
            }
        }
        // Il matcher secondario del piano: le due generazioni di dati non
        // sono sincronizzate e i trip_id orfani sono la normalita', non
        // l'eccezione.
        if (routeHash != 0L && direction >= 0 && startTime >= 0) {
            val matched = reader.findTripByRouteAndDeparture(routeHash, direction, startTime)
            if (matched >= 0) return matched
        }
        return -1
    }

    val buses = ArrayList<BusRender>(vehicles.list.size)
    val metaByKey = HashMap<Int, BusMeta>(vehicles.list.size * 2)
    val vehicleByTrip = HashMap<Int, Int>(vehicles.list.size * 2)

    for (v in vehicles.list) {
        // Igiene misurata sul feed vero: ~300 mezzi su 1100 non hanno ne'
        // corsa ne' linea (depositi, fuori servizio) e ~100 hanno un fix
        // piu' vecchio di 10 minuti. Non sono "bus vivi": via.
        if (v.tripHash == 0L && v.routeHash == 0L) continue
        if (v.fixAgeSec > 600) continue
        val tripIndex = resolveTrip(v.tripHash, v.routeHash, v.direction, v.startTimeSec)
        val routeIndex = when {
            tripIndex >= 0 -> reader.patternRoute(reader.tripPattern(tripIndex))
            v.routeHash != 0L -> reader.findRouteByIdHash(v.routeHash)
            else -> -1
        }
        val color = if (routeIndex >= 0) reader.routeDisplayColor(routeIndex) else UNKNOWN_COLOR
        val cat = if (
            routeIndex >= 0 &&
            reader.routeAgency(routeIndex).contains("extraurbano", ignoreCase = true)
        ) {
            "e"
        } else {
            "u"
        }
        val rhHex = java.lang.Long.toHexString(
            if (routeIndex >= 0) reader.routeIdHash(routeIndex) else v.routeHash,
        )
        buses.add(
            BusRender(
                vehKey = v.vehKey,
                lat = v.lat,
                lon = v.lon,
                bearingDeg = v.bearingDeg,
                colorRgb = color,
                cat = cat,
                routeHashHex = rhHex,
                tripHashHex = java.lang.Long.toHexString(v.tripHash),
            ),
        )
        metaByKey[v.vehKey] = BusMeta(
            vehKey = v.vehKey,
            tripHash = v.tripHash,
            routeHash = v.routeHash,
            tripIndex = tripIndex,
            routeIndex = routeIndex,
            lat = v.lat,
            lon = v.lon,
            fixAgeSec = v.fixAgeSec,
        )
        if (tripIndex >= 0) vehicleByTrip[tripIndex] = v.vehKey
    }

    val delayByTrip = HashMap<Int, Int>()
    val canceled = HashSet<Int>()
    if (delays != null) {
        for (d in delays.byTripHash.values) {
            val tripIndex = resolveTrip(d.tripHash, d.routeHash, d.direction, d.startTimeSec)
            if (tripIndex < 0) continue
            if (d.canceled) {
                canceled.add(tripIndex)
            } else if (!d.noData) {
                delayByTrip[tripIndex] = d.delaySec
            }
        }
    }

    return ResolvedRt(
        buses = buses,
        busMetaByKey = metaByKey,
        delayByTrip = delayByTrip,
        canceledTrips = canceled,
        vehicleByTrip = vehicleByTrip,
        resolvedPercent = if (withTripId > 0) resolved * 100 / withTripId else null,
    )
}
