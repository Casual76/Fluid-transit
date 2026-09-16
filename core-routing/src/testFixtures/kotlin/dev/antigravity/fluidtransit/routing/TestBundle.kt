package dev.antigravity.fluidtransit.routing

import java.io.File
import java.time.LocalDate

/**
 * La rete di prova: quattro fermate, una linea, due corse.
 *
 * A, B e C in fila a un chilometro l'una dall'altra; D lontana, per i test
 * spaziali. Una corsa diurna alle 08:00 e una notturna alle 25:30 — cioe'
 * l'una e mezza del mattino dopo, che e' il caso che le app sbagliano.
 *
 * Sta qui e non dentro un test perche' scrivere un `.ftb` a mano e' lungo, e
 * ogni test che ne ha bisogno ne avrebbe fatto una copia. E' anche la
 * documentazione eseguibile del formato: chi vuole sapere com'e' fatta una
 * sezione la trova costruita qui, campo per campo.
 *
 * Chi la usa passa la lista dei file temporanei da cancellare a fine test.
 */
object TestBundle {

    /** La finestra di validita' del feed finto. */
    val feedStart: LocalDate = LocalDate.of(2026, 9, 1)
    val dayCount = 21

    val stopIds = listOf("stopA", "stopB", "stopC", "stopD")
    val stopNames = listOf("Piazza Alfa", "Via Beta", "Corso Gamma", "Borgo Delta")
    val stopLats = listOf(43.000000, 43.001000, 43.002000, 43.500000)
    val stopLons = listOf(11.000000, 11.000000, 11.000000, 11.500000)
    val routeId = "R1"
    val tripIds = listOf("R1-morning", "R1-night")
    val dep0s = listOf(8 * 3600, 25 * 3600 + 30 * 60) // 08:00 e 25:30
    val profileOffsets = intArrayOf(0, 120, 240)
    val maxTripEnd = dep0s.max() + profileOffsets.last()

