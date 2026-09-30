package dev.antigravity.fluidtransit.routing

/**
 * I giorni di una routine, a parole.
 *
 * Stavano in due funzioni private dell'assistente, una per l'elenco e una per
 * le schede, con la stessa logica e lo stesso difetto: i nomi brevi li
 * chiedevano alla piattaforma (`DayOfWeek.getDisplayName`), che sulla JVM dei
 * test scrive "lun" e su certi telefoni "Lun." — la trappola dei mesi, vedi
 * [Words.month]. Adesso la frase e' una, e l'assistente la usa anche per dire
 * all'utente COSA sta per confermare: "dal lunedi' al venerdi'", non un
 * "Confermi?" nudo.
 */
object DaysText {

    private val BREVI = listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")

    /**
     * `days` e' lunedi' = 1 ... domenica = 7, come `java.time.DayOfWeek`.
     * Un numero fuori da 1..7 non e' un giorno e si salta.
     */
    fun label(days: Set<Int>): String {
        val valid = days.filter { it in 1..7 }.toSortedSet()
        return when {
            valid.isEmpty() -> "mai"
            valid.size == 7 -> "tutti i giorni"
            valid == setOf(1, 2, 3, 4, 5) -> "dal lunedi' al venerdi'"
            valid == setOf(6, 7) -> "sabato e domenica"
            else -> valid.joinToString(", ") { BREVI[it - 1] }
        }
    }
}
