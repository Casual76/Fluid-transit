package dev.antigravity.fluidtransit.routing

import kotlin.math.ln
import kotlin.math.min

/**
 * Quanto un risultato c'entra con quello che hai scritto — con UN criterio
 * solo, valido per fermate, linee, luoghi, vie e civici.
 *
 * Serve perche' fino alla Fase 8 la ricerca aveva criteri scollegati e uno
 * solo di essi teneva un punteggio: i luoghi si ordinavano fra loro, le
 * fermate no, e le due liste venivano incollate una dopo l'altra. Il
 * risultato, misurato sul file vero il 03/09: duecento "Via Roma" toscane
 * con lo stesso identico punteggio, restituite in ordine di file — cioe' la
 * propria via non usciva mai — e i luoghi relegati sotto venticinque
 * fermate, fuori dallo schermo.
 *
 * Tre cose contano insieme:
 *  - quanto il testo assomiglia, e DOVE (nel nome vale piu' che nel contorno,
 *    parola intera vale piu' che inizio di un'altra parola);
 *  - quanto la parola e' RARA, perche' in "scuola agnoletti sesto" la parola
 *    che dice davvero cosa cerchi e' una sola;
 *  - quanto e' vicino, perche' e' cio' che distingue la tua Via Roma dalle
 *    altre centonovantanove.
 *
 * Il punteggio lavora su una stringa sola — nome e contorno concatenati, con
 * l'indice dove finisce il nome — perche' l'indice dei luoghi ha 170.816
 * voci e tenerne due copie normalizzate costerebbe piu' memoria di quanta ne
 * valga la pena.
 */
object Relevance {

    /** Il candidato non c'entra abbastanza: si scarta. */
    const val NO_MATCH = Int.MIN_VALUE

    /** Le parole della query, normalizzate come l'indice. */
    fun tokens(query: String): List<String> =
        normalize(query).split(' ').filter { it.isNotEmpty() }

    /** Minuscole, senza accenti, apostrofi come spazi: scrittura e ricerca uguali. */
    fun normalize(s: String): String = Places.normalize(s)

    /** Concatena nome e contorno come li vogliono [score] e [Session]. */
    fun haystack(nameNorm: String, extraNorm: String): String =
        if (extraNorm.isEmpty()) nameNorm else "$nameNorm $extraNorm"

    /**
     * Una ricerca, in due passate.
     *
     * La prima passata guarda TUTTE le voci: raccoglie quanto ognuna
     * combacia e, insieme, quante voci contengono ciascuna parola. La
     * seconda pesa le parole rare piu' di quelle comuni e produce il
     * punteggio finale.
     *
     * Senza le due passate non si puo' sapere che "scuola" e' comune e
     * "agnoletti" no — ed e' proprio questo che sul telefono faceva finire
     * il Liceo Agnoletti sotto tre scuole qualsiasi che stavano solo un po'
     * piu' vicine.
     */
    class Session(private val tokens: List<String>) {

        private class Cand(
            val id: Int,
            val values: IntArray,
            val matched: Int,
            /** Bonus di forma gia' risolti nella prima passata. */
            val statics: Int,
        )

        private val n = tokens.size
        private val joined = tokens.joinToString(" ")

        /**
         * Un token puo' mancare, ma solo nelle query lunghe: "scuola
         * agnoletti sesto" deve trovare il liceo anche se l'etichetta
         * geografica dice Campi Bisenzio, perche' i confini amministrativi
         * non stanno in testa a nessuno. Chi manca paga in punteggio.
         */
        private val required = if (n >= 3) n - 1 else n

        private val df = IntArray(n)
        private var scanned = 0
        private val cands = ArrayList<Cand>(256)
        private var weights: DoubleArray? = null

        val isEmpty: Boolean get() = n == 0

        /** La prima passata, una voce alla volta. */
        fun observe(id: Int, hay: String, nameEnd: Int) {
            scanned++
            if (n == 0 || hay.isEmpty()) return
            val values = IntArray(n)
            var matched = 0
            for (t in 0 until n) {
                val v = bestTokenValue(hay, nameEnd, tokens[t])
                if (v > 0) {
                    values[t] = v
                    matched++
                    df[t]++
                }
            }
            if (matched < required) return
            var statics = 0
            if (nameEnd == joined.length && hay.startsWith(joined)) {
                statics += 30
            } else if (hay.startsWith(joined)) {
                statics += 14
            }
            // La lunghezza si misura sul NOME, non su nome piu' contorno:
            // prima si puniva chi aveva un contesto ricco, cioe' proprio le
            // voci descritte meglio.
            statics -= min(10, nameEnd / 12)
            if (matched < n) statics -= 12
            cands.add(Cand(id, values, matched, statics))
        }

        val candidateCount: Int get() = cands.size

        fun candidateId(k: Int): Int = cands[k].id

        /**
         * Il punteggio finale della voce [k]. [kindBonus] conta poco e solo
         * a parita' di pertinenza; [distanceMeters] negativo se la posizione
         * non si sa, e allora la vicinanza non entra e non penalizza nessuno.
         */
        fun score(k: Int, kindBonus: Int, distanceMeters: Double): Int {
            val w = weights ?: computeWeights().also { weights = it }
            val c = cands[k]
            var sum = 0.0
            for (t in 0 until n) if (c.values[t] > 0) sum += c.values[t] * w[t]
            return Math.round(sum).toInt() + c.statics + kindBonus + proximityBonus(distanceMeters)
        }

