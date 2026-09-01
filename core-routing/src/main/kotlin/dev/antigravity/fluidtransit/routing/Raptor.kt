package dev.antigravity.fluidtransit.routing

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * RAPTOR sul bundle mmap: il formato `.ftb` E' gia' la rappresentazione
 * route-oriented dell'algoritmo (pattern con corse ordinate per partenza,
 * CSR fermata->pattern, transfer precalcolati), quindi non c'e' alcuna
 * costruzione all'apertura.
 *
 * Scelte del piano rispettate:
 *  - K = 5 round, criteri Pareto DUE (arrivo, cambi);
 *  - boardSlack 45 s, mai zero: produce coincidenze false;
 *  - il realtime entra NEL round come mappa ritardi immutabile (una corsa
 *    in ritardo parte piu' tardi: si puo' prendere piu' tardi, arriva piu'
 *    tardi); le corse cancellate non si salgono;
 *  - i giorni di servizio candidati sono tre, come in nextDepartures: alle
 *    00:30 la corsa giusta e' quasi sempre quella di ieri alle "24:30".
 *
 * Il profilo ("le prossime ~5 soluzioni") e' una scansione di partenze
 * successive: si calcola l'arrivo piu' presto, si sposta la partenza un
 * minuto dopo quella appena usata, si ripete. Non e' l'rRAPTOR canonico ma
 * produce lo stesso elenco per la UI con un decimo della complessita'; se
 * un giorno serviranno finestre da ore, il loop interno e' gia' isolato.
 *
 * NON thread-safe: lo scratch e' preallocato e riusato fra le query, come
 * chiede il piano (niente boxing, niente GC). Una sola query alla volta.
 */
class Raptor(private val reader: BundleReader) {

    class Options(
        val maxRounds: Int = 5,
        val boardSlackSeconds: Int = 45,
        val walkSpeedMs: Double = 1.1,
        val walkFactor: Double = 1.35,
        /** Raggio di accesso a piedi da luogo a fermate (e viceversa). */
        val accessRadiusM: Double = 700.0,
        val maxAccessStops: Int = 10,
        /** Oltre questo cammino diretto non proponiamo il "solo a piedi". */
        val maxDirectWalkM: Double = 1500.0,
    )

    class Place(val lat: Double, val lon: Double)

    /** I ritardi live: tripIndex -> secondi. Vuota = solo orari programmati. */
    class Realtime(
        val delayByTrip: Map<Int, Int> = emptyMap(),
        val canceledTrips: Set<Int> = emptySet(),
    ) {
        companion object {
            val NONE = Realtime()
        }
    }

    sealed class Leg {
        abstract val departure: Instant
        abstract val arrival: Instant

        /** [fromStop]/[toStop] = -1 quando l'estremo e' un luogo, non una fermata. */
        class Walk(
            val fromStop: Int,
            val toStop: Int,
            val fromLat: Double,
            val fromLon: Double,
            val toLat: Double,
            val toLon: Double,
            val seconds: Int,
            override val departure: Instant,
        ) : Leg() {
            override val arrival: Instant get() = departure.plusSeconds(seconds.toLong())
        }

        class Ride(
            val pattern: Int,
            val trip: Int,
            val route: Int,
            val boardStop: Int,
            val alightStop: Int,
            val boardPosition: Int,
            val alightPosition: Int,
            override val departure: Instant,
            override val arrival: Instant,
            val delaySeconds: Int,
        ) : Leg()
    }

    class Journey(
        val legs: List<Leg>,
        val transfers: Int,
        val walkSeconds: Int,
    ) {
        val departure: Instant get() = legs.first().departure
        val arrival: Instant get() = legs.last().arrival
        val durationSeconds: Long get() = arrival.epochSecond - departure.epochSecond
        val isWalkOnly: Boolean get() = legs.size == 1 && legs[0] is Leg.Walk
    }

    private val options = Options()

    // ------------------------------------------------------------- scratch
    private val n = reader.stopCount
    private val k = options.maxRounds
    // Nascono a INF: uno zero qui significherebbe "gia' raggiunto
    // all'origine dei tempi" e nessuna etichetta migliorerebbe mai.
    private val roundArr = Array(k + 1) { LongArray(n) { INF } }
    private val bestArr = LongArray(n) { INF }
    private val parentKind = Array(k + 1) { ByteArray(n) }
    private val parentTrip = Array(k + 1) { IntArray(n) }
    private val parentBoardPos = Array(k + 1) { IntArray(n) }
    private val parentAlightPos = Array(k + 1) { IntArray(n) }
    private val parentDayStart = Array(k + 1) { LongArray(n) }
    private val parentFromStop = Array(k + 1) { IntArray(n) }
    private val parentWalkSec = Array(k + 1) { IntArray(n) }
    private val marked = IntArray(n)
    private val isMarked = BooleanArray(n)
    private var markedCount = 0
    private val touched = ArrayList<Int>(4096) // celle sporche da ripulire

