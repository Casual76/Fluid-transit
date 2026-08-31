package dev.antigravity.fluidtransit.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidSectionTitle
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState
import java.time.Instant
import kotlin.system.measureNanoTime

/**
 * Lo stato dei dati, in italiano comprensibile.
 *
 * E' la schermata diagnostica del piano: il trip_id + giorno di servizio e'
 * l'unica chiave condivisa fra bundle, RAPTOR e realtime, e quando si rompe
 * i sintomi appaiono lontano dalla causa. I cinque campi si accendono mano a
 * mano che le fasi arrivano; quelli non ancora attivi lo dicono, invece di
 * mostrare un trattino muto.
 */
@Composable
fun DataStatusScreen(app: FluidTransitApp, onBack: () -> Unit) {
    val state by app.bundleManager.state.collectAsStateWithLifecycle()
    val ready = state as? BundleState.Ready

    FluidScreen(title = "Stato dei dati", onBack = onBack) {
        item { FluidSectionTitle(eyebrow = "Orari", title = "Il bundle") }
        item {
            FluidListGroup {
                when (val s = state) {
                    is BundleState.Ready -> {
                        val r = s.reader
                        FluidListRow(
                            title = "Orari caricati",
                            subtitle = "Validi dal ${r.feedStart} al ${r.feedEnd}",
                            meta = "ok",
                        )
                        FluidListRow(
                            title = "Versione dei dati",
                            subtitle = "L'impronta del bundle: cambia a ogni aggiornamento notturno",
                            meta = java.lang.Long.toHexString(s.buildId).takeLast(8),
                        )
                        FluidListRow(
                            title = "Contenuto",
                            subtitle = "%,d fermate · %,d linee · %,d corse"
                                .format(r.stopCount, r.routeCount, r.tripCount).replace(',', '.'),
                        )
                        val queryMicros = remember(s.buildId) {
                            var result = 0L
                            val nanos = measureNanoTime {
                                result = r.nextDepartures(stop = 0, now = Instant.now(), limit = 5).size.toLong()
                            }
                            nanos / 1000 + (result * 0) // il risultato tiene viva la query
                        }
                        FluidListRow(
                            title = "Velocita' di ricerca",
                            subtitle = "Quanto ci ha messo l'ultima ricerca di passaggi",
                            meta = "$queryMicros µs",
                        )
                    }

                    is BundleState.Failed -> FluidListRow(
                        title = "Orari non scaricati",
                        subtitle = s.message,
                        meta = "errore",
                    )

                    else -> FluidListRow(
                        title = "Download in corso",
                        subtitle = "Gli orari stanno arrivando",
                    )
                }
            }
        }

        item { FluidSectionTitle(eyebrow = "Tempo reale", title = "I bus vivi") }
        item {
            FluidListGroup {
                FluidListRow(
                    title = "Posizioni dei bus",
                    subtitle = "Arrivano con la Fase 4: eta' del dato, corse riconosciute e stato del collegamento compariranno qui",
                    meta = "presto",
                )
            }
        }
    }
}
