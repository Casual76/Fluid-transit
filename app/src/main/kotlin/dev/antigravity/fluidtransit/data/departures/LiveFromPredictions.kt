package dev.antigravity.fluidtransit.data.departures

import dev.antigravity.fluidtransit.data.rt.RtPrediction
import dev.antigravity.fluidtransit.data.rt.RtPredictionSet
import dev.antigravity.fluidtransit.data.rt.RtTripPrediction
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Certainty
import dev.antigravity.fluidtransit.routing.LiveTimes
import dev.antigravity.fluidtransit.routing.StopSequenceMapping

/**
 * Le previsioni del feed, fermata per fermata.
 *
 * E' la risposta alla parte di "non mi fido" che riguarda i numeri. Fino a qui
 * il feed arrivava all'app ridotto a un numero per corsa e l'app si inventava
 * come propagarlo lungo il percorso: un recupero del 30% verso il capolinea,
 * salvo che l'andamento osservato dicesse altro. Era una stima ragionevole e
 * non era quella che usano le app ufficiali, quindi i minuti potevano non
 * combaciare anche quando il feed era d'accordo.
 *
 * Adesso, per le corse che il feed copre, si usa quello che il feed dice. Per
 * le altre — e per le fermate che le previsioni non raggiungono — resta il
 * [fallback], che e' il modello di prima. La differenza fra le due cose e'
 * visibile a schermo: "dal bus" contro "stimato".
 *
 * ## La regola di propagazione
 *
 * GTFS-RT dice che una previsione vale per tutte le fermate successive finche'
 * non ce n'e' un'altra. Quindi per la fermata in posizione P si cerca l'ultima
 * previsione con sequenza minore o uguale alla sua: se e' esattamente la sua,
 * il feed sta parlando di QUESTA fermata ([Certainty.DECLARED]); se e' di una
 * precedente, il numero arriva fin qui per la regola ([Certainty.PROPAGATED]).
 *
 * Se invece la prima previsione e' gia' oltre la nostra fermata, vuol dire che
 * il mezzo l'ha passata: [Certainty.SERVED], e quel ritardo non ci riguarda.
 */
