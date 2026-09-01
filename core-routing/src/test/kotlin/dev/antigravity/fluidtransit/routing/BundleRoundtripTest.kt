package dev.antigravity.fluidtransit.routing

import java.io.File
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scrive un bundle sintetico minuscolo e lo rilegge. E' sia il test di
 * roundtrip del formato 2 sia la sua documentazione eseguibile: chi vuole
 * sapere come e' fatta una sezione la trova costruita qui, campo per campo.
 *
 * La rete: quattro fermate (A, B, C vicine in fila; D lontana), una linea,
 * un pattern A->B->C con due corse - una diurna alle 08:00 e una notturna
 * alle 25:30, che e' il caso che le app sbagliano.
 */
class BundleRoundtripTest {

    private val feedStart = LocalDate.of(2026, 9, 1)
    private val dayCount = 21
    private val tmp = ArrayList<File>()

    @AfterTest
    fun cleanup() {
        tmp.forEach { it.delete() }
    }

    // --- la rete sintetica --------------------------------------------------

    private val stopIds = listOf("stopA", "stopB", "stopC", "stopD")
    private val stopNames = listOf("Piazza Alfa", "Via Beta", "Corso Gamma", "Borgo Delta")
    private val stopLats = listOf(43.000000, 43.001000, 43.002000, 43.500000)
    private val stopLons = listOf(11.000000, 11.000000, 11.000000, 11.500000)
    private val routeId = "R1"
    private val tripIds = listOf("R1-morning", "R1-night")
    private val dep0s = listOf(8 * 3600, 25 * 3600 + 30 * 60) // 08:00 e 25:30
    private val profileOffsets = intArrayOf(0, 120, 240)
    private val maxTripEnd = dep0s.max() + profileOffsets.last()

    private fun writeBundle(): File {
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
            .i32(0).i32(0).u16(3).u8(0).u8(0).i32(0).i32(2)
        w.section(Ftb.S_PATTERNS, patterns)

        // PATTERN_STOPS: A, B, C.
        w.section(Ftb.S_PATTERN_STOPS, ByteBuf().i32(3).i32(0).i32(0).i32(1).i32(2))

        // TRIPS (16 B): pattern, service u16, pad u16, dep0, profile. Ordinate per dep0.
        val trips = ByteBuf().i32(2).i32(0)
        for (t in 0 until 2) trips.i32(0).u16(0).u16(0).i32(dep0s[t]).i32(0)
        w.section(Ftb.S_TRIPS, trips)

        // PROFILES: un profilo condiviso [0, 120, 240].
        val profiles = ByteBuf().i32(1).i32(3).i32(0).i32(3)
        profileOffsets.forEach { profiles.u16(it) }
        w.section(Ftb.S_PROFILES, profiles)

        // DWELL: nessuna sosta.
        w.section(Ftb.S_DWELL, ByteBuf().i32(1).i32(0).i32(0).i32(0))

        // TRIP_ID_INDEX: hash ordinati + indici.
        val tripOrder = (0 until 2).sortedBy { Ftb.hash64(tripIds[it]) }
        val tripIdx = ByteBuf().i32(2).i32(0)
        tripOrder.forEach { tripIdx.i64(Ftb.hash64(tripIds[it])) }
        tripOrder.forEach { tripIdx.i32(it) }
        w.section(Ftb.S_TRIP_ID_INDEX, tripIdx)

        // STOP_PATTERNS (CSR): A, B, C -> pattern 0; D -> niente.
        w.section(
            Ftb.S_STOP_PATTERNS,
            ByteBuf().i32(4).i32(3)
                .i32(0).i32(1).i32(2).i32(3).i32(3) // offset per 4 fermate + sentinella
                .i32(0).i32(0).i32(0),
        )

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
        val poly = ByteBuf().i32(1).i32(3).i32(0).i32(blob.size)
        intArrayOf(0, 2, 4).forEach { poly.u16(it) }
        poly.padTo(4)
        poly.bytes(blob.array.copyOf(blob.size))
        w.section(Ftb.S_POLYLINES, poly)

        w.section(Ftb.S_STRINGS, strings.build())
        w.write(file, feedStart, feedStart.plusDays(dayCount - 1L), dayCount, maxTripEnd)
        return file
    }

