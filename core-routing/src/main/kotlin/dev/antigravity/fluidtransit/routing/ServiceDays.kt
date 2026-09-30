package dev.antigravity.fluidtransit.routing

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * I giorni di servizio che contano ADESSO, e le corse di una linea dentro
 * di loro.
 *
 * Alle 00:30 la corsa che sta per passare e' quasi sempre una "24:40" del
 * giorno di servizio di IERI. Il tabellone di una fermata lo sapeva da
 * tempo — `BundleReader.nextDepartures` e `Coverage.now` guardano indietro
 * di un giorno per questo — ma la scheda di una linea e lo strumento
 * `orari_linea` dell'assistente scandivano solo la data di oggi: a
 * mezzanotte e mezza il bus notturno che passava fra dieci minuti non
 * esisteva, le fermate mostravano gli orari della prima corsa del mattino
 * (05:10) e l'"ultima 00:40 di notte" del giorno che comincia si leggeva
 * come "l'ultimo bus e' fra dieci minuti". Una linea notturna, cioe' proprio
 * quella per cui si guarda l'app a quell'ora.
 *
 * Qui c'e' la regola, una sola, perche' due copie scritte in casa sono
 * quello che aveva prodotto il difetto: i candidati sono ieri e oggi, ognuno
 * col PROPRIO inizio di giorno di servizio (`Ftb.serviceDayStart`), cosi' i
 * giorni da 23 e da 25 ore restano quello che sono. Il tetto di quanto
 * guardare indietro sta nell'header del bundle, non in una costante: questo
 * feed ha corse fino a 30:10.
 */
object ServiceDays {

    /** Un giorno di servizio che puo' avere corse in viaggio o in partenza. */
    class Day(
        val date: LocalDate,
        /** Il giorno nella finestra di validita' del bundle: e' quello che chiede `serviceActive`. */
        val dayIndex: Int,
        /** L'istante da cui si contano gli scostamenti delle corse di questo giorno. */
        val startEpoch: Long,
        /** Il giorno di servizio della data di oggi, contro quello di ieri che sta finendo. */
        val isToday: Boolean,
    )

    /** Una corsa di una linea, datata al suo giorno di servizio. */
    class LineTrip(
        val tripIndex: Int,
        val patternIndex: Int,
        val direction: Int,
        val day: Day,
        /** Scostamento della partenza dall'inizio del SUO giorno: 24:40 e' 88.800. */
        val departureSeconds: Int,
    ) {
        /** Quando parte davvero, come istante: e' quello che si confronta con "adesso". */
        val departureEpoch: Long get() = day.startEpoch + departureSeconds
    }

    /**
     * Gli orari coprono questa data?
     *
     * Fuori dalla finestra del bundle nessuna corsa e' in servizio secondo
     * noi, e questo NON vuol dire che oggi non passi niente: vuol dire che
     * gli orari sono scaduti. Lo stesso confronto lo fa il tabellone di una
     * fermata (`DepartureBoard.outsideValidity`); chi ha bisogno della stessa
     * risposta per una data lo chiede qui.
     */
    fun covers(feedStart: LocalDate, feedEnd: LocalDate, date: LocalDate): Boolean =
        !date.isBefore(feedStart) && !date.isAfter(feedEnd)

    fun covers(reader: BundleReader, date: LocalDate): Boolean =
        covers(reader.feedStart, reader.feedEnd, date)

    /**
     * I giorni di servizio da guardare adesso, dal piu' vecchio: ieri (se sta
     * ancora finendo) e oggi.
     *
     * Ieri si tiene solo se l'istante e' entro [maxTripEndSeconds] dal suo
     * inizio, misurato fra istanti e non fra orologi: nel giorno da 25 ore
     * "30:10" cade alle 05:10 dell'ora solare, e contare con la mezzanotte
     * locale lo sposterebbe di un'ora. Oggi si tiene sempre, se e' dentro la
     * finestra: "prima e ultima corsa di oggi" sono fatti del giorno, non di
     * quest'ora.
     *
     * Un giorno fuori dalla finestra del bundle non compare: e' il modo in
     * cui chi chiama si accorge che oggi non e' coperto — e se ieri invece
     * lo e', le sue corse notturne ci sono, ed e' giusto che si vedano.
     */
    fun at(
        feedStart: LocalDate,
        dayCount: Int,
        maxTripEndSeconds: Int,
        now: Instant,
        zone: ZoneId = Ftb.ROME,
    ): List<Day> {
        val today = now.atZone(zone).toLocalDate()
        val out = ArrayList<Day>(2)
        for (offsetDays in -1..0) {
            val date = today.plusDays(offsetDays.toLong())
            val dayIndex = ChronoUnit.DAYS.between(feedStart, date).toInt()
            if (dayIndex < 0 || dayIndex >= dayCount) continue
            val start = Ftb.serviceDayStart(date, zone).epochSecond
            if (offsetDays < 0 && now.epochSecond - start > maxTripEndSeconds) continue
            out.add(Day(date, dayIndex, start, isToday = offsetDays == 0))
        }
        return out
    }

    fun at(reader: BundleReader, now: Instant, zone: ZoneId = Ftb.ROME): List<Day> =
        at(reader.feedStart, reader.dayCount, reader.maxTripEndSeconds, now, zone)

    /**
     * Tutte le corse di una linea attive nei [days] dati, una per giorno in
     * cui il loro servizio circola.
     *
     * Non filtra per orario: chi chiama sa cosa gli serve — la prossima
     * partenza, la frequenza attorno a ora, la prima e l'ultima di oggi — e
     * lo ricava dagli epoch senza rifare i conti dei giorni.
     */
    fun tripsOfRoute(reader: BundleReader, routeIndex: Int, days: List<Day>): List<LineTrip> {
        if (days.isEmpty()) return emptyList()
        val out = ArrayList<LineTrip>()
        for (pattern in reader.patternsOfRoute(routeIndex)) {
            val direction = reader.patternDirection(pattern)
            val first = reader.patternFirstTrip(pattern)
            for (t in first until first + reader.patternTripCount(pattern)) {
                val service = reader.tripService(t)
                val dep0 = reader.tripDeparture0(t)
                for (day in days) {
                    if (!reader.serviceActive(service, day.dayIndex)) continue
                    out.add(LineTrip(t, pattern, direction, day, dep0))
                }
            }
        }
        return out
    }

    /**
     * L'ultima partenza del giorno di servizio di IERI che non e' ancora
     * passata, o null se ieri non ha piu' niente da far partire.
     *
     * E' il segnale di "siamo dentro la coda della notte": la linea non ha
     * finito, anche se le corse di oggi non sono ancora cominciate.
     *
     * @param fromEpoch da quando una partenza conta ancora: chi mostra le
     *   fermate tiene qualche minuto di tolleranza per una corsa appena
     *   partita, chi risponde a parole no.
     */
    fun tail(trips: List<LineTrip>, fromEpoch: Long): Long? =
        trips.asSequence()
            .filter { !it.day.isToday && it.departureEpoch >= fromEpoch }
            .maxOfOrNull { it.departureEpoch }
}
