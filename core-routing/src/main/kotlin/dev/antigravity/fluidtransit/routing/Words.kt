package dev.antigravity.fluidtransit.routing

/**
 * Le parole che cambiano col numero.
 *
 * "1 fermate rimaste" e "1 cambi" non sono errori gravi, ma sono la prima
 * cosa che si nota e dicono che nessuno ha guardato. Nell'app ce n'erano una
 * mezza dozzina, ognuna scritta a mano dove serviva, e tre su sei sbagliate:
 * il singolare si ricorda quando ci si pensa, e a un certo punto non ci si
 * pensa piu'.
 *
 * Una riga sola, quindi, e il singolare non si dimentica piu'.
 */
object Words {

    /** `count(1, "fermata", "fermate")` -> "1 fermata"; con 3 -> "3 fermate". */
    fun count(n: Int, one: String, many: String): String =
        if (n == 1) "$n $one" else "$n $many"

    /**
     * Una distanza come la direbbe una persona: "350 m", "2,4 km".
     *
     * Sotto il chilometro i metri, che a piedi sono la grana giusta; sopra i
     * chilometri con un decimale, perche' "2437 m" e' un numero da
     * convertire. La virgola e non il punto: si legge in italiano.
     */
    fun distance(meters: Double): String {
        if (meters < 0) return ""
        if (meters < 1000) return "${meters.toInt()} m"
        return "%.1f km".format(meters / 1000).replace('.', ',')
    }

    /**
     * L'eta' di un dato: "18s", "6 min", "1 h 12 min".
     *
     * Sotto il minuto i secondi, perche' e' li' che la differenza conta —
     * "18s" vuol dire adesso, "50s" vuol dire quasi adesso. Sopra, i minuti:
     * la scheda di un bus scriveva "aggiornata 342s fa", e trecentoquarantadue
     * secondi sono un numero che si deve dividere prima di capirlo.
     */
    fun age(seconds: Int): String =
        if (seconds < 60) "${seconds.coerceAtLeast(0)}s" else Times.durationLabel(seconds)

    /**
     * La stessa eta', quando chi la porta la tiene in un `Long`.
     *
     * Le eta' dei feed si calcolano in millisecondi e viaggiano in `Long`:
     * senza questa riga ogni chiamante scriveva `.toInt()` per conto suo, e
     * un feed con la marca temporale a zero — che capita, ed e' un'eta' di
     * cinquantasei anni — diventava un numero negativo. Qui si tosa prima.
     */
    fun age(seconds: Long): String =
        age(seconds.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
}
