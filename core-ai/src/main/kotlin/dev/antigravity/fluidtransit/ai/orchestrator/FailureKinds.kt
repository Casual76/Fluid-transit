package dev.antigravity.fluidtransit.ai.orchestrator

import dev.antigravity.fluidengine.ai.net.AiError
import dev.antigravity.fluidengine.ai.orchestrator.FailureKind

/**
 * Da un errore del provider alla ragione che si dice all'utente, per la voce.
 *
 * La domanda scritta passa dal ripiego dell'engine (`FailoverPolicy`), che ha
 * la sua tabella; la trascrizione no, e prima dava a ogni guasto la stessa
 * faccia, "Non sono riuscito a capire l'audio": su un treno senza campo
 * l'audio era perfetto e il guasto era la rete, ma la frase spingeva a
 * parlare piu' forte e a ripetere. Cosi' le due strade dicono la stessa cosa
 * dello stesso guasto.
 */
object FailureKinds {

    /**
     * @param other cosa dire per gli errori che non sono di chiave, di limite o
     *   di rete (un 400, una risposta che non si capisce, un'eccezione che non
     *   conosciamo): dipende da chi chiede.
     */
    fun of(error: Throwable, other: FailureKind = FailureKind.UNKNOWN): FailureKind = when (error) {
        is AiError.Unauthorized -> FailureKind.UNAUTHORIZED
        is AiError.RateLimited -> FailureKind.RATE_LIMITED
        is AiError.Timeout -> FailureKind.TIMEOUT
        is AiError.Network -> FailureKind.NETWORK
        is AiError.Server -> FailureKind.PROVIDER
        else -> other
    }
}
