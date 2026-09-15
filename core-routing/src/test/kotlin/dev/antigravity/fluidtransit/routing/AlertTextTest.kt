package dev.antigravity.fluidtransit.routing

import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Il periodo di un avviso.
 *
 * "Deviazione in via Nazionale" e' un'altra cosa se dura fino a stasera o
 * fino a marzo, e l'informazione stava dentro il feed da sempre senza essere
 * mostrata. Qui si controlla che venga detta con la precisione giusta e non
 * di piu': oggi e domani per nome, la settimana col giorno, oltre con la
 * data.
 */
class AlertTextTest {

    /** Un mercoledi' alle 10:00, ora di Roma. */
    private val now = ZonedDateTime.of(2026, 9, 16, 10, 0, 0, 0, Ftb.ROME).toEpochSecond()

    private fun ore(n: Long) = now + n * 3600
    private fun giorni(n: Long) = now + n * 24 * 3600

    @Test
    fun `un avviso in corso senza fine non ha un periodo da raccontare`() {
        // E' semplicemente attivo: scriverlo occuperebbe una riga per non
        // dire niente.
        assertNull(AlertText.period(startEpoch = giorni(-3), endEpoch = 0, nowEpoch = now))
        assertNull(AlertText.period(startEpoch = 0, endEpoch = 0, nowEpoch = now))
    }

    @Test
    fun `una fine di oggi si dice oggi`() {
        assertEquals(
            "Fino a oggi alle 18:00",
            AlertText.period(giorni(-1), ore(8), now),
        )
    }

    @Test
    fun `una fine di domani si dice domani`() {
        val domaniAlle9 = ZonedDateTime.of(2026, 9, 17, 9, 0, 0, 0, Ftb.ROME).toEpochSecond()
        assertEquals("Fino a domani alle 09:00", AlertText.period(0, domaniAlle9, now))
    }

    @Test
    fun `dentro la settimana si dice il giorno`() {
        // Sabato, tre giorni dopo il mercoledi'.
        val sabato = ZonedDateTime.of(2026, 9, 19, 14, 30, 0, 0, Ftb.ROME).toEpochSecond()
        val p = AlertText.period(0, sabato, now)
        assertTrue(p != null && p.contains("sabato", ignoreCase = true), "trovato: $p")
        assertTrue(p != null && p.contains("14:30"), "manca l'ora: $p")
    }

    @Test
    fun `oltre la settimana si dice la data, senza l'ora`() {
        // Fra un mese: l'ora non serve a decidere niente.
        val fraUnMese = ZonedDateTime.of(2026, 10, 20, 7, 5, 0, 0, Ftb.ROME).toEpochSecond()
        val p = AlertText.period(0, fraUnMese, now)
        assertTrue(p != null && p.contains("20"), "manca il giorno: $p")
        assertTrue(p != null && p.contains("ottobre", ignoreCase = true), "manca il mese: $p")
        assertTrue(p != null && !p.contains("07:05"), "l'ora non serviva: $p")
    }

    @Test
    fun `un avviso che deve ancora cominciare lo dice`() {
        val p = AlertText.period(giorni(2), giorni(3), now)
        assertTrue(p != null && p.startsWith("Dal "), "trovato: $p")
        assertTrue(p != null && p.contains(" al "), "manca la fine: $p")
    }

    @Test
    fun `un avviso gia' finito non si spaccia per in corso`() {
        // Capita fra un giro di feed e l'altro.
        assertEquals("Terminato", AlertText.period(giorni(-5), giorni(-1), now))
    }

    @Test
    fun `il filtro dell'attivo tiene i due estremi aperti`() {
        // Zero vuol dire "da sempre" e "senza fine dichiarata": sono i valori
        // piu' frequenti nel feed vero, e trattarli come una data del 1970
        // nasconderebbe quasi tutti gli avvisi.
        assertTrue(AlertText.active(0, 0, now))
        assertTrue(AlertText.active(giorni(-1), 0, now))
        assertTrue(AlertText.active(0, giorni(1), now))
        assertTrue(AlertText.active(giorni(-1), giorni(1), now))

        assertTrue(!AlertText.active(giorni(1), giorni(2), now), "non e' ancora cominciato")
        assertTrue(!AlertText.active(giorni(-2), giorni(-1), now), "e' gia' finito")
    }
}
