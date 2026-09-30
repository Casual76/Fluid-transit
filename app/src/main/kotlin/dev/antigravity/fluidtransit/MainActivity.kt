package dev.antigravity.fluidtransit

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
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
            // Niente si compone finche' le impostazioni non sono lette.
            //
            // Prima si partiva da `EngineSettings()`, cioe' tema di sistema e
            // ametista, e il valore vero arrivava qualche fotogramma dopo:
            // con Tema=Scuro su un telefono chiaro ogni avvio a freddo apriva
            // la benvenuta o la mappa in chiaro, con le icone della barra di
            // stato chiare-su-chiaro, e poi passava allo scuro; con "Colori dal
            // telefono" il primo colpo era ametista e poi saltava ai colori
            // dello sfondo. La mappa, poi, riceveva `dark = false` e chiedeva
            // lo stile chiaro per correggerlo un attimo dopo. E' lo stesso
            // schema della configurazione del widget: si parte da null e non
            // si disegna niente, e la finestra intanto ha il colore del
            // sistema (vedi `Theme.FluidTransit`).
            //
            // Una rotazione ricrea l'activity nello stesso processo: li' le
            // impostazioni le conosciamo gia', e ripartire da null lasciava un
            // fotogramma vuoto a ogni giro.
            val settings by app.settingsStore.settings
                .collectAsStateWithLifecycle(initialValue = ultimeImpostazioni)
            val s = settings ?: return@setContent
            SideEffect { ultimeImpostazioni = s }
            FluidTheme(settings = s, brand = TransitBrand) {
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

    private companion object {
        /**
         * Le ultime impostazioni lette, per il processo.
         *
         * Serve solo a non ripartire da zero quando l'activity si ricrea
         * (rotazione, cambio di lingua): a un avvio a freddo e' null, ed e' li'
         * che si aspetta il DataStore.
         */
        var ultimeImpostazioni: EngineSettings? = null
    }
}
