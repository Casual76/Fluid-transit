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
    @Test
    fun `sotto il chilometro si dicono i metri`() {
        assertEquals("0 m", Words.distance(0.0))
        assertEquals("350 m", Words.distance(350.4))
        assertEquals("999 m", Words.distance(999.9))
    }

    @Test
    fun `sopra il chilometro si dicono i chilometri, con la virgola`() {
        // "2437 m" e' un numero da convertire in testa, e il punto decimale
        // in italiano si legge male.
        assertEquals("1,0 km", Words.distance(1000.0))
        assertEquals("2,4 km", Words.distance(2437.0))
        assertEquals("78,2 km", Words.distance(78_240.0))
    }

    @Test
    fun `una distanza che non si sa non si stampa`() {
        assertEquals("", Words.distance(-1.0))
    }

    @Test
    fun `l'eta' di un dato — secondi sotto il minuto, minuti sopra`() {
        // Sotto il minuto la differenza conta: "18s" vuol dire adesso.
        assertEquals("0s", Words.age(0))
        assertEquals("18s", Words.age(18))
        assertEquals("59s", Words.age(59))
        // Sopra, "342s" e' un numero da dividere prima di capirlo: era
        // scritto cosi' nella scheda di un bus.
        assertEquals("6 min", Words.age(342))
        assertEquals("1 h 12 min", Words.age(72 * 60))
    }

}