class LiveFromPredictions(
    private val byTrip: Map<Int, Resolved>,
    private val fallback: LiveTimes,
    private val canceledTrips: Set<Int>,
    private val withVehicle: Set<Int>,
    private val feedTimestamp: Long,
    /**
     * Lo snapshot da cui e' stata ricavata.
     *
     * Serve a riconoscere, per identita', se una risoluzione gia' fatta vale
     * ancora per i byte che il proxy sta servendo adesso: e' quello che
     * permette di risolvere al volo quando nessuno l'ha ancora fatto, invece
     * di mostrare una stima perche' il collettore non ha ancora avuto il suo
     * turno.
     */
    val set: RtPredictionSet,
) : LiveTimes {

    /** Una corsa, con lo scarto gia' verificato una volta sola. */
    class Resolved(
        val trip: RtTripPrediction,
        /** `seq - posizione`. Null se non si e' potuto verificare. */
        val offset: Int?,
    )

    /** Quante corse del feed si sono agganciate a una corsa del bundle. */
    val risolte: Int = byTrip.size

    /**
     * Quante di quelle sono davvero utilizzabili.
     *
     * Una previsione agganciata alla corsa giusta ma senza lo scarto delle
     * sequenze verificato non si puo' usare: non si sa a QUALE fermata si
     * riferisca, e applicarla a caso sposterebbe i minuti di una fermata.
     * Quelle tornano a essere una stima nostra, in silenzio — e senza questo
     * conteggio la differenza fra "il feed non segue questa corsa" e "il feed
     * la segue ma non siamo riusciti ad agganciarla" non si vede da nessuna
     * parte.
     */
    val agganciate: Int = byTrip.count { it.value.offset != null }

    override fun at(tripIndex: Int, position: Int, stopCount: Int, nowEpoch: Long): LiveTimes.At? {
        val r = byTrip[tripIndex] ?: return fallback.at(tripIndex, position, stopCount, nowEpoch)
        // Una previsione vecchia non e' la previsione di adesso. Stessa
        // soglia del modello di ripiego, per la stessa ragione: il feed puo'
        // smettere di parlare di una corsa, e l'ultimo numero visto non deve
        // restare a schermo per sempre.
        if (feedTimestamp > 0 && nowEpoch - feedTimestamp > STALE_SECONDS) {
            return fallback.at(tripIndex, position, stopCount, nowEpoch)
        }
        val offset = r.offset ?: return fallback.at(tripIndex, position, stopCount, nowEpoch)
        val targetSeq = position + offset

        val point = lastAtOrBefore(r.trip.points, targetSeq)
            // Nessuna previsione arriva fin qui: la prima e' gia' oltre, cioe'
            // il mezzo ha passato questa fermata.
            ?: return r.trip.points.firstOrNull()
                ?.let { LiveTimes.At(it.delaySec, Certainty.SERVED) }
                ?: fallback.at(tripIndex, position, stopCount, nowEpoch)

        // "Senza dati" e' una dichiarazione esplicita del feed: da li' in poi
        // non sa. Si torna alla stima, che e' meglio di un numero inventato
        // spacciato per dichiarato.
        if (point.relation == RtPrediction.REL_NO_DATA) {
            return fallback.at(tripIndex, position, stopCount, nowEpoch)
        }

        val certainty = if (point.stopSeq == targetSeq) Certainty.DECLARED else Certainty.PROPAGATED
        return LiveTimes.At(point.delaySec, certainty)
    }

    /**
     * Sapere QUALCOSA di questa corsa, in una lettura sola.
     *
     * Il ripiego copre tutto, quindi la risposta e' la sua: si sa qualcosa
     * se lo sa il feed delle previsioni oppure il modello dei ritardi.
     */
    override fun covers(tripIndex: Int): Boolean =
        byTrip.containsKey(tripIndex) || fallback.covers(tripIndex)

    override fun canceled(tripIndex: Int): Boolean =
        tripIndex in canceledTrips || byTrip[tripIndex]?.trip?.canceled == true

    /**
     * Il feed dichiara che il mezzo salta questa fermata.
     *
     * E' il fatto nuovo che prima non avevamo: la corsa c'e' ma non passa di
     * qui. Mostrarla in ritardo sarebbe peggio che non mostrarla.
     */
    override fun skipped(tripIndex: Int, position: Int): Boolean {
        val r = byTrip[tripIndex] ?: return false
        val offset = r.offset ?: return false
        val targetSeq = position + offset
        return r.trip.points.any { it.stopSeq == targetSeq && it.skipped }
    }

    override fun monitored(tripIndex: Int): Boolean =
        tripIndex in withVehicle || byTrip.containsKey(tripIndex)

    private fun lastAtOrBefore(points: List<RtPrediction>, seq: Int): RtPrediction? {
        // I punti arrivano in ordine di sequenza dal proxy: si scorre. Sono
        // pochi per corsa (mediana tre o quattro dopo la compattazione), e una
        // ricerca binaria qui costerebbe piu' in righe di quanto renda.
        var best: RtPrediction? = null
        for (p in points) {
            if (p.stopSeq < 0) continue
            if (p.stopSeq > seq) break
            best = p
        }
        return best
    }

    companion object {
        /** Oltre questa eta' la previsione non descrive piu' il presente. */
        const val STALE_SECONDS = 600L

        /**
         * Risolve le previsioni contro il bundle, una volta per snapshot.
         *
         * Qui si paga il conto una volta sola: la corsa si risolve in indice,
         * lo scarto delle sequenze si verifica contro le ancore, e da li' in
         * poi ogni interrogazione e' una lettura di mappa.
         */
        fun resolve(
            reader: BundleReader,
            set: RtPredictionSet,
            fallback: LiveTimes,
            canceledTrips: Set<Int>,
            withVehicle: Set<Int>,
        ): LiveFromPredictions {
            val byTrip = HashMap<Int, Resolved>(set.byTripHash.size * 2)
            for (t in set.byTripHash.values) {
                val tripIndex = reader.findTripByIdHash(t.tripHash).takeIf { it >= 0 }
                    ?: reader.findTripByRouteAndDeparture(
                        t.routeHash,
                        t.direction,
                        t.startTimeSec,
                    ).takeIf { it >= 0 }
                    ?: continue
                val pattern = reader.tripPattern(tripIndex)
                if (pattern < 0) continue
                val stopCount = reader.patternStopCount(pattern)
                val first = t.points.firstOrNull()
                val last = t.points.lastOrNull()
                val offset = if (first == null || last == null) {
                    null
                } else {
                    StopSequenceMapping.offset(
                        stopCount = stopCount,
                        firstSeq = first.stopSeq,
                        firstStopId32 = t.firstStopId32,
                        lastSeq = last.stopSeq,
                        lastStopId32 = t.lastStopId32,
                    ) { position ->
                        reader.stopIdHash(reader.patternStop(pattern, position)).toInt()
                    }
                }
                byTrip[tripIndex] = Resolved(t, offset)
            }
            return LiveFromPredictions(
                byTrip = byTrip,
                fallback = fallback,
                canceledTrips = canceledTrips,
                withVehicle = withVehicle,
                feedTimestamp = set.feedTimestamp,
                set = set,
            )
        }
    }
}
