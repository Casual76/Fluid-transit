package dev.antigravity.fluidtransit.ui.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.foundation.AccentMode
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidSectionTitle
import dev.antigravity.fluidtransit.BuildConfig
import dev.antigravity.fluidtransit.FluidTransitApp
import kotlinx.coroutines.launch

/**
 * Impostazioni: tema (sistema/chiaro/scuro), dynamic color opzionale, e in
 * fondo — visibile, non dietro un gesto: deciso cosi' — lo stato dei dati.
 */
@Composable
fun SettingsTab(app: FluidTransitApp) {
    var showDataStatus by rememberSaveable { mutableStateOf(false) }

    if (showDataStatus) {
        BackHandler { showDataStatus = false }
        DataStatusScreen(app, onBack = { showDataStatus = false })
        return
    }

    val scope = rememberCoroutineScope()
    val settings by app.settingsStore.settings
        .collectAsStateWithLifecycle(initialValue = EngineSettings())

    FluidScreen(title = "Impostazioni") {
        item { FluidSectionTitle(eyebrow = "Aspetto", title = "Tema") }
        item {
            FluidListGroup {
                // Il segmented control e' un SubcomposeLayout: non puo' stare
                // nel trailing di una FluidListRow, dove ListItem gli chiede
                // le misure intrinseche (crash trovato sul device). Vive come
                // riga propria del gruppo.
                FluidListRow(
                    title = "Tema",
                    subtitle = "Chiaro e scuro seguono il telefono, se li lasci decidere",
                )
                FluidSegmentedControl(
                    options = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
                    selected = if (settings.themeMode == ThemeMode.AMOLED) ThemeMode.DARK else settings.themeMode,
                    onSelect = { mode -> scope.launch { app.settingsStore.setThemeMode(mode) } },
                    label = {
                        when (it) {
                            ThemeMode.SYSTEM -> "Sistema"
                            ThemeMode.LIGHT -> "Chiaro"
                            else -> "Scuro"
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp),
                )
                if (Build.VERSION.SDK_INT >= 31) {
                    FluidListRow(
                        title = "Colori dal telefono",
                        subtitle = "Usa i colori del tuo sfondo al posto dell'ametista",
                        badge = {
                            FluidSwitch(
                                checked = settings.accentMode == AccentMode.DYNAMIC,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        app.settingsStore.setAccentMode(
                                            if (enabled) AccentMode.DYNAMIC else AccentMode.BRAND,
                                        )
                                        app.settingsStore.setDynamicColorEnabled(enabled)
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }

        item { FluidSectionTitle(eyebrow = "Viaggio", title = "A bordo") }
        item {
            var mode by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(app.travelMode.mode)
            }
            FluidListGroup {
                FluidListRow(
                    title = "Modo viaggio",
                    subtitle = when (mode) {
                        dev.antigravity.fluidtransit.data.nav.TravelMode.PRECISO ->
                            "GPS continuo e aggiornamenti fitti: il massimo, la batteria se ne accorge"
                        dev.antigravity.fluidtransit.data.nav.TravelMode.BILANCIATO ->
                            "Preciso quando serve, gentile con la batteria"
                        dev.antigravity.fluidtransit.data.nav.TravelMode.RISPARMIO ->
                            "Aggiornamenti radi: quasi zero batteria"
                    },
                    meta = mode.label,
                    onClick = {
                        val all = dev.antigravity.fluidtransit.data.nav.TravelMode.entries
                        mode = all[(all.indexOf(mode) + 1) % all.size]
                        app.travelMode.mode = mode
                    },
                )
            }
        }

        item { FluidSectionTitle(eyebrow = "Assistente", title = "Chiedi all'app") }
        item { AssistantSettingsGroup(app) }

        item { FluidSectionTitle(eyebrow = "Informazioni", title = "L'app") }
        item {
            FluidListGroup {
                FluidListRow(
                    title = "Versione",
                    subtitle = BuildConfig.VERSION_NAME,
                )
                FluidListRow(
                    title = "Stato dei dati",
                    subtitle = "Quanto sono freschi gli orari, e come sta andando",
                    onClick = { showDataStatus = true },
                )
            }
        }
    }
}
