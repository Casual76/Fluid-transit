package dev.antigravity.fluidtransit.routing

import java.time.Instant

/**
 * Le prossime partenze da una fermata, in UN modello solo.
 *
 * Prima di questo file lo stesso calcolo stava scritto in sei posti — la
 * scheda fermata, la scheda Oggi, i Preferiti, il widget, la navigazione, il
 * ponte dell'assistente — ognuno con la sua idea di cosa mostrare, il suo
 * battito e le sue parole. La stessa fermata, aperta da due strade diverse,
 * poteva dire "3 min" da una parte e "5 min" dall'altra nello stesso istante,
 * e nessuno dei due era sbagliato: erano stati calcolati a quindici secondi di
 * distanza, con regole diverse su quali corse contare.
 *
 * Non era un difetto di una schermata: era la ragione per cui l'app sembrava
 * approssimativa anche quando i dati erano giusti.
 *
 * Qui c'e' il dato. La grammatica sta in [DepartureText], la riga disegnata
 * sta nell'app, e tutti e tre vengono da qui.
 */

/**
 * Quanto ci si puo' fidare dell'orario, e da dove viene.
 *
 * L'ordine non e' casuale: va dal piu' certo al meno certo, e la UI puo'
 * confrontarli. `DECLARED` era nato vuoto, in attesa delle previsioni per
 * fermata; da quando ci sono, lo produce il loro lettore, e nessuna schermata
 * ha dovuto cambiare — che era il punto di definire il vocabolario prima.
 */
enum class Certainty {
    /** La fermata e' alle spalle del mezzo: quel ritardo non la riguarda piu'. */
    SERVED,

    /** Il feed dichiara una previsione per QUESTA fermata. */
    DECLARED,

    /**
     * Il feed dichiara una previsione per una fermata precedente, e la regola
     * di GTFS-RT la porta fin qui: vale finche' non ce n'e' un'altra.
     */
    PROPAGATED,

    /** Nessuna previsione copre questa fermata: il numero e' una stima nostra. */
    ESTIMATED,
}

/** Cosa il tempo reale sa di una corsa. L'implementazione sta nell'app. */
interface LiveTimes {

    class At(val delaySeconds: Int, val certainty: Certainty)

    /**
     * Il ritardo da applicare alla fermata in posizione [position] di un
     * pattern che ne ha [stopCount]. Null quando di quella corsa non si sa
     * niente, o quando quello che si sapeva e' troppo vecchio.
     */
    fun at(tripIndex: Int, position: Int, stopCount: Int, nowEpoch: Long): At?

    /**
     * Il feed sa qualcosa di questa corsa?
     *
     * Serve al motore degli itinerari, che chiede il ritardo per ogni corsa
     * candidata dentro il suo giro piu' stretto: una risposta secca prima di
     * mettersi a cercare la previsione giusta e' la differenza fra una
     * lettura e una ricerca binaria, moltiplicata per le migliaia di corse
     * che un calcolo scandisce.
     *
     * Il valore di default dice "chiedimelo": chi non sa rispondere in fretta
     * non e' peggio di come stava prima.
     */
    fun covers(tripIndex: Int): Boolean = true

    /** Il feed dichiara questa corsa cancellata. */
    fun canceled(tripIndex: Int): Boolean = false

    /** Il feed dichiara che questa fermata viene saltata. */
    fun skipped(tripIndex: Int, position: Int): Boolean = false

    /**
     * Il feed sta seguendo questa corsa.
     *
     * Diverso da "ha un ritardo": una corsa monitorata e puntuale ha ritardo
     * zero, ed e' un'informazione migliore di nessuna informazione.
     */
    fun monitored(tripIndex: Int): Boolean = false
}

/** Una partenza, con tutto quello che serve a mostrarla. */
class NextDeparture(
    val tripIndex: Int,
    val patternIndex: Int,
    val routeIndex: Int,
    val stopIndex: Int,
    val positionInPattern: Int,
    /** L'orario di tabella. */
    val scheduledEpoch: Long,
    /** Il ritardo applicato, in secondi. Null = nessun dato dal vivo. */
    val delaySeconds: Int?,
    val certainty: Certainty?,
    val canceled: Boolean,
    val skipped: Boolean,
    val monitored: Boolean,
    val line: String,
    val destination: String,
    val colorRgb: Int,
    val stopName: String,
) {
    /** L'orario a cui il mezzo passa davvero, per quanto ne sappiamo. */
    val effectiveEpoch: Long get() = scheduledEpoch + (delaySeconds ?: 0)

    /** C'e' un numero che viene dal vivo, comunque sia stato ottenuto. */
    val live: Boolean get() = delaySeconds != null

    /** Il numero viene dal feed, non da una stima nostra. */
    val fromFeed: Boolean
        get() = certainty == Certainty.DECLARED || certainty == Certainty.PROPAGATED
}

