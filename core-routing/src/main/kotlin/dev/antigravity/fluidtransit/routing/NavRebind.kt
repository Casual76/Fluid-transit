package dev.antigravity.fluidtransit.routing

import kotlin.math.abs

/**
 * Ritrovare una tappa in vettura dentro un bundle nuovo.
 *
 * La navigazione segue numeri puri — indice della corsa, del pattern, posizione
 * di salita e di discesa — e quei numeri sono indici dentro un file che si
 * scambia di notte. Continuare a seguirli dopo lo scambio vuol dire annunciare
 * la fermata di un'altra linea: "Scendi a" una fermata che il tuo bus non
 * tocca. E per via dei ritardi con cui GitHub fa partire il job, a fine
 * settembre 2026 lo scambio arrivava verso mezzogiorno, cioe' mentre la gente
 * viaggia.
 *
 * Quello che sopravvive alla notte sono gli hash: del `trip_id` e dello
 * `stop_id` delle due fermate, la stessa regola dei preferiti. Qui si
 * ritrovano. Se la corsa non c'e' piu', o non tocca piu' le due fermate
 * nell'ordine giusto, la risposta e' null e chi chiama deve dirlo — non
 * tirare a indovinare.
 */
object NavRebind {

    /** La tappa, con gli indici del bundle nuovo. */
    class Ride(
        val trip: Int,
        val pattern: Int,
        val route: Int,
        val boardPosition: Int,
        val alightPosition: Int,
        val dep0: Int,
        val profile: Int,
    )

    /**
     * @param boardPosition la posizione di salita nel bundle VECCHIO: sugli
     *   anelli la fermata compare due volte, e fra le due si sceglie quella
     *   piu' vicina a dov'era.
     */
    fun ride(
        reader: BundleReader,
        tripHash: Long,
        boardStopHash: Long,
        alightStopHash: Long,
        boardPosition: Int,
        alightPosition: Int,
    ): Ride? {
        if (tripHash == 0L || boardStopHash == 0L || alightStopHash == 0L) return null
        val trip = reader.findTripByIdHash(tripHash)
        if (trip < 0) return null
        val pattern = reader.tripPattern(trip)
        val n = reader.patternStopCount(pattern)
        val hashes = LongArray(n) { reader.stopIdHash(reader.patternStop(pattern, it)) }

        val board = nearest(hashes, boardStopHash, 0, boardPosition) ?: return null
        val alight = nearest(hashes, alightStopHash, board + 1, alightPosition) ?: return null
        return Ride(
            trip = trip,
            pattern = pattern,
            route = reader.patternRoute(pattern),
            boardPosition = board,
            alightPosition = alight,
            dep0 = reader.tripDeparture0(trip),
            profile = reader.tripProfile(trip),
        )
    }

    /** La posizione da [from] in poi con quell'hash, la piu' vicina a [near]. */
    internal fun nearest(hashes: LongArray, hash: Long, from: Int, near: Int): Int? {
        var best = -1
        for (pos in from.coerceAtLeast(0) until hashes.size) {
            if (hashes[pos] != hash) continue
            if (best < 0 || abs(pos - near) < abs(best - near)) best = pos
        }
        return best.takeIf { it >= 0 }
    }
}
