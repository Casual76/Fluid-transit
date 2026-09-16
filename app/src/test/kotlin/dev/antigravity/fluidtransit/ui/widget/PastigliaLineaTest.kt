package dev.antigravity.fluidtransit.ui.widget

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La pastiglia della linea sul widget.
 *
 * Glance non ha `widthIn(min = …)`: o la larghezza e' fissa, e allora le
 * destinazioni partono dalla stessa colonna ma i nomi lunghi si tagliano, o
 * non lo e', e allora niente si taglia ma la colonna balla. La regola qui
 * sotto e' il compromesso, e l'unica cosa che puo' romperla in silenzio e'
 * qualcuno che cambia i numeri senza guardare quanti caratteri ci stanno.
 */
class PastigliaLineaTest {

    @Test
    fun `le linee corte hanno tutte la stessa larghezza`() {
        // Quelle vere di questa rete: una, due o tre cifre.
        assertEquals(40.dp, larghezzaPastiglia("6", compact = false))
        assertEquals(40.dp, larghezzaPastiglia("12", compact = false))
        assertEquals(40.dp, larghezzaPastiglia("131", compact = false))
    }

    @Test
    fun `sul widget piccolo la colonna e' piu' stretta`() {
        assertEquals(34.dp, larghezzaPastiglia("12", compact = true))
    }

    @Test
    fun `una linea lunga non si taglia`() {
        // "301A" dentro quaranta punti a 13sp grassetto non ci sta: meglio
        // una pastiglia piu' larga delle altre che una linea che non esiste.
        assertNull(larghezzaPastiglia("301A", compact = false))
        assertNull(larghezzaPastiglia("BLUBUS", compact = true))
    }
}
