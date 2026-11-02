package dev.antigravity.fluidtransit.ai.keys

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.antigravity.fluidengine.ai.keys.AiSettingsStore
import dev.antigravity.fluidengine.ai.provider.OpenRouterDataPolicy

/**
 * Il file `ai` delle preferenze dell'assistente, aperto da qui e non
 * dall'engine.
 *
 * Il formato e' lo stesso (stesso nome, stesse chiavi: la copia che Fluid
 * Transit aveva in `:core-ai` era quella dell'engine), quindi le scelte gia'
 * salvate sui telefoni restano dove sono. Due cose pero' l'engine le risolve in
 * modo diverso da come le risolveva l'app, e un aggiornamento le avrebbe
 * cambiate in silenzio:
 *
 * - le **azioni nell'app** partivano accese ("mostra subito, agisci con
 *   conferma"); per l'engine un valore mai scelto vale spento, e chi non aveva
 *   mai toccato l'interruttore si sarebbe trovato un assistente che non apre
 *   piu' niente;
 * - a OpenRouter l'app chiedeva sempre `data_collection: deny`; l'engine di
 *   suo non dice niente (vale la politica dell'account).
 *
 * Per questo il file si apre con una migrazione ([TransitDefaults]) che scrive
 * le due scelte di prima dove non c'e' ancora niente: gira prima di
 * qualunque lettura, anche dell'engine, e una scelta gia' fatta dall'utente
 * non la tocca. E per questo l'engine riceve il [DataStore] gia' aperto
 * ([AiSettingsStore] col costruttore primario): due DataStore sullo stesso
 * file nello stesso processo fanno cadere l'app, quindi il costruttore
 * `AiSettingsStore(context)` qui non va usato mai.
 */
class TransitAiPreferences(context: Context) {

    private val store: DataStore<Preferences> = context.applicationContext.transitAiStore

    /** Le preferenze per l'engine: lo stesso file, la stessa istanza. */
    val settings: AiSettingsStore = AiSettingsStore(store)

    /**
     * Accende l'assistente solo se l'utente non ha mai scelto.
     *
     * `enabled` parte da false, e una chiave verificata non accendeva niente: chi incollava la chiave
     * vedeva "ok" e tornava sulla mappa col microfono di sistema, senza sapere che mancava
     * l'interruttore. Il valore assente e' "mai scelto" (il Boolean gia' risolto dell'engine non lo
     * distingue da "spento"): chi l'ha spento una volta lo trova spento.
     */
    suspend fun enableIfNeverChosen() {
        store.edit { if (ENABLED !in it) it[ENABLED] = true }
    }

    /** Le due scelte che Fluid Transit faceva prima dell'engine, scritte solo dove manca un valore. */
    internal object TransitDefaults : DataMigration<Preferences> {
        override suspend fun shouldMigrate(currentData: Preferences): Boolean =
            ACTIONS_ENABLED !in currentData || OPENROUTER_POLICY !in currentData

        override suspend fun migrate(currentData: Preferences): Preferences {
            val prefs = currentData.toMutablePreferences()
            if (ACTIONS_ENABLED !in prefs) prefs[ACTIONS_ENABLED] = true
            if (OPENROUTER_POLICY !in prefs) {
                // La vecchia chiave booleana non aveva un interruttore nell'app: era sempre
                // "deny". Se un giorno qualcuno l'avesse accesa, vale la politica dell'account.
                val allowed = prefs[OLD_DATA_COLLECTION] == true
                prefs[OPENROUTER_POLICY] = (if (allowed) OpenRouterDataPolicy.ACCOUNT else OpenRouterDataPolicy.DENY).name
            }
            return prefs.toPreferences()
        }

        override suspend fun cleanUp() = Unit
    }

    internal companion object {
        // Le chiavi sono quelle di `AiSettingsStore` dell'engine: e' il suo file.
        val ENABLED = booleanPreferencesKey("enabled")
        val ACTIONS_ENABLED = booleanPreferencesKey("actions_enabled")
        val OPENROUTER_POLICY = stringPreferencesKey("openrouter_data_policy")

        /** La chiave della copia di prima: si legge una volta, non si scrive piu'. */
        val OLD_DATA_COLLECTION = booleanPreferencesKey("openrouter_data_collection")
    }
}

private val Context.transitAiStore: DataStore<Preferences> by preferencesDataStore(
    name = "ai",
    produceMigrations = { listOf(TransitAiPreferences.TransitDefaults) },
)
