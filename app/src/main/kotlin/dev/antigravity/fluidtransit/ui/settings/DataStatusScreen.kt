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
            val rtStatus by app.realtime.status.collectAsStateWithLifecycle()
            val resolvedPct by app.realtime.resolvedPercent.collectAsStateWithLifecycle()
            FluidListGroup {
                FluidListRow(
                    title = "Collegamento",
                    subtitle = when (rtStatus.source) {
                        dev.antigravity.fluidtransit.data.rt.RealtimeClient.Source.PROXY ->
                            "Dal nostro proxy: la strada normale"
                        dev.antigravity.fluidtransit.data.rt.RealtimeClient.Source.DIRECT ->
                            "Direttamente dalla Regione: il proxy non rispondeva"
                        dev.antigravity.fluidtransit.data.rt.RealtimeClient.Source.SCHEDULE_ONLY ->
                            "Niente dati live: valgono gli orari programmati"
                    } + (rtStatus.lastError?.let { " · ultimo errore: $it" } ?: ""),
                    meta = when (rtStatus.source) {
                        dev.antigravity.fluidtransit.data.rt.RealtimeClient.Source.PROXY -> "proxy"
                        dev.antigravity.fluidtransit.data.rt.RealtimeClient.Source.DIRECT -> "diretto"
                        dev.antigravity.fluidtransit.data.rt.RealtimeClient.Source.SCHEDULE_ONLY -> "orari"
                    },
                )
                FluidListRow(
                    title = "Eta' del dato",
                    subtitle = "Quanto e' vecchia l'ultima posizione, rispetto all'origine. " +
                        "L'origine si rigenera ogni ~2 minuti: sotto i 300 s e' normale",
                    meta = rtStatus.feedAgeSeconds?.let { "${it}s" } ?: "—",
                )
                FluidListRow(
                    title = "Veicoli e ritardi",
                    subtitle = "Quanti bus vivi e quante corse con un ritardo dichiarato " +
                        "nell'ultimo aggiornamento. Si scaricano solo con la mappa aperta",
                    meta = "${rtStatus.vehicleCount} · ${rtStatus.delayCount}",
                )
                FluidListRow(
                    title = "Corse riconosciute",
                    subtitle = "Quanti bus del feed live combaciano con gli orari del bundle. " +
                        "Le due generazioni di dati non sono mai sincronizzate del tutto",
                    meta = resolvedPct?.let { "$it%" } ?: "—",
                )
            }
        }

        item { FluidSectionTitle(eyebrow = "Luoghi", title = "Il geocoding offline") }
        item {
            val placesState by app.placesManager.state.collectAsStateWithLifecycle()
            FluidListGroup {
                when (val p = placesState) {
                    is dev.antigravity.fluidtransit.data.places.PlacesManager.State.Ready -> FluidListRow(
                        title = "Luoghi caricati",
                        subtitle = "%,d fra POI e localita' · %,d vie con civici · %,d numeri"
                            .format(p.reader.fastCount, p.reader.streetCount, p.reader.civiciCount)
                            .replace(',', '.'),
                        meta = "ok",
                    )

                    is dev.antigravity.fluidtransit.data.places.PlacesManager.State.Downloading -> FluidListRow(
                        title = "Luoghi in arrivo",
                        subtitle = "L'indice dei posti della Toscana si sta scaricando",
                    )

                    else -> FluidListRow(
                        title = "Luoghi non ancora scaricati",
                        subtitle = "Arrivano da soli col Wi-Fi: fino ad allora la ricerca " +
                            "trova fermate e linee",
                        meta = "—",
                    )
                }
            }
        }

        item { FluidSectionTitle(eyebrow = "Rete", title = "La mappa") }
        item {
            FluidListGroup {
                val asked = dev.antigravity.fluidtransit.ui.map.MapNetworkStats
                    .pmtilesHeadRequests.get()
                val fetched = dev.antigravity.fluidtransit.ui.map.MapNetworkStats
                    .pmtilesHeadDownloads.get()
                FluidListRow(
                    title = "Riletture PMTiles evitate",
                    subtitle = "MapLibre rilegge la testa dell'archivio delle tratte a ogni " +
                        "tile; da questa sessione le serviamo noi dalla memoria",
                    meta = if (asked > 0) "${asked - fetched} su $asked" else "—",
                )
            }
        }
    }
}
