package dev.antigravity.fluidtransit.ai.orchestrator

import dev.antigravity.fluidtransit.ai.net.AiError
import dev.antigravity.fluidtransit.ai.net.RateLimitInfo
import dev.antigravity.fluidtransit.ai.provider.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Da un errore del provider alla frase che l'utente legge.
 *
 * Una domanda parlata su un treno senza campo diceva "Non sono riuscito a capire l'audio": la
 * trascrizione mappava ogni errore sullo stesso guasto, e l'utente alzava la voce invece di
 * accorgersi che mancava la rete. La domanda scritta, che passa dal ripiego, diceva gia' "Niente
 * rete": la tabella e' una sola, e questi test la inchiodano.
 */
class FailureKindTest {

    private val trascrizione = FailureKind.TRANSCRIPTION

    @Test
    fun `senza rete una domanda parlata dice niente rete e non audio incomprensibile`() {
        assertEquals(FailureKind.NETWORK, FailureKind.of(AiError.Network("rete"), other = trascrizione))
    }

    @Test
    fun `un provider che non risponde dice ci ha messo troppo`() {
        assertEquals(FailureKind.TIMEOUT, FailureKind.of(AiError.Timeout("timeout"), other = trascrizione))
    }

    @Test
    fun `il limite del servizio e la chiave sbagliata restano quello che sono`() {
        val limite = AiError.RateLimited(
            retryAfterSec = 3.0,
            rateLimit = RateLimitInfo.EMPTY,
            message = "429",
        )
        assertEquals(FailureKind.RATE_LIMITED, FailureKind.of(limite, other = trascrizione))
        assertEquals(FailureKind.UNAUTHORIZED, FailureKind.of(AiError.Unauthorized("401"), other = trascrizione))
    }

    @Test
    fun `un guasto del servizio non e un audio che non si capisce`() {
        assertEquals(FailureKind.PROVIDER, FailureKind.of(AiError.Server(503, "giu"), other = trascrizione))
    }

    @Test
    fun `solo quello che davvero non si capisce resta una trascrizione fallita`() {
        assertEquals(trascrizione, FailureKind.of(AiError.BadRequest(400, "formato"), other = trascrizione))
        assertEquals(trascrizione, FailureKind.of(AiError.Parse("json rotto"), other = trascrizione))
        assertEquals(trascrizione, FailureKind.of(IllegalStateException("boh"), other = trascrizione))
    }

    @Test
    fun `senza un ripiego indicato un errore sconosciuto e sconosciuto`() {
        assertEquals(FailureKind.UNKNOWN, FailureKind.of(IllegalStateException("boh")))
    }

    // Il ripiego della domanda scritta usa la stessa tabella: se ne cambiasse una i due percorsi
    // tornerebbero a dire cose diverse dello stesso guasto.
    @Test
    fun `il ripiego dice per la rete la stessa cosa della trascrizione`() {
        val policy = FailoverPolicy()
        val ultimoTentativo = 1
        val ancoraTempo = 60_000L
        assertEquals(
            FailoverDecision.Fail(FailureKind.NETWORK, null),
            policy.decide(AiError.Network("rete"), ProviderId.GROQ, emptyList(), 0, ultimoTentativo, ancoraTempo),
        )
        assertEquals(
            FailoverDecision.Fail(FailureKind.TIMEOUT, null),
            policy.decide(AiError.Timeout("timeout"), ProviderId.GROQ, emptyList(), 0, ultimoTentativo, ancoraTempo),
        )
        assertEquals(
            FailoverDecision.Fail(FailureKind.PROVIDER, null),
            policy.decide(AiError.Server(500, "giu"), ProviderId.GROQ, emptyList(), 0, ultimoTentativo, ancoraTempo),
        )
    }

    @Test
    fun `al primo errore di rete si riprova prima di dare la colpa alla rete`() {
        val decisione = FailoverPolicy().decide(
            AiError.Network("rete"), ProviderId.GROQ, emptyList(), 0, 0, 60_000L,
        )
        assertEquals(FailoverDecision.RetrySame, decisione)
    }
}
