package dev.antigravity.fluidtransit.bundler

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Il map matching delle tratte sulla strada vera di OSM, via Valhalla.
 *
 * Le shape GTFS sono tracce GPS registrate: zoomate diventano spezzate che
 * tagliano gli isolati — segnalato due volte dall'utente. Qui ogni traccia
 * si riproietta sul grafo stradale (`trace_route`, costing `bus`: corsie
 * preferenziali e ZTL dove OSM le tagga) e la linea disegnata segue
 * l'asfalto.
 *
 * Filosofia del fallimento: "se non e' fattibile pace" — ogni shape che il
 * matcher sbaglia (errore, timeout, o la guardia di lunghezza che scatta)
 * resta la traccia GPS originale. Meglio una linea grezza che un detour
 * inventato.
 */
class MapMatcher(baseUrl: String) {

    private val endpoint = URI.create(baseUrl.trimEnd('/') + "/trace_route")

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    class Matched(val lat: DoubleArray, val lon: DoubleArray)

    /**
     * L'interruttore di sicurezza: se Valhalla muore a meta' corsa, migliaia
     * di richieste in timeout appenderebbero il job per ore. Dopo troppi
     * fallimenti CONSECUTIVI il matcher si arrende in blocco e ogni shape
     * resta la traccia GPS; un successo azzera il conto.
     */
    private val consecutiveFailures = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Riproietta una traccia. Ritorna null quando il risultato non e'
     * affidabile: il chiamante tiene la geometria originale.
     */
    fun match(la: DoubleArray, lo: DoubleArray): Matched? {
        if (consecutiveFailures.get() > MAX_CONSECUTIVE_FAILURES) return null
        val idx = downsample(la, lo)
        if (idx.size < 2) return null

        val body = StringBuilder(idx.size * 40 + 128)
        body.append("{\"shape\":[")
        for ((k, i) in idx.withIndex()) {
            if (k > 0) body.append(',')
            body.append("{\"lat\":").append(la[i]).append(",\"lon\":").append(lo[i]).append('}')
        }
        body.append(
            "],\"costing\":\"bus\",\"shape_match\":\"map_snap\"," +
                "\"trace_options\":{\"search_radius\":40}}",
        )

        val response = post(body.toString()) ?: post(body.toString())
        if (response == null) {
            consecutiveFailures.incrementAndGet()
            return null
        }
        consecutiveFailures.set(0)

        val outLat = ArrayList<Double>(la.size * 2)
        val outLon = ArrayList<Double>(la.size * 2)
        for (shape in ValhallaJson.shapes(response)) {
            decodePolyline6(shape, outLat, outLon)
        }
        if (outLat.size < 2) return null

        // La guardia: un detour assurdo (senso unico o ZTL che il costing
        // non conosce) e' peggio della traccia GPS. Lunghezze troppo
        // diverse = matching non credibile, si tiene l'originale.
        val origLen = pathMeters(la, lo)
        val outLatArr = outLat.toDoubleArray()
        val outLonArr = outLon.toDoubleArray()
        val matchLen = pathMeters(outLatArr, outLonArr)
        if (origLen > 100 && (matchLen < origLen * 0.75 || matchLen > origLen * 1.35)) return null
        return Matched(outLatArr, outLonArr)
    }

