package dev.antigravity.fluidtransit.routing

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Il motore sugli scenari che il piano dice di NON saltare: il cambio, la
 * corsa notturna oltre le 24:00, il ritardo che fa perdere la coincidenza,
 * la cancellazione. Niente query facili centro-citta': quelle non rompono
 * mai.
 *
 * La rete: A, B, C in fila (100 m l'una dall'altra), D a ~7 km.
 * R1: A->B->C (0/120/240 s) alle 08:00, 08:30 e alle 25:30 (la notturna).
 * R2: C->D (0/600 s) alle 08:10 e alle 09:10. Il cambio a C e' in banchina.
 */
class RaptorTest {

    private val feedStart = LocalDate.of(2026, 9, 1)
    private val dayCount = 21
    private val tmp = ArrayList<File>()

    @AfterTest
    fun cleanup() {
        tmp.forEach { it.delete() }
    }

    private val stopIds = listOf("stopA", "stopB", "stopC", "stopD")
    private val stopLats = listOf(43.000000, 43.004500, 43.009000, 43.050000)
    private val stopLons = listOf(11.000000, 11.000000, 11.000000, 11.050000)

    // dep0 in secondi dal giorno di servizio, ordinate per pattern.
    private val r1Deps = intArrayOf(8 * 3600, 8 * 3600 + 1800, 25 * 3600 + 1800)
    private val r2Deps = intArrayOf(8 * 3600 + 600, 9 * 3600 + 600)
    private val tripIds = listOf("R1-0800", "R1-0830", "R1-2530", "R2-0810", "R2-0910")

    private fun writeBundle(): File {
        val file = File.createTempFile("raptor", ".ftb").also { tmp.add(it) }
        val strings = StringTable()
        val nameIdx = stopIds.map { strings.intern("Fermata $it") }
        val w = FtbWriter()

        val stops = ByteBuf().i32(4).i32(0)
        for (i in 0 until 4) {
            stops.i32(Math.round(stopLats[i] * Ftb.COORD_SCALE).toInt())
            stops.i32(Math.round(stopLons[i] * Ftb.COORD_SCALE).toInt())
            stops.i32(nameIdx[i]).i32(0).i32(-1)
            stops.i64(Ftb.hash64(stopIds[i]))
        }
        w.section(Ftb.S_STOPS, stops)

        val stopOrder = (0 until 4).sortedBy { Ftb.hash64(stopIds[it]) }
        val stopIdx = ByteBuf().i32(4).i32(0)
        stopOrder.forEach { stopIdx.i64(Ftb.hash64(stopIds[it])) }
        stopOrder.forEach { stopIdx.i32(it) }
        w.section(Ftb.S_STOP_ID_INDEX, stopIdx)

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

        val routes = ByteBuf().i32(2).i32(0)
        for (r in 0 until 2) {
            routes.i32(strings.intern("${r + 1}")).i32(strings.intern("Linea ${r + 1}"))
                .i32(strings.intern("at - Test")).i32(3).i32(0).i32(0x336699)
                .i64(Ftb.hash64("R${r + 1}"))
        }
        w.section(Ftb.S_ROUTES, routes)

        // PATTERNS: R1 A->B->C (corse 0-2), R2 C->D (corse 3-4).
        val patterns = ByteBuf().i32(2).i32(0)
            .i32(0).i32(0).u16(3).u8(0).u8(0).i32(0).i32(3)
            .i32(1).i32(3).u16(2).u8(0).u8(0).i32(3).i32(2)
        w.section(Ftb.S_PATTERNS, patterns)

        w.section(
            Ftb.S_PATTERN_STOPS,
            ByteBuf().i32(5).i32(0).i32(0).i32(1).i32(2).i32(2).i32(3),
        )

        val trips = ByteBuf().i32(5).i32(0)
        r1Deps.forEach { trips.i32(0).u16(0).u16(0).i32(it).i32(0) }
        r2Deps.forEach { trips.i32(1).u16(0).u16(0).i32(it).i32(1) }
        w.section(Ftb.S_TRIPS, trips)

        // PROFILES: p0 = 0/120/240, p1 = 0/600.
        val profiles = ByteBuf().i32(2).i32(5).i32(0).i32(3).i32(5)
        intArrayOf(0, 120, 240, 0, 600).forEach { profiles.u16(it) }
        w.section(Ftb.S_PROFILES, profiles)

        w.section(Ftb.S_DWELL, ByteBuf().i32(2).i32(0).i32(0).i32(0).i32(0))

        val tripOrder = (0 until 5).sortedBy { Ftb.hash64(tripIds[it]) }
        val tripIdx = ByteBuf().i32(5).i32(0)
        tripOrder.forEach { tripIdx.i64(Ftb.hash64(tripIds[it])) }
        tripOrder.forEach { tripIdx.i32(it) }
        w.section(Ftb.S_TRIP_ID_INDEX, tripIdx)

        // STOP_PATTERNS: A:[0] B:[0] C:[0,1] D:[1].
        w.section(
            Ftb.S_STOP_PATTERNS,
            ByteBuf().i32(4).i32(5)
                .i32(0).i32(1).i32(2).i32(4).i32(5)
                .i32(0).i32(0).i32(0).i32(1).i32(1),
        )

        val bitmapBytes = (dayCount + 7) / 8
        val services = ByteBuf().i32(1).i32(dayCount).i32(bitmapBytes)
        repeat(bitmapBytes) { services.u8(0xff) }
        w.section(Ftb.S_SERVICES, services)

        w.section(
            Ftb.S_TRANSFERS,
            ByteBuf().i32(4).i32(2)
                .i32(0).i32(1).i32(2).i32(2).i32(2)
                .i32(1).u16(600).u16(0)
                .i32(0).u16(600).u16(0),
        )

        w.section(Ftb.S_STRINGS, strings.build())
        w.write(
            file, feedStart, feedStart.plusDays(dayCount - 1L), dayCount,
            r1Deps.last() + 240,
        )
        return file
    }

