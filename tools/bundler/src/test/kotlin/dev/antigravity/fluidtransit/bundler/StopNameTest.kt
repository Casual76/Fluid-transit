package dev.antigravity.fluidtransit.bundler

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * I nomi delle fermate, ripuliti dalle code che nessuno voleva.
 *
 * Misurato sul feed del 16/09/2026, su 33.930 fermate: 212 nomi hanno uno
 * spazio doppio — quasi tutti all'Elba, "San Piero,  Via San Francesco" — e
 * 17 finiscono con un trattino o un trattino basso, tipo "ANTELLA_", che e'
 * la destinazione che si legge davvero nel tabellone della linea 32. Sono lo
 * 0,7% delle fermate, ma un trattino basso in fondo a un nome si legge come
 * un difetto dell'app, non della fonte: l'app e' l'unica cosa che si vede.
 *
 * Il caso che conta di piu' e' pero' l'ultimo di questo file: il punto
 * finale non si tocca, perche' 125 nomi lo hanno e sono abbreviazioni vere.
 */
class StopNameTest {

    private val b = BundleBuilder(File("."), null)

    @Test
    fun `il trattino basso in fondo se ne va`() {
        assertEquals("ANTELLA", b.pulisciNome("ANTELLA_"))
        assertEquals("ANTELLA", b.pulisciNome("ANTELLA__"))
        assertEquals("ANTELLA", b.pulisciNome("ANTELLA _"))
    }

    @Test
    fun `anche il trattino in fondo`() {
        assertEquals("VIA ROMA", b.pulisciNome("VIA ROMA-"))
    }

    @Test
    fun `lo spazio doppio diventa uno`() {
        assertEquals(
            "San Piero, Via San Francesco",
            b.pulisciNome("San Piero,  Via San Francesco"),
        )
        assertEquals("Spartaia, Hotel Valle Verde", b.pulisciNome("Spartaia, Hotel  Valle Verde"))
    }

    @Test
    fun `gli spazi ai bordi se ne vanno`() {
        assertEquals("LEOPOLDA", b.pulisciNome("  LEOPOLDA "))
    }

    @Test
    fun `il punto finale resta, perche' e' un'abbreviazione`() {
        // "Monsummano T." senza punto diventa "Monsummano T", che e' una
        // parola diversa. Centoventicinque nomi finiscono cosi'.
        assertEquals("Monsummano T.", b.pulisciNome("Monsummano T."))
        assertEquals("Serravalle P.se", b.pulisciNome("Serravalle P.se"))
    }

    @Test
    fun `un nome gia' pulito torna identico, senza allocare per forza`() {
        val nome = "SODERINI TORRINO SANTA ROSA"
        assertEquals(nome, b.pulisciNome(nome))
    }

    @Test
    fun `un nome fatto solo di code non diventa qualcosa di strano`() {
        assertEquals("", b.pulisciNome("_"))
        assertEquals("", b.pulisciNome("  "))
    }
}
