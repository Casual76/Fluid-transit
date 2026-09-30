package dev.antigravity.fluidtransit.data.nav

import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.NavAlternatives
import dev.antigravity.fluidtransit.routing.PathIndex
import dev.antigravity.fluidtransit.routing.PatternTopology
import dev.antigravity.fluidtransit.routing.StopGroups

/** Una linea che va bene lo stesso, gia' pronta da scrivere. */
class NavUseful(
    val lineName: String,
    val colorRgb: Int,
    val routeHashHex: String,
)

/**
 * Cosa accendere sulla mappa e di cosa parlare, adesso.
 *
 * E' la traduzione della tappa in corso in tutto quello che serve a
 * disegnarla: la tratta ritagliata, le SUE fermate, la sua linea, le linee
 * che vanno bene lo stesso. Si calcola una volta per tappa e non a ogni
 * giro, perche' dipende solo dal piano e dal bundle — due cose che durante
 * un viaggio non cambiano.
 *
 * Vive nel servizio e non nella schermata perche' i consumatori sono due,
 * la mappa e la card, e nessuno dei due deve rifare il conto dell'altro.
 */
class NavFocus(
    /**
     * Il bundle da cui vengono gli indici qui dentro. Dopo uno scambio degli
     * orari il servizio ricalcola il fuoco al giro dopo; nel frattempo chi
     * usa [trip] contro un altro bundle deve accorgersene da qui.
     */
    val buildId: Long,
    /**
     * Quale tappa del piano e' a fuoco. Durante l'ultima camminata resta
     * quella dell'ultimo bus, e lo stato e' gia' oltre: e' cosi' che la
     * mappa sa che quella tratta e' finita.
     */
    val legIndex: Int,
    /** La corsa, come indice del bundle [buildId]. */
    val trip: Int,
    /** Le posizioni gia' riportate dentro il pattern: vedi [buildNavFocus]. */
    val boardPosition: Int,
    val alightPosition: Int,
    val colorRgb: Int,
    val routeHashHex: String,
    /** Gli hash delle SOLE fermate fra salita e discesa, estremi compresi. */
    val stopHashes: Array<String>,
    /** Gli hash delle linee da tenere accese: la mia e le utili. */
    val usefulRouteHashes: Array<String>,
    val useful: List<NavUseful>,
    /** L'indice della tratta. Null sui bundle senza polilinee. */
    val path: PathIndex?,
    val sBoard: Double,
    val sAlight: Double,
    /** Il ripiego senza polilinee: le fermate in fila. */
    val fallbackLat: DoubleArray,
    val fallbackLon: DoubleArray,
    /** minLat, minLon, maxLat, maxLon della tratta. */
    val bounds: DoubleArray,
    val boardLat: Double,
    val boardLon: Double,
    val alightLat: Double,
    val alightLon: Double,
)

/**
 * Il fuoco della tappa [legIndex] del piano.
 *
 * Decodifica qualche migliaio di varint per la polilinea: **solo fuori dal
 * thread principale**.
 */
fun buildNavFocus(
    reader: BundleReader,
    plan: NavPlan,
    legIndex: Int,
    groups: StopGroups? = null,
): NavFocus? {
    val leg = plan.legs.getOrNull(legIndex) as? NavLeg.Ride ?: return null
    val pattern = leg.pattern
    val n = reader.patternStopCount(pattern)
    if (n <= 0) return null
    val board = leg.boardPosition.coerceIn(0, n - 1)
    val alight = leg.alightPosition.coerceIn(0, n - 1)
    if (alight <= board) return null

    val boardStop = reader.patternStop(pattern, board)
    val alightStop = reader.patternStop(pattern, alight)

    val hashes = ArrayList<String>(alight - board + 1)
    val fallbackLat = DoubleArray(alight - board + 1)
    val fallbackLon = DoubleArray(alight - board + 1)
    for (pos in board..alight) {
        val stop = reader.patternStop(pattern, pos)
        hashes.add(java.lang.Long.toHexString(reader.stopIdHash(stop)))
        fallbackLat[pos - board] = reader.stopLat(stop)
        fallbackLon[pos - board] = reader.stopLon(stop)
    }

    val path = PathIndex.build(reader, pattern)
    val sBoard = path?.stopS?.getOrNull(board) ?: 0.0
    val sAlight = path?.stopS?.getOrNull(alight) ?: 0.0

    // Il riquadro: la tratta ritagliata se c'e', le fermate se no.
    var minLat = 90.0
    var maxLat = -90.0
    var minLon = 180.0
    var maxLon = -180.0
    fun cresci(lat: Double, lon: Double) {
        if (lat < minLat) minLat = lat
        if (lat > maxLat) maxLat = lat
        if (lon < minLon) minLon = lon
        if (lon > maxLon) maxLon = lon
    }
    val tratta = path?.slice(sBoard, sAlight)
    if (tratta != null) {
        for (i in 0 until tratta.size) cresci(tratta.lat[i], tratta.lon[i])
    } else {
        for (i in fallbackLat.indices) cresci(fallbackLat[i], fallbackLon[i])
    }

    val routeHash = java.lang.Long.toHexString(reader.routeIdHash(leg.route))
    val utili = alternatives(reader, groups, boardStop, alightStop, leg.route)
    // La mia linea sta sempre fra quelle accese: la mappa disegna le utili
    // in un layer solo, e restarne fuori vorrebbe dire sparire.
    val accese = LinkedHashSet<String>()
    accese.add(routeHash)
    for (u in utili) accese.add(u.routeHashHex)

    return NavFocus(
        buildId = reader.buildId,
        legIndex = legIndex,
        trip = leg.trip,
        boardPosition = board,
        alightPosition = alight,
        colorRgb = reader.routeDisplayColor(leg.route) and 0xFFFFFF,
        routeHashHex = routeHash,
        stopHashes = hashes.toTypedArray(),
        usefulRouteHashes = accese.toTypedArray(),
        useful = utili,
        path = path,
        sBoard = sBoard,
        sAlight = sAlight,
        fallbackLat = fallbackLat,
        fallbackLon = fallbackLon,
        bounds = doubleArrayOf(minLat, minLon, maxLat, maxLon),
        boardLat = reader.stopLat(boardStop),
        boardLon = reader.stopLon(boardStop),
        alightLat = reader.stopLat(alightStop),
        alightLon = reader.stopLon(alightStop),
    )
}

/**
 * Le linee che partono dalla mia banchina (o da una sorella) e toccano la
 * mia discesa (o una sorella) piu' avanti.
 *
 * Le banchine si espandono qui e non dentro [NavAlternatives] perche' li'
 * non si sa niente di banchine: e' una funzione pura su una topologia, e
 * deve restarlo.
 */
private fun alternatives(
    reader: BundleReader,
    groups: StopGroups?,
    boardStop: Int,
    alightStop: Int,
    mine: Int,
): List<NavUseful> {
    val da = groups?.siblings(boardStop)?.takeIf { it.isNotEmpty() } ?: intArrayOf(boardStop)
    val a = groups?.siblings(alightStop)?.takeIf { it.isNotEmpty() } ?: intArrayOf(alightStop)
    return NavAlternatives.serving(PatternTopology.of(reader), da, a)
        .filter { it.route != mine }
        .map {
            NavUseful(
                lineName = reader.routeShortName(it.route)
                    .ifEmpty { reader.routeLongName(it.route) },
                colorRgb = reader.routeDisplayColor(it.route) and 0xFFFFFF,
                routeHashHex = java.lang.Long.toHexString(reader.routeIdHash(it.route)),
            )
        }
}
