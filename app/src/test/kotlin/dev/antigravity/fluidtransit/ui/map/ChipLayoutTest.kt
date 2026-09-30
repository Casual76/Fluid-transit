package dev.antigravity.fluidtransit.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La fila dei filtri (Tutti / Urbani / Extraurbani) col carattere ingrandito.
 *
 * Le quote sono tarate a scala 1,0; a 1,3 e a 2,0 le etichette uscivano dalla
 * loro quota e si leggeva "Tutt", "Urba", "Extraur", senza nemmeno i puntini.
 * La disposizione e' una funzione pura proprio per poterlo inchiodare qui,
 * senza uno schermo.
 */
class ChipLayoutTest {

    @Test
    fun `a carattere normale resta il disegno di sempre, con l'icona`() {
        val l = chipLayout(1.0f)
        assertEquals(ChipMode.WEIGHTED, l.mode)
        assertTrue(l.showIcon)
        assertEquals(10, l.horizontalPaddingDp)
    }

    @Test
    fun `a 1,3 l'icona lascia il posto al testo e la fila resta a quote`() {
        // Le etichette a 1,3 misurano circa 43, 57 e 100 dp; senza icona e
        // con il margine a 6 le quote ne lasciano 65, 87 e 128.
        val l = chipLayout(1.3f)
        assertEquals(ChipMode.WEIGHTED, l.mode)
        assertFalse(l.showIcon)
        assertTrue(l.horizontalPaddingDp < 10)
    }

    @Test
    fun `a 1,15 non c'e' piu' posto per l'icona`() {
        // 1,15 e' il primo scalino che Android offre sopra il normale, ed e'
        // gia' troppo: "Tutti" passa da 31 a 36 dp in 33 disponibili.
        assertFalse(chipLayout(1.15f).showIcon)
    }

    @Test
    fun `a 2,0 la fila scorre e ogni chip prende il suo testo`() {
        val l = chipLayout(2.0f)
        assertEquals(ChipMode.SCROLLING, l.mode)
        // Con lo spazio che la fila che scorre da', l'icona torna a servire
        // per riconoscere il filtro a colpo d'occhio.
        assertTrue(l.showIcon)
    }

    @Test
    fun `il passaggio alla fila che scorre e' oltre 1,5 e non prima`() {
        assertEquals(ChipMode.WEIGHTED, chipLayout(1.5f).mode)
        assertEquals(ChipMode.SCROLLING, chipLayout(1.6f).mode)
    }

    @Test
    fun `uno schermo piu' stretto sente di piu' lo stesso carattere`() {
        // Un 320 dp a 1,3 sta stretto quanto un 360 a 1,46: ancora a quote,
        // ma a 1,5 diventa scorrimento.
        assertEquals(ChipMode.WEIGHTED, chipLayout(1.3f, screenWidthDp = 320f).mode)
        assertEquals(ChipMode.SCROLLING, chipLayout(1.5f, screenWidthDp = 320f).mode)
    }

    @Test
    fun `uno schermo largo non toglie l'icona senza motivo`() {
        // Una piega aperta da 700 dp a 1,3: le quote sono larghe il doppio.
        val l = chipLayout(1.3f, screenWidthDp = 700f)
        assertEquals(ChipMode.WEIGHTED, l.mode)
        assertTrue(l.showIcon)
    }

    @Test
    fun `una larghezza assurda non divide per zero`() {
        assertEquals(ChipMode.SCROLLING, chipLayout(1.0f, screenWidthDp = 0f).mode)
    }
}
