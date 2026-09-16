package dev.antigravity.fluidtransit.routing

import kotlin.math.max

/**
 * Il ritardo di una corsa come cambia lungo il percorso.
 *
 * Fino alla Fase 8 il ritardo era UN intero per corsa, sommato identico a
 * tutte le fermate del pattern: anche a quelle che il bus aveva gia'
 * passato, anche a quelle di un'ora dopo. Un mezzo con otto minuti di
 * ritardo a Rifredi risultava in ritardo di otto minuti pure al capolinea
 * di tre quarti d'ora dopo, e le fermate alle sue spalle si coloravano di
 * verde come se il dato le riguardasse.
 *
 * La regola decisa con l'utente: **il ritardo si consuma strada facendo,
 * salvo che i dati dicano il contrario**. Quindi non una formula fissa di
 * recupero, ma l'osservazione: si tengono gli ultimi giri di
 * trip-updates, si guarda come il ritardo si muove di fermata in fermata,
 * e si proietta quell'andamento. Se cala, si proietta il recupero; se sta
 * fermo o cresce, non si attenua niente.
 *
 * L'ingrediente che rende tutto questo possibile e' `nextStopSeq`, che il
 * feed manda e che arrivava fino all'app per non essere mai letto.
 */
class DelayModel {

    enum class Confidence {
        /** La fermata e' alle spalle del bus: il ritardo non la riguarda piu'. */
        SERVED,

        /** E' la fermata verso cui il bus sta andando: il dato e' questo. */
        OBSERVED,

        /** Piu' avanti: e' una proiezione nostra, e va detto. */
        PROJECTED,
    }

    class Live(val delaySeconds: Int, val confidence: Confidence)

    private class Sample(val delaySeconds: Int, val seq: Int, val at: Long)

    private class Trend(val slopePerStop: Double?, val worsening: Boolean)

    private class Track {
        val samples = ArrayDeque<Sample>()
        var lastSeen: Long = 0L

        val newest: Sample? get() = samples.lastOrNull()

        fun add(s: Sample) {
            val prev = samples.lastOrNull()
            // Lo stesso giro riproposto (il feed si rigenera ogni ~2 minuti,
            // meta' degli snapshot sono fotocopie) non e' un'osservazione
            // nuova: falserebbe la pendenza con dei punti duplicati.
            if (prev != null && prev.seq == s.seq && prev.delaySeconds == s.delaySeconds) {
                lastSeen = s.at
                return
            }
            samples.addLast(s)
            while (samples.size > HISTORY) samples.removeFirst()
            lastSeen = s.at
        }

        fun trend(): Trend {
            if (samples.size < 2) return Trend(null, false)
            val first = samples.first()
            val last = samples.last()
            val stops = last.seq - first.seq
            if (last.seq >= 0 && first.seq >= 0 && stops >= 1) {
                return Trend((last.delaySeconds - first.delaySeconds).toDouble() / stops, false)
            }
            // Il bus non ha ancora cambiato fermata. Se nel frattempo il
            // ritardo e' cresciuto, e' bloccato: nessuna attenuazione.
            val grew = last.delaySeconds - first.delaySeconds
            val elapsed = last.at - first.at
            val worsening = grew >= WORSENING_SECONDS && elapsed >= WORSENING_WINDOW_SECONDS
            return Trend(null, worsening)
        }
    }

    private val tracks = HashMap<Int, Track>()

