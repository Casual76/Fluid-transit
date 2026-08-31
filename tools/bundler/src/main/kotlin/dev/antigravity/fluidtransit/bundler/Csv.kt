package dev.antigravity.fluidtransit.bundler

import dev.antigravity.fluidtransit.routing.Ftb

import java.io.File
import java.io.InputStream

/**
 * Lettore CSV in streaming, orientato ai byte.
 *
 * `stop_times.txt` sono 282 MB e 5,8 milioni di righe. Un lettore che
 * restituisca `Array<String>` per riga produrrebbe decine di milioni di
 * stringhe di cui quasi nessuna serve: e' il rischio numero 5 del piano
 * (il runner CI ucciso per memoria) nella sua forma piu' banale.
 *
 * Qui la riga resta un array di byte e i campi sono coppie (inizio, fine).
 * Il chiamante decodifica solo cio' che gli serve, e per i campi numerici
 * non decodifica affatto: [int] e [gtfsTime] leggono direttamente dai byte.
 *
 * Copre il sottoinsieme di RFC 4180 che i feed GTFS usano davvero: campi
 * quotati con virgole e virgolette raddoppiate all'interno. I ritorni a capo
 * dentro un campo quotato non sono supportati - non compaiono in questo feed
 * e supportarli costerebbe il buffering di riga.
 */
class CsvCursor(private val stream: InputStream) : AutoCloseable {

    private var buf = ByteArray(1 shl 20)
    private var limit = 0
    private var pos = 0
    private var eof = false

    /** Riga corrente, copiata in un buffer riusato. */
    private var row = ByteArray(4096)
    private var rowLen = 0

    private var fieldStart = IntArray(64)
    private var fieldEnd = IntArray(64)
    private var fieldCount = 0

    /** Nomi di colonna della prima riga, minuscoli e ripuliti dal BOM. */
    lateinit var header: List<String>
        private set

    private val columnIndex = HashMap<String, Int>()

    init {
        require(nextRow()) { "file CSV vuoto" }
        // Il BOM UTF-8 finirebbe attaccato al nome della prima colonna e la
        // lookup fallirebbe in modo silenzioso.
        header = (0 until fieldCount).map { string(it).removePrefix("﻿").trim().lowercase() }
        header.forEachIndexed { i, name -> columnIndex[name] = i }
    }

    /**
     * Indice della colonna, o -1. Le colonne opzionali di GTFS mancano
     * regolarmente: chiedere l'indice una volta sola e poi lavorare per
     * posizione evita una lookup per cella su milioni di righe.
     */
    fun column(name: String): Int = columnIndex[name] ?: -1

    fun requireColumn(name: String): Int =
        columnIndex[name] ?: error("colonna '$name' assente; presenti: $header")

    /** Avanza alla riga successiva. `false` a fine file. */
    fun nextRow(): Boolean {
        rowLen = 0
        fieldCount = 0
        var inQuotes = false
        var start = 0
        var sawAnything = false

        while (true) {
            if (pos >= limit) {
                if (!fill()) break
            }
            val b = buf[pos++]
            sawAnything = true
            when {
                inQuotes && b == QUOTE -> {
                    // Virgoletta raddoppiata: una sola finisce nel campo.
                    if (peek() == QUOTE) {
                        pos++
                        append(QUOTE)
                    } else {
                        inQuotes = false
                    }
                }
                inQuotes -> append(b)
                b == QUOTE -> inQuotes = true
                b == COMMA -> {
                    pushField(start)
                    start = rowLen
                }
                b == CR -> Unit
                b == LF -> {
                    pushField(start)
                    return true
                }
                else -> append(b)
            }
        }

        if (!sawAnything && rowLen == 0 && fieldCount == 0) return false
        pushField(start)
        return fieldCount > 0
    }

    private fun peek(): Byte {
        if (pos >= limit && !fill()) return 0
        return buf[pos]
    }

    private fun fill(): Boolean {
        if (eof) return false
        limit = stream.read(buf)
        pos = 0
        if (limit <= 0) {
            eof = true
            limit = 0
            return false
        }
        return true
    }

    private fun append(b: Byte) {
        if (rowLen == row.size) row = row.copyOf(row.size * 2)
        row[rowLen++] = b
    }