    @Test
    fun `la polilinea del pattern torna con l'aggancio delle fermate`() {
        BundleReader(writeBundle()).use { r ->
            assertTrue(r.hasPolylines)
            val poly = r.patternPolyline(0)!!
            assertEquals(5, poly.size)
            assertEquals(43.0005, poly.lat[1], 1e-9)
            assertEquals(11.0002, poly.lon[1], 1e-9)
            assertEquals(43.002, poly.lat[4], 1e-9)
            assertEquals(11.0, poly.lon[4], 1e-9)
            assertEquals(0, r.patternStopVertex(0, 0))
            assertEquals(2, r.patternStopVertex(0, 1))
            assertEquals(4, r.patternStopVertex(0, 2))
        }
    }

    // --- i test -------------------------------------------------------------

    @Test
    fun `header e sezioni tornano come scritti`() {
        BundleReader(writeBundle()).use { r ->
            assertEquals(feedStart, r.feedStart)
            assertEquals(dayCount, r.dayCount)
            assertEquals(maxTripEnd, r.maxTripEndSeconds)
            assertEquals(4, r.stopCount)
            assertEquals(1, r.routeCount)
            assertEquals(1, r.patternCount)
            assertEquals(2, r.tripCount)
            assertEquals(1, r.profileCount)
            assertTrue(r.verifyChecksums().isEmpty(), "CRC rotti: ${r.verifyChecksums()}")
        }
    }

    @Test
    fun `il buildId e' deterministico`() {
        val a = BundleReader(writeBundle()).use { it.buildId }
        val b = BundleReader(writeBundle()).use { it.buildId }
        assertEquals(a, b, "due build identici devono avere lo stesso buildId")
    }

    @Test
    fun `fermate, linee e stringhe`() {
        BundleReader(writeBundle()).use { r ->
            assertEquals("Piazza Alfa", r.stopName(0))
            assertEquals("Borgo Delta", r.stopName(3))
            assertEquals(43.001, r.stopLat(1), 1e-6)
            assertEquals("1", r.routeShortName(0))
            assertEquals("at - Test urbano", r.routeAgency(0))
            assertEquals(3, r.routeType(0))
            assertEquals(0x15AC96, r.routeColor(0))
            assertEquals(0x9B6DD6, r.routeDisplayColor(0))
        }
    }

    @Test
    fun `gli hash identificano fermate e linee attraverso i bundle`() {
        BundleReader(writeBundle()).use { r ->
            // Il contratto dei preferiti: quello che salvi oggi risolve domani.
            for (i in 0 until 4) {
                assertEquals(Ftb.hash64(stopIds[i]), r.stopIdHash(i))
                assertEquals(i, r.findStopByIdHash(Ftb.hash64(stopIds[i])))
            }
            assertEquals(-1, r.findStopByIdHash(Ftb.hash64("fermata-inventata")))
            assertEquals(0, r.findRouteByIdHash(Ftb.hash64(routeId)))
            assertEquals(-1, r.findRouteByIdHash(Ftb.hash64("R99")))
        }
    }

    @Test
    fun `trip_id e matcher secondario`() {
        BundleReader(writeBundle()).use { r ->
            assertEquals(0, r.findTripByTripId("R1-morning"))
            assertEquals(1, r.findTripByTripId("R1-night"))
            assertEquals(-1, r.findTripByTripId("R1-mai-esistita"))
            // Il matcher secondario: linea + direzione + orario di partenza.
            assertEquals(0, r.findTripByRouteAndDeparture(Ftb.hash64(routeId), 0, 8 * 3600))
            assertEquals(1, r.findTripByRouteAndDeparture(Ftb.hash64(routeId), 0, dep0s[1]))
            assertEquals(-1, r.findTripByRouteAndDeparture(Ftb.hash64(routeId), 1, 8 * 3600))
            assertEquals(-1, r.findTripByRouteAndDeparture(Ftb.hash64(routeId), 0, 9 * 3600))
        }
    }

    @Test
    fun `la ricerca spaziale trova le vicine e non la lontana`() {
        BundleReader(writeBundle()).use { r ->
            val near = r.stopsNear(43.001, 11.0, 500.0).sorted()
            assertContentEquals(listOf(0, 1, 2), near)
            assertTrue(r.stopsNear(42.0, 10.0, 500.0).isEmpty())
        }
    }