    private class ServiceDay(val dayIndex: Int, val startEpoch: Long)

    // ------------------------------------------------------------- pubblico

    /**
     * Le prossime soluzioni da [from] a [to] partendo non prima di
     * [departAt]. Ordinate per partenza; non dominate fra loro.
     */
    fun plan(
        from: Place,
        to: Place,
        departAt: Instant,
        realtime: Realtime = Realtime.NONE,
        windowSeconds: Int = 2 * 3600,
        maxJourneys: Int = 5,
    ): List<Journey> {
        val access = walkableStops(from)
        val egress = walkableStops(to)
        val out = ArrayList<Journey>()

        if (access.isNotEmpty() && egress.isNotEmpty()) {
            var t = departAt.epochSecond
            var runs = 0
            val seen = HashSet<Long>()
            while (runs < maxJourneys + 3 && out.size < maxJourneys * 3 &&
                t < departAt.epochSecond + windowSeconds
            ) {
                runs++
                val found = earliestArrival(from, to, access, egress, t, realtime)
                if (found.isEmpty()) break
                var minDep = Long.MAX_VALUE
                for (j in found) {
                    val key = j.departure.epochSecond * 1_000_003 + j.arrival.epochSecond * 31 + j.transfers
                    if (seen.add(key)) out.add(j)
                    minDep = minOf(minDep, j.departure.epochSecond)
                }
                if (minDep == Long.MAX_VALUE) break
                // La prossima scansione parte dopo la partenza appena usata.
                t = minDep + 60
            }
        }

        // Il "solo a piedi", quando ha senso: sotto il chilometro e mezzo
        // la risposta onesta puo' essere che il bus non serve.
        val direct = BundleReader.haversine(from.lat, from.lon, to.lat, to.lon)
        if (direct <= options.maxDirectWalkM) {
            val sec = walkSeconds(direct)
            out.add(
                Journey(
                    legs = listOf(
                        Leg.Walk(-1, -1, from.lat, from.lon, to.lat, to.lon, sec, departAt),
                    ),
                    transfers = 0,
                    walkSeconds = sec,
                ),
            )
        }

        return pareto(out).take(maxJourneys)
    }

    /**
     * "Arriva entro": le soluzioni con l'arrivo entro [arriveBy], preferendo
     * chi parte piu' tardi. Una scansione all'indietro sulla finestra.
     */
    fun planArriveBy(
        from: Place,
        to: Place,
        arriveBy: Instant,
        realtime: Realtime = Realtime.NONE,
        windowSeconds: Int = 3 * 3600,
        maxJourneys: Int = 5,
    ): List<Journey> {
        val all = plan(
            from, to,
            departAt = arriveBy.minusSeconds(windowSeconds.toLong()),
            realtime = realtime,
            windowSeconds = windowSeconds,
            maxJourneys = maxJourneys * 3,
        ).filter { it.arrival <= arriveBy }
        // Chi parte piu' tardi vince; a parita', meno cambi.
        return all
            .sortedWith(compareByDescending<Journey> { it.departure }.thenBy { it.transfers })
            .take(maxJourneys)
            .sortedBy { it.departure }
    }

    // -------------------------------------------------------------- il core

    private class Reached(val stop: Int, val walkSec: Int)

    private fun walkableStops(place: Place): List<Reached> {
        val near = reader.stopsNear(place.lat, place.lon, options.accessRadiusM)
        return near
            .map { s ->
                val d = BundleReader.haversine(
                    place.lat, place.lon, reader.stopLat(s), reader.stopLon(s),
                )
                Reached(s, walkSeconds(d))
            }
            .sortedBy { it.walkSec }
            .take(options.maxAccessStops)
    }

    private fun walkSeconds(meters: Double): Int =
        Math.ceil(meters * options.walkFactor / options.walkSpeedMs).toInt()

    private fun serviceDays(aroundEpoch: Long): List<ServiceDay> {
        val date = Instant.ofEpochSecond(aroundEpoch).atZone(Ftb.ROME).toLocalDate()
        val out = ArrayList<ServiceDay>(3)
        for (offset in -1..1) {
            val d = date.plusDays(offset.toLong())
            val dayIndex = ChronoUnit.DAYS.between(reader.feedStart, d).toInt()
            if (dayIndex < 0 || dayIndex >= reader.dayCount) continue
            out.add(ServiceDay(dayIndex, Ftb.serviceDayStart(d).epochSecond))
        }
        return out
    }

