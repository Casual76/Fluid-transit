package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Un tabellone vuoto e' cinque cose diverse, e si vedevano tutte uguali.
 *
 * Il widget della fermata scriveva "Nessun passaggio a breve" anche quando
 * gli orari non si erano aperti in tempo e anche quando la fermata salvata
 * non esisteva piu' negli orari di oggi: due guasti nostri raccontati come
 * un servizio fermo. La scheda fermata e la scheda Oggi ne distinguevano
 * due su cinque, ma con parole scritte a mano due volte, quasi uguali.
 *
 * Qui si fissa che le frasi restino distinte, e che nessuna di quelle che
 * NON sono "non passa niente" prometta che non passa niente.
 *
 * "Qui intorno" ne ha una sesta, che e' la stessa lista vuota una volta
 * ancora: nessuna fermata nel raggio. Il pannello la diceva "non parte
 * niente nelle prossime due ore", cioe' un'affermazione sul servizio quando
 * a mancare erano le fermate.
 */
class BoardEmptyTextTest {

    @Test
    fun `ogni situazione ha il suo titolo`() {
        val titoli = DepartureText.Trouble.entries.map { DepartureText.empty(it).title }
        assertEquals(titoli.size, titoli.toSet().size, "un titolo vale per due situazioni: $titoli")
    }

    @Test
    fun `solo la situazione vera dice che non passa niente`() {
        for (t in DepartureText.Trouble.entries) {
            val parole = DepartureText.empty(t)
            val dice = (parole.title + " " + parole.detail).lowercase()
            val promette = "non parte niente" in dice || "nessun passaggio" in dice
            assertEquals(
                t == DepartureText.Trouble.NIENTE_A_BREVE,
                promette,
                "$t non deve dire che non passa niente: ${parole.title}",
            )
        }
    }

    @Test
    fun `una fermata sparita non si confonde con una notte tranquilla`() {
        // E' il caso che il widget raccontava peggio: i preferiti salvano
        // l'hash dell'id, che sopravvive allo scambio notturno, ma una
        // fermata tolta dalla fonte no.
        val sparita = DepartureText.empty(DepartureText.Trouble.FERMATA_SCONOSCIUTA)
        assertTrue("orari di oggi" in sparita.detail, "deve dire dov'e' il problema")
        assertTrue("Sceglierne un'altra" in sparita.detail, "e cosa si puo' fare")
    }

    @Test
    fun `gli orari scaduti non fanno sembrare fermo il servizio`() {
        val scaduti = DepartureText.empty(DepartureText.Trouble.ORARI_SCADUTI)
        assertTrue(
            "non sappiamo quando" in scaduti.detail,
            "deve distinguere il nostro guasto dal servizio: ${scaduti.detail}",
        )
    }

    @Test
    fun `anche la fermata sparita si dice al plurale`() {
        // La scheda Oggi puo' avere piu' stelle, e se nessuna trova piu' una
        // fermata "Questa fermata non c'e' piu'" e' la frase di un'altra
        // situazione.
        val tante = DepartureText.empty(DepartureText.Trouble.FERMATA_SCONOSCIUTA, oneStop = false)
        assertTrue("tue fermate" in tante.title, tante.title)
        assertFalse("Questa fermata" in tante.detail, tante.detail)
    }

    @Test
    fun `il soggetto cambia fra una fermata e le tue fermate`() {
        val una = DepartureText.empty(DepartureText.Trouble.NIENTE_A_BREVE, oneStop = true)
        val tante = DepartureText.empty(DepartureText.Trouble.NIENTE_A_BREVE, oneStop = false)
        assertEquals(una.title, tante.title, "il titolo deve essere lo stesso")
        assertTrue("questa fermata" in una.detail, una.detail)
        assertTrue("tue fermate" in tante.detail, tante.detail)
    }

    @Test
    fun `nessuna fermata nel raggio dice il raggio e cosa fare`() {
        val parole = DepartureText.empty(DepartureText.Trouble.NESSUNA_FERMATA_VICINA)
        // Il numero e' quello con cui si cerca, non uno scritto a mano.
        assertTrue(
            Words.distance(DepartureText.NEARBY_RADIUS_METERS) in parole.detail,
            "deve dire fin dove ha guardato: ${parole.detail}",
        )
        assertTrue("cerca una fermata" in parole.detail, "deve dire cosa fare: ${parole.detail}")
        assertTrue("sposta la mappa" in parole.detail, "deve dire cosa fare: ${parole.detail}")
    }

    @Test
    fun `una lista vuota di fermate non diventa mai un non passa niente`() {
        // Il difetto: senza fermate nel raggio la capsula scriveva "Qui
        // intorno non passa niente a breve". Lo stato e' vero e va detto, ma
        // con le parole della fermata che manca.
        val nessuna = assertNotNull(DepartureText.nearbyBoardWithoutAsking(emptyList(), 1_000L))
        assertTrue(nessuna.rows.isEmpty())
        assertEquals(1_000L, nessuna.computedAtEpoch, "e' uno stato calcolato: la capsula si vede")
        val guaio = DepartureText.trouble(nessuna)
        assertEquals(DepartureText.Trouble.NESSUNA_FERMATA_VICINA, guaio)
        val parole = DepartureText.empty(guaio)
        val dice = (parole.title + " " + parole.detail).lowercase()
        assertFalse("non parte niente" in dice || "nessun passaggio" in dice, dice)
    }

    @Test
    fun `non sapere dove si guarda non e' un tabellone datato`() {
        // Il primo giro dell'ancora arriva dopo un paio di secondi: in quel
        // tempo il tabellone e' "non ancora calcolato" (zero), e la capsula
        // resta nascosta invece di scrivere che non passa niente.
        val ignoto = assertNotNull(DepartureText.nearbyBoardWithoutAsking(null, 1_000L))
        assertEquals(0L, ignoto.computedAtEpoch)
    }

    @Test
    fun `con delle fermate il tabellone si chiede agli orari`() {
        assertNull(DepartureText.nearbyBoardWithoutAsking(listOf(4, 9), 1_000L))
    }

    @Test
    fun `un orario zero non fa passare lo stato vero per non calcolato`() {
        val nessuna = assertNotNull(DepartureText.nearbyBoardWithoutAsking(emptyList(), 0L))
        assertTrue(nessuna.computedAtEpoch != 0L, "zero vorrebbe dire 'non calcolato'")
    }

    @Test
    fun `la riga corta del widget sta in un sottotitolo`() {
        // Il widget ha lo spazio di un sottotitolo, non di una frase: senza
        // una forma corta avrebbe ripreso a scriversi le sue parole.
        for (t in DepartureText.Trouble.entries) {
            val short = DepartureText.empty(t).short
            assertTrue(short.length <= 34, "$t: '$short' e' troppo lunga")
            assertFalse(short.endsWith("."), "$t: la riga corta non finisce col punto")
        }
    }

    @Test
    fun `un tabellone fuori validita' si legge come scaduto`() {
        assertEquals(
            DepartureText.Trouble.ORARI_SCADUTI,
            DepartureText.trouble(tabellone(outsideValidity = true)),
        )
        assertEquals(
            DepartureText.Trouble.NIENTE_A_BREVE,
            DepartureText.trouble(tabellone(outsideValidity = false)),
        )
    }

    private fun tabellone(outsideValidity: Boolean) = DepartureBoard(
        stopIndex = 0,
        stopName = "SODERINI TORRINO SANTA ROSA",
        computedAtEpoch = 1_000L,
        rows = emptyList(),
        outsideValidity = outsideValidity,
    )
}
