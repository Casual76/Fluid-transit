package dev.antigravity.fluidtransit.routing

/**
 * Da dove parte un viaggio, detto a parole.
 *
 * Senza un rilevamento la partenza e' il centro della mappa, ed era scritto
 * "Dal centro della mappa (GPS spento)" qualunque fosse la ragione. Ma le
 * ragioni sono tre e si rimediano in tre modi diversi: manca il permesso
 * dell'app (un nuovo utente, al primo viaggio, prima di aver mai toccato il
 * mirino), la Posizione di Android e' spenta dal pannello rapido, oppure
 * tutto e' in ordine e il primo rilevamento non e' ancora arrivato. "GPS
 * spento" era falso nel primo caso e inutile negli altri due.
 *
 * E il centro della mappa, al primo avvio, e' la campagna fra Siena e Colle:
 * un viaggio che parte da li' non trova niente, e "Prova a cambiare orario"
 * mandava a cercare il difetto nel posto sbagliato.
 */
object OriginText {

    /** La partenza e' la posizione vera: l'etichetta del pannello dei viaggi. */
    const val HERE = "Dalla tua posizione"

    /** La stessa, nella riga "Da" del pianificatore. */
    const val ROW_HERE = "La tua posizione"

    /** Il tasto che porta la persona a dare una posizione all'app. */
    const val USE_LOCATION = "Usa la mia posizione"

    /**
     * Perche' non abbiamo una posizione, in poche parole.
     *
     * L'ordine e' quello in cui si rimedia: prima il permesso, poi
     * l'interruttore di Android, poi si aspetta.
     */
    fun why(permissionGranted: Boolean, systemLocationOn: Boolean): String = when {
        !permissionGranted -> "posizione non concessa"
        !systemLocationOn -> "posizione del telefono spenta"
        else -> "posizione non ancora trovata"
    }

    /** L'etichetta del pannello dei viaggi quando si parte dal centro della mappa. */
    fun fromMapCenter(permissionGranted: Boolean, systemLocationOn: Boolean): String =
        "Dal centro della mappa (${why(permissionGranted, systemLocationOn)})"

    /** La riga "Da" del pianificatore quando si parte dal centro della mappa. */
    fun rowMapCenter(permissionGranted: Boolean, systemLocationOn: Boolean): String =
        "Il centro della mappa (${why(permissionGranted, systemLocationOn)})"

    /** Partenza e arrivo coincidono: il titolo e il rimedio, che dipende da dove si parte. */
    const val SAME_PLACE_TITLE = "Sei gia' li'"

    fun samePlaceDetail(fromMapCenter: Boolean): String =
        if (fromMapCenter) {
            "Partenza e arrivo sono lo stesso posto. Non hai ancora detto da dove " +
                "parti: senza la tua posizione si parte dal centro della mappa, che " +
                "dopo una ricerca e' proprio il posto trovato."
        } else {
            "Partenza e arrivo sono lo stesso posto. Se non e' quello che volevi, " +
                "scegli da dove parti."
        }

    /**
     * Nessun viaggio: col centro della mappa come partenza la ragione piu'
     * probabile e' la partenza, e dirlo e' il rimedio. L'orario resta fra le
     * possibilita': di notte, con la mappa su Firenze, "nessun viaggio" e'
     * spesso proprio quello, e chi non ha dato il permesso non deve perdere
     * anche quell'indizio.
     */
    const val NO_JOURNEY_TITLE = "Nessun viaggio trovato"

    fun noJourneyDetail(fromMapCenter: Boolean): String =
        if (fromMapCenter) {
            "Stai partendo dal centro della mappa, non da dove sei: da li' il bus " +
                "potrebbe non passare. Scegli da dove parti, usa la tua posizione, " +
                "oppure prova a cambiare orario."
        } else {
            "In questa finestra il bus non ci arriva. Prova a cambiare orario."
        }
}
