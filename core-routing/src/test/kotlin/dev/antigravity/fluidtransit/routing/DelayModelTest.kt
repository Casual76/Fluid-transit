package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Il ritardo che si consuma strada facendo — e che non si consuma quando i
 * dati dicono che il bus e' fermo.
 *
 * Il difetto di partenza: un solo intero per corsa, spalmato identico su
 * tutte le fermate, comprese quelle alle spalle del bus. Con trenta
 * fermate e otto minuti di ritardo dichiarati a meta' strada, l'app
 * prometteva otto minuti di ritardo anche al capolinea e ne prometteva
 * otto anche a chi era gia' salito.
 */
class DelayModelTest {

    private val trip = 42

    @Test
    fun `senza osservazioni non si dice niente`() {
        val m = DelayModel()
        assertNull(m.at(trip, 3, 20))
        assertNull(m.current(trip))
        assertNull(m.nextStop(trip))
    }

    @Test
    fun `le fermate alle spalle del bus non portano ritardo`() {
        val m = DelayModel()
        m.observe(trip, delaySeconds = 480, nextStopSeq = 10, atEpoch = 1_000)
        val passed = assertNotNull(m.at(trip, 4, 30))
        assertEquals(DelayModel.Confidence.SERVED, passed.confidence)
    }

    @Test
    fun `la prossima fermata riceve il dato osservato, non una stima`() {
        val m = DelayModel()
        m.observe(trip, delaySeconds = 300, nextStopSeq = 10, atEpoch = 1_000)
        val next = assertNotNull(m.at(trip, 10, 30))
        assertEquals(DelayModel.Confidence.OBSERVED, next.confidence)
        assertEquals(300, next.delaySeconds)
    }

    @Test
    fun `senza prove il ritardo si consuma verso il capolinea`() {
        val m = DelayModel()
        m.observe(trip, delaySeconds = 600, nextStopSeq = 10, atEpoch = 1_000)
        val mid = assertNotNull(m.at(trip, 20, 31))
        val end = assertNotNull(m.at(trip, 30, 31))
        assertEquals(DelayModel.Confidence.PROJECTED, end.confidence)
        assertTrue(mid.delaySeconds in 451..549, "a meta' strada: ${mid.delaySeconds}")
        assertEquals(420, end.delaySeconds, "al capolinea si assume il 30% recuperato")
        assertTrue(end.delaySeconds < mid.delaySeconds)
    }

    @Test
    fun `un ritardo che cala si proietta in recupero`() {
        val m = DelayModel()
        // Trecento secondi recuperati in cinque fermate: sessanta per fermata.
        m.observe(trip, delaySeconds = 600, nextStopSeq = 5, atEpoch = 1_000)
        m.observe(trip, delaySeconds = 300, nextStopSeq = 10, atEpoch = 1_300)
        val ahead = assertNotNull(m.at(trip, 13, 30))
        assertEquals(120, ahead.delaySeconds, "600->300 in 5 fermate: -60/fermata")
        // E non diventa mai un anticipo.
        val far = assertNotNull(m.at(trip, 25, 30))
        assertEquals(0, far.delaySeconds)
    }

    @Test
    fun `un ritardo che cresce si proietta in peggioramento`() {
        val m = DelayModel()
        m.observe(trip, delaySeconds = 120, nextStopSeq = 5, atEpoch = 1_000)
        m.observe(trip, delaySeconds = 300, nextStopSeq = 8, atEpoch = 1_400)
        val ahead = assertNotNull(m.at(trip, 11, 30))
        assertTrue(ahead.delaySeconds > 300, "sta peggiorando: ${ahead.delaySeconds}")
        // Ma la proiezione non scappa: al massimo il doppio dell'osservato.
        val far = assertNotNull(m.at(trip, 29, 30))
        assertTrue(far.delaySeconds <= 600, "proiezione fuori controllo: ${far.delaySeconds}")
    }

    @Test
    fun `un bus fermo che accumula ritardo non si attenua`() {
        val m = DelayModel()
        // Stessa fermata per cinque minuti, e il ritardo sale: e' bloccato.
        m.observe(trip, delaySeconds = 240, nextStopSeq = 7, atEpoch = 1_000)
        m.observe(trip, delaySeconds = 420, nextStopSeq = 7, atEpoch = 1_300)
        val end = assertNotNull(m.at(trip, 29, 30))
        assertEquals(420, end.delaySeconds, "niente recupero mentre e' fermo")
    }

    @Test
    fun `lo stesso giro riproposto non falsa l'andamento`() {
        val m = DelayModel()
        // L'origine si rigenera ogni due minuti: meta' degli snapshot sono
        // fotocopie e non devono contare come osservazioni.
        m.observe(trip, delaySeconds = 300, nextStopSeq = 8, atEpoch = 1_000)
        repeat(5) { m.observe(trip, delaySeconds = 300, nextStopSeq = 8, atEpoch = 1_030L + it * 30) }
        val end = assertNotNull(m.at(trip, 28, 30))
        // Un solo campione utile: vale il recupero di default, non una
        // pendenza nulla letta da campioni duplicati.
        assertTrue(end.delaySeconds < 300, "dovrebbe consumarsi: ${end.delaySeconds}")
    }

    @Test
    fun `un anticipo converge a zero e non diventa ritardo`() {
        val m = DelayModel()
        m.observe(trip, delaySeconds = -180, nextStopSeq = 4, atEpoch = 1_000)
        val end = assertNotNull(m.at(trip, 30, 31))
        assertTrue(end.delaySeconds <= 0, "un anticipo non diventa ritardo: ${end.delaySeconds}")
        assertTrue(end.delaySeconds > -180)
    }

    @Test
    fun `senza nextStopSeq si proietta comunque dall'inizio`() {
        val m = DelayModel()
        m.observe(trip, delaySeconds = 300, nextStopSeq = -1, atEpoch = 1_000)
        val first = assertNotNull(m.at(trip, 0, 20))
        assertEquals(DelayModel.Confidence.OBSERVED, first.confidence)
        val later = assertNotNull(m.at(trip, 10, 20))
        assertEquals(DelayModel.Confidence.PROJECTED, later.confidence)
    }

    @Test
    fun `le corse vecchie si dimenticano`() {
        val m = DelayModel()
        m.observe(trip, delaySeconds = 60, nextStopSeq = 1, atEpoch = 1_000)
        m.observe(99, delaySeconds = 60, nextStopSeq = 1, atEpoch = 5_000)
        m.forgetBefore(3_000)
        assertEquals(1, m.size)
        assertNull(m.at(trip, 5, 20))
        assertNotNull(m.at(99, 5, 20))
    }
}
