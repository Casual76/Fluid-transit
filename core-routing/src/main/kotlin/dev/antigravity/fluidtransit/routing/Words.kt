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
}
