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

    /** Un giro di trip-updates per una corsa. [nextStopSeq] -1 se ignota. */
    fun observe(tripKey: Int, delaySeconds: Int, nextStopSeq: Int, atEpoch: Long) {
        tracks.getOrPut(tripKey) { Track() }
            .add(Sample(delaySeconds, nextStopSeq, atEpoch))
    }

    /** Il ritardo corrente della corsa, quello della testata. Null se non si sa. */
    fun current(tripKey: Int): Int? = tracks[tripKey]?.newest?.delaySeconds

    /** La fermata verso cui il bus sta andando, se il feed la dichiara. */
    fun nextStop(tripKey: Int): Int? =
        tracks[tripKey]?.newest?.seq?.takeIf { it >= 0 }

    /**
     * Il ritardo da applicare alla fermata in posizione [position] di un
     * pattern che ne ha [stopCount]. Null quando di quella corsa non
     * sappiamo niente.
     */
    fun at(tripKey: Int, position: Int, stopCount: Int): Live? {
        val track = tracks[tripKey] ?: return null
        val last = track.newest ?: return null
        val seq = last.seq

        if (seq >= 0 && position < seq) {
            return Live(last.delaySeconds, Confidence.SERVED)
        }
        val from = max(seq, 0)
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

    /** Housekeeping: le corse di cui non si sente parlare da un pezzo. */
    fun forgetBefore(epoch: Long) {
        val it = tracks.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.lastSeen < epoch) it.remove()
        }
    }

    val size: Int get() = tracks.size

    private companion object {
        /** Quante osservazioni bastano a leggere un andamento senza inseguire il rumore. */
        const val HISTORY = 5

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
