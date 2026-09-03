package dev.antigravity.fluidtransit.routing

/**
 * Gli orari di una corsa, fermata per fermata, con le gemelle separate.
 *
 * Il feed regionale pubblica gli orari al minuto tondo e con arrivo uguale
 * a partenza (misurato in Fase 1: 77 soste non nulle su 270.388 posizioni,
 * lo 0,03%). Conseguenza diretta e visibile: due fermate vicine finiscono
 * con lo STESSO identico orario, e la scheda della corsa mostra due righe
 * "14:32" una sotto l'altra, come se l'app avesse sbagliato i conti.
 *
 * La scelta dell'utente e' stimare noi il divario. Qui il tempo che il feed
 * assegna in blocco a un gruppo di fermate gemelle viene ridistribuito in
 * proporzione alla distanza vera fra quelle fermate: chi e' piu' lontano
 * arriva piu' tardi, e la corsa torna a scorrere.
 *
 * La distanza e' quella in linea d'aria fra le fermate, non lungo la
 * strada: per separare fermate a poche centinaia di metri il RAPPORTO fra
 * le distanze e' lo stesso nei due casi, e cosi' non serve decodificare
 * nessuna polilinea — questa funzione la chiamano anche i widget.
 *
 * Resta una STIMA, e come tale va detta dove compare.
 */
object StopTimes {

    /**
     * Gli offset di percorrenza (secondi dalla partenza della corsa) del
     * pattern [pattern] col profilo [profile], gia' separati.
     */
    fun offsets(reader: BundleReader, pattern: Int, profile: Int): IntArray {
        val n = reader.patternStopCount(pattern)
        val raw = IntArray(n) { reader.profileOffset(profile, it) }
        if (n < 2) return raw
        val gaps = DoubleArray(n - 1) { k ->
            val a = reader.patternStop(pattern, k)
            val b = reader.patternStop(pattern, k + 1)
            BundleReader.haversine(
                reader.stopLat(a), reader.stopLon(a),
                reader.stopLat(b), reader.stopLon(b),
            )
        }
        return spread(raw, gaps)
    }

    /** Quante fermate ereditano l'orario della precedente: per la diagnostica. */
    fun twinCount(offsets: IntArray): Int {
        var twins = 0
        for (i in 1 until offsets.size) if (offsets[i] == offsets[i - 1]) twins++
        return twins
    }

    /**
     * Il cuore, senza bundle attorno: [offsets] sono i secondi grezzi del
     * profilo, [gapsMeters] le distanze fra fermate consecutive (n-1 valori).
     *
     * L'orario che il feed dichiara per la PRIMA fermata di un gruppo non si
     * tocca mai — e' l'unico che il feed asserisce davvero: si spostano in
     * avanti solo le gemelle che la seguono, e sempre restando prima della
     * fermata successiva vera.
     */
    fun spread(offsets: IntArray, gapsMeters: DoubleArray): IntArray {
        val n = offsets.size
        val out = offsets.copyOf()
        if (n < 2 || gapsMeters.size < n - 1) return out
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && out[j + 1] == out[i]) j++
            if (j > i) spreadRun(out, gapsMeters, i, j)
            i = j + 1
        }
        return out
    }

    // --------------------------------------------------------------- interni

    /** Distribuisce il gruppo di gemelle [from]..[to], estremi inclusi. */
    private fun spreadRun(out: IntArray, gaps: DoubleArray, from: Int, to: Int) {
        val n = out.size
        val start = out[from]

        // Il tempo a disposizione arriva fino alla prima fermata con un
        // orario diverso. Se le gemelle chiudono la corsa non c'e' un
        // "dopo", e si estrapola col ritmo con cui la corsa ci e' arrivata.
        val open = to + 1 >= n
        val budget: Int
        if (!open) {
            budget = out[to + 1] - start
        } else {
            val pace = paceAt(out, gaps, from)
            if (pace <= 0.0) return
            budget = Math.round(span(gaps, from, to) / pace).toInt()
        }
        val slots = if (open) to - from else to - from + 1
        // Meno di un secondo a testa: non c'e' niente da distribuire senza
        // inventare, e il dato resta com'e'.
        if (budget < slots) return

        val total = span(gaps, from, if (open) to else to + 1)
        if (total <= 0.0) return

        for (k in from + 1..to) {
            var t = start + Math.round(budget * (span(gaps, from, k) / total)).toInt()
            t = t.coerceAtLeast(out[k - 1] + 1)
            if (!open) t = t.coerceAtMost(out[to + 1] - (to - k) - 1)
            out[k] = t
        }
    }

    /** Metri fra la fermata [a] e la fermata [b]. */
    private fun span(gaps: DoubleArray, a: Int, b: Int): Double {
        var sum = 0.0
        for (k in a until b) sum += gaps[k]
        return sum
    }

    /** Metri al secondo con cui la corsa e' arrivata alla fermata [at]. */
    private fun paceAt(out: IntArray, gaps: DoubleArray, at: Int): Double {
        if (at <= 0) return 0.0
        val seconds = out[at] - out[at - 1]
        if (seconds <= 0) return 0.0
        return gaps[at - 1] / seconds
    }
}
