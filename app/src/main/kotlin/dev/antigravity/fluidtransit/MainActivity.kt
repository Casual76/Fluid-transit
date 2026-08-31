package dev.antigravity.fluidtransit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.antigravity.fluidtransit.ui.AppRoot
import dev.antigravity.fluidtransit.ui.theme.TransitBrand

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as FluidTransitApp
        setContent {
            val settings by app.settingsStore.settings
                .collectAsStateWithLifecycle(initialValue = EngineSettings())
            FluidTheme(settings = settings, brand = TransitBrand) {
                AppRoot(app)
            }
        }
    }
}
