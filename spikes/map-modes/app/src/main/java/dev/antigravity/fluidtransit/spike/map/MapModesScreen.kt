package dev.antigravity.fluidtransit.spike.map

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maplibre.android.geometry.LatLng

/**
 * Pannello di prova. **Non è la UI dell'app**: è il minimo che serve per
 * guardare le modalità una accanto all'altra e decidere.
 *
 * In Fase 3 sopra la mappa ci andranno i pannelli in vetro dell'engine —
 * ricerca, scheda fermata, foglio itinerari — e di questo file non resta
 * niente. Resta invece tutto ciò che sta sotto: [FluidMapView], [MapCatalog]
 * e [NetworkStats].
 */
@Composable
fun MapModesScreen(stats: NetworkStats) {
    var options by remember { mutableStateOf(MapOptions()) }
    var focus by remember { mutableStateOf<MapFocus?>(null) }
    var focusCount by remember { mutableStateOf(0) }

    fun goTo(target: LatLng, zoom: Double) {
        focusCount++
        focus = MapFocus(target, zoom, focusCount)
    }

    Box(Modifier.fillMaxSize()) {
        FluidMapView(
            options = options,
            focus = focus,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DiagnosticsStrip(stats)

            Card(
                colors = CardDefaults.cardColors(containerColor = PANEL),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MapCatalog.MapMode.entries.forEach { mode ->
                            FilterChip(
                                selected = options.mode == mode,
                                onClick = { options = options.copy(mode = mode) },
                                label = { Text(mode.label) },
                            )
                        }
                    }

                    Text(
                        options.mode.hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB9B2C8),
                    )

                    // Nel satellite puro non c'è vettoriale, quindi non ci sono
                    // edifici da estrudere. Dirlo è meglio che lasciare un
                    // interruttore che sembra rotto.
                    val threeDPossible = options.mode != MapCatalog.MapMode.SATELLITE
                    ToggleRow(
                        label = "Edifici 3D",
                        note = when {
                            !threeDPossible -> "non disponibile senza vettoriale"
                            else -> "da zoom 14 in su"
                        },
                        checked = options.buildings3d && threeDPossible,
                        enabled = threeDPossible,
                        onCheckedChange = { options = options.copy(buildings3d = it) },
                    )

                    ToggleRow(
                        label = "Rotazione con due dita",
                        note = null,
                        checked = options.rotationEnabled,
                        enabled = true,
                        onCheckedChange = { options = options.copy(rotationEnabled = it) },
                    )

                    Column {
                        Text(
                            "Inclinazione  ${options.pitch.toInt()}°",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Slider(
                            value = options.pitch.toFloat(),
                            onValueChange = { options = options.copy(pitch = it.toDouble()) },
                            // Oltre i 60° MapLibre mostra l'orizzonte e senza
                            // un cielo disegnato si vede il vuoto dello sfondo.
                            valueRange = 0f..60f,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton({ goTo(DUOMO, 16.5) }) { Text("Duomo") }
                        TextButton({ goTo(FIRENZE, 13.0) }) { Text("Firenze") }
                        TextButton({ goTo(SIENA, 14.5) }) { Text("Siena") }
                        TextButton({ goTo(TOSCANA, 8.0) }) { Text("Toscana") }
                    }

                    // Obbligo di licenza, non cortesia: entrambe le sorgenti
                    // vanno attribuite ovunque compaiano.
                    Text(
                        "${MapCatalog.ATTR_OSM} · ${MapCatalog.ATTR_RT}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF8E86A0),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    note: String?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8E86A0),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * La riga che rende visibile il costo di rete.
 *
 * Serve a rispondere alla domanda che il piano si porta dietro dalla Fase 1:
 * quanto costa davvero il satellite. Cambiare modalità, azzerare, e trascinare
 * la mappa per la stessa distanza dà il confronto in numeri invece che a
 * sensazione.
 */
@Composable
private fun DiagnosticsStrip(stats: NetworkStats) {
    val snapshot = stats.snapshot
    Card(
        colors = CardDefaults.cardColors(containerColor = PANEL),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${snapshot.requests} richieste · ${snapshot.fromCache} da cache · " +
                        "${snapshot.networkBytes / 1024} KB di rete · ${snapshot.slowestMs} ms max",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                val hosts = snapshot.perHost.entries.joinToString("  ") { "${it.key} ×${it.value}" }
                Text(
                    hosts.ifEmpty { "in attesa della prima tile" },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color(0xFF8E86A0),
                )
                snapshot.lastError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = Color(0xFFE58A8A),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton({ stats.reset() }) { Text("azzera") }
        }
    }
}

private val PANEL = Color(0xF7141119)

private val DUOMO = LatLng(43.7731, 11.2560)
private val SIENA = LatLng(43.3188, 11.3308)
private val TOSCANA = LatLng(43.4, 11.1)