/**
 * Il tabellone di una fermata in un istante preciso.
 *
 * [computedAtEpoch] non e' decorativo: e' l'istante rispetto al quale sono
 * stati calcolati i minuti, e due schermate che mostrano lo stesso tabellone
 * mostrano per forza lo stesso numero perche' partono dallo stesso istante.
 */
class DepartureBoard(
    val stopIndex: Int,
    val stopName: String,
    val computedAtEpoch: Long,
    val rows: List<NextDeparture>,
    /**
     * Oggi non e' dentro la validita' degli orari che abbiamo.
     *
     * Succede quando il bundle e' scaduto: il job notturno non pubblica da
     * troppo tempo, e gli orari in tasca non coprono piu' questo giorno.
     * Senza questo campo il tabellone e' semplicemente vuoto, e "nessun
     * passaggio nelle prossime due ore" e' la spiegazione sbagliata di un
     * problema che non ha niente a che fare con gli autobus: lo stesso
     * messaggio che si vede alle tre di notte, quando invece e' vero.
     *
     * A settembre 2026 il cancello del job notturno ha bloccato sette notti
     * di fila un feed sano: venti giorni non sono impensabili.
     */
    val outsideValidity: Boolean = false,
) {
    companion object {
        fun empty(stopIndex: Int, stopName: String, nowEpoch: Long) =
            DepartureBoard(stopIndex, stopName, nowEpoch, emptyList())
    }
}

object Departures {

    /**
     * Il tabellone di una fermata.
     *
     * Le corse gia' passate secondo il tempo reale non si mostrano: una corsa
     * il cui ritardo la porta indietro nel tempo non e' "imminente", e' finita.
     * Le cancellate invece si mostrano, dichiarate: sapere che il bus non
     * viene e' piu' utile che non vedere niente e continuare ad aspettarlo.
     */
    fun build(
        reader: BundleReader,
        stopIndex: Int,
        now: Instant,
        limit: Int = 10,
        horizonSeconds: Int = 2 * 3600,
        live: LiveTimes? = null,
    ): DepartureBoard {
        val nowEpoch = now.epochSecond
        val name = reader.stopName(stopIndex)
        val oggi = now.atZone(Ftb.ROME).toLocalDate()
        val fuoriValidita = oggi.isBefore(reader.feedStart) || oggi.isAfter(reader.feedEnd)
        // Si chiede qualche corsa in piu' del necessario: alcune spariranno
        // perche' saltate o gia' passate, e il tabellone deve restare pieno.
        // Si interroga qualche secondo indietro: il lettore taglia tutto
        // cio' che e' gia' partito, e senza questo margine una corsa spariva
        // dal tabellone nell'istante esatto in cui il suo orario passava --
        // proprio mentre la persona era alla fermata ad aspettarla.
        val raw = reader.nextDepartures(
            stop = stopIndex,
            now = now.minusSeconds(GRACE_SECONDS.toLong()),
            limit = limit + EXTRA,
            horizonSeconds = horizonSeconds + GRACE_SECONDS,
        )

        val rows = ArrayList<NextDeparture>(raw.size)
        for (d in raw) {
            val stopCount = reader.patternStopCount(d.patternIndex)
            val at = live?.at(d.tripIndex, d.positionInPattern, stopCount, nowEpoch)
            // Un ritardo riferito a una fermata che il mezzo ha gia' passato
            // non dice niente su quando passera' QUI: si mostra l'orario di
            // tabella, che e' l'unica cosa onesta che resta.
            val usable = at?.takeIf { it.certainty != Certainty.SERVED }
            val skipped = live?.skipped(d.tripIndex, d.positionInPattern) ?: false
            if (skipped) continue

            val row = NextDeparture(
                tripIndex = d.tripIndex,
                patternIndex = d.patternIndex,
                routeIndex = d.routeIndex,
                stopIndex = stopIndex,
                positionInPattern = d.positionInPattern,
                scheduledEpoch = d.instant.epochSecond,
                delaySeconds = usable?.delaySeconds,
                certainty = usable?.certainty,
                canceled = live?.canceled(d.tripIndex) ?: false,
                skipped = false,
                monitored = live?.monitored(d.tripIndex) ?: false,
                line = reader.routeShortName(d.routeIndex)
                    .ifEmpty { reader.routeLongName(d.routeIndex) },
                destination = reader.patternDestination(d.patternIndex),
                colorRgb = reader.routeDisplayColor(d.routeIndex),
                stopName = name,
            )
            // Passata: il ritardo l'ha portata indietro nel tempo.
            if (!row.canceled && row.effectiveEpoch < nowEpoch - GRACE_SECONDS) continue
            rows.add(row)
        }

        // L'ordine e' quello dell'orario EFFETTIVO: un bus in ritardo passa
        // dopo uno puntuale che parte dopo di lui, e il tabellone deve dirlo.
        rows.sortBy { it.effectiveEpoch }
        return DepartureBoard(
            stopIndex = stopIndex,
            stopName = name,
            computedAtEpoch = nowEpoch,
            rows = if (rows.size > limit) rows.subList(0, limit).toList() else rows,
            outsideValidity = fuoriValidita,
        )
    }