    private companion object {
        const val INF = Long.MAX_VALUE / 4
        const val KIND_NONE = 0.toByte()
        const val KIND_RIDE = 1.toByte()
        const val KIND_WALK = 2.toByte()
    }

        private fun mark(stop: Int) {
        if (!isMarked[stop]) {
            isMarked[stop] = true
            marked[markedCount++] = stop
        }
    }

    /**
     * Una scansione RAPTOR completa. Ritorna al piu' un viaggio per numero
     * di cambi (il fronte Pareto di questa partenza).
     */
    private fun earliestArrival(
        from: Place,
        to: Place,
        access: List<Reached>,
        egress: List<Reached>,
        departEpoch: Long,
        rt: Realtime,
    ): List<Journey> {
        // Lo scratch si ripulisce dalle tracce della query precedente.
        for (i in 0 until markedCount) isMarked[marked[i]] = false
        markedCount = 0
        for (s in touched) {
            bestArr[s] = INF
            for (r in 0..k) {
                roundArr[r][s] = INF
                parentKind[r][s] = KIND_NONE
            }
        }
        touched.clear()

        val days = serviceDays(departEpoch)
        val slack = options.boardSlackSeconds

        for (a in access) {
            val arr = departEpoch + a.walkSec
            touched.add(a.stop)
            roundArr[0][a.stop] = arr
            bestArr[a.stop] = arr
            parentKind[0][a.stop] = KIND_WALK
            parentFromStop[0][a.stop] = -1
            parentWalkSec[0][a.stop] = a.walkSec
            mark(a.stop)
        }

        // La potatura sull'obiettivo: l'arrivo migliore gia' trovato a
        // destinazione taglia i rami che non possono batterlo.
        var bestDest = INF

        val q = HashMap<Int, Int>(256) // pattern -> prima posizione marcata

        for (round in 1..k) {
            q.clear()
            for (i in 0 until markedCount) {
                val stop = marked[i]
                for (p in reader.patternsAtStop(stop)) {
                    val count = reader.patternStopCount(p)
                    var pos = -1
                    for (j in 0 until count - 1) {
                        if (reader.patternStop(p, j) == stop) {
                            pos = j
                            break
                        }
                    }
                    if (pos < 0) continue
                    val cur = q[p]
                    if (cur == null || pos < cur) q[p] = pos
                }
            }
            for (i in 0 until markedCount) isMarked[marked[i]] = false
            markedCount = 0
            if (q.isEmpty()) break

            // --- la fase in vettura ---------------------------------------
            for ((pattern, startPos) in q) {
                val count = reader.patternStopCount(pattern)
                var curTrip = -1
                var curTripBoardPos = 0
                var curDayStart = 0L
                var curDelay = 0
                var curProfile = 0
                var curDep0 = 0
                for (pos in startPos until count) {
                    val stop = reader.patternStop(pattern, pos)
                    if (curTrip >= 0) {
                        val arr = curDayStart + curDep0 +
                            reader.profileOffset(curProfile, pos) + curDelay
                        if (arr < bestArr[stop] && arr < bestDest) {
                            touched.add(stop)
                            roundArr[round][stop] = arr
                            bestArr[stop] = arr
                            parentKind[round][stop] = KIND_RIDE
                            parentTrip[round][stop] = curTrip
                            parentBoardPos[round][stop] = curTripBoardPos
                            parentAlightPos[round][stop] = pos
                            parentDayStart[round][stop] = curDayStart
                            mark(stop)
                        }
                    }
                    // Si puo' salire (o risalire su una corsa migliore)?
                    val label = roundArr[round - 1][stop]
                    if (label < INF && pos < count - 1) {
                        val notBefore = label + slack
                        val curDepHere = if (curTrip >= 0) {
                            curDayStart + curDep0 + reader.profileOffset(curProfile, pos) + curDelay
                        } else {
                            Long.MAX_VALUE
                        }
                        if (notBefore < curDepHere) {
                            val found = earliestBoard(pattern, pos, notBefore, days, rt)
                            if (found != null && found.dep < curDepHere) {
                                curTrip = found.trip
                                curDayStart = found.dayStart
                                curDelay = found.delay
                                curProfile = reader.tripProfile(found.trip)
                                curDep0 = reader.tripDeparture0(found.trip)
                                curTripBoardPos = pos
                            }
                        }
                    }
                }
            }

            // --- la fase a piedi: i transfer precalcolati ------------------
            val improvedNow = markedCount
            for (i in 0 until improvedNow) {
                val stop = marked[i]
                val arr = roundArr[round][stop]
                if (arr >= INF) continue
                for (tr in reader.transfersFrom(stop)) {
                    val t2 = arr + tr.seconds
                    if (t2 < bestArr[tr.targetStop] && t2 < bestDest) {
                        touched.add(tr.targetStop)
                        roundArr[round][tr.targetStop] = t2
                        bestArr[tr.targetStop] = t2
                        parentKind[round][tr.targetStop] = KIND_WALK
                        parentFromStop[round][tr.targetStop] = stop
                        parentWalkSec[round][tr.targetStop] = tr.seconds
                        mark(tr.targetStop)
                    }
                }
            }

            // La potatura si aggiorna a fine round.
            for (e in egress) {
                val arr = roundArr[round][e.stop]
                if (arr < INF) bestDest = minOf(bestDest, arr + e.walkSec)
            }
        }

        // --- estrazione: un viaggio per numero di round che migliora -------
        val out = ArrayList<Journey>(3)
        var prevBest = INF
        for (round in 1..k) {
            var best = INF
            var bestStop = -1
            var bestWalk = 0
            for (e in egress) {
                val arr = roundArr[round][e.stop]
                if (arr < INF && arr + e.walkSec < best) {
                    best = arr + e.walkSec
                    bestStop = e.stop
                    bestWalk = e.walkSec
                }
            }
            if (bestStop < 0 || best >= prevBest) continue
            prevBest = best
            reconstruct(from, to, round, bestStop, bestWalk, rt)?.let { out.add(it) }
        }
        return out
    }

