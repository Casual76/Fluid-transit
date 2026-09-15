package dev.antigravity.fluidtransit.ui.map

import dev.antigravity.fluidtransit.routing.BusPathMotion
import dev.antigravity.fluidtransit.routing.PathIndex

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

/** Dove disegnare un mezzo, adesso. Il resto lo porta [render]. */
class BusPose(
    val render: BusRender,
    val lat: Double,
    val lon: Double,
    /** -1 = direzione ignota: si disegna il pallino invece della freccia. */
    val bearingDeg: Int,
)

/**
 * Da dove arrivano le geometrie dei pattern.
 *
 * E' un'interfaccia e non direttamente [PathCache] perche' il moto dei mezzi
 * si verifica a tavolino: un test consegna le tratte che vuole, senza bundle
 * e senza decodifica asincrona.
 */
fun interface PathSource {
    /** La geometria del pattern, o null se non c'e' (ancora). */
    fun get(pattern: Int): PathIndex?
}

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
 *
 * Questa classe non sa niente di MapLibre di proposito: produce pose, e chi
 * disegna le trasforma in feature ([busFeatures]). Serviva per poterla
 * mettere sotto test — i difetti che restavano erano tutti qui dentro, nel
 * passaggio fra moto sulla strada e ripiego, e nessuno poteva vederli.
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
        /**
         * Qualcuno ha visto questo mezzo da quando e' arrivato l'ultimo dato?
         *
         * Se no — fuori dal riquadro, o troppo vecchio per disegnarlo — il
         * dato successivo puo' rimetterlo al suo posto di netto: il
         * riassorbimento graduale serve all'occhio, e qui l'occhio non c'era.
         */
        var drawnSinceFix: Boolean = false,
        /** Quanti dati di fila si e' deciso di aspettare invece di arretrare. */
        var heldFixes: Int = 0,
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

        /** Il ripiego riparte da qui, fermo, invece che da dove stava prima. */
        fun restGlideAt(lat: Double, lon: Double, nowMs: Long) {
            fromLat = lat
            fromLon = lon
            toLat = lat
            toLon = lon
            startMs = nowMs
            durationMs = 0
        }

        /** Dove il mezzo e' disegnato in questo istante, comunque si muova. */
        fun currentPosition(nowMs: Long, scratch: DoubleArray): Pair<Double, Double> {
            val m = motion
            if (m == null) return glideAt(nowMs)
            m.sample(scratch)
            return scratch[0] to scratch[1]
        }
    }

    private val tracks = LinkedHashMap<Int, Track>()
    private val scratch = DoubleArray(3)

    var selectedKey: Int? = null

    /** La geometria dei pattern. Si aggancia quando il bundle e' pronto. */
    var paths: PathSource? = null

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
            attachMotion(prev, b, nowMs)

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
                    motion.onFix(
                        b.lat, b.lon, b.speedMs, b.fixAgeSec, nowMs,
                        snap = !prev.drawnSinceFix,
                    )
                    prev.drawnSinceFix = false
                    // Il debito di tempo si azzera qui, ed e' un difetto che
                    // si vedeva solo ai bordi dello schermo. Un mezzo fuori
                    // dal riquadro non viene disegnato e `lastTickMs` non
                    // avanza, ma il dato vero gli arriva lo stesso: al rientro
                    // `tick` riceveva tutto il tempo passato e lo spingeva
                    // avanti di minuti di strada SOPRA una posizione che era
                    // gia' aggiornata. Il mezzo entrava in scena troppo avanti
                    // e al dato successivo tornava indietro.
                    prev.lastTickMs = nowMs
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

    /**
     * Dove disegnare ogni mezzo adesso.
     *
     * [view] e' il riquadro da disegnare, GIA' allargato dal chiamante:
     * (minLat, minLon, maxLat, maxLon). null = nessun taglio.
     */
    fun poses(nowMs: Long, view: DoubleArray? = null): List<BusPose> {
        val out = ArrayList<BusPose>(tracks.size)
        for (t in tracks.values) {
            if (nowMs - t.lastSeenMs > HIDE_MS) continue
            val b = t.render

            // Fuori dalla scena non si disegna e non si simula.
            //
            // Il taglio guarda la posizione dell'ULTIMO DATO VERO, che e' nota
            // senza far avanzare niente — ed e' per questo che il riquadro
            // arriva gia' allargato di qualche chilometro: fra un dato e
            // l'altro passano ~2 minuti, e un mezzo in quel tempo fa strada.
            if (view != null &&
                (b.lat < view[0] || b.lat > view[2] || b.lon < view[1] || b.lon > view[3])
            ) {
                continue
            }

            val dt = if (t.lastTickMs == 0L) 0L else nowMs - t.lastTickMs
            t.lastTickMs = nowMs
            t.drawnSinceFix = true
            val motion = t.motion
            val lat: Double
            val lon: Double
            val bearing: Int
            if (motion != null) {
                if (dt > 0) motion.tick(dt, nowMs)
                motion.sample(scratch)
                lat = scratch[0]
                lon = scratch[1]
                // Da fermo la tangente e' comunque la direzione di marcia:
                // e' proprio il caso in cui prima si ricadeva sul pallino.
                bearing = scratch[2].toInt()
            } else {
                val (gLat, gLon) = t.glideAt(nowMs)
                lat = gLat
                lon = gLon
                bearing = if (b.bearingDeg >= 0) b.bearingDeg else t.derivedBearing
            }
            out.add(BusPose(render = b, lat = lat, lon = lon, bearingDeg = bearing))
        }
        return out
    }

    fun clear() {
        tracks.clear()
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
        attachMotion(t, b, nowMs)
        // Comparire nel posto giusto non e' un teletrasporto: e' l'unica
        // cosa onesta da fare al primo dato.
        t.rememberFix(b)
        t.motion?.onFix(b.lat, b.lon, b.speedMs, b.fixAgeSec, nowMs)
        t.lastTickMs = nowMs
        return t
    }

    /**
     * Aggancia il moto sulla strada appena la geometria del pattern e'
     * disponibile: la decodifica e' asincrona, quindi i primi fotogrammi di
     * un mezzo possono ancora essere di ripiego.
     */
    private fun attachMotion(t: Track, b: BusRender, nowMs: Long) {
        if (t.motion != null && t.pattern == b.patternIndex) return

        // Il ripiego: nessuna geometria, si scivola fra due dati veri. E'
        // sempre meglio della geometria SBAGLIATA, che manda il mezzo a
        // percorrere una strada che non e' la sua.
        //
        // Riparte da DOVE IL MEZZO E' DISEGNATO ADESSO. Prima no: spegneva il
        // moto sulla strada e lasciava intatti i campi del glide, che erano
        // fermi a prima che il moto prendesse il comando — potevano avere
        // minuti, e il marker saltava indietro fin li'. Succedeva ogni volta
        // che un mezzo cambiava corsa e la geometria nuova non era ancora
        // decodificata, cioe' a ogni capolinea.
        fun fallback() {
            // La direzione di marcia la sa il moto sulla strada, ed e' l'unica
            // cosa che permette al ripiego di distinguere "avanti" da
            // "indietro" senza una strada sotto. Si porta con se'.
            t.motion?.let { m ->
                m.sample(scratch)
                t.derivedBearing = scratch[2].toInt()
            }
            val (lat, lon) = t.currentPosition(nowMs, scratch)
            t.restGlideAt(lat, lon, nowMs)
            t.motion = null
            t.pattern = b.patternIndex
            t.heldFixes = 0
        }

        if (b.patternIndex < 0) {
            fallback()
            return
        }
        val cache = paths ?: return
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

        // Il moto nasce DOVE IL MARKER E' DISEGNATO, non dove dice il dato.
        //
        // Sono due punti diversi ogni volta che il ripiego ha tenuto banco
        // per un po': il marker sta dove lo abbiamo lasciato, il dato dice
        // dov'era il mezzo. Far nascere il moto sul dato voleva dire spostare
        // il marker di netto nell'istante in cui la geometria diventava
        // disponibile — all'indietro, se avevamo estrapolato. Facendolo
        // nascere dove si trova, il dato diventa subito dopo una correzione
        // come tutte le altre, che si riassorbe rallentando.
        //
        // Se pero' il marker e' finito lontano da questa strada, il posto
        // buono e' quello del dato: ci si sposta, ed e' il dato ad avere
        // ragione.
        val (curLat, curLon) = t.currentPosition(nowMs, scratch)
        val here = DoubleArray(2)
        path.projectWithDistance(curLat, curLon, here)
        val bornS = if (here[1] <= MAX_PATH_OFFSET_M) here[0] else startS

        val motion = BusPathMotion(
            path = path,
            startS = bornS,
            startSpeed = if (b.speedMs >= 0) b.speedMs else -1.0,
        )
        // Il moto nasce gia' informato, e non e' un dettaglio.
        //
        // Un moto appena costruito non ha storia, e `onFix` in quel caso non
        // corregge: riposiziona. Quindi il primo dato che arrivava dopo
        // l'aggancio spostava il mezzo di netto — all'indietro, se nel
        // frattempo aveva corso. Succedeva a ogni mezzo poco dopo essere
        // comparso, e a ogni cambio di corsa: la geometria si decodifica in
        // sottofondo, il moto si aggancia quando e' pronta, e il fix
        // successivo lo tirava indietro di qualche centinaio di metri.
        //
        // `seed` gli dice dove sta il dato senza spostare il marker: da qui in
        // poi ogni fix e' una correzione come tutte le altre.
        motion.seed(startS, nowMs)
        t.motion = motion
        t.rememberFix(b)
        // Il moto nasce dove il mezzo e' adesso: il tempo accumulato dal
        // ripiego precedente non lo riguarda.
        t.lastTickMs = nowMs
        t.drawnSinceFix = false
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

        // Il dato nuovo sta DIETRO di noi?
        //
        // Succede quando il ripiego subentra al moto sulla strada: quello
        // estrapola, e al momento del cambio il marker e' piu' avanti del suo
        // ultimo dato vero. Scivolare verso il dato nuovo vuol dire scivolare
        // ALL'INDIETRO, lentamente e per due minuti — meno di un metro a
        // fotogramma, quindi impossibile da attribuire a qualcosa guardando la
        // mappa, ma perfettamente leggibile come "quel bus sta tornando
        // indietro".
        val toFix = bearingDegrees(curLat, curLon, b.lat, b.lon)
        val behind = prev.derivedBearing >= 0 &&
            angleBetween(prev.derivedBearing.toDouble(), toFix.toDouble()) > 90.0
        if (behind) {
            prev.heldFixes++
            prev.lastMoveMs = nowMs
            if (prev.heldFixes <= MAX_HELD_FIXES) {
                // Si resta fermi: il dato vero ci raggiungera'.
                prev.restGlideAt(curLat, curLon, nowMs)
                return
            }
            // Aspettato abbastanza: se il feed continua a dirci che il mezzo
            // e' indietro, il mezzo e' indietro e la nostra estrapolazione era
            // sbagliata. Un riposizionamento netto si legge come una
            // correzione; un arretramento lento si legge come un bus che torna
            // al capolinea.
            prev.restGlideAt(b.lat, b.lon, nowMs)
            prev.heldFixes = 0
            return
        }
        prev.heldFixes = 0
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

    internal companion object {
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

        /**
         * Quante volte si accetta di stare fermi piuttosto che arretrare.
         *
         * Due giri sono ~quattro minuti: abbastanza perche' un mezzo che si
         * stava solo muovendo piu' piano di quanto credevamo ci raggiunga, e
         * poco abbastanza da non lasciare un marker inchiodato in mezzo alla
         * strada se davvero avevamo sbagliato.
         */
        const val MAX_HELD_FIXES = 2

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
