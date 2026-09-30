package dev.antigravity.fluidtransit.routing

import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `gli avvisi rimasti fuori si contano in italiano`() {
        assertEquals("Un altro avviso su queste linee", AlertText.more(1, "su queste linee"))
        assertEquals("Altri 2 avvisi su questa linea", AlertText.more(2, "su questa linea"))
        assertEquals("Altri 5 avvisi su queste linee", AlertText.more(5, "su queste linee"))
    }

    @Test
    fun `avvisi vecchi dicono di quando sono`() {
        // 30/09/2026 08:05 e 14:00, ora di Roma.
        val alle8 = java.time.ZonedDateTime.of(2026, 9, 30, 8, 5, 0, 0, Ftb.ROME).toEpochSecond()
        val alle14 = alle8 + (5 * 60 + 55) * 60
        assertEquals(
            "Aggiornati oggi alle 08:05: adesso non riusciamo a scaricarli",
            AlertText.stale(alle8, alle14),
        )
    }

    @Test
    fun `la nota di una scheda nomina gli avvisi`() {
        val alle8 = java.time.ZonedDateTime.of(2026, 9, 30, 8, 5, 0, 0, Ftb.ROME).toEpochSecond()
        val alle14 = alle8 + (5 * 60 + 55) * 60
        assertEquals(
            "Avvisi aggiornati oggi alle 08:05: adesso non riusciamo a scaricarli",
            AlertText.staleCard(alle8, alle14),
        )
    }

    @Test
    fun `un controllo di ieri sera dice ieri e l'ora, non una data nuda`() {
        // Il download fallisce alle 00:20 e l'ultimo riuscito era delle 23:50:
        // e' mezz'ora, e "aggiornati 30 settembre" la faceva leggere come un
        // giorno intero.
        val ieriSera = ZonedDateTime.of(2026, 9, 30, 23, 50, 0, 0, Ftb.ROME).toEpochSecond()
        val dopoMezzanotte = ZonedDateTime.of(2026, 10, 1, 0, 20, 0, 0, Ftb.ROME).toEpochSecond()
        assertEquals(
            "Aggiornati ieri alle 23:50: adesso non riusciamo a scaricarli",
            AlertText.stale(ieriSera, dopoMezzanotte),
        )
        assertEquals("ieri alle 23:50", AlertText.moment(ieriSera, dopoMezzanotte))
    }

    @Test
    fun `ieri vale anche a cavallo dell'anno`() {
        val capodanno = ZonedDateTime.of(2026, 1, 1, 0, 20, 0, 0, Ftb.ROME).toEpochSecond()
        val sanSilvestro = ZonedDateTime.of(2025, 12, 31, 23, 50, 0, 0, Ftb.ROME).toEpochSecond()
        assertEquals("ieri alle 23:50", AlertText.moment(sanSilvestro, capodanno))
    }

    @Test
    fun `dentro la settimana il passato dice il giorno con scorso`() {
        // Mercoledi' 16/09: "lunedi' alle 10:00" potrebbe essere quello che
        // viene, e "il lunedi'" vorrebbe dire tutti i lunedi'.
        assertEquals(
            "Aggiornati lunedi' scorso alle 10:00: adesso non riusciamo a scaricarli",
            AlertText.stale(giorni(-2), now).accenti(),
        )
        assertEquals("giovedi' scorso alle 10:00", AlertText.moment(giorni(-6), now).accenti())
    }

    @Test
    fun `la domenica e' scorsa, non scorso`() {
        assertEquals("domenica scorsa alle 10:00", AlertText.moment(giorni(-3), now))
    }

    @Test
    fun `oltre la settimana il passato e' una data con l'articolo`() {
        // Sette giorni fa e' mercoledi' come oggi: "mercoledi' scorso"
        // sarebbe ambiguo, ed e' qui che si passa alla data.
        assertEquals("9 settembre", AlertText.moment(giorni(-7), now))
        assertEquals("il 9 settembre", AlertText.pastMoment(giorni(-7), now))
        assertEquals(
            "Aggiornati il 6 settembre: adesso non riusciamo a scaricarli",
            AlertText.stale(giorni(-10), now),
        )
    }

    @Test
    fun `una data passata di un altro anno porta l'anno`() {
        val dicembre = ZonedDateTime.of(2025, 12, 20, 12, 0, 0, 0, Ftb.ROME).toEpochSecond()
        val gennaio = ZonedDateTime.of(2026, 1, 10, 9, 0, 0, 0, Ftb.ROME).toEpochSecond()
        assertEquals("il 20 dicembre 2025", AlertText.pastMoment(dicembre, gennaio))
    }

    @Test
    fun `senza data l'istante non vuole l'articolo`() {
        assertEquals("oggi alle 10:00", AlertText.pastMoment(now, now))
        assertEquals("domani alle 10:00", AlertText.pastMoment(giorni(1), now))
        assertEquals("il 26 settembre", AlertText.pastMoment(giorni(10), now))
    }

    @Test
    fun `un avviso senza code torna identico`() {
        val raw = "Deviazione in via Nazionale fino a stasera."
        assertEquals(raw, AlertText.body(raw))
    }

    // Le 07:40: uno sciopero delle 08:30 non e' ancora cominciato, ed e'
    // proprio quello che chi aspetta un bus deve sapere.
    private val alleSette40 = ZonedDateTime.of(2026, 9, 30, 7, 40, 0, 0, Ftb.ROME).toEpochSecond()

    @Test
    fun `un avviso in corso, senza fine dichiarata, e' rilevante`() {
        assertTrue(AlertText.relevant(alleSette40 - 3600, 0L, alleSette40))
    }

    @Test
    fun `uno sciopero che comincia fra un'ora e' rilevante anche se non e' partito`() {
        assertFalse(AlertText.active(alleSette40 + 3600, alleSette40 + 5 * 3600, alleSette40))
        assertTrue(AlertText.relevant(alleSette40 + 3600, alleSette40 + 5 * 3600, alleSette40))
    }

    @Test
    fun `fra tre giorni e' un annuncio, non un avviso di adesso`() {
        assertFalse(AlertText.relevant(alleSette40 + 3 * 24 * 3600, 0L, alleSette40))
    }

    @Test
    fun `l'orizzonte e' due giorni, al secondo`() {
        assertTrue(AlertText.relevant(alleSette40 + AlertText.HORIZON_SECONDS, 0L, alleSette40))
        assertFalse(AlertText.relevant(alleSette40 + AlertText.HORIZON_SECONDS + 1, 0L, alleSette40))
    }

    @Test
    fun `uno gia' finito non e' rilevante, uno che finisce adesso si`() {
        assertFalse(AlertText.relevant(alleSette40 - 7200, alleSette40 - 60, alleSette40))
        assertTrue(AlertText.relevant(alleSette40 - 7200, alleSette40, alleSette40))
    }

    @Test
    fun `zero e zero vuol dire da sempre e senza fine`() {
        assertTrue(AlertText.relevant(0L, 0L, alleSette40))
    }
}
