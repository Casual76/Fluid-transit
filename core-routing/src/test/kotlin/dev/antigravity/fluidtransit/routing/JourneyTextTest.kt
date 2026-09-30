package dev.antigravity.fluidtransit.routing

import dev.antigravity.fluidtransit.routing.JourneyText.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Un viaggio proposto, detto a voce.
 *
 * Il difetto: la riga della lista era fatta di segni, e a un lettore di
 * schermo arrivava "07:12, 07:40, 3, 20, ›, 7, 28 min, 1 cambio". Quel 3 sono
 * tre minuti a piedi, ma suona come la linea 3 — e "1 cambio" subito dopo
 * lo smentiva. Qui si inchioda la frase che lo sostituisce.
 */
class JourneyTextTest {

    private fun spoken(
        transfers: Int,
        steps: List<Step>,
        walkOnly: Boolean = false,
        walkSeconds: Int = 0,
        durationLabel: String = "28 min",
    ) = JourneyText.spoken(
        depTime = "07:12",
        arrTime = "07:40",
        durationLabel = durationLabel,
        transfers = transfers,
        steps = steps,
        walkOnly = walkOnly,
        walkSeconds = walkSeconds,
    )

    @Test
    fun `un viaggio diretto dice partenza, arrivo, durata e la linea`() {
        assertEquals(
            "Parti alle 07:12, arrivi alle 07:40, 28 min, diretto. Percorso: linea 7.",
            spoken(0, listOf(Step.Ride("7", live = false))),
        )
    }

    @Test
    fun `i minuti a piedi si dicono a piedi e non si scambiano per una linea`() {
        // E' il caso che ha fatto scrivere la frase: camminare 3 minuti, salire
        // sulla 20, scendere, camminare 2, salire sulla 7. Nessun numero
        // nudo: ogni cifra ha accanto la sua parola.
        val testo = spoken(
            1,
            listOf(
                Step.Walk(180),
                Step.Ride("20", live = false),
                Step.Walk(120),
                Step.Ride("7", live = false),
                Step.Walk(240),
            ),
        )
        assertEquals(
            "Parti alle 07:12, arrivi alle 07:40, 28 min, 1 cambio. " +
                "Percorso: 3 min a piedi, linea 20, 2 min a piedi, linea 7, 4 min a piedi.",
            testo,
        )
    }

    @Test
    fun `i cambi hanno il singolare e il plurale`() {
        assertTrue("1 cambio." in spoken(1, listOf(Step.Ride("7", live = false))))
        assertTrue("2 cambi." in spoken(2, listOf(Step.Ride("7", live = false))))
    }

    @Test
    fun `i cambi hanno una parola sola per la riga, il dettaglio e la voce`() {
        assertEquals("diretto", JourneyText.transfersLabel(0))
        assertEquals("1 cambio", JourneyText.transfersLabel(1))
        assertEquals("3 cambi", JourneyText.transfersLabel(3))
    }

    @Test
    fun `la durata e' quella gia' detta a parole, non minuti nudi`() {
        val testo = spoken(0, listOf(Step.Ride("7", live = false)), durationLabel = "4 h 10 min")
        assertTrue(", 4 h 10 min, diretto." in testo)
    }

    @Test
    fun `una camminata di mezzo minuto o meno non si dice`() {
        // E' il marciapiede della fermata: "meno di un minuto a piedi" in
        // mezzo a un percorso e' rumore, e la striscia nemmeno ne scrive i
        // minuti.
        val testo = spoken(0, listOf(Step.Walk(20), Step.Ride("7", live = false), Step.Walk(30)))
        assertEquals(
            "Parti alle 07:12, arrivi alle 07:40, 28 min, diretto. Percorso: linea 7.",
            testo,
        )
    }

    @Test
    fun `una camminata di poco piu' di mezzo minuto e' un minuto`() {
        // Gli stessi secondi che la durata arrotonda: 45 s sono "1 min", come
        // ovunque altro nell'app.
        val testo = spoken(0, listOf(Step.Walk(45), Step.Ride("7", live = false)))
        assertTrue("Percorso: 1 min a piedi, linea 7." in testo)
    }

