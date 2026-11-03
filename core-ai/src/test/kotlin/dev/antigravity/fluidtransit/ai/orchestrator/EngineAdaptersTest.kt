package dev.antigravity.fluidtransit.ai.orchestrator

import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ai.orchestrator.ChipParser
import dev.antigravity.fluidtransit.ai.tools.OpenTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il punto in cui Fluid Transit adatta l'engine invece di usarlo cosi' com'e':
 * i chip che l'app capisce. Le scelte di partenza delle preferenze sono in
 * [AiPreferenceDefaultsTest].
 */
class EngineAdaptersTest {

    // ------------------------------------------------------------ i chip

    @Test
    fun `un posto si scrive luogo o place, sempre con un nome`() {
        assertEquals("Careggi", TransitChips.place(AnswerChip("luogo", "Careggi")))
        assertEquals("Careggi", TransitChips.place(AnswerChip("place", "Careggi")))
        assertNull(TransitChips.place(AnswerChip("luogo", null)))
        assertFalse(TransitChips.accepts(AnswerChip("luogo", null)))
    }

    @Test
    fun `una pagina e' un id senza valore, gli altri chip spariscono`() {
        assertEquals(OpenTarget.MAP, TransitChips.page(AnswerChip("mappa")))
        assertTrue(TransitChips.accepts(AnswerChip("preferiti")))
        assertFalse(TransitChips.accepts(AnswerChip("radar")))
        assertFalse(TransitChips.accepts(AnswerChip("mappa", "Firenze")))
    }

    @Test
    fun `la risposta perde i marcatori e i posti non si ripetono`() {
        val (text, chips) = ChipParser.extract(
            "Il 14 passa fra 3 minuti.\n\n[[luogo:Careggi]] [[place:Careggi]] [[radar]]",
            TransitChips::accepts,
        )
        assertEquals("Il 14 passa fra 3 minuti.", text)
        assertEquals(listOf("Careggi"), TransitChips.places(chips))
    }
}
