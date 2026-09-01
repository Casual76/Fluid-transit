package dev.antigravity.fluidtransit.routing

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.Normalizer

/**
 * Il file dei luoghi (`luoghi.bin`): il geocoding offline della Toscana,
 * costruito in CI dai POI, vie, localita' e civici di OSM e scaricato
 * accanto al bundle.
 *
 * Due sezioni per i due stadi decisi con l'utente:
 *  - FAST: POI, vie e localita' — la ricerca che risponde subito;
 *  - CIVICI: i numeri civici raggruppati per via — lo stadio lento, che
 *    parte dopo e aggiunge i risultati quando arrivano.
 *
 * Layout (little-endian, header 64 B):
 *   0  u8[4] "FTPL"   4 u16 version=1   6 u16 pad
 *   8  i32 fastCount   12 i32 streetCount   16 i32 civiciCount
 *   20 i32 stringsOff  24 i32 stringsLen
 *   28 i32 fastOff     32 i32 fastLen
 *   36 i32 streetsOff  40 i32 streetsLen
 *   44 i32 civOff      48 i32 civLen
 *
 *   STRINGS: il formato di StringTable (count, blobLen, offsets, blob).
 *   FAST    (24 B): u8 kind (1 localita', 2 POI, 3 via) + 3 pad,
 *                   i32 nameIdx, i32 ctxIdx, i32 lat6, i32 lon6,
 *                   i32 kwIdx (parole-categoria, solo ricerca)
 *   STREETS (24 B): i32 nameIdx, i32 ctxIdx, i32 firstEntry, i32 entryCount,
 *                   i32 lat6, i32 lon6 (il centro della via)
 *   CIVICI  (12 B): i32 numIdx, i32 lat6, i32 lon6
 */
object Places {
    const val MAGIC0 = 'F'.code.toByte()
    const val MAGIC1 = 'T'.code.toByte()
    const val MAGIC2 = 'P'.code.toByte()
    const val MAGIC3 = 'L'.code.toByte()
    const val VERSION = 2

    const val KIND_LOCALITY = 1
    const val KIND_POI = 2
    const val KIND_STREET = 3

    const val FAST_RECORD = 24
    const val STREET_RECORD = 24
    const val CIV_RECORD = 12

    /** Normalizzazione condivisa scrittura/ricerca: minuscole, senza accenti. */
    fun normalize(s: String): String {
        val d = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        val sb = StringBuilder(d.length)
        for (c in d) {
            if (c.code in 0x300..0x36f) continue // segni diacritici combinanti
            sb.append(if (c == '\'' || c == '’') ' ' else c)
        }
        return sb.toString().trim()
    }
}

/** Un record della sezione veloce, per il writer. */
class PlaceEntry(
    val kind: Int,
    val name: String,
    val context: String,
    val lat: Double,
    val lon: Double,
    /** Parole-categoria SOLO per la ricerca ("scuola liceo"): mai mostrate. */
    val keywords: String = "",
)

/** Una via con i suoi civici, per il writer. */
class StreetEntry(
    val name: String,
    val context: String,
    val lat: Double,
    val lon: Double,
    /** coppie (numero civico, lat, lon), gia' ordinate. */
    val numbers: List<Triple<String, Double, Double>>,
)

/** Scrive luoghi.bin. Ordina internamente: l'output e' deterministico. */
object PlacesWriter {

