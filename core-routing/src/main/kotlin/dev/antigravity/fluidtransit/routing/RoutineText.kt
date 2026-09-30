package dev.antigravity.fluidtransit.routing

/**
 * Cosa dice l'app di una routine appena creata.
 *
 * Il pannello scriveva "ti diro' io quando uscire" appena si toccava "Crea la
 * routine", qualunque cosa rispondesse Android alla richiesta delle
 * notifiche. Con le notifiche spente la promessa era falsa: la routine
 * risultava attiva in Oggi e ogni avviso "Esci alle..." cadeva in silenzio,
 * cosi' chi lo scopriva lo scopriva perdendo l'autobus. La promessa si fa
 * solo se l'avviso puo' arrivare, e le due frasi stanno qui accanto perche'
 * il pannello e la capsula che avvisa del rifiuto dicano la stessa cosa.
 */
object RoutineText {

    /** Cosa si perde a notifiche spente: la routine resta, l'avviso no. */
    const val ALERTS_OFF =
        "L'avviso per uscire di casa non ti arrivera': il consiglio lo trovi solo " +
            "nella scheda Oggi."

    /**
     * La frase sotto il tasto, a routine creata.
     *
     * [alertsOn] vuol dire che Android lascia arrivare le notifiche dell'app
     * adesso, non che una volta lo facesse.
     */
    fun created(alertsOn: Boolean): String =
        if (alertsOn) {
            "Routine creata: la trovi nella scheda Oggi. Nei giorni scelti " +
                "ti diro' io quando uscire."
        } else {
            "Routine creata, ma le notifiche sono spente. $ALERTS_OFF"
        }
}
