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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
/**
 * Una riga di vetro in fondo alla mappa, quando c'e' da dire una cosa sola.
 *
 * Sta esattamente dove sta la capsula dei prossimi passaggi e ne prende il
 * posto: sono risposte alla stessa domanda muta — "cosa c'e' qui?" — e due
 * capsule impilate che si contraddicono sono peggio di nessuna.
 */
@Composable
fun MapNoticeCapsule(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    backdrop: GlassBackdropState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    iconTint: Color? = null,
) {
    Row(
        modifier = modifier
            .glassSurface(
                state = backdrop,
                tint = GlassDefaults.floatingTint(),
                shape = ContinuousCornerShape(FluidRadius.Card),
                edge = GlassEdge.None,
            )
            .let { m ->
                if (onClick == null) {
                    m
                } else {
                    m.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClickLabel = title,
                        onClick = onClick,
                    )
                }
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun NearbyCapsule(
    board: DepartureBoard,
    backdrop: GlassBackdropState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val row = board.rows.firstOrNull()
    val phrase = row?.let { DepartureText.phrase(it, board.computedAtEpoch) }
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
            // Da dove viene il numero, per chi non vede il pallino.
            //
            // La capsula disegna la linea, la fermata e i minuti, mai la riga
            // di provenienza: "dal bus" contro "orario da tabella" e il ritardo
            // stavano solo nel pallino che pulsa e nel colore, e TalkBack
            // leggeva "20, PIAZZA DALMAZIA, 3 min" tanto per un bus seguito dal
            // feed quanto per una stima d'orario. E' uno stato, non un
            // contenuto, quindi si aggiunge al testo unito dal clic senza
            // sostituirlo: un contentDescription al suo posto avrebbe perso la
            // linea e la fermata.
            .let { m ->
                if (phrase == null) {
                    m
                } else {
                    m.semantics { stateDescription = DepartureText.spokenSupport(phrase) }
                }
            }
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
        if (row == null || phrase == null) {
            // Tre motivi diversi per non avere una riga, e solo l'ultimo e'
            // "non passa niente": senza fermate nel raggio o con gli orari
            // scaduti il servizio non c'entra, e dirlo come se ci entrasse
            // e' la lista vuota che si legge come una buona notizia.
            val guaio = DepartureText.trouble(board)
            Text(
                text = if (guaio == DepartureText.Trouble.NIENTE_A_BREVE) {
                    "Qui intorno non passa niente a breve"
                } else {
                    DepartureText.empty(guaio).title
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Row
        }
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
    /** Quanto dista una fermata da dove si guarda. Null = non si sa. */
    distanceOf: (stopIndex: Int) -> Double? = { null },
    /** Il tocco sulla provenienza: "perche' questo numero", come nella scheda fermata. */
    onWhyTap: (
        dev.antigravity.fluidtransit.routing.NextDeparture,
        androidx.compose.ui.geometry.Rect?,
    ) -> Unit = { _, _ -> },
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
                .minimumInteractiveComponentSize()
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

    fun distanzaFermata(row: dev.antigravity.fluidtransit.routing.NextDeparture): String? {
        if (row.stopName.isEmpty()) return null
        val m = distanceOf(row.stopIndex) ?: return null
        return "a " + dev.antigravity.fluidtransit.routing.Words.distance(m)
    }

    when {
        board.computedAtEpoch == 0L -> {
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.padding(horizontal = 20.dp)) { FluidSpinner() }
            Spacer(Modifier.height(24.dp))
        }

        board.rows.isEmpty() -> {
            // Gli orari scaduti e "nessuna fermata nel raggio" si dicono con
            // le parole di tutte le altre schede, che erano ricopiate qui
            // identiche; il "niente qui intorno" resta suo, perche' il
            // soggetto non e' una fermata ne' le tue fermate ma le fermate a
            // piedi da dove stai guardando.
            //
            // "Non parte niente nelle prossime due ore" si puo' dire solo se
            // le fermate ci sono: in campagna la lista vuota veniva da una
            // ricerca a vuoto, e il pannello dava del servizio fermo a una
            // zona in cui non c'e' proprio una fermata da guardare.
            val guaio = DepartureText.trouble(board)
            val parole = DepartureText.empty(guaio)
            val nienteABreve = guaio == DepartureText.Trouble.NIENTE_A_BREVE
            FluidEmptyState(
                title = if (nienteABreve) "Niente a breve, qui intorno" else parole.title,
                detail = if (nienteABreve) {
                    "Dalle fermate a piedi da qui non parte niente nelle prossime due ore."
                } else {
                    parole.detail
                },
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
                // Le righe si riconoscono per corsa e passaggio: quando la
                // prima se ne va le altre non si ridisegnano tutte da capo.
                val chiavi = dev.antigravity.fluidtransit.ui.common.uniqueKeys(
                    board.rows.map { "${it.tripIndex}-${it.stopIndex}-${it.positionInPattern}" },
                )
                items(board.rows.size, key = { chiavi[it] }) { i ->
                    val row = board.rows[i]
                    if (i > 0) FluidHairline()
                    DepartureRowUi(
                        row = row,
                        nowEpoch = board.computedAtEpoch,
                        // Il nome della fermata e quanto dista: senza la
                        // distanza, sapere da quale fermata parte aiuta solo
                        // chi conosce gia' la zona, e "qui intorno" serve
                        // soprattutto a chi non la conosce.
                        stopLabel = row.stopName,
                        stopDistance = distanzaFermata(row),
                        onLineTap = { onRouteTap(row.routeIndex) },
                        onSupportTap = { rect -> onWhyTap(row, rect) },
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

    // Null vuol dire "non so ancora dove guardare", lista vuota "ho guardato e
    // non c'e' niente": sono due risposte, e a chiedere il tabellone di una
    // lista vuota si otteneva la stessa per tutte e due.
    val stops: List<Int>? = androidx.compose.runtime.remember(anchor, buildId, stopGroups) {
        val a = anchor
        if (reader == null || a == null) {
            null
        } else {
            // Le banchine del gruppo entrano tutte: una fermata e' una
            // fermata, e le due direzioni si distinguono dalla destinazione.
            //
            // L'ordine e' quello di distanza e NON si riordina: per il
            // tabellone e' un ordine di preferenza, e decide da quale palo
            // mostrare un autobus che passa da piu' d'uno di questi. Il piu'
            // vicino a chi guarda e' la risposta giusta.
            reader.stopsNear(a.first, a.second, DepartureText.NEARBY_RADIUS_METERS)
                .take(NEARBY_STOPS)
                .flatMap { s -> stopGroups?.siblings(s)?.toList() ?: listOf(s) }
                .distinct()
        }
    }
    // Senza fermate, o senza sapere ancora dove guardare, gli orari non si
    // chiedono nemmeno: `merged` di una lista vuota restituisce un tabellone
    // datato adesso e senza righe, e la capsula ci scriveva "non passa niente
    // a breve" anche in mezzo alla campagna e nei due secondi dopo l'avvio,
    // in cui l'ancora non c'e' ancora. La firma resta quella di sempre — un
    // DepartureBoard — perche' lo stato "nessuna fermata" viaggia dentro il
    // tabellone stesso (vedi DepartureText.NEARBY_NO_STOPS).
    val flusso: StateFlow<DepartureBoard> = androidx.compose.runtime.remember(stops) {
        val senzaChiedere = DepartureText.nearbyBoardWithoutAsking(
            stops, java.time.Instant.now().epochSecond,
        )
        if (senzaChiedere != null) {
            MutableStateFlow(senzaChiedere)
        } else {
            app.departureBoards.merged(stops.orEmpty(), limit = NEARBY_LIMIT)
        }
    }
    val board by flusso.collectAsStateWithLifecycle()
    return board
}

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
