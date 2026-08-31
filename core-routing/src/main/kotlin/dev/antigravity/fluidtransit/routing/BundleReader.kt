package dev.antigravity.fluidtransit.routing

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.zip.CRC32

/**
 * Lettore mmap del container `.ftb` (formato 2).
 *
 * L'apertura non legge dati: mappa il file e legge l'header. Le pagine
 * entrano in memoria quando vengono toccate e le fa uscire il kernel quando
 * serve spazio - non l'heap dell'app, che e' esattamente il motivo per cui il
 * formato esiste al posto di SQLite.
 *
 * Ogni sezione viene verificata (CRC32) alla **prima lettura**, non
 * all'apertura: verificare tutto subito significherebbe leggere l'intero file
 * e vanificare la mmap. Una sezione mai toccata non costa niente; una sezione
 * corrotta viene scoperta prima che un solo valore ne esca.
 *
 * [close] rilascia davvero la mappa quando la JVM lo permette (build, CI,
 * test su desktop): senza, su Windows il file resta bloccato fino alla GC e
 * lo swap atomico del bundle - scarica il nuovo, sostituisci, cancella il
 * vecchio - fallisce in modo intermittente. Su Android il rilascio esplicito
 * non esiste, ma non serve: un file mappato si puo' sostituire comunque.
 * Dopo [close] ogni accesso e' un errore di programmazione: il lettore fa da
 * guardia nel punto di ingresso delle sezioni, non su ogni singola lettura.
 */
class BundleReader(file: File, private val verifyCrcOnFirstUse: Boolean = true) : AutoCloseable {

    private val map: ByteBuffer
    private val fileLength: Long

    val buildId: Long
    val feedStart: LocalDate
    val feedEnd: LocalDate
    val dayCount: Int

    /**
     * Fin dove si estende il giorno di servizio piu' lungo del bundle. Una
     * query deve guardare indietro almeno fin qui, altrimenti perde le corse
     * notturne ancora in viaggio (questo feed arriva a 30:10).
     */
    val maxTripEndSeconds: Int

    private class Section(val offset: Long, val length: Int, val crc: Int)

    private val sections = HashMap<Int, Section>()

    // Indicizzate per id di sezione: l'array evita un lookup di HashMap per
    // ogni accesso a un campo, che con RAPTOR sarebbero milioni per query.
    private val slices = arrayOfNulls<ByteBuffer>(MAX_SECTION_ID + 1)

    @Volatile
    private var closed = false

    init {
        RandomAccessFile(file, "r").use { raf ->
            fileLength = raf.length()
            require(fileLength >= Ftb.HEADER_SIZE) { "file troncato: ${fileLength} byte" }
            map = raf.channel
                .map(FileChannel.MapMode.READ_ONLY, 0, fileLength)
                .order(ByteOrder.LITTLE_ENDIAN)
            // Il canale si puo' chiudere subito: la mappa vive di vita propria.
        }

        require(map.getInt(Ftb.OFF_MAGIC) == Ftb.MAGIC) { "non e' un file .ftb (magic errato)" }
        val version = map.getShort(Ftb.OFF_FORMAT_VERSION).toInt() and 0xffff
        require(version == Ftb.FORMAT_VERSION) {
            "formato .ftb versione $version, questo lettore parla la ${Ftb.FORMAT_VERSION}"
        }
        val count = map.getShort(Ftb.OFF_SECTION_COUNT).toInt() and 0xffff
        buildId = map.getLong(Ftb.OFF_BUILD_ID)
        feedStart = Ftb.parseGtfsDate(map.getInt(Ftb.OFF_FEED_START))
        feedEnd = Ftb.parseGtfsDate(map.getInt(Ftb.OFF_FEED_END))
        dayCount = map.getInt(Ftb.OFF_DAY_COUNT)
        maxTripEndSeconds = map.getInt(Ftb.OFF_MAX_TRIP_END)

        for (i in 0 until count) {
            val base = Ftb.OFF_SECTION_TABLE + i * Ftb.SECTION_ENTRY_SIZE
            val id = map.getInt(base)
            val offset = map.getLong(base + 8)
            val length = map.getInt(base + 16)
            // Il formato dichiara offset a 64 bit; questo lettore mappa il
            // file in un singolo ByteBuffer, che ne regge al massimo 2^31-1.
            // Il limite va dichiarato all'apertura, non scoperto come un
            // troncamento silenzioso a meta' di una sezione.
            require(offset >= 0 && length >= 0 && offset + length <= fileLength) {
                "sezione ${Ftb.SECTION_NAMES[id] ?: id} fuori dal file"
            }
            require(offset + length <= Int.MAX_VALUE) {
                "bundle oltre 2 GB: non supportato da questo lettore"
            }
            require(id in 1..MAX_SECTION_ID) { "id di sezione sconosciuto: $id" }
            sections[id] = Section(offset, length, map.getInt(base + 20))
        }
    }

