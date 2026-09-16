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
 * **Oggi e' il ripiego, non la sorgente.** Quando il proxy serve le previsioni
 * per fermata comanda [LiveFromPredictions], che dice `DECLARED` dove il feed
 * parla di QUESTA fermata; questa classe copre le corse che quelle previsioni
 * non toccano, e per quelle il feed arriva ancora ridotto a un numero per
 * corsa — quindi `PROPAGATED` quando il numero e' quello della fermata verso
 * cui il mezzo sta andando, `ESTIMATED` quando lo si proietta piu' avanti.
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
     * Le fermate saltate non arrivano fin QUI.
     *
     * Il feed le dichiara nel `schedule_relationship` di ogni StopTimeUpdate,
     * e quella strada l'app la percorre: [LiveFromPredictions] le legge e le
     * dichiara, e il formato le inchioda in `RtPredictionGoldenTest`. Ma la
     * sezione compatta da cui pesca questa classe porta un numero per corsa e
     * basta, e li' dentro l'informazione non c'e'. Per le corse che le
     * previsioni non coprono, quindi, una fermata saltata resta invisibile.
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