    @Test
    fun `un viaggio a piedi dice solo a piedi e la durata una volta sola`() {
        assertEquals(
            "Parti alle 07:12, arrivi alle 07:40, solo a piedi, 8 min.",
            spoken(0, listOf(Step.Walk(480)), walkOnly = true, walkSeconds = 480),
        )
    }

    @Test
    fun `un viaggio a piedi di pochi secondi non dice zero minuti`() {
        assertEquals(
            "Parti alle 07:12, arrivi alle 07:40, solo a piedi, meno di un minuto.",
            spoken(0, listOf(Step.Walk(10)), walkOnly = true, walkSeconds = 10),
        )
    }

    @Test
    fun `se tutte le corse sono dal vivo lo dice col vocabolario del tabellone`() {
        val testo = spoken(
            1,
            listOf(Step.Ride("20", live = true), Step.Walk(120), Step.Ride("7", live = true)),
        )
        assertTrue(testo.endsWith(" Orari dal bus."), testo)
        // Le parole sono quelle di DepartureText, non una copia: se un giorno
        // "dal bus" cambia, cambia anche qui.
        assertEquals("dal bus", DepartureText.source(Certainty.DECLARED))
    }

    @Test
    fun `se solo alcune corse sono dal vivo non promette dal bus su tutte`() {
        val testo = spoken(
            1,
            listOf(Step.Ride("20", live = true), Step.Walk(120), Step.Ride("7", live = false)),
        )
        assertTrue(testo.endsWith(" Orari in parte dal bus."), testo)
    }

    @Test
    fun `senza corse dal vivo non si parla di provenienza`() {
        val testo = spoken(0, listOf(Step.Ride("7", live = false)))
        assertFalse("Orari" in testo, testo)
        assertFalse("bus" in testo, testo)
    }

    @Test
    fun `una linea senza nome breve non prende linea davanti`() {
        // Il chiamante ripiega sul nome lungo quando il breve manca: "linea
        // Firenze - Prato" e' una frase storta.
        val testo = spoken(0, listOf(Step.Ride("Firenze - Prato", live = false)))
        assertTrue("Percorso: Firenze - Prato." in testo, testo)
        assertFalse("linea Firenze" in testo, testo)
    }

    @Test
    fun `una linea senza nessun nome si dice una linea`() {
        val testo = spoken(0, listOf(Step.Ride("", live = false)))
        assertTrue("Percorso: una linea." in testo, testo)
    }

    @Test
    fun `senza tappe non c'e' un percorso vuoto`() {
        assertEquals(
            "Parti alle 07:12, arrivi alle 07:40, 28 min, diretto.",
            spoken(0, emptyList()),
        )
    }

    @Test
    fun `i nomi brevi con lettere e cifre restano linee`() {
        val testo = spoken(1, listOf(Step.Ride("T1", live = false), Step.Ride("LAM", live = false)))
        assertTrue("linea T1, linea LAM." in testo, testo)
    }

    @Test
    fun `un ritardo che conta si dice a voce`() {
        val frase = JourneyText.spoken(
            depTime = "18:08", arrTime = "18:53", durationLabel = "45 min", transfers = 0,
            steps = listOf(JourneyText.Step.Ride("57", live = true, delaySeconds = 21 * 60)),
            walkOnly = false, walkSeconds = 0,
        )
        assertTrue(frase.contains("linea 57, +21 min di ritardo"), frase)
        // Un minuto non e' una notizia, e un numero non dal vivo non si dice.
        val poco = JourneyText.spoken(
            depTime = "18:08", arrTime = "18:53", durationLabel = "45 min", transfers = 0,
            steps = listOf(JourneyText.Step.Ride("57", live = true, delaySeconds = 60)),
            walkOnly = false, walkSeconds = 0,
        )
        assertTrue(!poco.contains("ritardo"), poco)
    }
}
