package dev.antigravity.fluidtransit.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Le righe Da e A, dette a voce.
 *
 * Scelto un posto, il segnaposto spariva e un lettore di schermo leggeva
 * "Piazza Dalmazia, pulsante" e "Careggi, pulsante": due nomi, e nessuna
 * parola che dicesse quale fosse la partenza.
 */
class PlannerGlassTest {

    @Test
    fun `la riga dice se e' la partenza o la destinazione`() {
        assertEquals("Partenza: Piazza Dalmazia", plannerFieldSpoken("Partenza", "Piazza Dalmazia"))
        assertEquals("Destinazione: Careggi", plannerFieldSpoken("Destinazione", "Careggi"))
    }

    @Test
    fun `una destinazione non scelta non legge l'invito scritto sullo schermo`() {
        // "Dove vai?" e' un invito: letto dopo "Destinazione:" sembrerebbe il
        // nome di un posto.
        assertEquals("Destinazione: da scegliere", plannerFieldSpoken("Destinazione", null))
    }

    @Test
    fun `un nome vuoto conta come non scelto`() {
        assertEquals("Destinazione: da scegliere", plannerFieldSpoken("Destinazione", ""))
        assertEquals("Destinazione: da scegliere", plannerFieldSpoken("Destinazione", "   "))
    }

    @Test
    fun `la partenza di riserva e' un valore vero e si legge`() {
        // Senza una partenza scelta si parte da dove sei: e' una risposta,
        // non un invito.
        assertEquals("Partenza: La tua posizione", plannerFieldSpoken("Partenza", "La tua posizione"))
    }
}
