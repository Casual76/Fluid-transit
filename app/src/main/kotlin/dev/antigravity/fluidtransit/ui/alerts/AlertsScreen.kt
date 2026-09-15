package dev.antigravity.fluidtransit.ui.alerts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidSectionTitle
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState
import dev.antigravity.fluidtransit.data.rt.GtfsRtLite
import dev.antigravity.fluidtransit.routing.AlertText
import dev.antigravity.fluidtransit.ui.map.RoutePill
import java.time.Instant
import kotlinx.coroutines.launch

/**
 * Gli avvisi di servizio, per esteso.
 *
 * Erano sei righe in fondo alla scheda Oggi: testata e primi 220 caratteri,
 * filtrate sulle linee preferite, caricate una volta sola all'apertura della
 * scheda. Senza il periodo — e un avviso senza periodo e' quasi inutile,
 * perche' "deviazione in via Nazionale" e' un'altra cosa se dura fino a
 * stasera o fino a marzo — e senza dire quali linee tocca, che era
 * l'informazione piu' importante dopo il testo.
 *
 * Qui ci sono tutti, con le linee scritte e il periodo detto a parole. Quelli
 * che toccano le tue linee stanno in cima, perche' e' per quelli che si apre
 * questa schermata.
 */
@Composable
fun AlertsScreen(app: FluidTransitApp, onBack: () -> Unit) {
    val bundleState by app.bundleManager.state.collectAsStateWithLifecycle()
    val reader = (bundleState as? BundleState.Ready)?.reader
    val favVersion by app.favorites.version.collectAsStateWithLifecycle()
    val mine = remember(favVersion) {
        app.favorites.routes().mapNotNull { it.idHashHex.toULongOrNull(16)?.toLong() }.toSet()
    }

    var round by remember { mutableStateOf(0) }
    val alerts by produceState(initialValue = null as List<GtfsRtLite.RtAlert>?, round) {
        value = runCatching { app.realtime.fetchAlerts() }.getOrDefault(emptyList())
    }

    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    val now = Instant.now().epochSecond
    val attivi = (alerts ?: emptyList())
        .filter { AlertText.active(it.startEpoch, it.endEpoch, now) }
        // Le tue linee per prime; poi quelli di rete, che riguardano tutti;
        // poi il resto.
        .sortedBy { a ->
            when {
                a.routeHashes.any { it in mine } -> 0
                a.routeHashes.isEmpty() -> 1
                else -> 2
            }
        }

    FluidScreen(
        title = "Avvisi",
        subtitle = "Deviazioni, scioperi e lavori dichiarati dal gestore",
        onBack = onBack,
        isRefreshing = refreshing,
        onRefresh = {
            if (!refreshing) {
                refreshing = true
                scope.launch {
                    round++
                    kotlinx.coroutines.delay(600)
                    refreshing = false
                }
            }
        },
    ) {
        if (alerts == null) {
            item { dev.antigravity.fluidengine.ui.fluid.FluidLoadingBlock() }
            return@FluidScreen
        }
        if (attivi.isEmpty()) {
            item {
                FluidEmptyState(
                    title = "Nessun avviso in corso",
                    detail = "Quando il gestore dichiara una deviazione, uno sciopero " +
                        "o dei lavori, compaiono qui.",
                )
            }
            return@FluidScreen
        }

        val tuoi = attivi.filter { a -> a.routeHashes.any { it in mine } }
        val altri = attivi - tuoi.toSet()

        if (tuoi.isNotEmpty()) {
            item { FluidSectionTitle(eyebrow = "Avvisi", title = "Sulle tue linee") }
            item { AlertGroup(tuoi, reader, now) }
        }
        if (altri.isNotEmpty()) {
            item {
                FluidSectionTitle(
                    eyebrow = "Avvisi",
                    title = if (tuoi.isEmpty()) "In corso" else "Sul resto della rete",
                )
            }
            item { AlertGroup(altri, reader, now) }
        }
    }
}

@Composable
private fun AlertGroup(
    alerts: List<GtfsRtLite.RtAlert>,
    reader: dev.antigravity.fluidtransit.routing.BundleReader?,
    nowEpoch: Long,
) {
    FluidListGroup {
        for ((i, a) in alerts.withIndex()) {
            if (i > 0) dev.antigravity.fluidengine.ui.theme.FluidListDivider()
            AlertCard(a, reader, nowEpoch)
        }
    }
}

@Composable
private fun AlertCard(
    alert: GtfsRtLite.RtAlert,
    reader: dev.antigravity.fluidtransit.routing.BundleReader?,
    nowEpoch: Long,
) {
    // Le linee toccate, coi loro nomi e i loro colori: l'avviso arriva con
    // gli hash, che da soli non dicono niente a nessuno.
    val linee = remember(alert, reader) {
        val r = reader ?: return@remember emptyList()
        alert.routeHashes.mapNotNull { h ->
            val idx = r.findRouteByIdHash(h)
            if (idx < 0) {
                null
            } else {
                r.routeShortName(idx).ifEmpty { r.routeLongName(idx) } to r.routeDisplayColor(idx)
            }
        }.distinct()
    }
    val periodo = AlertText.period(alert.startEpoch, alert.endEpoch, nowEpoch)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = alert.header.ifEmpty { "Avviso di servizio" },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (periodo != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = periodo,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (alert.description.isNotEmpty() && alert.description != alert.header) {
            // Ripiegata, con la porta per aprirla.
            //
            // Gli avvisi veri contengono l'elenco completo del percorso nuovo,
            // fermata per fermata, in due direzioni: uno solo riempie tre
            // schermate. Mostrarli tutti per intero rende l'elenco illeggibile
            // e nasconde gli altri; tagliarli a 220 caratteri, com'era prima,
            // toglie proprio il pezzo che serve. Quindi: sei righe, e chi
            // vuole legge il resto.
            var aperto by remember(alert) { mutableStateOf(false) }
            // "Leggi tutto" compare se il testo e' stato DAVVERO tagliato.
            //
            // Prima la porta si apriva sopra i 240 caratteri, ma il taglio e'
            // a sei righe: due misure diverse per la stessa cosa. Un avviso
            // di duecento caratteri che su uno schermo stretto occupa sette
            // righe finiva con i puntini e nessun modo di aprirlo — visto
            // sull'emulatore, "Fermata spostata per lavori" tagliato a
            // meta' di "a causa di lav...".
            var troncato by remember(alert) { mutableStateOf(false) }
            Spacer(Modifier.height(6.dp))
            Text(
                text = alert.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (aperto) Int.MAX_VALUE else 6,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                onTextLayout = { if (!aperto) troncato = it.hasVisualOverflow },
            )
            if (troncato || aperto) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (aperto) "Mostra meno" else "Leggi tutto",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { aperto = !aperto }
                        .padding(vertical = 4.dp),
                )
            }
        }
        if (linee.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for ((nome, colore) in linee.take(12)) {
                    RoutePill(text = nome, colorRgb = colore)
                }
                if (linee.size > 12) {
                    Text(
                        text = "+${linee.size - 12}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (alert.routeHashes.isEmpty()) {
            // Non "riguarda tutta la rete": non lo sappiamo.
            //
            // Nel feed vero un avviso intitolato "Nuovo percorso linea 17"
            // arriva senza nessuna linea dichiarata. La regola di GTFS-RT
            // direbbe che senza entita' informate vale per tutti, ma dirlo
            // qui sarebbe una nostra interpretazione spacciata per un fatto.
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Il gestore non dichiara quali linee tocca",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