    /**
     * Slice little-endian di una sezione, con posizione indipendente.
     * Alla prima materializzazione verifica il CRC32 dichiarato nell'header.
     */
    private fun sec(id: Int): ByteBuffer {
        slices[id]?.let { return it }
        check(!closed) { "lettore chiuso" }
        val s = sections[id] ?: error("sezione ${Ftb.SECTION_NAMES[id] ?: id} assente dal bundle")
        val slice = map.duplicate().apply {
            position(s.offset.toInt())
            limit((s.offset + s.length).toInt())
        }.slice().order(ByteOrder.LITTLE_ENDIAN)
        if (verifyCrcOnFirstUse) {
            val bytes = ByteArray(s.length)
            slice.duplicate().get(bytes)
            val actual = CRC32().apply { update(bytes) }.value.toInt()
            check(actual == s.crc) {
                "sezione ${Ftb.SECTION_NAMES[id] ?: id} corrotta (CRC atteso ${s.crc}, letto $actual)"
            }
        }
        slices[id] = slice
        return slice
    }

    fun has(id: Int): Boolean = id in sections

    fun sectionLength(id: Int): Int = sections[id]?.length ?: 0

    /** Verifica esaustiva di tutte le sezioni: per la diagnostica e i gate CI, non per l'apertura. */
    fun verifyChecksums(): List<String> {
        val bad = ArrayList<String>()
        for ((id, s) in sections) {
            val buf = map.duplicate().apply {
                position(s.offset.toInt())
                limit((s.offset + s.length).toInt())
            }
            val bytes = ByteArray(s.length)
            buf.get(bytes)
            val actual = CRC32().apply { update(bytes) }.value.toInt()
            if (actual != s.crc) bad.add(Ftb.SECTION_NAMES[id] ?: "id $id")
        }
        return bad
    }

    // ------------------------------------------------------------- STRINGS

    fun string(index: Int): String {
        if (index <= 0) return ""
        val s = sec(Ftb.S_STRINGS)
        val count = s.getInt(0)
        if (index >= count) return ""
        val offsetsBase = 8
        val blobBase = offsetsBase + (count + 1) * 4
        val from = s.getInt(offsetsBase + index * 4)
        val to = s.getInt(offsetsBase + (index + 1) * 4)
        val bytes = ByteArray(to - from)
        val dup = s.duplicate()
        dup.position(blobBase + from)
        dup.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    // --------------------------------------------------------------- STOPS

    val stopCount: Int get() = sec(Ftb.S_STOPS).getInt(0)

    private fun stopBase(i: Int): Int {
        val count = sec(Ftb.S_STOPS).getInt(0)
        require(i in 0 until count) { "indice fermata fuori dominio: $i su $count" }
        return 8 + i * Ftb.STOP_RECORD
    }

    fun stopLat(i: Int): Double = sec(Ftb.S_STOPS).getInt(stopBase(i)) / Ftb.COORD_SCALE
    fun stopLon(i: Int): Double = sec(Ftb.S_STOPS).getInt(stopBase(i) + 4) / Ftb.COORD_SCALE
    fun stopName(i: Int): String = string(sec(Ftb.S_STOPS).getInt(stopBase(i) + 8))
    fun stopCode(i: Int): String = string(sec(Ftb.S_STOPS).getInt(stopBase(i) + 12))
    fun stopParent(i: Int): Int = sec(Ftb.S_STOPS).getInt(stopBase(i) + 16)

    /**
     * Hash dello `stop_id` del feed: l'identita' stabile della fermata.
     * E' cio' che i preferiti devono ricordare - un indice interno cambia a
     * ogni bundle notturno, questo no.
     */
    fun stopIdHash(i: Int): Long = sec(Ftb.S_STOPS).getLong(stopBase(i) + 20)

    /** Risolve l'hash di uno `stop_id` nell'indice odierno, o -1. */
    fun findStopByIdHash(hash: Long): Int {
        val s = sec(Ftb.S_STOP_ID_INDEX)
        val count = s.getInt(0)
        val hashBase = 8
        val valueBase = hashBase + count * 8
        var lo = 0
        var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = s.getLong(hashBase + mid * 8)
            when {
                v < hash -> lo = mid + 1
                v > hash -> hi = mid - 1
                else -> return s.getInt(valueBase + mid * 4)
            }
        }
        return -1
    }

