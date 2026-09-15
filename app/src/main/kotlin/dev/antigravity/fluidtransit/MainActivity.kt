package dev.antigravity.fluidtransit

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.antigravity.fluidtransit.ui.AppRoot
import dev.antigravity.fluidtransit.ui.nav.Deeplink
import dev.antigravity.fluidtransit.ui.theme.TransitBrand

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as FluidTransitApp

        // Solo alla prima creazione: dopo una rotazione l'intent e' sempre
        // quello di prima, e rileggerlo riporterebbe alla fermata del widget
        // ogni volta che si gira il telefono.
        if (savedInstanceState == null) receive(intent)

        setContent {
            val settings by app.settingsStore.settings
                .collectAsStateWithLifecycle(initialValue = EngineSettings())
            FluidTheme(settings = settings, brand = TransitBrand) {
                AppRoot(app)
            }
        }
    }

    /**
     * L'app e' gia' aperta e arriva un altro indirizzo.
     *
     * Succede con `launchMode="singleTask"`: toccare il widget mentre l'app
     * e' in secondo piano la riporta davanti passando di qui, invece di
     * creare una seconda copia della mappa.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent?) {
        val link = Deeplink.parse(intent?.dataString) ?: return
        (application as FluidTransitApp).pendingLink.value = link
    }
}
