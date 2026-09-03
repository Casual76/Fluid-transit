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

/**
 * La chiave con cui un mezzo resta LO STESSO mezzo fra due snapshot.
 *
 * Il feed non mette `vehicle.id` su tutti i veicoli, e fino alla Fase 8
 * quelli senza finivano tutti sulla chiave 0: un solo marker, che a ogni
 * poll rimbalzava da un capo all'altro della Toscana. Senza id l'identita'
 * migliore che abbiamo e' la corsa che il mezzo sta facendo.
 */
private fun vehicleKey(v: dev.antigravity.fluidtransit.data.rt.RtVehicle): Int {
    if (v.vehKey != 0) return v.vehKey
    var h = v.tripHash
    if (h == 0L) h = v.routeHash * 31 + v.startTimeSec
    val mixed = (h xor (h ushr 32)).toInt()
    return if (mixed == 0) 1 else mixed
}

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
        val patternIndex = if (tripIndex >= 0) reader.tripPattern(tripIndex) else -1
        val routeIndex = when {
            patternIndex >= 0 -> reader.patternRoute(patternIndex)
            v.routeHash != 0L -> reader.findRouteByIdHash(v.routeHash)
            else -> -1
        }
        val key = vehicleKey(v)
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
                vehKey = key,
                lat = v.lat,
                lon = v.lon,
                bearingDeg = v.bearingDeg,
                colorRgb = color,
                cat = cat,
                routeHashHex = rhHex,
                tripHashHex = java.lang.Long.toHexString(v.tripHash),
                patternIndex = patternIndex,
                speedMs = v.speedMs,
                fixAgeSec = v.fixAgeSec,
            ),
        )
        metaByKey[key] = BusMeta(
            vehKey = key,
            tripHash = v.tripHash,
            routeHash = v.routeHash,
            tripIndex = tripIndex,
            routeIndex = routeIndex,
            lat = v.lat,
            lon = v.lon,
            fixAgeSec = v.fixAgeSec,
        )
        if (tripIndex >= 0) vehicleByTrip[tripIndex] = key
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