    // ----------------------------------------------------------- STOP_GRID

    /**
     * Fermate entro [radiusMeters] da un punto, senza R-tree.
     *
     * La griglia a 0,01 gradi e' circa 1,1 km di lato in latitudine, ma a
     * 43 gradi nord un passo di longitudine vale ~810 m: lo span si calcola
     * sull'asse peggiore, cosi' anche i raggi grandi non perdono fermate
     * oltre il bordo est/ovest.
     */
    fun stopsNear(lat: Double, lon: Double, radiusMeters: Double): List<Int> {
        val g = sec(Ftb.S_STOP_GRID)
        val cellCount = g.getInt(0)
        val keysBase = 8
        val startsBase = keysBase + cellCount * 8
        val valuesBase = startsBase + (cellCount + 1) * 4

        val cellSize = (Ftb.GRID_DEGREES * Ftb.COORD_SCALE).toInt()
        val latMicro = Math.round(lat * Ftb.COORD_SCALE).toInt()
        val lonMicro = Math.round(lon * Ftb.COORD_SCALE).toInt()
        val latCell = Math.floorDiv(latMicro, cellSize)
        val lonCell = Math.floorDiv(lonMicro, cellSize)
        // Metri coperti da una cella sull'asse piu' stretto: la longitudine,
        // che si accorcia col coseno della latitudine.
        val metersPerLonCell = 111_320.0 * Ftb.GRID_DEGREES * Math.cos(Math.toRadians(lat))
        val span = Math.ceil(radiusMeters / metersPerLonCell.coerceAtLeast(1.0)).toInt().coerceAtLeast(1)

        val out = ArrayList<Int>()
        for (dLat in -span..span) {
            for (dLon in -span..span) {
                val key = ((latCell + dLat).toLong() shl 32) or ((lonCell + dLon).toLong() and 0xffffffffL)
                val at = binarySearchKeys(g, keysBase, cellCount, key)
                if (at < 0) continue
                val from = g.getInt(startsBase + at * 4)
                val to = g.getInt(startsBase + (at + 1) * 4)
                for (p in from until to) {
                    val stop = g.getInt(valuesBase + p * 4)
                    if (haversine(lat, lon, stopLat(stop), stopLon(stop)) <= radiusMeters) out.add(stop)
                }
            }
        }
        return out
    }

