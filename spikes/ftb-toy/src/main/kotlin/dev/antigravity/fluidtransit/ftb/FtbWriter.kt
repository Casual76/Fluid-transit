package dev.antigravity.fluidtransit.ftb

import java.io.File
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.CRC32

/** Buffer little-endian che cresce da solo. Nessun boxing, nessuna copia per elemento. */
class ByteBuf(initial: Int = 1 shl 16) {
    var array = ByteArray(initial)
        private set
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra <= array.size) return
        var cap = array.size
        while (cap < size + extra) cap = cap shl 1
        array = array.copyOf(cap)
    }

    fun u8(v: Int) = apply { ensure(1); array[size++] = v.toByte() }

    fun u16(v: Int) = apply {
        ensure(2)
        array[size++] = v.toByte()
        array[size++] = (v ushr 8).toByte()
    }

    fun i32(v: Int) = apply {
        ensure(4)
        array[size++] = v.toByte()
        array[size++] = (v ushr 8).toByte()
        array[size++] = (v ushr 16).toByte()
        array[size++] = (v ushr 24).toByte()
    }

    fun i64(v: Long) = apply {
        ensure(8)
        var x = v
        repeat(8) {
            array[size++] = x.toByte()
            x = x ushr 8
        }
    }

    fun bytes(b: ByteArray) = apply {
        ensure(b.size)
        System.arraycopy(b, 0, array, size, b.size)
        size += b.size
    }

    /** Riempie fino al multiplo di [align] con zeri. */
    fun padTo(align: Int) = apply {
        val rem = size % align
        if (rem != 0) {
            val pad = align - rem
            ensure(pad)
            java.util.Arrays.fill(array, size, size + pad, 0)
            size += pad
        }
    }

    fun crc32(): Int {
        val c = CRC32()
        c.update(array, 0, size)
        return c.value.toInt()
    }
}

/**
 * Assembla il container. Le sezioni si aggiungono in qualsiasi ordine e
 * vengono scritte ordinate per id, cosi' che due build con lo stesso
 * contenuto producano lo stesso file byte per byte.
 */
class FtbWriter {

    private val sections = LinkedHashMap<Int, ByteArray>()

    fun section(id: Int, buf: ByteBuf) {
        require(id !in sections) { "sezione ${Ftb.SECTION_NAMES[id]} gia' presente" }
        sections[id] = buf.array.copyOf(buf.size)
    }

    fun sizeOf(id: Int): Int = sections[id]?.size ?: 0

    fun write(
        file: File,
        feedStart: LocalDate,
        feedEnd: LocalDate,
        dayCount: Int,
        maxTripEndSeconds: Int,
    ) {
        val ordered = sections.entries.sortedBy { it.key }
        val header = ByteArray(Ftb.HEADER_SIZE)
        val headerBuf = ByteBuf(Ftb.HEADER_SIZE)

        // Gli offset si calcolano prima di scrivere: ogni sezione parte a un
        // multiplo di 4096, cioe' su un confine di pagina.
        var offset = Ftb.HEADER_SIZE.toLong()
        val offsets = LinkedHashMap<Int, Long>()
        for ((id, data) in ordered) {
            offsets[id] = offset
            offset += data.size
            val rem = offset % Ftb.SECTION_ALIGN
            if (rem != 0L) offset += Ftb.SECTION_ALIGN - rem
        }

        // Il buildId e' l'hash del contenuto, non un orario: due build
        // identici devono produrre lo stesso identificatore, altrimenti
        // "pubblica solo se cambiato" non puo' funzionare.
        var buildId = CsvCursor.FNV_OFFSET
        for ((id, data) in ordered) {
            buildId = buildId xor id.toLong()
            buildId *= CsvCursor.FNV_PRIME
            val crc = CRC32().apply { update(data) }.value
            buildId = buildId xor crc
            buildId *= CsvCursor.FNV_PRIME
        }

        headerBuf.i32(Ftb.MAGIC)
        headerBuf.u16(Ftb.FORMAT_VERSION)
        headerBuf.u16(ordered.size)
        headerBuf.i64(buildId)
        headerBuf.i32(Ftb.formatGtfsDate(feedStart))
        headerBuf.i32(Ftb.formatGtfsDate(feedEnd))
        headerBuf.i32(dayCount)
        headerBuf.i32(maxTripEndSeconds)
        require(headerBuf.size == Ftb.OFF_SECTION_TABLE)

        for ((id, data) in ordered) {
            val crc = CRC32().apply { update(data) }
            headerBuf.i32(id)
            headerBuf.i32(0) // flags di sezione: nessuna compressione nello spike
            headerBuf.i64(offsets.getValue(id))
            headerBuf.i32(data.size)
            headerBuf.i32(crc.value.toInt())
        }
        require(headerBuf.size <= Ftb.HEADER_SIZE) {
            "tabella delle sezioni oltre l'header di ${Ftb.HEADER_SIZE} byte"
        }
        System.arraycopy(headerBuf.array, 0, header, 0, headerBuf.size)

        file.parentFile?.mkdirs()
        file.outputStream().buffered(1 shl 20).use { out ->
            out.write(header)
            var written = Ftb.HEADER_SIZE.toLong()
            for ((_, data) in ordered) {
                out.write(data)
                written += data.size
                written += pad(out, written)
            }
        }
    }

    private fun pad(out: OutputStream, written: Long): Long {
        val rem = written % Ftb.SECTION_ALIGN
        if (rem == 0L) return 0
        val pad = Ftb.SECTION_ALIGN - rem
        out.write(ByteArray(pad.toInt()))
        return pad
    }
}

/**
 * Tabella delle stringhe. Deduplica e restituisce indici stabili.
 *
 * Il feed ripete lo stesso nome di fermata su ogni banchina e lo stesso
 * capolinea su ogni corsa: senza deduplica la sezione sarebbe il pezzo piu'
 * grosso del bundle e quasi tutto ripetizione.
 */
class StringTable {
    private val index = HashMap<String, Int>()
    private val values = ArrayList<String>()

    init {
        intern("") // l'indice 0 e' la stringa vuota: vale come "assente"
    }

    fun intern(s: String): Int = index.getOrPut(s) {
        values.add(s)
        values.size - 1
    }

    val size: Int get() = values.size

    fun build(): ByteBuf {
        val blob = ByteBuf(1 shl 20)
        val offsets = IntArray(values.size + 1)
        for (i in values.indices) {
            offsets[i] = blob.size
            blob.bytes(values[i].toByteArray(Charsets.UTF_8))
        }
        offsets[values.size] = blob.size

        val out = ByteBuf(blob.size + offsets.size * 4 + 8)
        out.i32(values.size)
        out.i32(blob.size)
        for (o in offsets) out.i32(o)
        out.bytes(blob.array.copyOf(blob.size))
        return out
    }
}