    private fun epochAt(day: LocalDate, hour: Int, minute: Int): java.time.Instant =
        LocalDateTime.of(day, java.time.LocalTime.of(hour, minute)).atZone(Ftb.ROME).toInstant()

    private val nearA = Raptor.Place(43.000200, 11.000100)
    private val nearB = Raptor.Place(43.004600, 11.000100)
    private val nearC = Raptor.Place(43.009100, 11.000100)
    private val nearD = Raptor.Place(43.050200, 11.050100)

    @Test
    fun `da A a D con il cambio in banchina a C`() {
        BundleReader(writeBundle()).use { r ->
            val raptor = Raptor(r)
            val journeys = raptor.plan(nearA, nearD, epochAt(feedStart, 7, 50))
            assertTrue(journeys.isNotEmpty(), "nessun viaggio trovato")
            val bus = journeys.first { !it.isWalkOnly }
            assertEquals(1, bus.transfers)
            val rides = bus.legs.filterIsInstance<Raptor.Leg.Ride>()
            assertEquals(2, rides.size)
            assertEquals(0, rides[0].route)
            assertEquals(1, rides[1].route)
            // R1 08:00 -> C 08:04, R2 08:10 -> D 08:20.
            val day = Ftb.serviceDayStart(feedStart).epochSecond
            assertEquals(day + 8 * 3600, rides[0].departure.epochSecond)
            assertEquals(day + 8 * 3600 + 600 + 600, rides[1].arrival.epochSecond)
        }
    }

    @Test
    fun `il ritardo della R1 fa perdere la coincidenza delle 08_10`() {
        BundleReader(writeBundle()).use { r ->
            val raptor = Raptor(r)
            // R1 delle 08:00 con 10 minuti di ritardo: arriva a C alle 08:14,
            // la R2 delle 08:10 e' persa, si prende quella delle 09:10.
            val rt = Raptor.Realtime(delayByTrip = mapOf(0 to 600))
            val journeys = raptor.plan(nearA, nearD, epochAt(feedStart, 7, 50), rt)
            val bus = journeys.first { !it.isWalkOnly }
            val rides = bus.legs.filterIsInstance<Raptor.Leg.Ride>()
            val day = Ftb.serviceDayStart(feedStart).epochSecond
            assertEquals(day + 9 * 3600 + 600 + 600, rides.last().arrival.epochSecond)
        }
    }

    @Test
    fun `la corsa cancellata non si sale`() {
        BundleReader(writeBundle()).use { r ->
            val raptor = Raptor(r)
            // La R2 delle 08:10 (trip 3) e' cancellata: si arriva con quella
            // delle 09:10 anche partendo in perfetto orario.
            val rt = Raptor.Realtime(canceledTrips = setOf(3))
            val journeys = raptor.plan(nearA, nearD, epochAt(feedStart, 7, 50), rt)
            val bus = journeys.first { !it.isWalkOnly }
            val rides = bus.legs.filterIsInstance<Raptor.Leg.Ride>()
            val day = Ftb.serviceDayStart(feedStart).epochSecond
            assertEquals(day + 9 * 3600 + 600 + 600, rides.last().arrival.epochSecond)
        }
    }

    @Test
    fun `all_01_15 la notturna di ieri esiste ancora`() {
        BundleReader(writeBundle()).use { r ->
            val raptor = Raptor(r)
            // 01:15 del 2 settembre: la R1 delle "25:30" del giorno di
            // servizio dell'1 parte all'01:30 e arriva a C all'01:34.
            val journeys = raptor.plan(
                nearA, nearC,
                epochAt(feedStart.plusDays(1), 1, 15),
                windowSeconds = 3600,
            )
            val bus = journeys.firstOrNull { !it.isWalkOnly }
            assertTrue(bus != null, "la notturna di ieri e' sparita")
            val ride = bus.legs.filterIsInstance<Raptor.Leg.Ride>().single()
            val day1 = Ftb.serviceDayStart(feedStart).epochSecond
            assertEquals(day1 + 25 * 3600 + 1800, ride.departure.epochSecond)
        }
    }

    @Test
    fun `sotto il chilometro c_e_ anche il solo-a-piedi`() {
        BundleReader(writeBundle()).use { r ->
            val raptor = Raptor(r)
            val journeys = raptor.plan(nearA, nearB, epochAt(feedStart, 7, 50))
            assertTrue(journeys.any { it.isWalkOnly }, "manca la soluzione a piedi")
        }
    }

    @Test
    fun `arriva entro preferisce chi parte piu_ tardi`() {
        BundleReader(writeBundle()).use { r ->
            val raptor = Raptor(r)
            // Devo essere a D per le 09:00: l'unica e' R1 08:00 + R2 08:10.
            val journeys = raptor.planArriveBy(nearA, nearD, epochAt(feedStart, 9, 0))
            val bus = journeys.firstOrNull { !it.isWalkOnly }
            assertTrue(bus != null, "niente soluzioni entro le 09:00")
            val day = Ftb.serviceDayStart(feedStart).epochSecond
            assertEquals(day + 8 * 3600 + 1200, bus.arrival.epochSecond - bus.legs.last().let { (it as Raptor.Leg.Walk).seconds })
        }
    }
}