    private fun binarySearchKeys(buf: ByteBuffer, base: Int, count: Int, key: Long): Int {
        var lo = 0
        var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = buf.getLong(base + mid * 8)
            when {
                v < key -> lo = mid + 1
                v > key -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    // -------------------------------------------------------------- ROUTES

    val routeCount: Int get() = sec(Ftb.S_ROUTES).getInt(0)

    private fun routeBase(i: Int): Int {
        val count = sec(Ftb.S_ROUTES).getInt(0)
        require(i in 0 until count) { "indice linea fuori dominio: $i su $count" }
        return 8 + i * Ftb.ROUTE_RECORD
    }

    fun routeShortName(i: Int): String = string(sec(Ftb.S_ROUTES).getInt(routeBase(i)))
    fun routeLongName(i: Int): String = string(sec(Ftb.S_ROUTES).getInt(routeBase(i) + 4))
    fun routeAgency(i: Int): String = string(sec(Ftb.S_ROUTES).getInt(routeBase(i) + 8))
    fun routeType(i: Int): Int = sec(Ftb.S_ROUTES).getInt(routeBase(i) + 12)

    /** Colore dichiarato dal feed come 0xRRGGBB (e' un colore di categoria), o 0. */
    fun routeColor(i: Int): Int = sec(Ftb.S_ROUTES).getInt(routeBase(i) + 16)

    /**
     * Il colore con cui la linea si disegna davvero, come 0xRRGGBB: assegnato
     * dal bundler con la colorazione del grafo di sovrapposizione, identico a
     * quello dell'overlay sulla mappa.
     */
    fun routeDisplayColor(i: Int): Int = sec(Ftb.S_ROUTES).getInt(routeBase(i) + 20)

    /** Hash del `route_id` del feed: identita' stabile della linea. */
    fun routeIdHash(i: Int): Long = sec(Ftb.S_ROUTES).getLong(routeBase(i) + 24)

    /**
     * Risolve l'hash di un `route_id` nell'indice odierno, o -1.
     * Le linee sono poche centinaia: la scansione lineare costa meno
     * dell'indice che eviterebbe.
     */
    fun findRouteByIdHash(hash: Long): Int {
        val count = routeCount
        for (i in 0 until count) if (routeIdHash(i) == hash) return i
        return -1
    }

    // ------------------------------------------------------------ PATTERNS

    val patternCount: Int get() = sec(Ftb.S_PATTERNS).getInt(0)
    private fun patternBase(i: Int) = 8 + i * Ftb.PATTERN_RECORD
    fun patternRoute(p: Int): Int = sec(Ftb.S_PATTERNS).getInt(patternBase(p))
    private fun patternFirstStop(p: Int) = sec(Ftb.S_PATTERNS).getInt(patternBase(p) + 4)
    fun patternStopCount(p: Int): Int = sec(Ftb.S_PATTERNS).getShort(patternBase(p) + 8).toInt() and 0xffff
    fun patternDirection(p: Int): Int = sec(Ftb.S_PATTERNS).get(patternBase(p) + 10).toInt() and 0xff
    fun patternFirstTrip(p: Int): Int = sec(Ftb.S_PATTERNS).getInt(patternBase(p) + 12)
    fun patternTripCount(p: Int): Int = sec(Ftb.S_PATTERNS).getInt(patternBase(p) + 16)

    fun patternStop(p: Int, position: Int): Int =
        sec(Ftb.S_PATTERN_STOPS).getInt(8 + (patternFirstStop(p) + position) * 4)

    /**
     * I pattern di una linea. Costruito pigramente in memoria alla prima
     * richiesta (una passata sui pattern: microsecondi) perche' serve solo al
     * matcher secondario e alla scheda linea, non al percorso caldo.
     */
    private val routePatternsCsr: Pair<IntArray, IntArray> by lazy {
        val nRoutes = routeCount
        val nPatterns = patternCount
        val counts = IntArray(nRoutes + 1)
        for (p in 0 until nPatterns) counts[patternRoute(p) + 1]++
        for (r in 1..nRoutes) counts[r] += counts[r - 1]
        val values = IntArray(nPatterns)
        val cursor = counts.copyOf()
        for (p in 0 until nPatterns) {
            val r = patternRoute(p)
            values[cursor[r]++] = p
        }
        counts to values
    }

    fun patternsOfRoute(route: Int): IntArray {
        val (offsets, values) = routePatternsCsr
        require(route in 0 until offsets.size - 1) { "indice linea fuori dominio: $route" }
        return values.copyOfRange(offsets[route], offsets[route + 1])
    }

    // --------------------------------------------------------------- TRIPS

    val tripCount: Int get() = sec(Ftb.S_TRIPS).getInt(0)
    private fun tripBase(i: Int) = 8 + i * Ftb.TRIP_RECORD
    fun tripPattern(i: Int): Int = sec(Ftb.S_TRIPS).getInt(tripBase(i))
    fun tripService(i: Int): Int = sec(Ftb.S_TRIPS).getShort(tripBase(i) + 4).toInt() and 0xffff
    fun tripDeparture0(i: Int): Int = sec(Ftb.S_TRIPS).getInt(tripBase(i) + 8)
    fun tripProfile(i: Int): Int = sec(Ftb.S_TRIPS).getInt(tripBase(i) + 12)

    /** Direzione della corsa (0/1), ereditata dal suo pattern. */
    fun tripDirection(i: Int): Int = patternDirection(tripPattern(i))

    // ------------------------------------------------------------ PROFILES

    val profileCount: Int get() = sec(Ftb.S_PROFILES).getInt(0)

    /** Scostamento in secondi dalla partenza della corsa, alla posizione data. */
    fun profileOffset(profile: Int, position: Int): Int {
        val s = sec(Ftb.S_PROFILES)
        val count = s.getInt(0)
        val start = s.getInt(8 + profile * 4)
        val valuesBase = 8 + (count + 1) * 4
        return s.getShort(valuesBase + (start + position) * 2).toInt() and 0xffff
    }

    /** Sosta in secondi alla posizione data: `arrivo = partenza - sosta`. */
    fun dwellAt(profile: Int, position: Int): Int {
        if (!has(Ftb.S_DWELL)) return 0
        val s = sec(Ftb.S_DWELL)
        val count = s.getInt(0)
        if (profile >= count) return 0
        val from = s.getInt(8 + profile * 4)
        val to = s.getInt(8 + (profile + 1) * 4)
        val base = 8 + (count + 1) * 4
        for (e in from until to) {
            val pos = s.getShort(base + e * 4).toInt() and 0xffff
            if (pos == position) return s.getShort(base + e * 4 + 2).toInt() and 0xffff
        }
        return 0
    }

    // ------------------------------------------------------------ SERVICES

    val serviceCount: Int get() = sec(Ftb.S_SERVICES).getInt(0)

    fun serviceActive(service: Int, dayIndex: Int): Boolean {
        if (dayIndex < 0 || dayIndex >= dayCount) return false
        val s = sec(Ftb.S_SERVICES)
        val bitmapBytes = s.getInt(8)
        val base = 12 + service * bitmapBytes
        val b = s.get(base + dayIndex / 8).toInt()
        return (b shr (dayIndex % 8)) and 1 == 1
    }

    // ------------------------------------------------------- TRIP_ID_INDEX

    /**
     * Risolve un `trip_id` del feed realtime in un indice di corsa, o -1.
     *
     * Il -1 succede regolarmente: statico e realtime non sono generati
     * insieme. Sopra il 20% di id non risolti si passa al matcher secondario
     * ([findTripByRouteAndDeparture]); questo metodo e' il punto in cui
     * quella percentuale si misura.
     *
     * L'uguaglianza e' sull'hash: il builder garantisce a ogni build che gli
     * id reali non collidono (e fallisce il gate notturno altrimenti), quindi
     * il confronto con la stringa - che il bundle non conserva piu' - non
     * serve.
     */
    fun findTripByTripId(tripId: String): Int = findTripByIdHash(Ftb.hash64(tripId))

    fun findTripByIdHash(hash: Long): Int {
        val s = sec(Ftb.S_TRIP_ID_INDEX)
        val count = s.getInt(0)
        val hashBase = 8
        val valueBase = hashBase + count * 8
        var lo = 0
        var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = s.getLong(hashBase + mid * 8)
            when {
                v < hash -> lo = mid + 1
                v > hash -> hi = mid - 1
                else -> return s.getInt(valueBase + mid * 4)
            }
        }
        return -1
    }

    /**
     * Il matcher secondario del realtime: `(route_id, direction_id,
     * orario di partenza)` -> corsa, quando il `trip_id` non risolve.
     *
     * [dep0Seconds] e' l'orario di partenza al primo stop in secondi dal
     * giorno di servizio (il campo `start_time` di GTFS-RT, gia' convertito).
     * Restituisce -1 se nessuna corsa di quella linea e direzione parte
     * esattamente a quell'ora.
     */
    fun findTripByRouteAndDeparture(routeIdHash: Long, direction: Int, dep0Seconds: Int): Int {
        val route = findRouteByIdHash(routeIdHash)
        if (route < 0) return -1
        for (pattern in patternsOfRoute(route)) {
            if (patternDirection(pattern) != direction) continue
            val first = patternFirstTrip(pattern)
            val count = patternTripCount(pattern)
            // Le corse del pattern sono ordinate per dep0: binaria.
            var lo = 0
            var hi = count - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val v = tripDeparture0(first + mid)
                when {
                    v < dep0Seconds -> lo = mid + 1
                    v > dep0Seconds -> hi = mid - 1
                    else -> return first + mid
                }
            }
        }
        return -1
    }

