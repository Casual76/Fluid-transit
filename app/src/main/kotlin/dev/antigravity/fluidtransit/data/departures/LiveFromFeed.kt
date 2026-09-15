package dev.antigravity.fluidtransit.data.departures

import dev.antigravity.fluidtransit.routing.Certainty
import dev.antigravity.fluidtransit.routing.DelayModel
import dev.antigravity.fluidtransit.routing.LiveTimes

/**
 * Quello che il tempo reale sa, tradotto nel vocabolario di [LiveTimes].
 *
 * E' il punto in cui le tre cose che oggi arrivano dal feed — il ritardo
 * modellato lungo il percorso, le corse cancellate, i mezzi vivi — diventano
 * un'unica risposta alla domanda "cosa si sa di questa corsa, a questa
 * fermata". Il resto dell'app non deve piu' sapere che sono tre.
 *
 * E' anche il punto che cambiera' quando arriveranno le previsioni per
 * fermata: oggi produce `PROPAGATED` e `ESTIMATED` perche' il feed ci arriva
 * ridotto a un numero per corsa; domani produrra' anche `DECLARED`, e nessuna
 * schermata se ne accorgera'.
 */
class LiveFromFeed(
    private val delays: DelayModel,
    private val canceled: Set<Int>,
    private val withVehicle: Set<Int>,
) : LiveTimes {

    override fun at(tripIndex: Int, position: Int, stopCount: Int, nowEpoch: Long): LiveTimes.At? {
        val live = delays.at(tripIndex, position, stopCount, nowEpoch) ?: return null
        return LiveTimes.At(live.delaySeconds, live.confidence.asCertainty())
    }

    override fun canceled(tripIndex: Int): Boolean = tripIndex in canceled

    /**
     * Le fermate saltate oggi non arrivano fin qui: il feed le dichiara nel
     * `schedule_relationship` di ogni StopTimeUpdate, e la catena realtime le
     * porta fino al proxy ma non ancora fino all'app.
     */
    override fun skipped(tripIndex: Int, position: Int): Boolean = false

    override fun monitored(tripIndex: Int): Boolean = tripIndex in withVehicle

    private fun DelayModel.Confidence.asCertainty(): Certainty = when (this) {
        DelayModel.Confidence.SERVED -> Certainty.SERVED
        // Il numero viene dal feed, solo non per QUESTA fermata: e' quello
        // della fermata verso cui il mezzo sta andando, portato fin qui. E'
        // esattamente cosa vuol dire propagato.
        DelayModel.Confidence.OBSERVED -> Certainty.PROPAGATED
        DelayModel.Confidence.PROJECTED -> Certainty.ESTIMATED
    }

    companion object {
        /** Quando non si sa niente di niente: gli orari di tabella e basta. */
        val NOTHING = object : LiveTimes {
            override fun at(
                tripIndex: Int,
                position: Int,
                stopCount: Int,
                nowEpoch: Long,
            ): LiveTimes.At? = null
        }
    }
}
