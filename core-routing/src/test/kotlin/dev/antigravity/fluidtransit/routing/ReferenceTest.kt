package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Da dove si misura "vicino".
 *
 * La stessa regola vale per i risultati della ricerca, per i recenti, per le
 * fermate vicine e per l'assistente: erano quattro copie e due di loro
 * dicevano un'altra cosa.
 */
class ReferenceTest {

    private val firenze = 43.7731 to 11.2559
    private val duomo = 43.7731 to 11.2560 // qualche metro piu' in la'
    private val siena = 43.3188 to 11.3308 // una sessantina di chilometri

    @Test
    fun `normalmente comanda dove sei`() {
        assertEquals(firenze, Reference.point(here = firenze, looking = duomo))
    }

    @Test
    fun `la mappa portata lontano comanda lei`() {
        // Cercare "via Roma" mentre si esplora Siena deve dare le vie senesi.
        assertEquals(siena, Reference.point(here = firenze, looking = siena))
    }

    @Test
    fun `quando ne manca uno vale l'altro`() {
        assertEquals(siena, Reference.point(here = null, looking = siena))
        assertEquals(firenze, Reference.point(here = firenze, looking = null))
        assertNull(Reference.point(here = null, looking = null))
    }
}