    // ------------------------------------------------------- STOP_PATTERNS

    fun patternsAtStop(stop: Int): IntArray {
        val s = sec(Ftb.S_STOP_PATTERNS)
        val stops = s.getInt(0)
        if (stop < 0 || stop >= stops) return IntArray(0)
        val from = s.getInt(8 + stop * 4)
        val to = s.getInt(8 + (stop + 1) * 4)
        val valuesBase = 8 + (stops + 1) * 4
        return IntArray(to - from) { s.getInt(valuesBase + (from + it) * 4) }
    }

    // ----------------------------------------------------------- TRANSFERS

    class Transfer(val targetStop: Int, val seconds: Int)

    /**
     * I trasferimenti a piedi precalcolati da una fermata. Vuoto se il bundle
     * non porta la sezione (i bundle di transizione) o se la fermata non ha
     * vicini raggiungibili.
     */
    fun transfersFrom(stop: Int): List<Transfer> {
        if (!has(Ftb.S_TRANSFERS)) return emptyList()
        val s = sec(Ftb.S_TRANSFERS)
        val stops = s.getInt(0)
        if (stop < 0 || stop >= stops) return emptyList()
        val from = s.getInt(8 + stop * 4)
        val to = s.getInt(8 + (stop + 1) * 4)
        val base = 8 + (stops + 1) * 4
        return (from until to).map { e ->
            Transfer(
                targetStop = s.getInt(base + e * Ftb.TRANSFER_RECORD),
                seconds = s.getShort(base + e * Ftb.TRANSFER_RECORD + 4).toInt() and 0xffff,
            )
        }
    }

