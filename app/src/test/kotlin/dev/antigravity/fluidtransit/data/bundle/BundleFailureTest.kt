package dev.antigravity.fluidtransit.data.bundle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le prime dieci parole che l'app dice di se stessa.
 *
 * Su un telefono nuovo senza rete, la schermata di benvenuto scriveva
 * `Unable to resolve host "github.com": No address associated with hostname`
 * sopra un tasto "Riprova" che in quel caso non serve a niente, perche' il
 * problema non e' qui. Un'app che si presenta cosi' non si spiega: si
 * scusa in una lingua che non e' la tua.
 */
class BundleFailureTest {

    @Test
    fun `il DNS che non risolve diventa una frase sulla rete`() {
        val w = BundleFailure.words(
            "Unable to resolve host \"github.com\": No address associated with hostname",
        )
        assertTrue(w.title, "non arriva a internet" in w.title)
        assertFalse("la frase non deve contenere il testo tecnico", "github.com" in w.title)
        assertNotNull("ma il testo tecnico non si butta", w.technical)
    }

    @Test
    fun `nessuna frase e' in inglese`() {
        // E' la regola che conta piu' delle singole frasi: qualunque sia il
        // caso, sopra c'e' italiano.
        val casi = listOf(
            "Unable to resolve host \"x\"",
            "SocketTimeoutException: timeout",
            "Connection reset by peer",
            "failed to connect to github.com: Connection refused",
            "javax.net.ssl.SSLHandshakeException: Trust anchor for certification path not found",
            "bundle corrotto in transito (sha256 diverso)",
            "write failed: ENOSPC (No space left on device)",
            "download fallito: HTTP 404",
            "HTTP 503 su https://...",
            "bundle senza fermate",
            "qualcosa che non abbiamo mai visto",
            "",
            null,
        )
        val inglese = listOf(
            " the ", " host", "failed", "unable", "error", "connection", "timeout",
        )
        for (c in casi) {
            val t = BundleFailure.words(c).title.lowercase()
            for (parola in inglese) {
                assertFalse("\"$c\" -> $t", parola in t)
            }
        }
    }

    @Test
    fun `un errore del server non da' la colpa al telefono`() {
        val quattro = BundleFailure.words("download fallito: HTTP 404")
        assertTrue(quattro.title, "Non e' un problema del telefono" in quattro.title)
        val cinque = BundleFailure.words("HTTP 503 su https://esempio")
        assertTrue(cinque.title, "server" in cinque.title)
    }

    @Test
    fun `lo spazio finito si dice col numero che serve a decidere`() {
        val w = BundleFailure.words("write failed: ENOSPC (No space left on device)")
        assertTrue(w.title, "spazio" in w.title)
        assertTrue("senza un ordine di grandezza non si sa cosa cancellare", "megabyte" in w.title)
    }

    @Test
    fun `un file danneggiato invita a riprovare, un DNS no`() {
        // La differenza che cambia cosa fa la persona: riprovare risolve il
        // primo caso e non il secondo.
        assertTrue(BundleFailure.words("sha256 diverso").title.contains("Riprova"))
        assertFalse(BundleFailure.words("Unable to resolve host").title.contains("Riprova"))
    }

    @Test
    fun `un errore che non conosciamo non si finge di capirlo`() {
        val w = BundleFailure.words("qualcosa di nuovo sotto il sole")
        assertEquals("Il download degli orari non e' riuscito.", w.title)
        assertEquals("qualcosa di nuovo sotto il sole", w.technical)
    }

    @Test
    fun `senza messaggio non si inventa una causa`() {
        for (vuoto in listOf(null, "", "   ")) {
            val w = BundleFailure.words(vuoto)
            assertTrue(w.title, "non sappiamo dire perche'" in w.title)
            assertEquals("niente testo tecnico da mostrare", null, w.technical)
        }
    }
}
