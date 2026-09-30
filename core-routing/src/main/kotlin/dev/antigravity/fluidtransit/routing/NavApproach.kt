package dev.antigravity.fluidtransit.routing

/**
 * Dov'e' il mezzo che sto aspettando, contato in fermate.
 *
 * "Parte fra 4 minuti" e' un numero che non si puo' controllare: o ci credi
 * o non ci credi. "E' a tre fermate da te" si controlla guardando fuori, ed
 * e' la sola frase che fa capire a chiunque che il bus esiste davvero e si
 * sta avvicinando.
 *
 * La regola di dove sia arrivato il mezzo e' una sola in tutta l'app —
 * [TripProgress.nextPosition] — ed e' la stessa che usano il tabellone, la
 * scheda della corsa e la navigazione a bordo. Qui non se ne inventa
 * un'altra: si traduce in fermate quello che quella gia' risponde.
 */
object NavApproach {

    /** Una fermata fra il mezzo e me, gia' in parole. */
    class Stop(val position: Int, val name: String, val etaEpoch: Long)

    class State(
        /**
         * Fermate fra il mezzo e il punto di riferimento.
         *
         * -1 vuol dire "non si sa", che non e' zero: zero vuol dire "il bus
         * e' alla tua fermata", ed e' la frase che fa correre.
         */
        val stopsAway: Int,
        /**
         * Le fermate che il mezzo deve ancora servire, dalla sua posizione
         * fino al riferimento COMPRESO. L'ultima della lista e' la mia.
         */
        val stops: List<Stop>,
        /** Quando il mezzo arriva al riferimento. 0 = non si sa. */
        val arrivalEpoch: Long,
        /** Da dove viene il numero: decide se i pallini sono pieni o vuoti. */
        val certainty: Certainty?,
        /** Quante fermate la lista ha dovuto nascondere per stare nel tetto. */
        val hidden: Int = 0,
        /**
         * Il feed segue la corsa e dice che il mezzo ha gia' lasciato il
         * riferimento. [stopsAway] resta zero, ma zero vuol dire "alla tua
         * fermata": chi aspetta non deve leggere quella frase, ne' sentire
         * "il tuo bus sta arrivando", per un bus che se n'e' andato.
         */
        val passed: Boolean = false,
    ) {
        val known: Boolean get() = stopsAway >= 0
    }

    val UNKNOWN = State(-1, emptyList(), 0L, null)

    /**
     * Il mezzo di [trip] ha gia' lasciato [position]? E' la risposta di
     * [State.passed], anche per chi non deve disegnare le fermate: chi
     * cammina verso la fermata vuole sapere solo questo.
     *
     * Solo quando il feed dichiara SERVITA quella fermata, e non con la
     * regola di [TripProgress], che quando il feed tace chiede all'orologio.
     * Qui l'orologio mentirebbe nel modo peggiore: una corsa seguita di cui
     * si e' persa la previsione ricade sull'orario di tabella, e un bus con
     * otto minuti di ritardo risulterebbe "gia' passato" sei minuti dopo
     * l'orario — mentre chi lo aspetta e' ancora in strada per prenderlo.
     */
    fun passed(
        live: LiveTimes?,
        trip: Int,
        stopCount: Int,
        position: Int,
        nowEpoch: Long,
    ): Boolean {
        if (live == null || trip < 0 || position < 0 || position >= stopCount) return false
        if (!live.monitored(trip, nowEpoch)) return false
        return live.at(trip, position, stopCount, nowEpoch)?.certainty == Certainty.SERVED
    }

    /**
     * Quante fermate mancano al mezzo della corsa [trip] per arrivare in
     * [toPosition], e quali sono.
     *
     * Nomi e orari entrano come lambda apposta: cosi' questa funzione non ha
     * bisogno di un bundle e si prova con venti righe di finto.
     *
     * @param maxDots quanti pallini la barra puo' disegnare davvero. Le
     *   fermate in mezzo che non ci stanno finiscono in [State.hidden].
     * @param requireVehicle serve un mezzo vivo perche' la risposta valga?
     *   Vero mentre si ASPETTA: li' il numero e' un'affermazione su un
     *   autobus che non si vede, e senza feed quell'autobus potrebbe non
     *   essere mai partito. Falso quando si e' A BORDO: il mezzo e' sotto i
     *   piedi, e l'orologio basta a dire quante fermate mancano — e' la
     *   stessa regola con cui la scheda della corsa elenca le sue.
     */
    fun between(
        live: LiveTimes?,
        trip: Int,
        stopCount: Int,
        toPosition: Int,
        nowEpoch: Long,
        maxDots: Int = 6,
        fallbackDelaySeconds: Int = 0,
        requireVehicle: Boolean = true,
        name: (Int) -> String,
        scheduledAt: (Int) -> Long,
    ): State {
        if (trip < 0 || stopCount <= 0 || toPosition < 0 || toPosition >= stopCount) return UNKNOWN

        // Il numero e' un'affermazione su un MEZZO. Senza un feed che stia
        // seguendo questa corsa non c'e' nessun mezzo di cui dire dov'e', e
        // riempire il vuoto con la tabella vorrebbe dire disegnare un bus
        // che potrebbe non essere mai partito.
        if (requireVehicle && (live == null || !live.monitored(trip, nowEpoch))) return UNKNOWN

        val next = TripProgress.nextPosition(
            live,
            trip,
            stopCount,
            nowEpoch,
            fallbackDelaySeconds,
            scheduledAt,
        )
        val passato = passed(live, trip, stopCount, toPosition, nowEpoch)
        // Corsa finita: non c'e' piu' niente da aspettare.
        if (next < 0) return if (passato) State(-1, emptyList(), 0L, null, passed = true) else UNKNOWN

        val target = live?.at(trip, toPosition, stopCount, nowEpoch)
        val arrival = scheduledAt(toPosition) + (target?.delaySeconds ?: fallbackDelaySeconds)

        // Il mezzo ha gia' passato il riferimento: zero, mai un negativo.
        // Capita sul serio, e non e' un errore — e' il bus che ti e'
        // appena sfuggito.
        val away = (toPosition - next).coerceAtLeast(0)

        val tutte = ArrayList<Stop>(away + 1)
        for (pos in minOf(next, toPosition)..toPosition) {
            val at = live?.at(trip, pos, stopCount, nowEpoch)
            tutte.add(
                Stop(
                    position = pos,
                    name = name(pos),
                    etaEpoch = scheduledAt(pos) + (at?.delaySeconds ?: fallbackDelaySeconds),
                ),
            )
        }

        if (tutte.size <= maxDots) {
            return State(away, tutte, arrival, target?.certainty, passed = passato)
        }
        // Si tiene dov'e' il bus adesso e le ultime prima di me: il buco in
        // mezzo lo racconta un numero. Le fermate lontane non le guarda
        // nessuno, quelle vicine si.
        val mostrate = ArrayList<Stop>(maxDots)
        mostrate.add(tutte.first())
        mostrate.addAll(tutte.subList(tutte.size - (maxDots - 1), tutte.size))
        return State(
            away,
            mostrate,
            arrival,
            target?.certainty,
            hidden = tutte.size - maxDots,
            passed = passato,
        )
    }
}
