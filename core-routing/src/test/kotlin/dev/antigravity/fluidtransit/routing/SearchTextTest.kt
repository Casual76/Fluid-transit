package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Nel pianificatore le linee sono filtrate via dai risultati, e una ricerca
 * vuota non puo' dire che "nessuna linea si chiama cosi'": la linea 6 c'e'.
 */
class SearchTextTest {

    @Test
    fun `le frasi del pianificatore non dicono che una linea non esiste`() {
        for (frase in listOf(
            SearchText.PLANNER_ONE_CHAR,
            SearchText.PLANNER_NONE,
            SearchText.PLANNER_STOPS_ONLY,
        )) {
            assertFalse(frase.contains("Nessuna linea"), frase)
            assertFalse(frase.contains("fermate e linee", ignoreCase = true), frase)
        }
    }

    @Test
    fun `con una lettera sola si dice di cosa c'e' bisogno`() {
        assertTrue(SearchText.PLANNER_ONE_CHAR.contains("almeno due lettere"))
    }

    @Test
    fun `senza risultati si spiega dove sono finite le linee`() {
        assertTrue(SearchText.PLANNER_NONE.contains("linee"))
    }
}
