package dev.antigravity.fluidtransit.ai.orchestrator

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.antigravity.fluidengine.ai.keys.AiSettingsDefaults
import dev.antigravity.fluidengine.ai.keys.AiSettingsStore
import dev.antigravity.fluidengine.ai.provider.OpenRouterDataPolicy
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I valori di partenza delle preferenze dell'assistente.
 *
 * Prima della 3.0 l'app apriva il file `ai` da se' e lo riscriveva con una
 * migrazione, perche' l'engine risolveva un valore mai scelto in modo diverso
 * da lei: azioni nell'app spente invece che accese, politica sui dati
 * dell'account invece di `deny` verso OpenRouter. Adesso sono i default
 * dell'engine (`AiSettingsDefaults`) e il file non si tocca: questi test
 * provano che chi non ha mai scelto trova le due scelte di prima, che chi ha
 * scelto trova la sua, e che un file scritto dalla versione che usava la
 * migrazione si legge uguale.
 */
class AiPreferenceDefaultsTest {

    // Le chiavi del file `ai`: sono quelle di AiSettingsStore, che qui non le espone.
    private val enabledKey = booleanPreferencesKey("enabled")
    private val actionsKey = booleanPreferencesKey("actions_enabled")
    private val policyKey = stringPreferencesKey("openrouter_data_policy")

    private val transitDefaults = AiSettingsDefaults(
        actionsEnabled = true,
        openRouterDataPolicy = OpenRouterDataPolicy.DENY,
    )

    private fun withStore(block: suspend (DataStore<Preferences>) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("ft-ai").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            block(PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "ai.preferences_pb") }))
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `su un file nuovo le azioni partono accese e OpenRouter riceve deny`() = withStore { store ->
        val settings = AiSettingsStore(store, transitDefaults).current()
        assertTrue(settings.actionsEnabled)
        assertEquals(OpenRouterDataPolicy.DENY, settings.openRouterDataPolicy)
        // E l'assistente resta spento finche' nessuno lo accende: una chiave non basta da sola.
        assertFalse(settings.enabled)
        assertFalse(settings.enabledChosen)
    }

    @Test
    fun `una scelta gia' fatta dall'utente vale quella, il file non si riscrive`() = withStore { store ->
        val settings = AiSettingsStore(store, transitDefaults)
        settings.setActionsEnabled(false)
        settings.setOpenRouterDataPolicy(OpenRouterDataPolicy.ACCOUNT)
        val letto = settings.current()
        assertFalse(letto.actionsEnabled)
        assertEquals(OpenRouterDataPolicy.ACCOUNT, letto.openRouterDataPolicy)
        // Un secondo store sullo stesso file (un riavvio) non rimette i default sopra.
        val riaperto = AiSettingsStore(store, transitDefaults).current()
        assertFalse(riaperto.actionsEnabled)
        assertEquals(OpenRouterDataPolicy.ACCOUNT, riaperto.openRouterDataPolicy)
    }

    @Test
    fun `un file scritto dalla vecchia migrazione si legge come prima`() = withStore { store ->
        // La migrazione di TransitAiPreferences scriveva proprio queste due voci.
        store.edit {
            it[actionsKey] = true
            it[policyKey] = OpenRouterDataPolicy.DENY.name
        }
        val letto = AiSettingsStore(store, transitDefaults).current()
        assertTrue(letto.actionsEnabled)
        assertEquals(OpenRouterDataPolicy.DENY, letto.openRouterDataPolicy)
    }

    @Test
    fun `una chiave verificata accende l'assistente solo a chi non ha mai scelto`() = withStore { store ->
        val settings = AiSettingsStore(store, transitDefaults)
        assertTrue(settings.enableIfNeverChosen())
        assertTrue(settings.current().enabled)
        assertTrue(settings.current().enabledChosen)

        // Chi l'ha spento una volta lo trova spento: e' una scelta come un'altra.
        settings.setEnabled(false)
        assertFalse(settings.enableIfNeverChosen())
        assertFalse(settings.current().enabled)
    }

    @Test
    fun `un assistente spento a mano prima di verificare una chiave non si riaccende`() = withStore { store ->
        store.edit { it[enabledKey] = false }
        val settings = AiSettingsStore(store, transitDefaults)
        assertFalse(settings.enableIfNeverChosen())
        assertFalse(settings.current().enabled)
    }
}