    // ------------------------------------------------------------- query

    class Departure(
        val tripIndex: Int,
        val patternIndex: Int,
        val routeIndex: Int,
        val serviceDate: LocalDate,
        val instant: Instant,
        val positionInPattern: Int,
    )

    /**
     * I prossimi passaggi da una fermata.
     *
     * I giorni di servizio candidati sono tre, non uno. Alle 00:30 la corsa
     * che passa e' quasi sempre quella del giorno di servizio di *ieri*, che
     * sta viaggiando alle "24:30": interrogare solo oggi e' il motivo per cui
     * certe app sembrano dire che l'ultimo autobus non esiste. E il giorno
     * dopo serve per le query fatte poco prima di mezzanotte.
     */
    fun nextDepartures(
        stop: Int,
        now: Instant,
        limit: Int = 10,
        horizonSeconds: Int = 3 * 3600,
        zone: ZoneId = Ftb.ROME,
    ): List<Departure> {
        val today = now.atZone(zone).toLocalDate()
        val found = ArrayList<Departure>()

        for (offsetDays in -1..1) {
            val date = today.plusDays(offsetDays.toLong())
            val dayIndex = ChronoUnit.DAYS.between(feedStart, date).toInt()
            if (dayIndex < 0 || dayIndex >= dayCount) continue
            val dayStart = Ftb.serviceDayStart(date, zone)
            // Secondi trascorsi dall'inizio del giorno di servizio calcolati
            // fra istanti, non fra orologi: e' cosi' che un giorno di 25 ore
            // resta di 25 ore invece di far scivolare tutto di un'ora.
            val target = now.epochSecond - dayStart.epochSecond
            // Il tetto viene dal bundle, non da una costante: questo feed ha
            // corse fino a 30:10, e un limite scelto a occhio le perderebbe.
            if (target > maxTripEndSeconds) continue // giorno di servizio ormai chiuso

            for (pattern in patternsAtStop(stop)) {
                val stopCountInPattern = patternStopCount(pattern)
                for (position in 0 until stopCountInPattern) {
                    if (patternStop(pattern, position) != stop) continue
                    // L'ultima fermata di un pattern non ha partenze utili.
                    if (position == stopCountInPattern - 1) continue
                    collectFromPattern(
                        pattern, position, dayIndex, date, dayStart,
                        target, horizonSeconds, found,
                    )
                }
            }
        }

        found.sortBy { it.instant }
        return if (found.size > limit) found.subList(0, limit) else found
    }

