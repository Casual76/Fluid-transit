package dev.antigravity.fluidtransit.routing

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Dove e' arrivato un mezzo lungo la sua corsa.
 *
 * La domanda "qual e' la prossima fermata" l'app se la faceva in quattro
 * posti, e si rispondeva in tre modi diversi:
 *
 * - la scheda della corsa salta le fermate che il feed dichiara **servite**
 *   e quelle il cui orario effettivo e' passato;
 * - il piano di "sono su questo bus" guardava solo l'orologio, con il ritardo
 *   della corsa intera: bastava che il feed dichiarasse servita una fermata
 *   in anticipo perche' il viaggio cominciasse da una fermata gia' passata,
 *   mentre la lista sopra, sullo stesso schermo, partiva da quella dopo;
 *   la corsa che stava finendo, poi, si riconosceva da `boardPos >= n - 1`,
 *   cioe' mai — perche' quel ciclo, non trovando nessuna fermata futura,
 *   lasciava `boardPos` a zero e proponeva di salire al capolinea di
 *   partenza;
 * - la navigazione a bordo, di nuovo, solo l'orologio.
 *
 * Tre risposte per la stessa domanda sono tre numeri diversi sullo stesso
 * schermo, ed e' esattamente il motivo per cui l'app "si comporta in modo
 * diverso ogni volta". Qui c'e' la regola, una sola, e sta in `:core-routing`
 * perche' e' aritmetica pura: il feed batte l'orologio quando parla, e
 * l'orologio decide quando il feed tace.
 */
object TripProgress {

    /**
     * Quanto si perdona a una fermata appena passata.
     *
     * Un mezzo che sta fermo ALLA fermata ha gia' l'orario di partenza alle
     * spalle di qualche secondo, e chiamarla "passata" vorrebbe dire far
     * sparire proprio quella dove la persona sta salendo.
     */
    const val GRACE_SECONDS = 60L

    /**
     * Questa fermata il mezzo l'ha gia' servita?
     *
     * [effectiveEpoch] e' l'orario a cui il mezzo ci passa davvero, ritardo
     * compreso. La dichiarazione del feed viene prima dell'orologio: una
     * corsa in anticipo ha le fermate servite mentre i loro orari di tabella
     * sono ancora nel futuro.
     */
    fun served(at: LiveTimes.At?, effectiveEpoch: Long, nowEpoch: Long): Boolean =
        at?.certainty == Certainty.SERVED || effectiveEpoch < nowEpoch - GRACE_SECONDS

    /**
     * La prima fermata che il mezzo deve ancora servire.
     *
     * @param scheduledAt l'orario di tabella della fermata in quella posizione.
     * @param fallbackDelaySeconds il ritardo della corsa intera, per le
     *   fermate di cui il feed non parla. Zero vuol dire "in orario".
     * @return la posizione nel pattern, oppure -1 se la corsa e' finita.
     */
    fun nextPosition(
        live: LiveTimes?,
        tripIndex: Int,
        stopCount: Int,
        nowEpoch: Long,
        fallbackDelaySeconds: Int = 0,
        scheduledAt: (Int) -> Long,
    ): Int {
        for (position in 0 until stopCount) {
            val at = live?.at(tripIndex, position, stopCount, nowEpoch)
            val effective = scheduledAt(position) + (at?.delaySeconds ?: fallbackDelaySeconds)
            if (!served(at, effective, nowEpoch)) return position
        }
        return -1
    }

    /**
     * L'inizio del giorno di servizio della corsa che sta viaggiando adesso.
     *
     * Quasi sempre e' oggi, ma una notturna dopo mezzanotte appartiene al
     * giorno prima: e' la corsa delle "25:30", e datarla oggi la sposta di
     * ventiquattr'ore. Il giorno si sceglie fra i due candidati tenendo solo
     * quelli in cui il servizio di questa corsa e' davvero attivo — senza
     * quel controllo una corsa feriale, letta di lunedi' mattina, poteva
     * essere datata alla domenica in cui non esiste.
     */
    fun serviceDayStart(reader: BundleReader, tripIndex: Int, nowEpoch: Long): Long {
        val dep0 = reader.tripDeparture0(tripIndex)
        val today = Instant.ofEpochSecond(nowEpoch).atZone(Ftb.ROME).toLocalDate()
        for (offset in 0 downTo -1) {
            val date = today.plusDays(offset.toLong())
            val dayIndex = ChronoUnit.DAYS.between(reader.feedStart, date).toInt()
            if (dayIndex < 0 || dayIndex >= reader.dayCount) continue
            if (!reader.serviceActive(reader.tripService(tripIndex), dayIndex)) continue
            val start = Ftb.serviceDayStart(date).epochSecond + dep0
            // Partita da meno di dodici ore, o in partenza entro due.
            if (nowEpoch in (start - 2 * 3600)..(start + 12 * 3600)) {
                return Ftb.serviceDayStart(date).epochSecond
            }
        }
        return Ftb.serviceDayStart(today).epochSecond
    }
}