    fun write(out: File, fast: List<PlaceEntry>, streets: List<StreetEntry>) {
        val strings = StringTable()
        val sortedFast = fast.sortedWith(
            compareBy({ it.kind }, { Places.normalize(it.name) }, { it.lat }, { it.lon }),
        )
        val sortedStreets = streets.sortedWith(
            compareBy({ Places.normalize(it.name) }, { Places.normalize(it.context) }),
        )

        val fastBuf = ByteBuf(sortedFast.size * Places.FAST_RECORD)
        for (e in sortedFast) {
            fastBuf.u8(e.kind).u8(0).u16(0)
            fastBuf.i32(strings.intern(e.name))
            fastBuf.i32(strings.intern(e.context))
            fastBuf.i32(Math.round(e.lat * Ftb.COORD_SCALE).toInt())
            fastBuf.i32(Math.round(e.lon * Ftb.COORD_SCALE).toInt())
            fastBuf.i32(strings.intern(e.keywords))
        }

        val streetsBuf = ByteBuf(sortedStreets.size * Places.STREET_RECORD)
        val civBuf = ByteBuf(1 shl 20)
        var entryCount = 0
        for (s in sortedStreets) {
            streetsBuf.i32(strings.intern(s.name))
            streetsBuf.i32(strings.intern(s.context))
            streetsBuf.i32(entryCount)
            streetsBuf.i32(s.numbers.size)
            streetsBuf.i32(Math.round(s.lat * Ftb.COORD_SCALE).toInt())
            streetsBuf.i32(Math.round(s.lon * Ftb.COORD_SCALE).toInt())
            for ((num, la, lo) in s.numbers) {
                civBuf.i32(strings.intern(num))
                civBuf.i32(Math.round(la * Ftb.COORD_SCALE).toInt())
                civBuf.i32(Math.round(lo * Ftb.COORD_SCALE).toInt())
                entryCount++
            }
        }

        val stringsBuf = strings.build()

        val header = ByteBuf(64)
        header.u8(Places.MAGIC0.toInt()).u8(Places.MAGIC1.toInt())
            .u8(Places.MAGIC2.toInt()).u8(Places.MAGIC3.toInt())
        header.u16(Places.VERSION).u16(0)
        header.i32(sortedFast.size).i32(sortedStreets.size).i32(entryCount)
        var off = 64
        header.i32(off).i32(stringsBuf.size); off += align16(stringsBuf.size)
        header.i32(off).i32(fastBuf.size); off += align16(fastBuf.size)
        header.i32(off).i32(streetsBuf.size); off += align16(streetsBuf.size)
        header.i32(off).i32(civBuf.size)
        header.padTo(64)

        out.parentFile?.mkdirs()
        out.outputStream().buffered(1 shl 20).use { o ->
            o.write(header.array, 0, 64)
            for (buf in listOf(stringsBuf, fastBuf, streetsBuf)) {
                o.write(buf.array, 0, buf.size)
                val pad = align16(buf.size) - buf.size
                if (pad > 0) o.write(ByteArray(pad))
            }
            o.write(civBuf.array, 0, civBuf.size)
        }
    }

    private fun align16(v: Int): Int = (v + 15) and 15.inv()
}

/** Lettore mmap di luoghi.bin. Come BundleReader: unmap vero alla close. */
class PlacesReader(file: File) : AutoCloseable {

    private val map: ByteBuffer
    private var closed = false

    val fastCount: Int
    val streetCount: Int
    val civiciCount: Int

    private val stringsOff: Int
    private val fastOff: Int
    private val streetsOff: Int
    private val civOff: Int
    private val stringCount: Int

    init {
        RandomAccessFile(file, "r").use { raf ->
            require(raf.length() <= Int.MAX_VALUE) { "luoghi.bin oltre i 2 GB" }
            map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                .order(ByteOrder.LITTLE_ENDIAN)
        }
        require(
            map.get(0) == Places.MAGIC0 && map.get(1) == Places.MAGIC1 &&
                map.get(2) == Places.MAGIC2 && map.get(3) == Places.MAGIC3,
        ) { "non e' un luoghi.bin" }
        val version = map.getShort(4).toInt() and 0xffff
        require(version == Places.VERSION) { "luoghi.bin versione $version" }
        fastCount = map.getInt(8)
        streetCount = map.getInt(12)
        civiciCount = map.getInt(16)
        stringsOff = map.getInt(20)
        fastOff = map.getInt(28)
        streetsOff = map.getInt(36)
        civOff = map.getInt(44)
        stringCount = map.getInt(stringsOff)
    }

    fun string(index: Int): String {
        require(index in 0 until stringCount) { "stringa $index fuori tabella" }
        val offsetsBase = stringsOff + 8
        val from = map.getInt(offsetsBase + index * 4)
        val to = map.getInt(offsetsBase + (index + 1) * 4)
        val blobBase = offsetsBase + (stringCount + 1) * 4
        val bytes = ByteArray(to - from)
        map.duplicate().position(blobBase + from).get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun fastBase(i: Int) = fastOff + i * Places.FAST_RECORD
    fun fastKind(i: Int): Int = map.get(fastBase(i)).toInt() and 0xff
    fun fastName(i: Int): String = string(map.getInt(fastBase(i) + 4))
    fun fastContext(i: Int): String = string(map.getInt(fastBase(i) + 8))
    fun fastLat(i: Int): Double = map.getInt(fastBase(i) + 12) / Ftb.COORD_SCALE
    fun fastLon(i: Int): Double = map.getInt(fastBase(i) + 16) / Ftb.COORD_SCALE
    fun fastKeywords(i: Int): String = string(map.getInt(fastBase(i) + 20))

    private fun streetBase(s: Int) = streetsOff + s * Places.STREET_RECORD
    fun streetName(s: Int): String = string(map.getInt(streetBase(s)))
    fun streetContext(s: Int): String = string(map.getInt(streetBase(s) + 4))
    fun streetFirstEntry(s: Int): Int = map.getInt(streetBase(s) + 8)
    fun streetEntryCount(s: Int): Int = map.getInt(streetBase(s) + 12)
    fun streetLat(s: Int): Double = map.getInt(streetBase(s) + 16) / Ftb.COORD_SCALE
    fun streetLon(s: Int): Double = map.getInt(streetBase(s) + 20) / Ftb.COORD_SCALE

    private fun civBase(e: Int) = civOff + e * Places.CIV_RECORD
    fun civNumber(e: Int): String = string(map.getInt(civBase(e)))
    fun civLat(e: Int): Double = map.getInt(civBase(e) + 4) / Ftb.COORD_SCALE
    fun civLon(e: Int): Double = map.getInt(civBase(e) + 8) / Ftb.COORD_SCALE

    override fun close() {
        if (closed) return
        closed = true
        BundleReader.unmapQuietly(map)
    }
}

/**
 * La ricerca sui luoghi, nei due stadi decisi.
 *
 * [fast] risponde subito su POI/vie/localita' (l'indice normalizzato si
 * costruisce alla prima chiamata: ~100 ms, da fare fuori dal main).
 * [civici] e' lo stadio lento: capisce "via roma 12 firenze" scandendo le
 * vie e poi i numeri; si chiama da un thread suo e i risultati si
 * aggiungono a quelli gia' mostrati.
 */
class PlacesSearch(private val reader: PlacesReader) {

