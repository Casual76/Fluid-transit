package dev.antigravity.fluidtransit.routing

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Quanta parte di quello che sta viaggiando adesso e' davvero seguita.
 *
 * Lo stato dei dati diceva "Corse seguite adesso: 1650", e quel numero da
 * solo non vuol dire niente: seguirne milleseicentocinquanta e' tutto se le
 * corse in strada sono milleseicentocinquanta, ed e' un terzo se sono
 * cinquemila. Ed e' la domanda che viene guardando un tabellone dove sette
 * righe su otto dicono "orario da tabella" — misurato il 16/09 a un capo
 * del feed: quelle corse non erano un aggancio fallito, erano corse di cui
 * il feed non parla affatto.
 *
 * Il conto lo sanno gia' gli orari: una corsa e' in viaggio se il suo giorno
 * di servizio e' attivo e se adesso sta fra la sua partenza e il suo arrivo.
 * Si scandisce per pattern e non per corsa perche' il numero di fermate e
 * l'ultimo scostamento si leggono una volta per pattern invece di
 * duecentottantasettemila volte.
 *
 * Le due meta' del rapporto NON sono lo stesso insieme di "corse seguite
 * adesso" nella riga accanto: quella conta i ritardi che l'app ha in
 * memoria, comprese le corse che devono ancora partire e quelle appena
 * finite, e per questo puo' essere piu' grande del numero di corse in
 * strada. Qui si guarda solo chi sta viaggiando in questo istante.
 */
object Coverage {

    /** Quante corse sono in strada, e di quante il tempo reale dice qualcosa. */
    class Stato(val inViaggio: Int, val seguite: Int) {
        /** La quota seguita, arrotondata. Null quando non c'e' niente in strada. */
        val percento: Int? get() =
            if (inViaggio <= 0) null else Math.round(seguite * 100.0 / inViaggio).toInt()
    }

    /**
     * Quante corse stanno viaggiando in questo istante, secondo gli orari, e
     * quante di quelle il tempo reale sta seguendo.
     *
     * I giorni candidati sono due: oggi e ieri. Una corsa che parte alle
     * "25:30" appartiene al giorno di servizio precedente e alle due del
     * mattino sta ancora viaggiando; una di domani non puo' essere in
     * viaggio adesso.
     */
    fun now(
        reader: BundleReader,
        now: Instant,
        live: LiveTimes? = null,
        zone: ZoneId = Ftb.ROME,
    ): Stato {
        val today = now.atZone(zone).toLocalDate()
        var totale = 0
        var seguite = 0
        for (offsetDays in -1..0) {
            val date = today.plusDays(offsetDays.toLong())
            val dayIndex = ChronoUnit.DAYS.between(reader.feedStart, date).toInt()
            if (dayIndex < 0 || dayIndex >= reader.dayCount) continue
            val target = now.epochSecond - Ftb.serviceDayStart(date, zone).epochSecond
            // Giorno di servizio ormai chiuso: nemmeno la piu' lunga delle
            // corse di quel giorno puo' essere ancora in strada.
            if (target < 0 || target > reader.maxTripEndSeconds) continue
            for (pattern in 0 until reader.patternCount) {
                val n = reader.patternStopCount(pattern)
                if (n <= 0) continue
                val first = reader.patternFirstTrip(pattern)
                val count = reader.patternTripCount(pattern)
                for (t in first until first + count) {
                    val dep0 = reader.tripDeparture0(t)
                    if (dep0 > target) continue
                    val fine = dep0 + reader.profileOffset(reader.tripProfile(t), n - 1)
                    if (fine < target) continue
                    if (!reader.serviceActive(reader.tripService(t), dayIndex)) continue
                    totale++
                    if (live?.covers(t) == true) seguite++
                }
            }
        }
        return Stato(totale, seguite)
    }
}