        /**
         * Il peso di ogni parola: rara vale di piu'. E' l'inverse document
         * frequency di sempre, riportata attorno a 1 per una parola di
         * frequenza normale e tenuta fra mezzo e tre volte, perche' oltre
         * comincia a fare scherzi sui refusi.
         */
        private fun computeWeights(): DoubleArray = DoubleArray(n) { t ->
            val d = df[t].coerceAtLeast(1)
            (ln(1.0 + scanned.toDouble() / d) / 4.0).coerceIn(0.5, 3.0)
        }
    }

    /**
     * I migliori [capacity] per punteggio, senza ordinare tutto il resto.
     *
     * Serve perche' una parola comune come "via" lascia in gara decine di
     * migliaia di voci, mentre a schermo ne finiscono otto: leggere nome e
     * contesto di tutte, dal file mappato, costerebbe piu' del punteggio.
     */
    class TopK(private val capacity: Int) {
        private val ids = IntArray(capacity)
        private val scores = IntArray(capacity)
        var size = 0
            private set

        fun offer(id: Int, score: Int) {
            if (size == capacity && score <= scores[size - 1]) return
            var at = if (size < capacity) size else capacity - 1
            while (at > 0 && scores[at - 1] < score) {
                ids[at] = ids[at - 1]
                scores[at] = scores[at - 1]
                at--
            }
            ids[at] = id
            scores[at] = score
            if (size < capacity) size++
        }

        fun forEachByScore(block: (id: Int, score: Int) -> Unit) {
            for (i in 0 until size) block(ids[i], scores[i])
        }
    }

    /**
     * Il punteggio di un candidato isolato, senza la pesatura per rarita'
     * (che richiede di aver visto tutto l'indice). Per i casi in cui si
     * confronta poca roba, e per i test.
     */
    fun score(
        hay: String,
        nameEnd: Int,
        tokens: List<String>,
        kindBonus: Int,
        distanceMeters: Double,
    ): Int {
        if (tokens.isEmpty() || hay.isEmpty()) return NO_MATCH
        var score = 0
        var matched = 0
        for (t in tokens) {
            val v = bestTokenValue(hay, nameEnd, t)
            if (v > 0) {
                matched++
                score += v
            }
        }
        val required = if (tokens.size >= 3) tokens.size - 1 else tokens.size
        if (matched < required) return NO_MATCH
        if (matched < tokens.size) score -= 12

        val joined = tokens.joinToString(" ")
        if (nameEnd == joined.length && hay.startsWith(joined)) {
            score += 30
        } else if (hay.startsWith(joined)) {
            score += 14
        }
        score -= min(10, nameEnd / 12)
        score += kindBonus
        score += proximityBonus(distanceMeters)
        return score
    }

    /** La comodita' per chi ha nome e contorno gia' separati. */
    fun score(
        nameNorm: String,
        extraNorm: String,
        tokens: List<String>,
        kindBonus: Int,
        distanceMeters: Double,
    ): Int = score(haystack(nameNorm, extraNorm), nameNorm.length, tokens, kindBonus, distanceMeters)

    /**
     * Il premio per la vicinanza, a fasce.
     *
     * A fasce e non con una curva continua perche' cosi' e' prevedibile: due
     * risultati nella stessa citta' restano ordinati da quanto il testo
     * combacia, non da trecento metri di differenza.
     */
    fun proximityBonus(distanceMeters: Double): Int = when {
        distanceMeters < 0 -> 0
        distanceMeters < 2_000 -> 34
        distanceMeters < 6_000 -> 26
        distanceMeters < 15_000 -> 18
        distanceMeters < 40_000 -> 10
        distanceMeters < 90_000 -> 4
        else -> 0
    }

    /**
     * Quanto vale la migliore occorrenza di [token]. Zero se manca.
     *
     * Tre gradi, e la distinzione fra i primi due e' quella che fa la
     * differenza vera: "roma" dentro "Roma Termini" e' una PAROLA INTERA,
     * dentro "Romagnosi" e' solo l'inizio di un'altra parola. Senza questa
     * distinzione le due valevano uguale, e cercando "via roma 12" si
     * finiva in via Romagnosi.
     */
    private fun bestTokenValue(hay: String, nameEnd: Int, token: String): Int {
        var best = 0
        var at = hay.indexOf(token)
        while (at >= 0) {
            val end = at + token.length
            val startsWord = at == 0 || hay[at - 1] == ' '
            val endsWord = end == hay.length || hay[end] == ' '
            val inName = end <= nameEnd
            val value = when {
                inName && startsWord && endsWord -> 14
                inName && startsWord -> 9
                inName -> 4
                startsWord && endsWord -> 6
                startsWord -> 4
                else -> 1
            }
            if (value > best) best = value
            if (best == 14) return best
            at = hay.indexOf(token, at + 1)
        }
        return best
    }
}
