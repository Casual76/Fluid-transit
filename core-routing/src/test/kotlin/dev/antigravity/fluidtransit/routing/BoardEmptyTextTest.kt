package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 * Qui si fissa che le cinque frasi restino cinque, e che nessuna delle
 * quattro che NON sono "non passa niente" prometta che non passa niente.
 */
class BoardEmptyTextTest {

    @Test
    fun `le cinque situazioni hanno cinque titoli diversi`() {
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