    @Test
    fun `i transfer tornano nei due versi`() {
        BundleReader(writeBundle()).use { r ->
            val fromA = r.transfersFrom(0)
            assertEquals(1, fromA.size)
            assertEquals(1, fromA[0].targetStop)
            assertEquals(100, fromA[0].seconds)
            assertEquals(0, r.transfersFrom(1)[0].targetStop)
            assertTrue(r.transfersFrom(2).isEmpty())
            assertTrue(r.transfersFrom(3).isEmpty())
        }
    }

    @Test
    fun `prossimi passaggi alle 07_50 trovano la corsa delle 08_00`() {
        BundleReader(writeBundle()).use { r ->
            val date = feedStart.plusDays(3)
            val now = Ftb.serviceDayStart(date).plusSeconds(7 * 3600 + 50 * 60)
            val deps = r.nextDepartures(stop = 1, now = now)
            assertEquals(1, deps.size)
            assertEquals(0, deps[0].tripIndex)
            // Alla fermata B (posizione 1) la corsa passa 120 s dopo la partenza.
            assertEquals(
                Ftb.serviceDayStart(date).plusSeconds((8 * 3600 + 120).toLong()),
                deps[0].instant,
            )
            assertEquals("Corso Gamma", r.patternDestination(deps[0].patternIndex))
        }
    }

    @Test
    fun `alle 01_15 la corsa notturna di ieri esiste ancora`() {
        // La corsa parte alle 25:30 del giorno di servizio precedente, cioe'
        // all'01:30 di orologio: chi interroga solo "oggi" la perde, ed e'
        // l'errore per cui certe app dicono che l'ultimo bus non esiste.
        BundleReader(writeBundle()).use { r ->
            val serviceDate = feedStart.plusDays(5)
            val now = Ftb.serviceDayStart(serviceDate).plusSeconds(25 * 3600 + 15 * 60)
            assertEquals(1, now.atZone(Ftb.ROME).hour, "premessa: e' l'una di notte")
            val deps = r.nextDepartures(stop = 0, now = now)
            assertEquals(1, deps.size)
            assertEquals(1, deps[0].tripIndex)
            assertEquals(serviceDate, deps[0].serviceDate, "il giorno di servizio e' ieri")
        }
    }

    @Test
    fun `dopo close il file si puo' cancellare anche su Windows`() {
        val file = writeBundle()
        val r = BundleReader(file)
        r.stopName(0) // tocca davvero la mappa
        r.close()
        assertTrue(file.delete(), "il file e' rimasto bloccato dopo close()")
    }

    @Test
    fun `un CRC rotto viene scoperto alla prima lettura della sezione`() {
        val file = writeBundle()
        // Corrompe un byte dentro la sezione STOPS senza toccare l'header.
        java.io.RandomAccessFile(file, "rw").use { raf ->
            // La prima sezione per id e' STRINGS(1)... troviamo STOPS dalla tabella.
            raf.seek(Ftb.OFF_SECTION_TABLE.toLong())
            var stopsOffset = -1L
            val count = 14 // basta scorrere le voci presenti
            for (i in 0 until count) {
                val base = Ftb.OFF_SECTION_TABLE + i * Ftb.SECTION_ENTRY_SIZE
                raf.seek(base.toLong())
                val id = Integer.reverseBytes(raf.readInt()) // il file e' little-endian
                if (id == Ftb.S_STOPS) {
                    raf.seek((base + 8).toLong())
                    stopsOffset = java.lang.Long.reverseBytes(raf.readLong())
                    break
                }
            }
            assertTrue(stopsOffset > 0, "sezione STOPS non trovata nella tabella")
            raf.seek(stopsOffset + 16)
            raf.writeByte(0x7f)
        }
        val r = BundleReader(file)
        assertEquals(4, runCatching { r.stopCount }.let { result ->
            // La prima lettura della sezione deve fallire con il CRC.
            assertTrue(result.isFailure, "la corruzione non e' stata rilevata")
            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue("STOPS" in message, "messaggio senza nome sezione: $message")
            4
        })
        r.close()
    }
}