    private fun post(body: String): String? = try {
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val res = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() == 200) res.body() else null
    } catch (_: Exception) {
        null
    }

    private companion object {
        /** Sotto i 25 m i punti GPS sono ridondanza: il grafo interpola meglio. */
        const val SPACING_M = 25.0

        /** Fallimenti consecutivi oltre i quali il matcher si spegne per questo build. */
        const val MAX_CONSECUTIVE_FAILURES = 50

        /** Tetto di punti per richiesta, sotto i limiti di servizio di Valhalla. */
        const val MAX_POINTS = 900

        /** Indici dei punti da mandare: spaziati e in numero limitato. */
        fun downsample(la: DoubleArray, lo: DoubleArray): IntArray {
            val spaced = ArrayList<Int>(la.size / 4)
            spaced.add(0)
            for (i in 1 until la.size - 1) {
                val last = spaced.last()
                if (haversineMeters(la[last], lo[last], la[i], lo[i]) >= SPACING_M) spaced.add(i)
            }
            if (la.size > 1) spaced.add(la.size - 1)
            if (spaced.size <= MAX_POINTS) return spaced.toIntArray()
            // Ancora troppi (tratte extraurbane lunghe): passo uniforme.
            val out = IntArray(MAX_POINTS)
            for (k in 0 until MAX_POINTS) {
                out[k] = spaced[(k.toLong() * (spaced.size - 1) / (MAX_POINTS - 1)).toInt()]
            }
            return out
        }

        /** Polyline di Valhalla, precisione 1e-6. Salta i punti di giunzione fra leg. */
        fun decodePolyline6(s: String, lat: MutableList<Double>, lon: MutableList<Double>) {
            var i = 0
            var accLat = 0L
            var accLon = 0L
            while (i < s.length) {
                var result = 0L
                var shift = 0
                var b: Int
                do {
                    b = s[i++].code - 63
                    result = result or ((b.toLong() and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                accLat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                result = 0
                shift = 0
                do {
                    b = s[i++].code - 63
                    result = result or ((b.toLong() and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                accLon += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                val plat = accLat / 1e6
                val plon = accLon / 1e6
                if (lat.isEmpty() ||
                    Math.abs(lat[lat.size - 1] - plat) > 1e-7 ||
                    Math.abs(lon[lon.size - 1] - plon) > 1e-7
                ) {
                    lat.add(plat)
                    lon.add(plon)
                }
            }
        }

        fun pathMeters(la: DoubleArray, lo: DoubleArray): Double {
            var sum = 0.0
            for (i in 1 until la.size) {
                sum += haversineMeters(la[i - 1], lo[i - 1], la[i], lo[i])
            }
            return sum
        }

        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val kx = 111_320.0 * Math.cos(Math.toRadians(lat1))
            val ky = 110_574.0
            val dx = (lon2 - lon1) * kx
            val dy = (lat2 - lat1) * ky
            return Math.sqrt(dx * dx + dy * dy)
        }
    }
}

/**
 * Le polilinee di una risposta di Valhalla, lette a mano.
 *
 * Erano estratte con `"shape":"((?:[^"\\]|\\.)*)"`, e in Java
 * un'alternanza sotto `*` si ricorre a ogni carattere: su una polilinea
 * lunga — una tratta extraurbana codificata con precisione 1e-6 sono decine
 * di migliaia di caratteri — la pila finisce. Il 30/09/2026 il job del
 * bundle ha stampato 2.346 `StackOverflowError`: delle 2.362 tracce rimaste
 * GPS su 9.211, tutte tranne sedici erano morte qui, non nel grafo.
 * L'errore uccideva il thread senza passare dal conteggio dei fallimenti, e
 * il "74-76% di tracce aderite" che si leggeva nei log da settimane — e che
 * teneva gli overlay alla revisione bassa — era questo difetto. Nessuno se
 * n'era accorto, perche' il ripiego sulla traccia grezza e' silenzioso per
 * progetto.
 *
 * Qui si scandisce la stringa una volta, senza ricorsione. Le polilinee usano
 * i caratteri da `?` a `~`, e l'unico che JSON deve proteggere e' la barra
 * rovescia: ogni `\x` diventa `x`.
 */
internal object ValhallaJson {

    private const val KEY = "\"shape\":\""

    fun shapes(json: String): List<String> {
        val out = ArrayList<String>(2)
        var from = 0
        while (true) {
            val start = json.indexOf(KEY, from)
            if (start < 0) break
            var i = start + KEY.length
            val sb = StringBuilder()
            while (i < json.length) {
                val c = json[i]
                if (c == '"') break
                if (c == '\\' && i + 1 < json.length) {
                    sb.append(json[i + 1])
                    i += 2
                    continue
                }
                sb.append(c)
                i++
            }
            out.add(sb.toString())
            from = i + 1
        }
        return out
    }
}
