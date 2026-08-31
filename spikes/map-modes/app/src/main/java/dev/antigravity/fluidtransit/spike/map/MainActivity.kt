package dev.antigravity.fluidtransit.spike.map

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.module.http.HttpRequestUtil

/**
 * ⚠️ Banco di prova delle modalità di mappa. **Non è l'app**, e nemmeno un suo
 * primo pezzo: esisteva per verificare che OpenFreeMap e le ortofoto della
 * Regione funzionassero davvero su un telefono. Verificato.
 *
 * Nessuna scelta di esperienza utente qui dentro è stata decisa — dalla
 * modalità di partenza al tema, dai controlli alla diagnostica a schermo. Vanno
 * tutte chieste prima di scrivere l'app vera, in Fase 2 e 3.
 */
class MainActivity : ComponentActivity() {

    private val stats by lazy { NetworkStats(cacheDir) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // L'ordine di queste due righe non è indifferente, ed è costato tempo
        // nello spike 2: `getInstance` vuole tre argomenti (con chiave nulla,
        // perché nessuna delle nostre sorgenti ne usa una), e `HttpRequestUtil`
        // passa dal module provider, che pretende l'istanza già creata.
        // Invertendole si ottiene la stessa eccezione di quando `getInstance`
        // manca del tutto, che indica il posto sbagliato.
        MapLibre.getInstance(this, null, WellKnownTileServer.MapLibre)
        HttpRequestUtil.setOkHttpClient(stats.client())

        enableEdgeToEdge()
        setContent {
            // Tema minimo, con l'accento ametista del progetto. Il vero
            // `FluidTheme` dell'engine arriva in Fase 2.
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF9B6DD6),
                    secondary = Color(0xFF7E63B8),
                    surface = Color(0xFF141119),
                    background = Color(0xFF0C0A12),
                )
            ) {
                MapModesScreen(stats)
            }
        }
    }
}
