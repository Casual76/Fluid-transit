package dev.antigravity.fluidtransit.data.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Le parole dell'attesa, in navigazione.
 *
 * La notifica che si guarda alla fermata contava `secondi / 60 + 1`: troncato
 * e poi alzato di uno. Col bus in arrivo fra dieci secondi diceva "parte tra
 * 1 min" — cioe' dava un minuto di tempo che non c'era — e col bus partito da
 * un minuto diceva "parte tra 0 min", la stessa frase vuota che gli itinerari
 * avevano e che li' era gia' stata tolta.
 */
class NavWordsTest {

    @Test
    fun `il bus in arrivo adesso non regala un minuto`() {
        assertEquals("parte ora", NavigationService.attesa(10))
        assertEquals("parte ora", NavigationService.attesa(0))
    }

    @Test
    fun `non esiste "parte tra 0 min"`() {
        // Il caso che si vedeva davvero: bus partito da poco, conto a zero.
        assertEquals("parte ora", NavigationService.attesa(-20))
        assertEquals("e' gia' partita", NavigationService.attesa(-90))
    }

    @Test
    fun `i minuti si arrotondano, non si alzano sempre`() {
        assertEquals("parte tra 4 min", NavigationService.attesa(4 * 60 + 10))
        assertEquals("parte tra 5 min", NavigationService.attesa(4 * 60 + 40))
    }

    @Test
    fun `oltre l'ora non si contano i minuti`() {
        // Una routine si arma quarantacinque minuti prima, ma un'attesa in
        // navigazione puo' passare l'ora: "parte tra 72 min" e' un numero da
        // dividere prima di capirlo.
        assertEquals("parte tra 1 h 12 min", NavigationService.attesa(72 * 60))
    }
}
