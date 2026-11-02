package dev.antigravity.fluidtransit.ai.orchestrator

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import dev.antigravity.fluidengine.ai.keys.AiSettingsStore
import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ai.orchestrator.ChipParser
import dev.antigravity.fluidengine.ai.provider.OpenRouterDataPolicy
import dev.antigravity.fluidtransit.ai.keys.TransitAiPreferences
import dev.antigravity.fluidtransit.ai.tools.OpenTarget
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I due punti in cui Fluid Transit adatta l'engine invece di usarlo cosi'
 * com'e': i chip che l'app capisce, e le scelte di partenza delle preferenze
 * che l'engine risolve diversamente.
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

    // ------------------------------------------- le preferenze di partenza

    private val defaults = TransitAiPreferences.TransitDefaults

    @Test
    fun `su un file nuovo le azioni partono accese e OpenRouter riceve deny`() = runBlocking {
        val prefs = emptyPreferences()
        assertTrue(defaults.shouldMigrate(prefs))
        val migrated = defaults.migrate(prefs)
        assertEquals(true, migrated[TransitAiPreferences.ACTIONS_ENABLED])
        assertEquals(OpenRouterDataPolicy.DENY.name, migrated[TransitAiPreferences.OPENROUTER_POLICY])
    }

    @Test
    fun `una scelta gia' fatta dall'utente non si tocca`() = runBlocking {
        val prefs = preferencesOf(
            TransitAiPreferences.ACTIONS_ENABLED to false,
            TransitAiPreferences.OPENROUTER_POLICY to OpenRouterDataPolicy.ACCOUNT.name,
        )
        assertFalse(defaults.shouldMigrate(prefs))
    }

    @Test
    fun `la vecchia chiave accesa vale la politica dell'account`() = runBlocking {
        val migrated = defaults.migrate(preferencesOf(TransitAiPreferences.OLD_DATA_COLLECTION to true))
        assertEquals(OpenRouterDataPolicy.ACCOUNT.name, migrated[TransitAiPreferences.OPENROUTER_POLICY])
    }

    @Test
    fun `l'engine legge le scelte di prima dallo stesso file`() = runBlocking {
        val dir = Files.createTempDirectory("ft-ai").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(
                migrations = listOf(defaults),
                scope = scope,
                produceFile = { File(dir, "ai.preferences_pb") },
            )
            val settings = AiSettingsStore(store).current()
            assertTrue(settings.actionsEnabled)
            assertEquals(OpenRouterDataPolicy.DENY, settings.openRouterDataPolicy)
            // Spente dall'utente restano spente: la migrazione non riparte.
            AiSettingsStore(store).setActionsEnabled(false)
            store.edit { }
            assertFalse(AiSettingsStore(store).current().actionsEnabled)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
