package dev.antigravity.fluidtransit.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il controllo degli aggiornamenti girava una volta per processo: un telefono
 * che tiene l'app in memoria per giorni non vedeva mai la capsula, e un primo
 * giro caduto senza rete restava fallito per tutta la sessione.
 */
class UpdateCheckPolicyTest {

    private val now = 1_700_000_000_000L
    private val ora = 60L * 60 * 1000

    @Test
    fun `mai controllato, si controlla`() {
        assertTrue(UpdateCheckPolicy.due(null, lastCheckOk = false, nowMs = now))
    }

    @Test
    fun `dopo un controllo riuscito non si ripete a ogni apertura`() {
        assertFalse(UpdateCheckPolicy.due(now - 5 * 60 * 1000, lastCheckOk = true, nowMs = now))
        assertFalse(UpdateCheckPolicy.due(now - 5 * ora, lastCheckOk = true, nowMs = now))
    }

    @Test
    fun `dopo sei ore si ricontrolla`() {
        assertTrue(UpdateCheckPolicy.due(now - 6 * ora, lastCheckOk = true, nowMs = now))
    }

    @Test
    fun `un controllo fallito non vale sei ore di silenzio`() {
        // Il caso dell'avvio: il giro cade con la rete ancora chiusa, fallisce
        // su "Unable to resolve host", e non deve restare cosi' fino a
        // domani.
        assertTrue(
            UpdateCheckPolicy.due(
                now - UpdateCheckPolicy.RETRY_AFTER_FAILURE_MS,
                lastCheckOk = false,
                nowMs = now,
            ),
        )
    }

    @Test
    fun `un fallimento appena avvenuto non si martella`() {
        assertFalse(UpdateCheckPolicy.due(now - 10_000, lastCheckOk = false, nowMs = now))
    }

    @Test
    fun `con l'orologio tornato indietro si controlla`() {
        assertTrue(UpdateCheckPolicy.due(now + ora, lastCheckOk = true, nowMs = now))
    }
}
