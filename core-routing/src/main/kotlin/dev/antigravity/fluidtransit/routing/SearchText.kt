package dev.antigravity.fluidtransit.routing

/**
 * Cosa dice la ricerca quando non trova niente e si sta compilando una riga
 * "Da" o "A" del pianificatore.
 *
 * In quel caso le linee non si offrono (non sono posti), e le frasi della
 * ricerca normale diventano false: con "6" nel campo dicevano "Nessuna linea
 * si chiama cosi'" mentre le linee 6 esistono, solo che qui sono filtrate. Il
 * filtro e' nostro, non un fatto sul mondo, e la frase deve dire quello che e'
 * vero: qui servono fermate o luoghi.
 */
object SearchText {

    /** Una lettera sola: nel pianificatore non basta, le sigle di linea non contano. */
    const val PLANNER_ONE_CHAR =
        "Qui servono fermate o luoghi: scrivi almeno due lettere."

    /** Niente fra fermate e luoghi, e si spiega perche' non ci sono le linee. */
    const val PLANNER_NONE =
        "Niente con questo nome fra fermate e luoghi. Le linee non si possono " +
            "usare come partenza o arrivo."

    /** I luoghi non sono pronti: si parla delle sole fermate, mai di "fermate e linee". */
    const val PLANNER_STOPS_ONLY = "Fermate non ne ho con questo nome. "
}
