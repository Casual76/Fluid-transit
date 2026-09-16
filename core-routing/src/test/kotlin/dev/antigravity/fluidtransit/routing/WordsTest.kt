package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `sotto il chilometro si dicono i metri, alla decina`() {
        // In linea d'aria: "a 296 m" promette una precisione che quel numero
        // non ha, perche' la strada vera e' sempre piu' lunga e di quanto
        // non lo sappiamo.
        assertEquals("350 m", Words.distance(350.4))
        assertEquals("300 m", Words.distance(296.0))
        assertEquals("1000 m", Words.distance(999.9))
        // Sotto i dieci metri si dice dieci: "a 0 m" non aiuta nessuno, e
        // "sei gia' li'" lo dice gia' un'altra riga.
        assertEquals("10 m", Words.distance(0.0))
        assertEquals("10 m", Words.distance(4.0))
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


    @Test
    fun `un'eta' in Long si dice come quella in Int`() {
        assertEquals(Words.age(42), Words.age(42L))
        assertEquals(Words.age(3_600), Words.age(3_600L))
    }

    @Test
    fun `una marca temporale a zero non diventa un'eta' negativa`() {
        // Capita davvero: un feed senza `timestamp` da' un'eta' di decine di
        // anni, che passata a 32 bit si ribalta. Meglio un numero grande e
        // assurdo che uno negativo, che sembrerebbe un dato dal futuro.
        val cinquantaseiAnni = 56L * 365 * 24 * 3600
        assertTrue(Words.age(cinquantaseiAnni).first().isDigit())
        assertEquals("0s", Words.age(-5L))
    }
}