    /**
     * Scrive un bundle di prova.
     *
     * Le corse si possono cambiare: alcuni test hanno bisogno di piu' corse
     * ravvicinate sulla stessa fermata — l'ordinamento di un tabellone non si
     * puo' provare con due corse a diciassette ore di distanza. La rete
     * (fermate, linea, pattern, geometria) resta quella.
     */
    fun write(
        tmp: MutableList<File>,
        tripIds: List<String> = this.tripIds,
        dep0s: List<Int> = this.dep0s,
        /**
         * I nomi delle fermate, quando a un test servono le OMONIME.
         *
         * Sulla rete vera le fermate che condividono il nome sono il 53%, e
         * una manciata di cose — il raggruppamento della ricerca, e quindi
         * tutto cio' che indicizza per gruppo invece che per banchina — si
         * comporta in modo diverso solo quando ce ne sono. Con quattro nomi
         * tutti diversi quel ramo non si prova mai, ed e' li' che si era
         * nascosto un difetto vero.
         */
        stopNames: List<String> = this.stopNames,
        /**
         * Le fermate del pattern, quando a un test serve un ANELLO.
         *
         * Una linea che passa due volte dalla stessa fermata esiste sulla
         * rete vera — e il cancello notturno ci ha sbattuto contro — ma con
         * un pattern A, B, C non si prova mai: la fermata ripetuta va
         * dichiarata, e la catena che ne discende (numero di fermate del
         * pattern, profilo degli scostamenti, indice fermata->pattern) si
         * ricava da qui come la ricava il builder.
         */
        patternStops: List<Int> = listOf(0, 1, 2),
    ): File {
        require(tripIds.size == dep0s.size) { "una corsa, un orario" }
        require(stopNames.size == this.stopNames.size) { "la rete ha quattro fermate" }
        require(patternStops.size >= 2) { "un pattern ha almeno due fermate" }
        val n = tripIds.size
        // Due minuti fra una fermata e l'altra, come il profilo di default.
        val offsets = List(patternStops.size) { it * 120 }
        val file = File.createTempFile("roundtrip", ".ftb").also { tmp.add(it) }
        val strings = StringTable()
        val nameIdx = stopNames.map { strings.intern(it) }
        val routeShort = strings.intern("1")
        val routeLong = strings.intern("Alfa - Gamma")
        val agency = strings.intern("at - Test urbano")

        val w = FtbWriter()

        // STOPS: lat, lon, nameIdx, codeIdx, parent, idHash (28 B).
        val stops = ByteBuf().i32(4).i32(0)
        for (i in 0 until 4) {
            stops.i32(Math.round(stopLats[i] * Ftb.COORD_SCALE).toInt())
            stops.i32(Math.round(stopLons[i] * Ftb.COORD_SCALE).toInt())
            stops.i32(nameIdx[i])
            stops.i32(0) // codice assente
            stops.i32(-1) // nessuna fermata padre
            stops.i64(Ftb.hash64(stopIds[i]))
        }
        w.section(Ftb.S_STOPS, stops)

        // STOP_ID_INDEX: hash ordinati + indici paralleli.
        val stopOrder = (0 until 4).sortedBy { Ftb.hash64(stopIds[it]) }
        val stopIdx = ByteBuf().i32(4).i32(0)
        stopOrder.forEach { stopIdx.i64(Ftb.hash64(stopIds[it])) }
        stopOrder.forEach { stopIdx.i32(it) }
        w.section(Ftb.S_STOP_ID_INDEX, stopIdx)

        // STOP_GRID: celle 0,01 gradi -> chiave (latCell<<32 | lonCell).
        val cellSize = (Ftb.GRID_DEGREES * Ftb.COORD_SCALE).toInt()
        val byCell = (0 until 4).groupBy { i ->
            val latCell = Math.floorDiv(Math.round(stopLats[i] * Ftb.COORD_SCALE).toInt(), cellSize)
            val lonCell = Math.floorDiv(Math.round(stopLons[i] * Ftb.COORD_SCALE).toInt(), cellSize)
            (latCell.toLong() shl 32) or (lonCell.toLong() and 0xffffffffL)
        }.toSortedMap()
        val grid = ByteBuf().i32(byCell.size).i32(4)
        byCell.keys.forEach { grid.i64(it) }
        var acc = 0
        grid.i32(0)
        byCell.values.forEach { acc += it.size; grid.i32(acc) }
        byCell.values.forEach { cell -> cell.forEach { grid.i32(it) } }
        w.section(Ftb.S_STOP_GRID, grid)

        // ROUTES: shortName, longName, agency, type, colorFeed, colorDisplay, idHash (32 B).
        val routes = ByteBuf().i32(1).i32(0)
            .i32(routeShort).i32(routeLong).i32(agency)
            .i32(3) // GTFS route_type 3 = bus
            .i32(0x15AC96) // il colore di categoria del feed
            .i32(0x9B6DD6) // il colore di visualizzazione assegnato
            .i64(Ftb.hash64(routeId))
        w.section(Ftb.S_ROUTES, routes)

        // PATTERNS: route, firstStop, stopCount u16, dir u8, pad, firstTrip, tripCount.
        val patterns = ByteBuf().i32(1).i32(0)
            .i32(0).i32(0).u16(patternStops.size).u8(0).u8(0).i32(0).i32(n)
        w.section(Ftb.S_PATTERNS, patterns)

        // PATTERN_STOPS: A, B, C (o l'anello che il test ha chiesto).
        val patternStopsBuf = ByteBuf().i32(patternStops.size).i32(0)
        patternStops.forEach { patternStopsBuf.i32(it) }
        w.section(Ftb.S_PATTERN_STOPS, patternStopsBuf)

        // TRIPS (16 B): pattern, service u16, pad u16, dep0, profile. Ordinate per dep0.
        val trips = ByteBuf().i32(n).i32(0)
        for (t in 0 until n) trips.i32(0).u16(0).u16(0).i32(dep0s[t]).i32(0)
        w.section(Ftb.S_TRIPS, trips)

        // PROFILES: un profilo condiviso [0, 120, 240, ...].
        val profiles = ByteBuf().i32(1).i32(offsets.size).i32(0).i32(offsets.size)
        offsets.forEach { profiles.u16(it) }
        w.section(Ftb.S_PROFILES, profiles)

        // DWELL: nessuna sosta.
        w.section(Ftb.S_DWELL, ByteBuf().i32(1).i32(0).i32(0).i32(0))

        // TRIP_ID_INDEX: hash ordinati + indici.
        val tripOrder = (0 until n).sortedBy { Ftb.hash64(tripIds[it]) }
        val tripIdx = ByteBuf().i32(n).i32(0)
        tripOrder.forEach { tripIdx.i64(Ftb.hash64(tripIds[it])) }
        tripOrder.forEach { tripIdx.i32(it) }
        w.section(Ftb.S_TRIP_ID_INDEX, tripIdx)

        // STOP_PATTERNS (CSR): le fermate del pattern -> pattern 0.
        //
        // Una voce per OGNI passaggio, non per ogni pattern: e' quello che
        // scrive il builder, e su una linea ad anello significa lo stesso
        // pattern scritto due volte sotto la stessa fermata. Chi legge deve
        // reggerlo, quindi il bundle di prova deve poterlo produrre.
        val perStop = List(4) { st -> patternStops.count { it == st } }
        val sp = ByteBuf().i32(4).i32(patternStops.size)
        var accSp = 0
        sp.i32(0)
        perStop.forEach { accSp += it; sp.i32(accSp) }
        perStop.forEach { quante -> repeat(quante) { sp.i32(0) } }
        w.section(Ftb.S_STOP_PATTERNS, sp)

        // SERVICES: un servizio attivo tutti i giorni.
        val bitmapBytes = (dayCount + 7) / 8
        val services = ByteBuf().i32(1).i32(dayCount).i32(bitmapBytes)
        repeat(bitmapBytes) { services.u8(0xff) }
        w.section(Ftb.S_SERVICES, services)

        // TRANSFERS (CSR): A<->B 100 s; C e D senza vicini.
        w.section(
            Ftb.S_TRANSFERS,
            ByteBuf().i32(4).i32(2)
                .i32(0).i32(1).i32(2).i32(2).i32(2)
                .i32(1).u16(100).u16(0) // da A verso B
                .i32(0).u16(100).u16(0), // da B verso A
        )

        // POLYLINES (v4): cinque vertici a zig-zag lungo la linea, con le
        // tre fermate agganciate ai vertici 0, 2 e 4.
        val polyPts = listOf(
            43.000000 to 11.000000,
            43.000500 to 11.000200,
            43.001000 to 11.000000,
            43.001500 to 11.000200,
            43.002000 to 11.000000,
        )
        val blob = ByteBuf()
        var pLat = 0
        var pLon = 0
        for ((la, lo) in polyPts) {
            val il = Math.round(la * Ftb.COORD_SCALE).toInt()
            val io = Math.round(lo * Ftb.COORD_SCALE).toInt()
            blob.varintZigzag(il - pLat)
            blob.varintZigzag(io - pLon)
            pLat = il
            pLon = io
        }
        val poly = ByteBuf().i32(1).i32(patternStops.size).i32(0).i32(blob.size)
        patternStops.forEach { poly.u16(minOf(it * 2, polyPts.size - 1)) }
        poly.padTo(4)
        poly.bytes(blob.array.copyOf(blob.size))
        w.section(Ftb.S_POLYLINES, poly)

        w.section(Ftb.S_STRINGS, strings.build())
        val maxEnd = dep0s.max() + offsets.last()
        w.write(file, feedStart, feedStart.plusDays(dayCount - 1L), dayCount, maxEnd)
        return file
    }
}