    /**
     * Una mano per volta sulla mappa delle corse.
     *
     * Non e' prudenza teorica: chi scrive e chi legge sono thread diversi e
     * lo sono sempre. Le osservazioni entrano da un collettore
     * dell'Application, novecento corse in fila a ogni giro di trip-updates,
     * piu' una potatura e uno svuotamento completo allo scambio notturno del
     * bundle; le letture arrivano dai tabelloni, che si calcolano sul pool di
     * sfondo, e dagli strumenti dell'assistente.
     *
     * Una `HashMap` letta mentre la si riempie non da' un errore: da'
     * risposte sbagliate. Durante l'ingrandimento della tabella una chiave
     * che c'e' puo' risultare assente, e una corsa che il feed sta seguendo
     * perde il suo ritardo per quel giro — cioe' una riga che dice "orario da
     * tabella" mentre la riga accanto dice "dal bus", a caso, e diversa a
     * ogni ricalcolo.
     *
     * Trovato leggendo, non guardando: e' esattamente il genere di difetto
     * che non si riesce a riprodurre apposta e che da fuori si racconta come
     * "a volte funziona e a volte no".
     *
     * Il lucchetto e' grosso di proposito. Le sezioni critiche sono letture
     * di una mappa e di una coda da cinque elementi: nanosecondi, contro il
     * costo di rendere concorrente anche ogni `Track`, che ha una coda
     * mutabile dentro e non basterebbe una `ConcurrentHashMap`.
     */
    private val lock = Any()

    /** Un giro di trip-updates per una corsa. [nextStopSeq] -1 se ignota. */
    fun observe(tripKey: Int, delaySeconds: Int, nextStopSeq: Int, atEpoch: Long) {
        synchronized(lock) {
            tracks.getOrPut(tripKey) { Track() }
                .add(Sample(delaySeconds, nextStopSeq, atEpoch))
        }
    }

    /** Il ritardo corrente della corsa, quello della testata. Null se non si sa. */
    fun current(tripKey: Int): Int? =
        synchronized(lock) { tracks[tripKey]?.newest?.delaySeconds }

    /** La fermata verso cui il bus sta andando, se il feed la dichiara. */
    fun nextStop(tripKey: Int): Int? =
        synchronized(lock) { tracks[tripKey]?.newest?.seq?.takeIf { it >= 0 } }

    /**
     * Il ritardo da applicare alla fermata in posizione [position] di un
     * pattern che ne ha [stopCount]. Null quando di quella corsa non
     * sappiamo niente.
     */
    fun at(tripKey: Int, position: Int, stopCount: Int, nowEpoch: Long = 0L): Live? =
        synchronized(lock) { atLocked(tripKey, position, stopCount, nowEpoch) }