    private fun pushField(start: Int) {
        if (fieldCount == fieldStart.size) {
            fieldStart = fieldStart.copyOf(fieldCount * 2)
            fieldEnd = fieldEnd.copyOf(fieldCount * 2)
        }
        fieldStart[fieldCount] = start
        fieldEnd[fieldCount] = rowLen
        fieldCount++
    }

    fun fieldCount(): Int = fieldCount

    fun isEmpty(i: Int): Boolean = i < 0 || i >= fieldCount || fieldEnd[i] == fieldStart[i]

    fun string(i: Int): String =
        if (i < 0 || i >= fieldCount) "" else String(row, fieldStart[i], fieldEnd[i] - fieldStart[i], Charsets.UTF_8)

    /** Intero senza allocare: i campi numerici di GTFS sono milioni. */
    fun int(i: Int, default: Int = -1): Int {
        if (i < 0 || i >= fieldCount) return default
        var p = fieldStart[i]
        val end = fieldEnd[i]
        if (p == end) return default
        var negative = false
        if (row[p] == MINUS) {
            negative = true
            p++
        }
        var value = 0
        while (p < end) {
            val d = row[p] - ZERO
            if (d < 0 || d > 9) return default
            value = value * 10 + d
            p++
        }
        return if (negative) -value else value
    }

    fun double(i: Int, default: Double = Double.NaN): Double {
        if (isEmpty(i)) return default
        return string(i).toDoubleOrNull() ?: default
    }

    /**
     * `HH:MM:SS` -> secondi dall'inizio del giorno di servizio.
     *
     * Le ore oltre 24 sono legali e frequenti (`25:10:00`): sono corse che
     * appartengono ancora al giorno di servizio precedente. Non vanno mai
     * normalizzate a 24 - farlo e' il modo classico di far sparire l'ultimo
     * autobus della notte dalla app.
     */
    fun gtfsTime(i: Int, default: Int = -1): Int {
        if (isEmpty(i)) return default
        var p = fieldStart[i]
        val end = fieldEnd[i]
        var hours = 0
        var minutes = 0
        var seconds = 0
        var part = 0
        var acc = 0
        var digits = 0
        while (p < end) {
            val c = row[p]
            if (c == COLON) {
                if (digits == 0) return default
                when (part) {
                    0 -> hours = acc
                    1 -> minutes = acc
                    else -> return default
                }
                part++
                acc = 0
                digits = 0
            } else {
                val d = c - ZERO
                if (d < 0 || d > 9) return default
                acc = acc * 10 + d
                digits++
            }
            p++
        }
        if (part != 2 || digits == 0) return default
        seconds = acc
        if (minutes > 59 || seconds > 59) return default
        return hours * 3600 + minutes * 60 + seconds
    }

    /** Byte grezzi del campo, per l'hashing senza decodifica. */
    fun hashField(i: Int, seed: Long = Ftb.FNV_OFFSET): Long {
        if (i < 0 || i >= fieldCount) return seed
        var h = seed
        for (p in fieldStart[i] until fieldEnd[i]) {
            h = h xor (row[p].toLong() and 0xff)
            h *= Ftb.FNV_PRIME
        }
        return h
    }

    /** True se il campo e' byte-per-byte uguale a [other]. */
    fun fieldEquals(i: Int, other: ByteArray): Boolean {
        if (i < 0 || i >= fieldCount) return false
        val len = fieldEnd[i] - fieldStart[i]
        if (len != other.size) return false
        for (k in 0 until len) if (row[fieldStart[i] + k] != other[k]) return false
        return true
    }

    /** Copia dei byte del campo, per usarlo come chiave. */
    fun bytes(i: Int): ByteArray =
        if (i < 0 || i >= fieldCount) ByteArray(0)
        else row.copyOfRange(fieldStart[i], fieldEnd[i])

    override fun close() = stream.close()

    companion object {
        private const val COMMA = ','.code.toByte()
        private const val QUOTE = '"'.code.toByte()
        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()
        private const val COLON = ':'.code.toByte()
        private const val MINUS = '-'.code.toByte()
        private const val ZERO = '0'.code.toByte()


        inline fun <T> open(file: File, block: (CsvCursor) -> T): T =
            CsvCursor(file.inputStream().buffered(1 shl 20)).use(block)
    }
}
