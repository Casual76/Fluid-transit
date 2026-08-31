package dev.antigravity.fluidtransit.ftb

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * Legge l'header di un archivio PMTiles v3 e ne stampa i metadati.
 *
 * Serve allo spike 2: per scrivere uno stile MapLibre che disegni qualcosa
 * bisogna sapere come si chiamano i layer dentro le tile, e indovinarli
 * produce una mappa vuota indistinguibile da un PMTiles che non funziona.
 * Cosi' il fallimento, se arriva, e' informativo.
 *
 * L'header e' 127 byte a offset 0, tutto little-endian. Qui si leggono solo
 * i campi che servono; il resto e' saltato.
 */
fun main(args: Array<String>) {
    val file = File(args.getOrElse(0) { "../pmtiles-android/work/firenze.pmtiles" })
    require(file.isFile) { "file non trovato: ${file.absolutePath}" }

    val header = ByteArray(127)
    file.inputStream().use { it.read(header) }
    val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

    val magic = String(header, 0, 7, Charsets.US_ASCII)
    require(magic == "PMTiles") { "non e' un PMTiles: '$magic'" }
    val specVersion = header[7].toInt()

    val rootOffset = b.getLong(8)
    val rootLength = b.getLong(16)
    val metadataOffset = b.getLong(24)
    val metadataLength = b.getLong(32)
    val leafOffset = b.getLong(40)
    val leafLength = b.getLong(48)
    val tileDataOffset = b.getLong(56)
    val tileDataLength = b.getLong(64)
    val addressedTiles = b.getLong(72)
    val tileEntries = b.getLong(80)
    val tileContents = b.getLong(88)
    val clustered = header[96].toInt() == 1
    val internalCompression = header[97].toInt()
    val tileCompression = header[98].toInt()
    val tileType = header[99].toInt()
    val minZoom = header[100].toInt()
    val maxZoom = header[101].toInt()
    val minLonE7 = b.getInt(102)
    val minLatE7 = b.getInt(106)
    val maxLonE7 = b.getInt(110)
    val maxLatE7 = b.getInt(114)
    val centerZoom = header[118].toInt()
    val centerLonE7 = b.getInt(119)
    val centerLatE7 = b.getInt(123)

    fun compression(v: Int) = when (v) {
        1 -> "nessuna"; 2 -> "gzip"; 3 -> "brotli"; 4 -> "zstd"; else -> "sconosciuta ($v)"
    }
    fun type(v: Int) = when (v) {
        1 -> "mvt (vector tile)"; 2 -> "png"; 3 -> "jpeg"; 4 -> "webp"; 5 -> "avif"
        else -> "sconosciuto ($v)"
    }

    println("file            ${file.name}  (${file.length()} byte)")
    println("spec            v$specVersion")
    println("tipo tile       ${type(tileType)}")
    println("compressione    tile ${compression(tileCompression)}, directory ${compression(internalCompression)}")
    println("zoom            $minZoom .. $maxZoom (centro z$centerZoom)")
    println("bbox            ${minLatE7 / 1e7}, ${minLonE7 / 1e7}  ->  ${maxLatE7 / 1e7}, ${maxLonE7 / 1e7}")
    println("centro          ${centerLatE7 / 1e7}, ${centerLonE7 / 1e7}")
    println("clustered       $clustered")
    println("tile indirizzate $addressedTiles, voci $tileEntries, contenuti distinti $tileContents")
    println("root dir        offset $rootOffset, $rootLength byte")
    println("leaf dir        offset $leafOffset, $leafLength byte")
    println("tile data       offset $tileDataOffset, $tileDataLength byte")
    println()

    val raw = ByteArray(metadataLength.toInt())
    file.inputStream().use { s ->
        s.skip(metadataOffset)
        var read = 0
        while (read < raw.size) {
            val n = s.read(raw, read, raw.size - read)
            if (n <= 0) break
            read += n
        }
    }
    val json = if (internalCompression == 2) gunzip(raw) else String(raw, Charsets.UTF_8)
    println("== metadata ==")
    println(json)
}

private fun gunzip(bytes: ByteArray): String {
    GZIPInputStream(bytes.inputStream()).use { gz ->
        val out = ByteArrayOutputStream()
        gz.copyTo(out)
        return out.toString(Charsets.UTF_8.name())
    }
}