    private fun atLocked(tripKey: Int, position: Int, stopCount: Int, nowEpoch: Long): Live? {
        val track = tracks[tripKey] ?: return null
        val last = track.newest ?: return null

        // Un'osservazione vecchia non e' il ritardo di adesso.
        //
        // Il feed puo' smettere di parlare di una corsa (mezzo che esce dal
        // servizio, trip-updates che non arrivano piu', app in DIRECT), e
        // senza questo controllo l'ultimo numero visto restava a schermo per
        // sempre: mezz'ora dopo si leggeva ancora "+8 min" come se fosse
        // fresco. `forgetBefore` da solo non basta, perche' gira solo quando
        // arriva uno snapshot nuovo — cioe' mai, proprio nel caso che conta.
        if (nowEpoch > 0 && last.at > 0 && nowEpoch - last.at > STALE_SECONDS) return null

        // Lo `stop_sequence` del feed NON e' la posizione nel pattern.
        //
        // GTFS lo lascia libero, ma il feed di Autolinee Toscane e' stato
        // MISURATO sui dati veri: su 1649 corse con la sequenza dichiarata i
        // valori vanno da 1 a 71 e lo zero non compare mai (452 corse
        // partono da 1). E' 1-based. Il bundle invece indicizza le fermate
        // da 0, e i numeri originali non li conserva: il builder li usa per
        // ordinare le fermate e poi li butta.
        //
        // Confrontarli come se fossero la stessa cosa — che e' quello che si
        // faceva qui — sposta tutto di una fermata, e a sparire e' proprio
        // quella verso cui il bus sta andando: l'unica che all'utente
        // interessa davvero. Un `seq` fuori scala, poi, marcava OGNI fermata
        // come gia' servita e faceva sparire il live per intero.
        val seq = if (last.seq in 0..stopCount) last.seq else -1
        val servedBelow = if (seq > 0) seq - 1 else 0

        if (seq >= 0 && position < servedBelow) {
            return Live(last.delaySeconds, Confidence.SERVED)
        }
        val from = max(servedBelow, 0)
        val ahead = position - from
        if (ahead <= 0) return Live(last.delaySeconds, Confidence.OBSERVED)

        val total = (stopCount - 1) - from
        val fraction = if (total > 0) (ahead.toDouble() / total).coerceIn(0.0, 1.0) else 0.0
        val base = last.delaySeconds.toDouble()
        val trend = track.trend()

        val projected = when {
            trend.slopePerStop != null -> base + trend.slopePerStop * ahead
            // Bloccato e in peggioramento: si tiene tutto il ritardo.
            trend.worsening -> base
            // Nessuna prova in un senso o nell'altro: si consuma strada
            // facendo, che e' quello che i bus fanno di solito.
            else -> base * (1.0 - DEFAULT_RECOVERY * fraction)
        }

        // Un recupero non diventa mai un anticipo, e un ritardo che cresce
        // non raddoppia oltre il ragionevole: la proiezione resta dalla
        // parte del dato osservato.
        val clamped = when {
            base > 0 -> projected.coerceIn(0.0, base * MAX_GROWTH)
            base < 0 -> projected.coerceIn(base * MAX_GROWTH, 0.0)
            else -> projected.coerceIn(-MAX_FROM_ZERO, MAX_FROM_ZERO)
        }
        return Live(Math.round(clamped).toInt(), Confidence.PROJECTED)
    }

    /**
     * Butta tutto.
     *
     * Le chiavi sono indici del bundle, e gli indici NON sopravvivono a un
     * bundle nuovo: sono assegnati nell'ordine in cui il builder scandisce
     * gli orari. Dopo lo scambio notturno i ritardi di ieri restavano
     * appiccicati a indici che ormai indicano altre corse — un ritardo vero,
     * su una linea sbagliata.
     */
    fun clear() = synchronized(lock) { tracks.clear() }

    /** Housekeeping: le corse di cui non si sente parlare da un pezzo. */
    fun forgetBefore(epoch: Long) {
        synchronized(lock) {
            val it = tracks.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value.lastSeen < epoch) it.remove()
            }
        }
    }

    val size: Int get() = synchronized(lock) { tracks.size }

    /**
     * Si sa qualcosa di questa corsa?
     *
     * Una lettura sola, per chi deve chiederlo migliaia di volte dentro un
     * giro stretto — il motore degli itinerari — prima di mettersi a
     * calcolare la proiezione.
     */
    fun knows(tripIndex: Int): Boolean = synchronized(lock) { tracks.containsKey(tripIndex) }

    private companion object {
        /** Quante osservazioni bastano a leggere un andamento senza inseguire il rumore. */
        const val HISTORY = 5

        /**
         * Oltre questa eta' l'osservazione non descrive piu' il presente.
         * Dieci minuti: il feed si rigenera ogni due, quindi cinque giri
         * mancati sono gia' un silenzio che vuol dire qualcosa.
         */
        const val STALE_SECONDS = 600L

        /** Quanto del ritardo si assume recuperato al capolinea, senza altre prove. */
        const val DEFAULT_RECOVERY = 0.30

        /** Oltre questo, una proiezione che cresce non e' piu' una proiezione. */
        const val MAX_GROWTH = 2.0

        const val MAX_FROM_ZERO = 120.0

        /** Crescita che, a bus fermo alla stessa fermata, vale "sta peggiorando". */
        const val WORSENING_SECONDS = 60
        const val WORSENING_WINDOW_SECONDS = 120L
    }
}
