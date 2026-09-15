package dev.antigravity.fluidtransit.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidHairline
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassEdge
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidtransit.routing.DepartureBoard
import dev.antigravity.fluidtransit.routing.DepartureText
import dev.antigravity.fluidtransit.ui.common.DepartureRowUi
import dev.antigravity.fluidtransit.ui.common.toneColor
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.setValue

/**
 * Cosa passa qui intorno, senza cercare niente.
 *
 * Era la domanda piu' frequente e la piu' scomoda: per sapere quando passa il
 * prossimo bus servivano tre tocchi e una ricerca — aprire la barra, scrivere
 * il nome di una fermata che devi gia' sapere, sceglierla fra due righe
 * identiche. Le app che si usano per questo aprono su un elenco di partenze.
 *
 * Qui la mappa resta la casa, ma la risposta sta sopra la tab bar e non serve
 * toccare niente per leggerla: la prossima partenza si vede, e toccandola si
 * aprono tutte.
 */
@Composable
fun NearbyCapsule(
    board: DepartureBoard,
    backdrop: GlassBackdropState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val row = board.rows.firstOrNull()
    Row(
        modifier = modifier
            .glassSurface(
                state = backdrop,
                tint = GlassDefaults.floatingTint(),
                shape = ContinuousCornerShape(FluidRadius.Card),
                edge = GlassEdge.None,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "Mostra cosa passa qui intorno",
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.NearMe,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        if (row == null) {
            Text(
                text = "Qui intorno non passa niente a breve",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Row
        }
        val phrase = DepartureText.phrase(row, board.computedAtEpoch)
        RoutePill(text = row.line, colorRgb = row.colorRgb)
        Spacer(Modifier.width(10.dp))
        Text(
            text = row.stopName,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(10.dp))
        if (phrase.pulse) {
            LiveDot(toneColor(phrase.tone))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = phrase.headline,
            style = MaterialTheme.typography.labelLarge,
            color = toneColor(phrase.tone),
        )
    }
}

/**
 * Le prossime partenze delle fermate qui intorno, mescolate per orario.
 *
 * Non raggruppate per fermata: la domanda e' "cosa passa", non "cosa passa da
 * ognuna di queste". Ogni riga dice da quale fermata parte, ed e' quello che
 * serve per decidere se conviene camminare.
 */
@Composable
fun NearbyPanelContent(
    board: DepartureBoard,
    onDismiss: () -> Unit,
    onStopTap: (stopIndex: Int) -> Unit,
    onRouteTap: (routeIndex: Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Qui intorno",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "Chiudi",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(40.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onDismiss,
                )
                .padding(8.dp),
        )
    }

    when {
        board.computedAtEpoch == 0L -> {
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.padding(horizontal = 20.dp)) { FluidSpinner() }
            Spacer(Modifier.height(24.dp))
        }

        board.rows.isEmpty() -> {
            FluidEmptyState(
                title = "Niente a breve, qui intorno",
                detail = "Dalle fermate a piedi da qui non parte niente nelle prossime due ore.",
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(16.dp))
        }

        else -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = panelListMax(380.dp))
                    .fadeVerticalEdges()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.Top,
            ) {
                items(board.rows.size) { i ->
                    val row = board.rows[i]
                    if (i > 0) FluidHairline()
                    DepartureRowUi(
                        row = row,
                        nowEpoch = board.computedAtEpoch,
                        showStopName = true,
                        onLineTap = { onRouteTap(row.routeIndex) },
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClickLabel = "Apri la fermata ${row.stopName}",
                            onClick = { onStopTap(row.stopIndex) },
                        ),
                    )
                }
            }
            Text(
                text = "Prossimi passaggi · " + DepartureText.boardSource(board),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * Il tabellone di cosa passa qui intorno.
 *
 * Stava dentro MapScreen, che e' il file piu' toccato del repo: qui sta
 * accanto al pannello che lo mostra, dove chi cambia una delle due cose vede
 * anche l'altra.
 *
 * Il punto di riferimento si muove con la mappa, ma l'elenco delle fermate
 * NO: si ricalcola solo quando ci si e' spostati di duecento metri. Senza quel
 * gradino ogni pixel di trascinamento avrebbe creato un tabellone nuovo, con
 * la sua cache e il suo giro di calcolo.
 */
@Composable
fun rememberNearbyBoard(
    app: dev.antigravity.fluidtransit.FluidTransitApp,
    reader: dev.antigravity.fluidtransit.routing.BundleReader?,
    buildId: Long?,
    stopGroups: dev.antigravity.fluidtransit.routing.StopGroups?,
    /** Serve solo come chiave: cambiando modo di inseguimento si ricomincia. */
    follow: Any?,
    here: () -> Pair<Double, Double>?,
): DepartureBoard {
    var anchor by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Pair<Double, Double>?>(null)
    }
    // Senza lo zoom fra le chiavi: il giro rilegge la posizione da solo a ogni
    // iterazione, e tenerlo li' faceva ripartire il ciclo — e quindi
    // ricalcolare l'ancora — a ogni pizzicata.
    androidx.compose.runtime.LaunchedEffect(follow, buildId) {
        while (true) {
            val adesso = here()
            val prima = anchor
            if (adesso != null && (
                    prima == null ||
                        dev.antigravity.fluidtransit.routing.BundleReader.haversine(
                            prima.first, prima.second, adesso.first, adesso.second,
                        ) > NEARBY_ANCHOR_MOVE_M
                    )
            ) {
                anchor = adesso
            }
            kotlinx.coroutines.delay(ANCHOR_POLL_MS)
        }
    }

    val stops = androidx.compose.runtime.remember(anchor, buildId, stopGroups) {
        val a = anchor
        if (reader == null || a == null) {
            emptyList()
        } else {
            // Le banchine del gruppo entrano tutte: una fermata e' una
            // fermata, e le due direzioni si distinguono dalla destinazione.
            reader.stopsNear(a.first, a.second, NEARBY_RADIUS_M)
                .take(NEARBY_STOPS)
                .flatMap { s -> stopGroups?.siblings(s)?.toList() ?: listOf(s) }
                .distinct()
                .sorted()
        }
    }
    val board by androidx.compose.runtime.remember(stops) {
        app.departureBoards.merged(stops, limit = NEARBY_LIMIT)
    }.collectAsStateWithLifecycle()
    return board
}

/** Quanto lontano si guarda per "qui intorno": dieci minuti a piedi scarsi. */
private const val NEARBY_RADIUS_M = 700.0

/** Quante fermate al massimo, prima di espanderle nelle loro banchine. */
private const val NEARBY_STOPS = 8

/** Quante righe: abbastanza per vedere anche la seconda occasione di ogni linea. */
private const val NEARBY_LIMIT = 12

/**
 * Di quanto ci si deve spostare perche' "qui intorno" cambi.
 *
 * Senza questo gradino, ogni pixel di trascinamento della mappa avrebbe
 * prodotto un elenco di fermate diverso, e quindi un tabellone nuovo da
 * calcolare e da tenere in cache.
 */
private const val NEARBY_ANCHOR_MOVE_M = 200.0

/** Ogni quanto si guarda se ci si e' spostati. */
private const val ANCHOR_POLL_MS = 2_000L
