package dev.antigravity.fluidtransit.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import dev.antigravity.fluidtransit.routing.BusPathMotion
import org.maplibre.android.maps.Style
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * I bus vivi come li vuole la mappa: cosa disegnare e dove, gia' risolto
 * contro il bundle (colore della linea, categoria per i filtri, hash per la
 * modalita' linea). Il vero movimento sta in [BusOverlay].
 */
class BusRender(
    val vehKey: Int,
    val lat: Double,
    val lon: Double,
    val bearingDeg: Int, // -1 = ignoto: si disegna il pallino
    val colorRgb: Int,
    val cat: String, // "u" | "e", per i chip
    val routeHashHex: String,
    val tripHashHex: String,
    /** Il pattern della corsa: e' l'aggancio alla geometria. -1 se ignoto. */
    val patternIndex: Int = -1,
    /** Velocita' dichiarata dal feed, m/s. -1 se il mezzo non la manda. */
    val speedMs: Double = -1.0,
    /** Eta' del rilevamento GPS alla generazione dello snapshot. -1 ignota. */
    val fixAgeSec: Int = -1,
)

/**
 * Dove sta ogni bus, adesso.
 *
 * Il feed di at pubblica una posizione nuova ogni ~120 secondi (misurato in
 * Fase 1 e riconfermato il 03/09). Fino alla Fase 8 fra un dato e l'altro il
 * marker scivolava in linea retta e poi si CONGELAVA: venticinque secondi di
 * moto e novantacinque di immobilita', cioe' esattamente il "si
 * teletrasportano" che l'utente ha visto.
 *
 * Adesso, quando la geometria della tratta c'e', il bus corre sulla strada
 * vera: avanza da solo alla velocita' che il feed dichiara — che finora
 * arrivava fino qui e veniva buttata — rallenta dove la strada gira, sosta
 * alle fermate, e quando il dato vero arriva riassorbe l'errore in un paio
 * di secondi invece di saltare. Il conto sta in [BusPathMotion].
 *
 * Senza geometria (mezzi con una linea ma nessuna corsa riconosciuta) resta
 * il ripiego: interpolazione fra i due ultimi dati veri, senza estrapolare.
 */
class BusOverlay {

    private class Track(
        var render: BusRender,
        /** Il moto sulla strada. Null finche' la geometria non c'e'. */
        var motion: BusPathMotion? = null,
        var pattern: Int = -1,
        // --- ripiego senza geometria: interpolazione fra due dati veri
        var fromLat: Double = 0.0,
        var fromLon: Double = 0.0,
        var toLat: Double = 0.0,
        var toLon: Double = 0.0,
        var startMs: Long = 0L,
        var durationMs: Long = 0L,
        /**
         * La rotta DERIVATA dal movimento fra due snapshot: il feed di at
         * non manda quasi mai il bearing (misurato: 37 su ~1250), quindi la
         * freccia si orienta cosi' — e chi e' fermo resta un pallino. Col
         * moto sulla strada la rotta viene invece dalla tangente, che e'
         * giusta anche da fermo.
         */
        var derivedBearing: Int = -1,
        var lastMoveMs: Long = 0L,
        /** Ultimo snapshot in cui il feed ha nominato questo mezzo. */
        var lastSeenMs: Long = 0L,
        /**
         * L'ultimo dato VERO gia' consegnato al moto sulla strada. Serve a
         * riconoscere le fotocopie: il feed si rigenera ogni ~2 minuti e
         * l'app polla ogni 30 s, quindi la maggior parte degli snapshot
         * ripete lo stesso rilevamento.
         */
        var fixLat: Double = Double.NaN,
        var fixLon: Double = Double.NaN,
        var fixAgeSec: Int = Int.MIN_VALUE,
        /**
         * Quando questo mezzo e' stato mosso l'ultima volta. Per mezzo e non
         * globale, perche' con il taglio per riquadro un mezzo puo' restare
         * fermo molti fotogrammi: al rientro deve recuperare il SUO tempo,
         * non quello dell'ultimo fotogramma disegnato.
         */
        var lastTickMs: Long = 0L,
    ) {
        /** Questo dato dice qualcosa che non sapevamo gia'? */
        fun isNewFix(b: BusRender): Boolean =
            b.lat != fixLat || b.lon != fixLon || b.fixAgeSec != fixAgeSec

        fun rememberFix(b: BusRender) {
            fixLat = b.lat
            fixLon = b.lon
            fixAgeSec = b.fixAgeSec
        }

        fun glideAt(nowMs: Long): Pair<Double, Double> {
            if (durationMs <= 0) return toLat to toLon
            val t = ((nowMs - startMs).toDouble() / durationMs).coerceIn(0.0, 1.0)
            return (fromLat + (toLat - fromLat) * t) to (fromLon + (toLon - fromLon) * t)
        }
    }

    private val tracks = LinkedHashMap<Int, Track>()
    private var lastFrameMs = 0L

    var selectedKey: Int? = null

    /** La geometria dei pattern. Si aggancia quando il bundle e' pronto. */
    var paths: PathCache? = null

    /** Un nuovo snapshot dal feed. */
    fun setTargets(list: List<BusRender>, nowMs: Long) {
        for (b in list) {
            val prev = tracks[b.vehKey]
            if (prev == null) {
                tracks[b.vehKey] = newTrack(b, nowMs)
                continue
            }
            prev.render = b
            prev.lastSeenMs = nowMs
            attachMotion(prev, b)

            val motion = prev.motion
            if (motion != null) {
                // Solo se il dato e' nuovo. Riapplicare la stessa posizione
                // non era innocuo: l'eta' del rilevamento e' congelata, quindi
                // il bersaglio resta fermo mentre il mezzo simulato e'
                // avanzato, e la "correzione" lo tirava indietro — con la
                // freccia che, venendo dalla tangente della strada, continuava
                // a puntare avanti. Il ramo di ripiego qui sotto questo filtro
                // ce l'ha da sempre (moved < 8); mancava solo qui.
                if (prev.isNewFix(b)) {
                    prev.rememberFix(b)
                    motion.onFix(b.lat, b.lon, b.speedMs, b.fixAgeSec, nowMs)
                }
                continue
            }
            glideToward(prev, b, nowMs)
        }
        // Un mezzo che manca da un giro non viene dimenticato: sparire e
        // ricomparire era una delle sorgenti di teletrasporto. Si smette di
        // disegnarlo dopo un po', e si butta solo quando e' chiaro che non
        // torna.
        val it = tracks.entries.iterator()
        while (it.hasNext()) {
            if (nowMs - it.next().value.lastSeenMs > FORGET_MS) it.remove()
        }
    }

    val isEmpty: Boolean get() = tracks.isEmpty()

    fun features(
        nowMs: Long,
        style: Style,
        density: Float,
        /**
         * Il riquadro da disegnare, GIA' allargato dal chiamante:
         * (minLat, minLon, maxLat, maxLon). null = nessun taglio.
         */
        view: DoubleArray? = null,
    ): FeatureCollection {
        lastFrameMs = nowMs

        val out = ArrayList<Feature>(tracks.size)
        val sample = DoubleArray(3)
        for (t in tracks.values) {
            if (nowMs - t.lastSeenMs > HIDE_MS) continue
            val b = t.render

            // Fuori dalla scena non si disegna e non si simula.
            //
            // Il taglio guarda la posizione dell'ULTIMO DATO VERO, che e' nota
            // senza far avanzare niente — ed e' per questo che il riquadro
            // arriva gia' allargato di qualche chilometro: fra un dato e
            // l'altro passano ~2 minuti, e un mezzo in quel tempo fa strada.
            // Quando rientra, il tempo accumulato manda tick() sul recupero in
            // blocco, che e' esattamente il caso per cui quel ramo esiste.
            if (view != null &&
                (b.lat < view[0] || b.lat > view[2] || b.lon < view[1] || b.lon > view[3])
            ) {
                continue
            }

            val dt = if (t.lastTickMs == 0L) 0L else nowMs - t.lastTickMs
            t.lastTickMs = nowMs
            val motion = t.motion
            val lat: Double
            val lon: Double
            val bearing: Int
            if (motion != null) {
                if (dt > 0) motion.tick(dt, nowMs)
                motion.sample(sample)
                lat = sample[0]
                lon = sample[1]
                // Da fermo la tangente e' comunque la direzione di marcia:
                // e' proprio il caso in cui prima si ricadeva sul pallino.
                bearing = sample[2].toInt()
            } else {
                val (gLat, gLon) = t.glideAt(nowMs)
                lat = gLat
                lon = gLon
                bearing = if (b.bearingDeg >= 0) b.bearingDeg else t.derivedBearing
            }
            BusIcons.ensure(style, b.colorRgb, density)
            val f = Feature.fromGeometry(Point.fromLngLat(lon, lat))
            f.addStringProperty("sh", if (bearing >= 0) "a" else "d")
            f.addStringProperty("ci", BusIcons.hex(b.colorRgb))
            f.addNumberProperty("b", if (bearing >= 0) bearing else 0)
            f.addStringProperty("cat", b.cat)
            f.addStringProperty("rh", b.routeHashHex)
            f.addStringProperty("th", b.tripHashHex)
            f.addNumberProperty("vk", b.vehKey)
            f.addBooleanProperty("sel", b.vehKey == selectedKey)
            out.add(f)
        }
        return FeatureCollection.fromFeatures(out)
    }

    fun clear() {
        tracks.clear()
        lastFrameMs = 0L
    }

    // --------------------------------------------------------------- interni

    private fun newTrack(b: BusRender, nowMs: Long): Track {
        val t = Track(
            render = b,
            fromLat = b.lat,
            fromLon = b.lon,
            toLat = b.lat,
            toLon = b.lon,
            startMs = nowMs,
            durationMs = 0,
            lastSeenMs = nowMs,
        )
        attachMotion(t, b)
        // Comparire nel posto giusto non e' un teletrasporto: e' l'unica
        // cosa onesta da fare al primo dato.
        t.rememberFix(b)
        t.motion?.onFix(b.lat, b.lon, b.speedMs, b.fixAgeSec, nowMs)
        return t
    }

    /**
     * Aggancia il moto sulla strada appena la geometria del pattern e'
     * disponibile: la decodifica e' asincrona, quindi i primi fotogrammi di
     * un mezzo possono ancora essere di ripiego.
     */
    private fun attachMotion(t: Track, b: BusRender) {
        if (t.motion != null && t.pattern == b.patternIndex) return
        val cache = paths ?: return

        // Il ripiego: nessuna geometria, si scivola fra due dati veri. E'
        // sempre meglio della geometria SBAGLIATA, che manda il mezzo a
        // percorrere una strada che non e' la sua.
        fun fallback() {
            t.motion = null
            t.pattern = b.patternIndex
        }

        if (b.patternIndex < 0) {
            t.motion = null
            t.pattern = -1
            return
        }
        val path = cache.get(b.patternIndex)
        if (path == null) {
            // La decodifica del pattern e' asincrona. Prima si usciva di qui
            // lasciando il moto sulla geometria PRECEDENTE: il mezzo aveva
            // gia' cambiato corsa — al capolinea vuol dire verso invertito —
            // e continuava a correre sul ramo di prima, cioe' all'indietro.
            fallback()
            return
        }

        val probe = DoubleArray(2)
        path.projectWithDistance(b.lat, b.lon, probe)
        val startS = probe[0]

        // 1. Il mezzo deve stare SULLA strada che gli stiamo attribuendo.
        //    project() aggancia sempre, anche a chilometri di distanza: senza
        //    questo controllo una corsa risolta male faceva correre il bus
        //    lungo un percorso qualsiasi della linea. distanceTo esisteva gia'
        //    e fuori dai test non la chiamava nessuno.
        if (probe[1] > MAX_PATH_OFFSET_M) {
            fallback()
            return
        }

        // 2. Quando il feed dichiara la rotta e' una controprova gratuita: se
        //    la tangente della tratta punta dalla parte opposta, quello e' il
        //    pattern del verso sbagliato. Il bearing arriva su pochi mezzi
        //    (misurati 37 su ~1250), quindi vale come smentita e non come
        //    sorgente — ma quando c'e' e' decisivo.
        if (b.bearingDeg >= 0 &&
            angleBetween(path.headingAtS(startS), b.bearingDeg.toDouble()) > OPPOSITE_DEG
        ) {
            fallback()
            return
        }

        t.pattern = b.patternIndex
        t.motion = BusPathMotion(
            path = path,
            startS = startS,
            startSpeed = if (b.speedMs >= 0) b.speedMs else -1.0,
        )
    }

    private fun glideToward(prev: Track, b: BusRender, nowMs: Long) {
        val moved = dev.antigravity.fluidtransit.routing.BundleReader
            .haversine(prev.toLat, prev.toLon, b.lat, b.lon)
        if (moved < 8) {
            // Stessa posizione di prima: meta' degli snapshot sono
            // fotocopie. Il glide in corso continua indisturbato.
            return
        }
        val (curLat, curLon) = prev.glideAt(nowMs)
        prev.fromLat = curLat
        prev.fromLon = curLon
        // Il ritmo lo detta il feed di QUESTO mezzo; al primo movimento si
        // assume il periodo vero dell'origine invece di venticinque secondi.
        prev.durationMs = if (prev.lastMoveMs > 0) {
            (nowMs - prev.lastMoveMs).coerceIn(MIN_GLIDE_MS, MAX_GLIDE_MS)
        } else {
            FEED_PERIOD_MS
        }
        val jump = dev.antigravity.fluidtransit.routing.BundleReader
            .haversine(curLat, curLon, b.lat, b.lon)
        if (jump > 25) {
            prev.derivedBearing = bearingDegrees(curLat, curLon, b.lat, b.lon)
        }
        prev.lastMoveMs = nowMs
        prev.toLat = b.lat
        prev.toLon = b.lon
        prev.startMs = nowMs
    }

    private companion object {
        /** Il periodo vero con cui l'origine si rigenera: ~2 minuti. */
        const val FEED_PERIOD_MS = 120_000L
        const val MIN_GLIDE_MS = 20_000L
        const val MAX_GLIDE_MS = 150_000L

        /** Assente da tanto: si smette di disegnarlo. */
        const val HIDE_MS = 180_000L

        /** Assente da tantissimo: si butta. */
        const val FORGET_MS = 300_000L

        /**
         * Oltre questa distanza dalla tratta, quel mezzo non sta percorrendo
         * quella tratta. Le shape sono semplificate e il GPS sbaglia, quindi
         * la soglia e' larga: serve a scartare gli agganci assurdi, non a
         * fare i pignoli sui metri.
         */
        const val MAX_PATH_OFFSET_M = 250.0

        /**
         * Oltre questo scarto fra la rotta dichiarata dal feed e la tangente
         * della tratta, il mezzo sta andando dall'altra parte.
         */
        const val OPPOSITE_DEG = 120.0

        /** Differenza fra due rotte, 0..180. */
        fun angleBetween(a: Double, b: Double): Double {
            var d = kotlin.math.abs(a - b) % 360.0
            if (d > 180.0) d = 360.0 - d
            return d
        }

        /** Rotta iniziale (gradi da nord, orari) dal punto vecchio al nuovo. */
        fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
            val f1 = Math.toRadians(lat1)
            val f2 = Math.toRadians(lat2)
            val dl = Math.toRadians(lon2 - lon1)
            val y = kotlin.math.sin(dl) * kotlin.math.cos(f2)
            val x = kotlin.math.cos(f1) * kotlin.math.sin(f2) -
                kotlin.math.sin(f1) * kotlin.math.cos(f2) * kotlin.math.cos(dl)
            val deg = Math.toDegrees(kotlin.math.atan2(y, x))
            return (((deg % 360) + 360) % 360).toInt()
        }
    }
}

