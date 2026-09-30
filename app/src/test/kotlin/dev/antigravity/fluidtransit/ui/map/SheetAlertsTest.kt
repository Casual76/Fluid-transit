package dev.antigravity.fluidtransit.ui.map

import dev.antigravity.fluidtransit.data.rt.GtfsRtLite
import dev.antigravity.fluidtransit.routing.Ftb
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gli avvisi delle schede: quello che e' in corso e quello che sta per
 * cominciare, e niente altro.
 *
 * Il difetto era un silenzio: alle 07:40 uno sciopero annunciato per le 08:30
 * non compariva sulla fermata, mentre "Oggi" lo diceva. Nessun errore, una
 * riga che non c'e'. Quindi si inchioda l'insieme di quelli che entrano e
 * l'ordine in cui escono, che decide quali due stanno in cima.
 */
class SheetAlertsTest {

    // Le 07:40 di un mercoledi' di fine settembre, ora di Roma.
    private val adesso = ZonedDateTime.of(2026, 9, 30, 7, 40, 0, 0, Ftb.ROME).toEpochSecond()

    private fun fra(secondi: Long) = adesso + secondi

    private fun avviso(
        linee: Set<Long>,
        header: String,
        inizio: Long,
        fine: Long = 0L,
        descrizione: String = "",
    ) = GtfsRtLite.RtAlert(linee, header, descrizione, inizio, fine)

    private val linee = mapOf(12L to "12", 7L to "7")

    private fun righe(
        avvisi: List<GtfsRtLite.RtAlert>,
        conNomi: Boolean = true,
    ) = SheetAlerts.rows(avvisi, linee, adesso, withLineNames = conNomi, maxBodyChars = 80)

    @Test
    fun `lo sciopero delle 08 e 30 si legge sulla fermata alle 07 e 40`() {
        val sciopero = avviso(setOf(12L), "Sciopero", fra(50 * 60), fra(4 * 3600 + 50 * 60))
        assertEquals(
            listOf("Da oggi alle 08:30 a oggi alle 12:30 · 12 · Sciopero"),
            righe(listOf(sciopero)),
        )
    }

    @Test
    fun `sulla scheda di una linea sola il nome della linea non si ripete`() {
        val sciopero = avviso(setOf(12L), "Sciopero", fra(50 * 60), fra(4 * 3600 + 50 * 60))
        assertEquals(
            listOf("Da oggi alle 08:30 a oggi alle 12:30 · Sciopero"),
            righe(listOf(sciopero), conNomi = false),
        )
    }

    @Test
    fun `un avviso in corso non ripete il periodo`() {
        val deviazione = avviso(setOf(12L), "Deviazione in via Nazionale", fra(-3600))
        assertEquals(listOf("12 · Deviazione in via Nazionale"), righe(listOf(deviazione)))
    }

    @Test
    fun `quello in corso sta prima anche se quello di domani comincia dopo`() {
        val domani = avviso(setOf(12L), "Sciopero", fra(20 * 3600), fra(24 * 3600))
        val inCorso = avviso(setOf(12L), "Deviazione", fra(-3600))
        val r = righe(listOf(domani, inCorso))
        assertEquals("12 · Deviazione", r[0])
        assertTrue(r[1].endsWith("12 · Sciopero"))
    }

    @Test
    fun `fra quelli in corso il piu' recente per primo`() {
        val vecchio = avviso(setOf(12L), "Lavori", fra(-90 * 24 * 3600))
        val recente = avviso(setOf(12L), "Deviazione", fra(-3600))
        assertEquals(
            listOf("12 · Deviazione", "12 · Lavori"),
            righe(listOf(vecchio, recente)),
        )
    }

    @Test
    fun `fra quelli che cominciano il piu' vicino per primo`() {
        val piuTardi = avviso(setOf(12L), "Tardi", fra(30 * 3600))
        val prima = avviso(setOf(12L), "Presto", fra(3600))
        val r = righe(listOf(piuTardi, prima))
        assertTrue(r[0].endsWith("12 · Presto"))
        assertTrue(r[1].endsWith("12 · Tardi"))
    }

    @Test
    fun `un avviso di rete, senza linee, non e' di questa scheda`() {
        val rete = avviso(emptySet(), "Sciopero generale", fra(3600))
        assertTrue(righe(listOf(rete)).isEmpty())
    }

    @Test
    fun `un avviso di un'altra linea non entra`() {
        val altra = avviso(setOf(99L), "Deviazione", fra(-3600))
        assertTrue(righe(listOf(altra)).isEmpty())
    }

    @Test
    fun `finito o troppo in la' non entra`() {
        val finito = avviso(setOf(12L), "Finito", fra(-7200), fra(-60))
        val lontano = avviso(setOf(12L), "Lontano", fra(3 * 24 * 3600))
        assertTrue(righe(listOf(finito, lontano)).isEmpty())
    }

    @Test
    fun `senza testata si prende l'inizio del testo, ripulito dagli hashtag`() {
        val senzaTestata = avviso(
            setOf(7L), "", fra(-3600),
            descrizione = "#at_Firenze\n\nLinea 7 deviata in via Nazionale",
        )
        assertEquals(
            listOf("7 · Linea 7 deviata in via Nazionale"),
            righe(listOf(senzaTestata)),
        )
    }

    @Test
    fun `una lista vuota di avvisi resta vuota`() {
        assertTrue(righe(emptyList()).isEmpty())
    }

    // --- il giro degli avvisi: si riprova, e si dice quando e' vecchio ---

    private fun vista(feed: SheetAlerts.Feed) =
        SheetAlerts.view(feed, linee, adesso, withLineNames = true, maxBodyChars = 80)

    @Test
    fun `dopo un download fallito si riprova presto, non fra cinque minuti`() {
        // Il difetto: in galleria il download falliva, e la scheda restava su
        // "Avvisi non arrivati" anche a rete tornata, fino a chiuderla e
        // riaprirla. Il ritmo dopo un null e' quello del ritentativo.
        assertEquals(SheetAlerts.RETRY_MS, SheetAlerts.nextPollMs(SheetAlerts.Feed(null, null)))
    }

    @Test
    fun `servendo una lista vecchia si riprova presto`() {
        val vecchia = SheetAlerts.Feed(emptyList(), adesso - 600)
        assertEquals(SheetAlerts.RETRY_MS, SheetAlerts.nextPollMs(vecchia))
    }

    @Test
    fun `dopo un successo si aspetta come Oggi e la schermata Avvisi`() {
        assertEquals(
            dev.antigravity.fluidtransit.data.rt.RealtimeClient.ALERTS_POLL_MS,
            SheetAlerts.nextPollMs(SheetAlerts.Feed(emptyList(), null)),
        )
    }

    @Test
    fun `un download fallito si dice, non diventa nessun avviso`() {
        val v = vista(SheetAlerts.Feed(null, null))
        assertEquals(null, v.rows)
        assertEquals(null, v.staleNote)
    }

    @Test
    fun `una lista vecchia porta la sua eta'`() {
        val sciopero = avviso(setOf(12L), "Sciopero", fra(-3600))
        val v = vista(SheetAlerts.Feed(listOf(sciopero), adesso - 1800))
        assertEquals(1, v.rows?.size)
        assertEquals(
            dev.antigravity.fluidtransit.routing.AlertText.staleCard(adesso - 1800, adesso),
            v.staleNote,
        )
    }

    @Test
    fun `una lista fresca non ha nota`() {
        val v = vista(SheetAlerts.Feed(emptyList(), null))
        assertEquals(emptyList<String>(), v.rows)
        assertEquals(null, v.staleNote)
    }
}