    /**
     * Il tabellone di piu' fermate insieme, in ordine di orario.
     *
     * E' quello che serve a "cosa passa vicino a me" e alla scheda Oggi: la
     * domanda non e' "cosa passa da ognuna di queste fermate", e' "cosa passa,
     * qui intorno". Elencarle raggruppate per fermata dava 3 min, 40 min,
     * 5 min, e non si capiva.
     *
     * **L'ordine di [stops] e' un ordine di preferenza.** Lo stesso autobus
     * passa da piu' fermate vicine, e va mostrato una volta sola: quale delle
     * sue fermate mostrare lo decide chi chiama, mettendola prima. Per "qui
     * intorno" e' la piu' vicina a chi guarda.
     */
    fun merged(
        reader: BundleReader,
        stops: List<Int>,
        now: Instant,
        limit: Int = 10,
        horizonSeconds: Int = 2 * 3600,
        live: LiveTimes? = null,
    ): DepartureBoard {
        val nowEpoch = now.epochSecond
        val all = ArrayList<NextDeparture>()
        var fuoriValidita = false
        for (s in stops) {
            val uno = build(reader, s, now, limit, horizonSeconds, live)
            all.addAll(uno.rows)
            if (uno.outsideValidity) fuoriValidita = true
        }
        val rank = HashMap<Int, Int>(stops.size * 2)
        stops.forEachIndexed { i, s -> rank.putIfAbsent(s, i) }
        val rows = oneRowPerBus(all) { rank[it] ?: Int.MAX_VALUE }
        rows.sortBy { it.effectiveEpoch }
        return DepartureBoard(
            stopIndex = -1,
            stopName = "",
            computedAtEpoch = nowEpoch,
            rows = if (rows.size > limit) rows.subList(0, limit).toList() else rows,
            outsideValidity = fuoriValidita,
        )
    }

    /**
     * Lo stesso autobus, una riga sola.
     *
     * Un tabellone di piu' fermate vicine elencava la stessa corsa una volta
     * per fermata: la linea 14 compariva come "3 min", "5 min" e "7 min", e
     * si leggeva come tre autobus. Misurato sul feed vero: fra le sole otto
     * fermate piu' vicine al centro di Firenze ci sono ventuno pattern che
     * ne toccano piu' d'una, e a Careggi trentuno, fino a quattro fermate
     * per pattern. Non era un caso raro: era la regola.
     *
     * Il criterio per capire se sono due passaggi o uno solo non puo' essere
     * il tempo, perche' le due distribuzioni si sovrappongono — misurate: la
     * stessa corsa a due fermate vicine dista in media 44 secondi ma arriva
     * a 34 minuti, e un anello che ripassa dalla stessa fermata ci mette al
     * minimo 60 secondi. E' la FERMATA a distinguerli: due fermate diverse
     * sono la stessa occasione vista da due pali, la stessa fermata due volte
     * e' un anello che ripassa, e quella e' un'occasione vera in piu'.
     *
     * Quindi: di ogni corsa si tiene la fermata preferita (quella con
     * [rank] piu' basso), e da quella si tengono tutti i passaggi.
     */
    internal fun oneRowPerBus(
        rows: List<NextDeparture>,
        rank: (Int) -> Int,
    ): ArrayList<NextDeparture> {
        val preferita = HashMap<Int, Int>()
        for (r in rows) {
            val attuale = preferita[r.tripIndex]
            if (attuale == null || rank(r.stopIndex) < rank(attuale)) {
                preferita[r.tripIndex] = r.stopIndex
            }
        }
        val out = ArrayList<NextDeparture>(rows.size)
        for (r in rows) if (preferita[r.tripIndex] == r.stopIndex) out.add(r)
        return out
    }

    /**
     * Quanto si puo' sbagliare in difetto prima di dare una corsa per passata.
     *
     * Mezzo minuto: il tempo di un arrotondamento e di un giro di poll. Senza,
     * una corsa spariva dal tabellone un istante prima che il bus arrivasse
     * davvero alla fermata — proprio mentre la persona lo stava aspettando.
     */
    private const val GRACE_SECONDS = 30

    /** Quante corse in piu' chiedere, per compensare quelle che si scartano. */
    private const val EXTRA = 4
}
