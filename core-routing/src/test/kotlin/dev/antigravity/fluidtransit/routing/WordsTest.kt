package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Il singolare che ci si dimentica.
 *
 * "1 fermate rimaste" e "1 cambi" erano scritte sul telefono: non sono errori
 * gravi, ma sono la prima cosa che si nota e dicono che nessuno ha guardato.
 */
class WordsTest {

    @Test
    fun `uno e' singolare`() {
        assertEquals("1 fermata", Words.count(1, "fermata", "fermate"))
    }

    @Test
    fun `tutti gli altri sono plurale`() {
        assertEquals("0 fermate", Words.count(0, "fermata", "fermate"))
        assertEquals("2 fermate", Words.count(2, "fermata", "fermate"))
        assertEquals("214 mezzi", Words.count(214, "mezzo", "mezzi"))
    }

    @Test
    fun `lo zero non e' singolare, in italiano`() {
        // "0 fermata" non lo direbbe nessuno.
        assertEquals("0 corse", Words.count(0, "corsa", "corse"))
    }
}
