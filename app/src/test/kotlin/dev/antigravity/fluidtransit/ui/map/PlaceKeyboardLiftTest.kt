package dev.antigravity.fluidtransit.ui.map

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Quanto va alzato il modulo "Salva come" quando compare la tastiera.
 *
 * Il difetto: il pannello dal basso siede a 90 dp dal fondo e non sale con la
 * tastiera, che arriva solo come margine perche' l'app e' a tutto schermo.
 * Con 265 dp di tastiera il modulo restava coperto tranne la testata, e si
 * scriveva alla cieca. Le misure sono quelle di questo telefono: 265 dp di
 * tastiera, 24 di barra di sistema, 90 dal fondo.
 */
class PlaceKeyboardLiftTest {

    private val tastiera = 265.dp
    private val barra = 24.dp
    private val margine = 90.dp

    @Test
    fun `senza tastiera il pannello resta dov'e'`() {
        assertEquals(0.dp, keyboardLift(ime = 0.dp, navBar = barra, restingMargin = margine))
    }

    @Test
    fun `con la tastiera sale fino a toccarla, e non oltre`() {
        // 265 - 24 - 90: il fondo del pannello, che stava a 90 dp sopra la
        // barra di sistema, arriva al bordo alto della tastiera. Alzarlo di
        // piu' — sommando la tastiera al margine — lascerebbe 90 dp di vetro
        // vuoto in mezzo.
        assertEquals(151.dp, keyboardLift(ime = tastiera, navBar = barra, restingMargin = margine))
    }

    @Test
    fun `una tastiera bassa non sposta un pannello che gia' la libera`() {
        // Il margine di riposo e' piu' alto della tastiera: non c'e' niente
        // da alzare, e mai un valore negativo.
        assertEquals(0.dp, keyboardLift(ime = 100.dp, navBar = barra, restingMargin = margine))
    }

    @Test
    fun `la barra di sistema si toglie una volta sola`() {
        // Il pannello e' gia' sopra la barra: la tastiera la comprende, e
        // contarla due volte alzerebbe il modulo di quanto e' alta la barra.
        assertEquals(
            125.dp,
            keyboardLift(ime = tastiera, navBar = 50.dp, restingMargin = margine),
        )
    }

    @Test
    fun `in orizzontale il modulo sale lo stesso`() {
        // 411 dp di finestra, 200 di tastiera, nessuna barra di sistema.
        assertEquals(110.dp, keyboardLift(ime = 200.dp, navBar = 0.dp, restingMargin = margine))
    }
}