    class Hit(
        val kind: Int, // Places.KIND_* — 4 = civico
        val name: String,
        val context: String,
        val lat: Double,
        val lon: Double,
        val score: Int,
    )

    private val fastNorm: Array<String> by lazy {
        Array(reader.fastCount) { i ->
            Places.normalize(reader.fastName(i)) + " " +
                Places.normalize(reader.fastContext(i)) + " " + reader.fastKeywords(i)
        }
    }

    private val streetNorm: Array<String> by lazy {
        Array(reader.streetCount) { s ->
            Places.normalize(reader.streetName(s)) + " " + Places.normalize(reader.streetContext(s))
        }
    }

    fun fast(query: String, limit: Int = 8): List<Hit> {
        val tokens = Places.normalize(query).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val out = ArrayList<Hit>(limit * 4)
        for (i in 0 until reader.fastCount) {
            val hay = fastNorm[i]
            var okAll = true
            var score = 0
            for (t in tokens) {
                val at = hay.indexOf(t)
                if (at < 0) {
                    okAll = false
                    break
                }
                // Inizio parola vale piu' di un pezzo in mezzo.
                if (at == 0 || hay[at - 1] == ' ') score += 10 else score += 2
            }
            if (!okAll) continue
            // Le localita' prima dei POI, i POI prima delle vie; i nomi
            // corti (match piu' "pieno") prima dei lunghi.
            score += (4 - reader.fastKind(i)) * 5
            score -= hay.length / 8
            out.add(
                Hit(reader.fastKind(i), reader.fastName(i), reader.fastContext(i),
                    reader.fastLat(i), reader.fastLon(i), score),
            )
        }
        out.sortByDescending { it.score }
        return dedup(out).take(limit)
    }

    /** "via roma 12 firenze": il numero e' il token numerico, il resto e' la via. */
    fun civici(query: String, limit: Int = 6): List<Hit> {
        val tokens = Places.normalize(query).split(' ').filter { it.isNotEmpty() }
        val number = tokens.firstOrNull { it.first().isDigit() } ?: return emptyList()
        val nameTokens = tokens.filter { it != number }
        if (nameTokens.isEmpty()) return emptyList()
        val out = ArrayList<Hit>(limit * 2)
        for (s in 0 until reader.streetCount) {
            val hay = streetNorm[s]
            if (nameTokens.any { !hay.contains(it) }) continue
            val first = reader.streetFirstEntry(s)
            val count = reader.streetEntryCount(s)
            for (e in first until first + count) {
                val num = Places.normalize(reader.civNumber(e))
                if (num == number || (num.startsWith(number) && num.length <= number.length + 1)) {
                    out.add(
                        Hit(
                            kind = 4,
                            name = "${reader.streetName(s)} ${reader.civNumber(e)}",
                            context = reader.streetContext(s),
                            lat = reader.civLat(e),
                            lon = reader.civLon(e),
                            score = if (num == number) 100 else 50,
                        ),
                    )
                }
            }
            if (out.size >= limit * 2) break
        }
        out.sortByDescending { it.score }
        return out.take(limit)
    }

    private fun dedup(hits: List<Hit>): List<Hit> {
        val seen = HashSet<String>()
        val out = ArrayList<Hit>(hits.size)
        for (h in hits) {
            if (seen.add("${h.kind}|${h.name}|${h.context}")) out.add(h)
        }
        return out
    }
}
