package dev.antigravity.fluidtransit.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidHairline
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidtransit.ui.common.DepartureRowUi
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Ftb
import dev.antigravity.fluidtransit.routing.Times
import java.time.Instant
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Sfuma il contenuto ai bordi verticali dello scorrimento: senza, le righe
 * si troncano di netto contro il pannello — il "taglio brutto" segnalato
 * alla prima prova.
 */
fun Modifier.fadeVerticalEdges(edge: androidx.compose.ui.unit.Dp = 16.dp): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = edge.toPx()
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to Color.Transparent, 1f to Color.Black, endY = h,
            ),
            size = androidx.compose.ui.geometry.Size(size.width, h),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to Color.Black, 1f to Color.Transparent,
                startY = size.height - h, endY = size.height,
            ),
            topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h),
            size = androidx.compose.ui.geometry.Size(size.width, h),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }

/**
 * Il contenuto della scheda fermata: le prossime partenze, ogni linea con la
 * pillola del SUO colore — lo stesso della tratta sulla mappa. Il vetro, il
 * grabber e i gesti vivono in [BottomGlassPanel]: questo e' solo il dentro,
 * cosi' il passaggio a scheda linea e' un morphing della stessa superficie.
 *
 * Il tabellone non si calcola piu' qui. Arriva da [DepartureBoards], che e'
 * l'unica fonte per tutta l'app: e' quello che garantisce che questa fermata
 * mostri gli stessi minuti che mostra nella scheda Oggi, nei Preferiti e nel
 * widget, nello stesso istante. Prima erano quattro calcoli con quattro
 * battiti diversi, e non potevano coincidere.
 */
@Composable
fun StopPanelContent(
    app: FluidTransitApp,
    reader: BundleReader,
    stopIdHashHex: String,
    fallbackName: String,
    onDismiss: () -> Unit,
    onRouteTap: (routeIndex: Int) -> Unit,
    backdrop: dev.antigravity.fluidengine.ui.fluid.GlassBackdropState,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onFlyToBus: (tripIndex: Int) -> Unit = {},
    /** "Parti da qui": questa fermata come ORIGINE del pianificatore. */
    onStartHere: (() -> Unit)? = null,
    /** Il tocco su una riga: "perche' questo numero", aperto sulla riga stessa. */
    onWhyTap: (
        dev.antigravity.fluidtransit.routing.NextDeparture,
        androidx.compose.ui.geometry.Rect?,
    ) -> Unit = { _, _ -> },
) {
    val stopIndex = androidx.compose.runtime.remember(stopIdHashHex, reader) {
        stopIdHashHex.toULongOrNull(16)?.toLong()?.let { reader.findStopByIdHash(it) } ?: -1
    }
    val board by if (stopIndex >= 0) {
        app.departureBoards.board(stopIndex, limit = 10).collectAsStateWithLifecycle()
    } else {
        androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(
                dev.antigravity.fluidtransit.routing.DepartureBoard.empty(-1, fallbackName, 0L),
            )
        }
    }
    val liveTrips by app.tripsWithVehicle.collectAsStateWithLifecycle()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = board.stopName.ifEmpty { fallbackName },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // La stella dei preferiti: piena quando la fermata e' tua.
        Icon(
            imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            contentDescription = if (isFavorite) "Togli dai preferiti" else "Salva nei preferiti",
            tint = if (isFavorite) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .size(40.dp)
                .clickable(
                    interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onToggleFavorite,
                )
                .padding(8.dp),
        )
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "Chiudi",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(40.dp)
                .clickable(
                    interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onDismiss,
                )
                .padding(8.dp),
        )
    }

    // "Parti da qui": la fermata aperta diventa l'origine del pianificatore.
    // E' una delle quattro strade decise per scegliere una partenza diversa
    // da dove sei.
    if (onStartHere != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 4.dp),
        ) {
            GlassActionButton(
                text = "Parti da qui",
                icon = Icons.Rounded.MyLocation,
                backdrop = backdrop,
                onClick = onStartHere,
                modifier = Modifier.weight(1f),
            )
        }
    }

    when {
        // computedAtEpoch a zero vuol dire che il primo calcolo non c'e'
        // ancora stato: e' diverso da "non passa niente", e dirlo sbagliato
        // e' il difetto che i Preferiti avevano da sempre.
        board.computedAtEpoch == 0L || stopIndex < 0 -> {
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.padding(horizontal = 20.dp)) { FluidSpinner() }
            Spacer(Modifier.height(24.dp))
        }

        board.rows.isEmpty() -> {
            // Un tabellone vuoto perche' gli orari sono scaduti non e' un
            // tabellone vuoto perche' e' notte, e dirlo con le stesse parole
            // fa sembrare rotto il servizio invece dell'app.
            FluidEmptyState(
                title = if (board.outsideValidity) {
                    "Gli orari sono scaduti"
                } else {
                    "Nessun passaggio nelle prossime due ore"
                },
                detail = if (board.outsideValidity) {
                    "Quelli che abbiamo non coprono piu' oggi, e non ne " +
                        "arrivano di nuovi. Non vuol dire che i bus non " +
                        "passino: vuol dire che non sappiamo quando."
                } else {
                    "Da questa fermata non parte niente a breve."
                },
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(16.dp))
        }

        else -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = panelListMax(340.dp))
                    .fadeVerticalEdges()
                    .padding(horizontal = 20.dp),
            ) {
                items(board.rows.size) { i ->
                    val row = board.rows[i]
                    if (i > 0) FluidHairline()
                    // Il tocco sulle PAROLE della provenienza apre "perche'
                    // questo numero", e nasce da quelle parole.
                    //
                    // Prima era la riga intera, senza niente che lo
                    // annunciasse: chi non provava non lo trovava. Adesso il
                    // bersaglio e' proprio la frase che si sta mettendo in
                    // dubbio, ed e' sottolineata.
                    DepartureRowUi(
                        row = row,
                        nowEpoch = board.computedAtEpoch,
                        onSupportTap = { rect -> onWhyTap(row, rect) },
                        onLineTap = { onRouteTap(row.routeIndex) },
                        trailing = {
                            if (row.tripIndex in liveTrips && !row.canceled) {
                                // Il tasto del prossimo bus live — in vetro,
                                // vetro su vetro, come da regola: vola sul bus
                                // di QUESTA corsa e apre la sua scheda.
                                dev.antigravity.fluidengine.ui.fluid.FluidGlassIconButton(
                                    onClick = { onFlyToBus(row.tripIndex) },
                                    backdrop = backdrop,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DirectionsBus,
                                        contentDescription = "Vola sul bus della ${row.line}",
                                        tint = liveGreen(),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        },
                    )
                }
            }
            Text(
                text = "Prossimi passaggi · " +
                    dev.antigravity.fluidtransit.routing.DepartureText.boardSource(board),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}
