package dev.antigravity.fluidtransit.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Il segno sulla pastiglia dei posti salvati: bianco o scuro.
 *
 * Il segno era sempre bianco. Con "Colori dal telefono" e il tema scuro
 * l'accento e' un pastello, e Casa, Lavoro e Scuola diventavano dischi pallidi
 * con un segno bianco che non si leggeva. La funzione e' pura — calcola la
 * luminanza dai canali — proprio per potersi provare sulla JVM, dove non c'e'
 * `android.graphics.Color`.
 */
class SavedIconsInkTest {

    @Test
    fun `il lilla del marchio resta bianco`() {
        // 9B6DD6 su bianco fa circa 3,8 a 1: leggibile, e non deve cambiare
        // solo perche' e' arrivata una regola per i pastelli.
        assertEquals(INK_LIGHT, inkFor(0x9B6DD6))
    }

    @Test
    fun `il lilla del tema chiaro resta bianco`() {
        assertEquals(INK_LIGHT, inkFor(0x7C4DC4))
    }

    @Test
    fun `un pastello del tema scuro passa al segno scuro`() {
        // D0BCFF e' il tono 80 di Material, quello che "Colori dal telefono"
        // mette come accento in scuro: su bianco fa circa 1,7 a 1.
        assertEquals(INK_DARK, inkFor(0xD0BCFF))
    }

    @Test
    fun `il nero e il bianco puri prendono il segno che si legge`() {
        assertEquals(INK_LIGHT, inkFor(0x000000))
        assertEquals(INK_DARK, inkFor(0xFFFFFF))
    }

    @Test
    fun `il canale alfa dell'accento non conta`() {
        // Il colore arriva a volte con l'alfa gia' dentro (toArgb): la
        // luminanza si calcola solo sui tre canali del colore.
        assertEquals(INK_LIGHT, inkFor(0xFF9B6DD6.toInt()))
        assertEquals(INK_DARK, inkFor(0xFFD0BCFF.toInt()))
    }

    @Test
    fun `le due tinte del segno sono quelle dichiarate`() {
        // Un ritocco silenzioso a uno dei due cambierebbe come si leggono
        // tutti i posti sulla mappa: meglio che lo dica un test.
        assertEquals(0xFF121214.toInt(), INK_DARK)
        assertEquals(0xFFFFFFFF.toInt(), INK_LIGHT)
    }
}