    private class Board(val trip: Int, val dep: Long, val dayStart: Long, val delay: Int)

    /**
     * La prima corsa del [pattern] che si puo' prendere alla posizione
     * [pos] non prima di [notBefore], sui giorni di servizio candidati.
     * Ricalca collectFromPattern: ricerca binaria su dep0 col margine dei
     * 65.535 s, poi scansione in avanti finche' una partenza minore resta
     * possibile.
     */
    private fun earliestBoard(
        pattern: Int,
        pos: Int,
        notBefore: Long,
        days: List<ServiceDay>,
        rt: Realtime,
    ): Board? {
        val first = reader.patternFirstTrip(pattern)
        val count = reader.patternTripCount(pattern)
        if (first < 0 || count == 0) return null
        var best: Board? = null
        for (day in days) {
            val target = notBefore - day.startEpoch
            if (target > reader.maxTripEndSeconds) continue
            val lowerBound = target - 65_535
            var lo = 0
            var hi = count - 1
            var start = count
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (reader.tripDeparture0(first + mid) >= lowerBound) {
                    start = mid
                    hi = mid - 1
                } else {
                    lo = mid + 1
                }
            }
            for (kk in start until count) {
                val trip = first + kk
                val dep0 = reader.tripDeparture0(trip)
                val bestSoFar = best
                // Oltre questo dep0 nessuna corsa puo' battere la migliore.
                if (bestSoFar != null && day.startEpoch + dep0 > bestSoFar.dep) break
                if (trip in rt.canceledTrips) continue
                if (!reader.serviceActive(reader.tripService(trip), day.dayIndex)) continue
                val delay = rt.delayByTrip[trip] ?: 0
                val dep = day.startEpoch + dep0 +
                    reader.profileOffset(reader.tripProfile(trip), pos) + delay
                if (dep < notBefore) continue
                if (bestSoFar == null || dep < bestSoFar.dep) {
                    best = Board(trip, dep, day.startEpoch, delay)
                }
            }
        }
        return best
    }

    private fun reconstruct(
        from: Place,
        to: Place,
        roundIn: Int,
        egressStop: Int,
        egressWalkSec: Int,
        rt: Realtime,
    ): Journey? {
        val legs = ArrayList<Leg>(roundIn * 2 + 2)
        var walkTotal = egressWalkSec
        var cur = egressStop
        var round = roundIn

        legs.add(
            Leg.Walk(
                fromStop = egressStop,
                toStop = -1,
                fromLat = reader.stopLat(egressStop),
                fromLon = reader.stopLon(egressStop),
                toLat = to.lat,
                toLon = to.lon,
                seconds = egressWalkSec,
                departure = Instant.ofEpochSecond(roundArr[roundIn][egressStop]),
            ),
        )

        var rides = 0
        while (round > 0) {
            when (parentKind[round][cur]) {
                KIND_WALK -> {
                    val fromStop = parentFromStop[round][cur]
                    if (fromStop < 0) return null // accesso: non dovrebbe stare qui
                    val sec = parentWalkSec[round][cur]
                    legs.add(
                        Leg.Walk(
                            fromStop = fromStop,
                            toStop = cur,
                            fromLat = reader.stopLat(fromStop),
                            fromLon = reader.stopLon(fromStop),
                            toLat = reader.stopLat(cur),
                            toLon = reader.stopLon(cur),
                            seconds = sec,
                            departure = Instant.ofEpochSecond(roundArr[round][cur] - sec),
                        ),
                    )
                    walkTotal += sec
                    cur = fromStop
                }

                KIND_RIDE -> {
                    val trip = parentTrip[round][cur]
                    val boardPos = parentBoardPos[round][cur]
                    val alightPos = parentAlightPos[round][cur]
                    val dayStart = parentDayStart[round][cur]
                    val pattern = reader.tripPattern(trip)
                    val profile = reader.tripProfile(trip)
                    val dep0 = reader.tripDeparture0(trip)
                    val delay = rt.delayByTrip[trip] ?: 0
                    val boardStop = reader.patternStop(pattern, boardPos)
                    legs.add(
                        Leg.Ride(
                            pattern = pattern,
                            trip = trip,
                            route = reader.patternRoute(pattern),
                            boardStop = boardStop,
                            alightStop = cur,
                            boardPosition = boardPos,
                            alightPosition = alightPos,
                            departure = Instant.ofEpochSecond(
                                dayStart + dep0 + reader.profileOffset(profile, boardPos) + delay,
                            ),
                            arrival = Instant.ofEpochSecond(
                                dayStart + dep0 + reader.profileOffset(profile, alightPos) + delay,
                            ),
                            delaySeconds = delay,
                        ),
                    )
                    rides++
                    cur = boardStop
                    round--
                }

                else -> return null // etichetta orfana: non ricostruibile
            }
        }

        // L'accesso a piedi dal luogo di partenza.
        if (parentKind[0][cur] != KIND_WALK || parentFromStop[0][cur] != -1) return null
        val accessSec = parentWalkSec[0][cur]
        walkTotal += accessSec
        // La camminata parte giusto in tempo per la prima salita: partire
        // prima significherebbe solo aspettare alla fermata.
        val firstRideDep = legs.last().let { (it as? Leg.Ride)?.departure }
        val accessDep = firstRideDep?.minusSeconds(accessSec.toLong() + options.boardSlackSeconds)
            ?: Instant.ofEpochSecond(roundArr[0][cur] - accessSec)
        legs.add(
            Leg.Walk(
                fromStop = -1,
                toStop = cur,
                fromLat = from.lat,
                fromLon = from.lon,
                toLat = reader.stopLat(cur),
                toLon = reader.stopLon(cur),
                seconds = accessSec,
                departure = accessDep,
            ),
        )

        legs.reverse()

        // Camminate consecutive (transfer + uscita, o accesso + transfer) si
        // fondono in una: "8 min a piedi" e' leggibile, "8 min + 0 min" no.
        val merged = ArrayList<Leg>(legs.size)
        for (leg in legs) {
            val prev = merged.lastOrNull()
            if (leg is Leg.Walk && prev is Leg.Walk) {
                merged[merged.size - 1] = Leg.Walk(
                    fromStop = prev.fromStop,
                    toStop = leg.toStop,
                    fromLat = prev.fromLat,
                    fromLon = prev.fromLon,
                    toLat = leg.toLat,
                    toLon = leg.toLon,
                    seconds = prev.seconds + leg.seconds,
                    departure = prev.departure,
                )
            } else {
                merged.add(leg)
            }
        }
        return Journey(legs = merged, transfers = rides - 1, walkSeconds = walkTotal)
    }

    /** Filtra i dominati: parte prima, arriva dopo, con piu' cambi = fuori. */
    private fun pareto(journeys: List<Journey>): List<Journey> {
        val sorted = journeys.sortedWith(
            compareBy({ it.departure }, { it.arrival }, { it.transfers }),
        )
        val out = ArrayList<Journey>(sorted.size)
        for (j in sorted) {
            val dominated = sorted.any { o ->
                o !== j &&
                    o.departure >= j.departure &&
                    o.arrival <= j.arrival &&
                    o.transfers <= j.transfers &&
                    (o.departure > j.departure || o.arrival < j.arrival || o.transfers < j.transfers)
            }
            if (!dominated) out.add(j)
        }
        return out
    }
}
