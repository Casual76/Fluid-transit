package dev.antigravity.fluidtransit.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.aiui.settings.fluidAiSettingsSection
import dev.antigravity.fluidengine.foundation.EngineBuild
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.fluid.FluidEngineLicenses
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidengine.ui.fluid.FluidUpdateChannelStyle
import dev.antigravity.fluidengine.ui.fluid.fluidAppearanceSection
import dev.antigravity.fluidengine.ui.fluid.fluidLicensesSection
import dev.antigravity.fluidengine.ui.fluid.fluidUpdateSection
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidtransit.BuildConfig
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.data.nav.TravelMode
import dev.antigravity.fluidtransit.ui.theme.TransitBrand
import kotlinx.coroutines.launch

/**
 * Impostazioni: l'aspetto, il modo viaggio, l'assistente, gli aggiornamenti, e
 * in fondo — visibile, non dietro un gesto: deciso cosi' — lo stato dei dati e
 * le licenze.
 *
 * Quasi tutte le sezioni sono dell'engine (`fluidAppearanceSection`,
 * `fluidAiSettingsSection`, `fluidUpdateSection`, `fluidLicensesSection`: la
 * stessa pagina di ogni app Pampa, quindi con gli stessi bug corretti una volta
 * sola). Qui restano il modo viaggio, che e' di questa app, e i collegamenti.
 *
 * Lo stato dei dati era un `if` qui dentro, e quindi esisteva solo per chi
 * passava da questa schermata: non aveva un nome, non lo poteva aprire un
 * deep link, e non ci si poteva mandare la capsula "Bus live non
 * disponibili", che di quello parla. Adesso e' una destinazione della shell
 * e questa riga si limita a chiederla.
 */
@Composable
fun SettingsTab(app: FluidTransitApp, onOpenDataStatus: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val settings by app.settingsStore.settings
        .collectAsStateWithLifecycle(initialValue = EngineSettings())
    val assistant = rememberAssistantSettingsSection(app)
    val update = rememberUpdateSection(app)

    FluidScreen(title = "Impostazioni") {
        // Tema, nero assoluto, accento e aptica. L'accento e' quello di sempre: l'ametista
        // dell'app oppure i colori del telefono (dove ci sono), senza i quattro preset
        // dell'engine, che qui non c'erano.
        fluidAppearanceSection(
            settings = settings,
            onSettingsChange = { next -> scope.launch { app.settingsStore.update { next } } },
            brand = TransitBrand,
            presets = emptyList(),
            dynamicAccent = Build.VERSION.SDK_INT >= 31,
        )

        item(key = "travel-header") { FluidSectionHeader(title = "A bordo") }
        item(key = "travel") {
            var mode by remember { mutableStateOf(app.travelMode.mode) }
            FluidListGroup {
                // Tre scelte visibili, come il tema. Prima era una riga che a
                // ogni tocco passava al modo dopo, senza niente che dicesse
                // che si poteva toccare ne' quali fossero le altre due.
                FluidListRow(
                    title = "Modo viaggio",
                    subtitle = when (mode) {
                        TravelMode.PRECISO ->
                            "GPS continuo e aggiornamenti fitti: il massimo, la batteria se ne accorge"
                        TravelMode.BILANCIATO ->
                            "Preciso quando serve, gentile con la batteria"
                        TravelMode.RISPARMIO ->
                            "Aggiornamenti radi: quasi zero batteria"
                    },
                )
                FluidSegmentedControl(
                    options = TravelMode.entries.toList(),
                    selected = mode,
                    onSelect = {
                        mode = it
                        app.travelMode.mode = it
                    },
                    label = { it.label },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp),
                )
            }
        }

        item(key = "assistant-header") {
            FluidSectionHeader(title = "Assistente", detail = "Chiedi all'app, a voce o scrivendo")
        }
        fluidAiSettingsSection(
            state = assistant.state,
            actions = assistant.actions,
            strings = AssistantSettingsStrings,
            options = AssistantSettingsOptions,
            glass = false,
        )

        fluidUpdateSection(
            state = update.state,
            onCheck = update.onCheck,
            onInstall = update.onInstall,
            currentVersion = BuildConfig.VERSION_NAME,
            lastCheckedAtMillis = update.lastCheckedAtMillis,
            channel = update.channel,
            onChannelChange = update.onChannelChange,
            channelStyle = FluidUpdateChannelStyle.BetaToggle,
        )

        item(key = "data-header") { FluidSectionHeader(title = "Dati") }
        item(key = "data") {
            FluidListGroup {
                FluidListRow(
                    title = "Stato dei dati",
                    subtitle = "Quanto sono freschi gli orari, e come sta andando",
                    onClick = onOpenDataStatus,
                )
            }
        }

        // Le licenze: quelle dell'engine (fra cui LibrePods, GPL-3.0, che fa di Fluid Transit
        // un'opera GPL-3.0 con i sorgenti a disposizione) e le altre cose che l'APK porta.
        fluidLicensesSection(
            footnote = "Fluid Transit è software libero (GPL-3.0): chi riceve l'APK ha diritto ai " +
                "sorgenti, che sono su github.com/Casual76/Fluid-transit. Il Fluid Engine " +
                "${EngineBuild.VERSION} include opere di terze parti, fra cui codice GPL-3.0 " +
                "(LibrePods); le licenze complete sono nel repository dell'engine, in LICENSES/. " +
                "Orari e posizioni dei mezzi: Regione Toscana e Autolinee Toscane (CC-BY).",
            licences = TransitLicenses + FluidEngineLicenses,
        )
    }
}
