package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Le fermate gemelle: quelle a cui il feed da' lo stesso identico minuto
 * perche' pubblica gli orari arrotondati. Sullo schermo erano due righe
 * "14:32" di fila, e sembravano un errore dell'app.
 */
class StopTimesTest {

    @Test
    fun `la gemella in mezzo prende il tempo in proporzione alla distanza`() {
        // B e C hanno lo stesso orario; da B a D ci sono 180 s e 1000 m,
        // e C sta a 300 m da B: 180 * 0,3 = 54.
        val out = StopTimes.spread(
            intArrayOf(0, 120, 120, 300),
            doubleArrayOf(200.0, 300.0, 700.0),
        )
        assertContentEquals(intArrayOf(0, 120, 174, 300), out)
    }

    @Test
    fun `il primo di un gruppo non si tocca mai`() {
        // E' l'unico orario che il feed asserisce davvero.
        val out = StopTimes.spread(
            intArrayOf(600, 600, 600, 900),
            doubleArrayOf(100.0, 100.0, 800.0),
        )
        assertEquals(600, out[0])
        assertTrue(out[1] > 600 && out[2] > out[1] && out[2] < 900)
    }

    @Test
    fun `tre gemelle restano in ordine e sotto la fermata dopo`() {
        val out = StopTimes.spread(
            intArrayOf(0, 60, 60, 60, 120),
            doubleArrayOf(500.0, 100.0, 100.0, 100.0),
        )
        for (i in 1 until out.size) assertTrue(out[i] > out[i - 1], "non crescente: ${out.toList()}")
        assertTrue(out.last() == 120)
    }

    @Test
    fun `le gemelle in fondo alla corsa si estrapolano col ritmo di arrivo`() {
        // Da A a B: 200 m in 120 s. Da B a C ci sono 300 m, cioe' 180 s.
        val out = StopTimes.spread(
            intArrayOf(0, 120, 120),
            doubleArrayOf(200.0, 300.0),
        )
        assertContentEquals(intArrayOf(0, 120, 300), out)
    }

    @Test
    fun `senza gemelle non si tocca niente`() {
        val raw = intArrayOf(0, 60, 180, 400)
        assertContentEquals(raw, StopTimes.spread(raw, doubleArrayOf(300.0, 500.0, 900.0)))
    }

    @Test
    fun `se non c_e_ tempo da distribuire il dato resta com_e_`() {
        // Un secondo solo fra le due gemelle e la fermata dopo: separarle
        // vorrebbe dire inventare, e non si inventa.
        val raw = intArrayOf(0, 100, 100, 101)
        assertContentEquals(raw, StopTimes.spread(raw, doubleArrayOf(10.0, 10.0, 10.0)))
    }

    @Test
    fun `fermate nello stesso punto restano nello stesso minuto`() {
        // Distanza zero fra le due: non c'e' niente da distribuire, e
        // l'orario mostrato, che e' al minuto, non cambia comunque.
        val out = StopTimes.spread(intArrayOf(0, 60, 60, 300), doubleArrayOf(500.0, 0.0, 500.0))
        assertEquals(60, out[1])
        assertTrue(out[2] - out[1] < 60, "spostata di un minuto intero: ${out.toList()}")
    }

    @Test
    fun `il conteggio delle gemelle e_ quello che si vede`() {
        assertEquals(2, StopTimes.twinCount(intArrayOf(0, 60, 60, 60, 200)))
        assertEquals(0, StopTimes.twinCount(intArrayOf(0, 60, 120)))
    }
}
