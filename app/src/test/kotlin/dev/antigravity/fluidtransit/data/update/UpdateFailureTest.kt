package dev.antigravity.fluidtransit.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In Impostazioni > L'app si leggeva l'eccezione nuda, in inglese, col nome
 * del dominio dentro: `Unable to resolve host "raw.githubusercontent.com"`.
 */
class UpdateFailureTest {

    @Test
    fun `il DNS che non risolve diventa una frase sulla rete, senza il dominio`() {
        val w = UpdateFailure.words(
            "Unable to resolve host \"raw.githubusercontent.com\": No address associated with hostname",
        )
        assertTrue(w.title, "non arriva a internet" in w.title)
        assertFalse("raw.githubusercontent.com" in w.title)
        assertNotNull("il testo tecnico non si butta", w.technical)
    }

    @Test
    fun `nessun caso mette inglese sopra`() {
        val casi = listOf(
            "Unable to resolve host \"x\"",
            "SocketTimeoutException: timeout",
            "Connection reset by peer",
            "failed to connect to raw.githubusercontent.com: Connection refused",
            "javax.net.ssl.SSLHandshakeException: Trust anchor for certification path not found",
            "HTTP 404 su manifest.json",
            "java.lang.IllegalStateException: boom",
            "kotlinx.serialization.SerializationException: Unexpected JSON token",
        )
        for (c in casi) {
            val t = UpdateFailure.words(c).title
            assertFalse("inglese sopra per '$c': $t", c.lowercase() in t.lowercase())
            assertFalse(t, "exception" in t.lowercase())
            assertFalse(t, "unable" in t.lowercase())
        }
    }

    @Test
    fun `un errore senza testo dice che non si sa perche', non un vuoto`() {
        for (m in listOf(null, "", "  ")) {
            val w = UpdateFailure.words(m)
            assertTrue(w.title.isNotBlank())
            assertNull(w.technical)
        }
    }

    @Test
    fun `il messaggio gia' in italiano dell'installazione non si nasconde`() {
        // Il motore scrive da solo cosa fare ("Abilita l'installazione da
        // questa app..."): e' l'istruzione utile, non un dettaglio tecnico.
        val m = "Abilita l'installazione da questa app nelle impostazioni di Android."
        val w = UpdateFailure.words(m, installing = true)
        assertEquals(m, w.title)
        assertNull(w.technical)
    }

    @Test
    fun `un errore ignoto del controllo ha la frase generica e il testo sotto`() {
        val w = UpdateFailure.words("java.lang.IllegalStateException: boom")
        assertEquals("Non riusciamo a controllare gli aggiornamenti.", w.title)
        assertEquals("java.lang.IllegalStateException: boom", w.technical)
    }
}
