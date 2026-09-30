package dev.antigravity.fluidtransit.routing

/**
 * La rete vista da chi deve solo sapere "quale pattern passa di qui".
 *
 * Esiste come interfaccia e non come [BundleReader] per una ragione sola:
 * la fixture dei test scrive una linea e un pattern, e contro un bundle
 * vero i casi che contano — il verso opposto, l'anello che passa due volte
 * — non si possono costruire senza allargare una fixture che usano tutti.
 */
interface PatternTopology {
    fun patternsAtStop(stop: Int): IntArray
    fun patternStopCount(p: Int): Int
    fun patternStop(p: Int, position: Int): Int
    fun patternRoute(p: Int): Int

    companion object {
        fun of(reader: BundleReader): PatternTopology = object : PatternTopology {
            override fun patternsAtStop(stop: Int): IntArray = reader.patternsAtStop(stop)
            override fun patternStopCount(p: Int): Int = reader.patternStopCount(p)
            override fun patternStop(p: Int, position: Int): Int = reader.patternStop(p, position)
            override fun patternRoute(p: Int): Int = reader.patternRoute(p)
        }
    }
}

/**
 * Le linee che vanno bene lo stesso.
 *
 * Chi aspetta alla fermata non aspetta "la 23": aspetta qualcosa che lo
 * porti dove deve andare. Se dalla stessa banchina passa anche la 22 e
 * scende alla stessa fermata, toglierla dalla mappa perche' non e' quella
 * del piano e' esattamente il modo in cui un'app fa perdere un autobus a
 * qualcuno.
 *
 * E' anche la risposta alla corsa cancellata: quando la 23 non arriva piu',
 * queste non sono un di piu' — sono l'unica cosa da dire.
 */
object NavAlternatives {

    class Alternative(
        val pattern: Int,
        val route: Int,
        val boardPosition: Int,
        val alightPosition: Int,
    ) {
        /** Quante fermate si fanno a bordo. Meno sono, meglio e'. */
        val stops: Int get() = alightPosition - boardPosition
    }

    /**
     * I pattern che passano da una delle [boardStops] e toccano una delle
     * [alightStops] PIU' AVANTI nella loro sequenza.
     *
     * "Piu' avanti" e' tutto il mestiere di questa funzione: la stessa linea
     * tocca quasi sempre entrambe le fermate, ma nel verso che torna
     * indietro, e una linea che ti porta dalla parte opposta e' peggio di
     * nessuna linea.
     *
     * Le banchine sorelle vanno espanse dal chiamante (con
     * [StopGroups.siblings]): qui dentro non si sa niente di banchine, e
     * cosi' resta una funzione pura.
     *
     * Una linea sola per [Alternative.route]: fra due pattern della stessa
     * linea vince quello con meno fermate in mezzo.
     */
    fun serving(
        net: PatternTopology,
        boardStops: IntArray,
        alightStops: IntArray,
        limit: Int = 12,
    ): List<Alternative> {
        if (boardStops.isEmpty() || alightStops.isEmpty()) return emptyList()

        val visti = HashSet<Int>()
        val perLinea = LinkedHashMap<Int, Alternative>()
        for (stop in boardStops) {
            for (p in net.patternsAtStop(stop)) {
                if (!visti.add(p)) continue
                val alt = match(net, p, boardStops, alightStops) ?: continue
                val prima = perLinea[alt.route]
                if (prima == null || alt.stops < prima.stops) perLinea[alt.route] = alt
            }
        }
        return perLinea.values
            .sortedWith(compareBy({ it.stops }, { it.route }))
            .take(limit)
    }

    /**
     * La prima salita da cui la discesa si raggiunge, dentro un pattern.
     *
     * Il ciclo aggancia la PRIMA occorrenza utile e non la piu' corta perche'
     * su un anello — 215 pattern su 8.331 passano due volte dalla stessa
     * fermata — le due occorrenze sono lo stesso mezzo che ripassa: salire
     * al secondo giro vuol dire aspettare un giro intero.
     */
    private fun match(
        net: PatternTopology,
        pattern: Int,
        boardStops: IntArray,
        alightStops: IntArray,
    ): Alternative? {
        val n = net.patternStopCount(pattern)
        var board = -1
        for (i in 0 until n) {
            val stop = net.patternStop(pattern, i)
            if (board < 0) {
                if (boardStops.has(stop)) board = i
                continue
            }
            if (alightStops.has(stop)) {
                return Alternative(pattern, net.patternRoute(pattern), board, i)
            }
        }
        return null
    }

    private fun IntArray.has(value: Int): Boolean {
        for (v in this) if (v == value) return true
        return false
    }
}