    private fun collectFromPattern(
        pattern: Int,
        position: Int,
        dayIndex: Int,
        date: LocalDate,
        dayStart: Instant,
        target: Long,
        horizonSeconds: Int,
        out: MutableList<Departure>,
    ) {
        val first = patternFirstTrip(pattern)
        val count = patternTripCount(pattern)
        if (first < 0 || count == 0) return

        // Le corse sono ordinate per partenza, quindi la prima candidata si
        // trova per ricerca binaria. Il limite inferiore tiene conto del fatto
        // che alla posizione `position` la corsa passa fino a 65.535 secondi
        // dopo la propria partenza: senza questo margine si perderebbero le
        // corse gia' partite ma non ancora arrivate qui.
        val lowerBound = target - 65_535
        var lo = 0
        var hi = count - 1
        var start = count
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (tripDeparture0(first + mid) >= lowerBound) {
                start = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }

        for (k in start until count) {
            val trip = first + k
            val dep0 = tripDeparture0(trip)
            if (dep0 > target + horizonSeconds) break
            if (!serviceActive(tripService(trip), dayIndex)) continue
            val departure = dep0 + profileOffset(tripProfile(trip), position)
            if (departure < target || departure > target + horizonSeconds) continue
            out.add(
                Departure(
                    tripIndex = trip,
                    patternIndex = pattern,
                    routeIndex = patternRoute(pattern),
                    serviceDate = date,
                    instant = dayStart.plusSeconds(departure.toLong()),
                    positionInPattern = position,
                )
            )
        }
    }

    /** Capolinea del pattern: e' la destinazione da mostrare accanto alla linea. */
    fun patternDestination(pattern: Int): String =
        stopName(patternStop(pattern, patternStopCount(pattern) - 1))

    override fun close() {
        if (closed) return
        closed = true
        java.util.Arrays.fill(slices, null)
        unmap(map)
    }

    companion object {
        /** Id di sezione piu' alto che questo lettore conosce. */
        private const val MAX_SECTION_ID = Ftb.S_STOP_ID_INDEX

        private const val EARTH_RADIUS_M = 6_371_000.0

        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
            return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)))
        }

        /**
         * Rilascio esplicito della mappa, dove la JVM lo offre.
         *
         * Sul JDK desktop (build, CI, test) `sun.misc.Unsafe.invokeCleaner`
         * esiste dal 9 in poi e libera il file immediatamente - senza, su
         * Windows il file resta bloccato fino alla GC e la sostituzione del
         * bundle fallisce a caso. Su Android il metodo non c'e', e non e' un
         * problema: il filesystem e' POSIX e un file mappato si puo'
         * sostituire o cancellare comunque. Il fallimento silenzioso e'
         * quindi la semantica giusta, non una scorciatoia.
         */
        private fun unmap(buffer: ByteBuffer) {
            if (!buffer.isDirect) return
            try {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val theUnsafe = unsafeClass.getDeclaredField("theUnsafe")
                    .apply { isAccessible = true }
                    .get(null)
                unsafeClass.getMethod("invokeCleaner", ByteBuffer::class.java)
                    .invoke(theUnsafe, buffer)
            } catch (_: Throwable) {
                // ART o JVM blindata: se ne occupa il GC.
            }
        }
    }
}
