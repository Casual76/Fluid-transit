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
        // Venerdi' e sabato: dentro la settimana si dicono per nome, e un
        // giorno per nome non vuole l'articolo.
        assertEquals(
            "Da venerdi' alle 10:00 a sabato alle 10:00",
            AlertText.period(giorni(2), giorni(3), now).accenti(),
        )
        // Oltre la settimana sono date, e le date l'articolo lo vogliono.
        assertEquals(
            "Dal 26 settembre al 6 ottobre",
            AlertText.period(giorni(10), giorni(20), now),
        )
    }

    @Test
    fun `l'articolo segue il modo in cui si nomina il giorno`() {
        // Si legge "fino a domani" ma "fino AL 31 dicembre": la preposizione
        // cambia con la forma del giorno, e senza questa distinzione sulla
        // scheda Oggi si leggeva "Fino a 31 dicembre". Il caso opposto e'
        // peggio: un avviso che comincia piu' tardi oggi dava "Dal oggi alle
        // 14:00".
        val piuTardiOggi = ore(4)
        assertEquals("Da oggi alle 14:00", AlertText.period(piuTardiOggi, 0, now))
        val dicembre = ZonedDateTime.of(2026, 12, 31, 23, 59, 0, 0, Ftb.ROME).toEpochSecond()
        assertEquals("Fino al 31 dicembre", AlertText.period(0, dicembre, now))
    }

    /** Il nome dei giorni arriva da `Locale.ITALIAN`, con gli accenti veri. */
    private fun String?.accenti(): String? = this
        ?.replace("ì", "i'")
        ?.replace("à", "a'")

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
    @Test
    fun `una data di un altro anno porta l'anno`() {
        // "Fino a 28 febbraio" letto a settembre puo' voler dire il febbraio
        // appena passato o quello che viene, e i due sensi sono opposti: uno
        // dice "e' finita", l'altro "dura ancora cinque mesi". Gli avvisi per
        // lavori scavalcano l'anno regolarmente — quello visto sul telefono
        // dura dal 14 ottobre 2025 al 28 febbraio.
        val febbraioProssimo =
            ZonedDateTime.of(2027, 2, 28, 23, 59, 0, 0, Ftb.ROME).toEpochSecond()
        assertEquals(
            "Fino al 28 febbraio 2027",
            AlertText.period(0, febbraioProssimo, now),
        )
    }

    @Test
    fun `una data di quest'anno resta senza anno`() {
        val dicembre = ZonedDateTime.of(2026, 12, 24, 12, 0, 0, 0, Ftb.ROME).toEpochSecond()
        assertEquals("Fino al 24 dicembre", AlertText.period(0, dicembre, now))
    }

    @Test
    fun `l'hashtag del gestore non e' la prima cosa che si legge`() {
        // Gli avvisi di Autolinee Toscane nascono come messaggi social:
        // cominciano con "#at_Firenze" e una riga vuota. In un sottotitolo
        // tagliato a centoventi caratteri, le prime undici lettere di un
        // avviso erano quelle.
        val raw = "#at_Firenze\n\nDa martedi' la linea 17 cambia percorso."
        assertEquals("Da martedi' la linea 17 cambia percorso.", AlertText.body(raw))
    }

    @Test
    fun `un hashtag in mezzo al testo resta dov'e'`() {
        val raw = "Sciopero venerdi'.\nInformazioni su #scioperi e altro."
        assertTrue(AlertText.body(raw).contains("#scioperi"))
    }

    @Test
    fun `le righe vuote a raffica diventano una`() {
        val raw = "Prima.\n\n\n\nSeconda."
        assertEquals("Prima.\n\nSeconda.", AlertText.body(raw))
    }

    @Test
    fun `le emoji restano, perche' dicono qualcosa a colpo d'occhio`() {
        val cantiere = "\uD83D\uDEA7"
        val raw = "#at_Firenze\n\n$cantiere Lavori in via della Scala."
        assertTrue(AlertText.body(raw).startsWith(cantiere), AlertText.body(raw))
    }

    @Test
    fun `un avviso senza code torna identico`() {
        val raw = "Deviazione in via Nazionale fino a stasera."
        assertEquals(raw, AlertText.body(raw))
    }
}