/**
 * Le icone dei bus, disegnate al volo e registrate nello stile: una freccia
 * di navigazione e un pallino per ogni colore di linea incontrato. La
 * tavolozza vera e' di ~12 tinte, quindi sono poche bitmap piccole — e non
 * serve il giro degli SDF, che sfocano i bordi.
 */
object BusIcons {

    fun hex(colorRgb: Int): String = "%06x".format(colorRgb and 0xFFFFFF)

    fun arrowName(colorRgb: Int) = "bus-a-${hex(colorRgb)}"

    fun dotName(colorRgb: Int) = "bus-d-${hex(colorRgb)}"

    /**
     * I colori gia' registrati, PER stile: interrogare style.getImage a ogni
     * fotogramma sarebbe una chiamata JNI che copia la bitmap — a 8 Hz per
     * mille bus e' un costo vero. La mappa debole muore con lo stile.
     */
    private val registered = java.util.WeakHashMap<Style, HashSet<Int>>()

    fun ensure(style: Style, colorRgb: Int, density: Float) {
        val colors = registered.getOrPut(style) { HashSet() }
        if (!colors.add(colorRgb)) return
        style.addImage(arrowName(colorRgb), arrowBitmap(colorRgb, density))
        style.addImage(dotName(colorRgb), dotBitmap(colorRgb, density))
    }

    /** La freccia di marcia: punta in alto, il layer la ruota col bearing. */
    private fun arrowBitmap(colorRgb: Int, density: Float): Bitmap {
        val size = (26 * density).toInt().coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val w = size.toFloat()
        val path = Path().apply {
            moveTo(w * 0.5f, w * 0.06f) // punta
            lineTo(w * 0.88f, w * 0.88f) // ala destra
            lineTo(w * 0.5f, w * 0.66f) // incavo
            lineTo(w * 0.12f, w * 0.88f) // ala sinistra
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF000000.toInt() or (colorRgb and 0xFFFFFF)
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeJoin = Paint.Join.ROUND
            color = 0xFFFFFFFF.toInt()
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
        return bmp
    }

    /** Il ripiego senza direzione: pallino pieno col bordo bianco. */
    private fun dotBitmap(colorRgb: Int, density: Float): Bitmap {
        val size = (18 * density).toInt().coerceAtLeast(16)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val c = size / 2f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF000000.toInt() or (colorRgb and 0xFFFFFF)
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = 0xFFFFFFFF.toInt()
        }
        canvas.drawCircle(c, c, c - 2.5f * density, fill)
        canvas.drawCircle(c, c, c - 2.5f * density, stroke)
        return bmp
    }
}
