package dev.antigravity.fluidtransit.ui.map

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidHairline
import dev.antigravity.fluidengine.ui.fluid.FluidTabBarDefaults
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Certainty
import dev.antigravity.fluidtransit.routing.DepartureText
import dev.antigravity.fluidtransit.routing.Ftb
import dev.antigravity.fluidtransit.routing.LiveTimes
import dev.antigravity.fluidtransit.routing.StopTimes
import dev.antigravity.fluidtransit.routing.Times
import dev.antigravity.fluidtransit.routing.Words
import dev.antigravity.fluidtransit.ui.common.toneColor
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Le chiavi di un bus toccato, come bastano a risalire a corsa e linea.
 * `tripIndex`/`routeIndex` valgono -1 quando il bundle non riconosce gli
 * hash — succede, le due generazioni di dati non sono sincronizzate.
 */
class TripRef(
    val vehKey: Int,
    val tripHash: Long,
    val routeHash: Long,
    val tripIndex: Int,
    val routeIndex: Int,
)

/**
 * Tutto quello che la scheda corsa sa dire. Come RouteInfo, si calcola in
 * un passaggio su Dispatchers.Default e si ricalcola quando arriva un
 * ritardo nuovo.
 */
class TripInfo(
    val ref: TripRef,
    val shortName: String,
    val colorRgb: Int,
    val headsign: String,
    val delaySec: Int?, // null = nessun trip-update per questa corsa
    val canceled: Boolean,
    val stops: List<NextStop>, // le prossime fermate, coi minuti gia' corretti
    val stopsTotal: Int,
    /**
     * Quante fermate hanno l'orario ripartito da noi.
     *
     * Il feed regionale pubblica gli orari al minuto tondo, quindi due
     * fermate vicine finiscono con lo stesso identico minuto e la scheda
     * mostrava due righe "14:32" una sotto l'altra, come se l'app avesse
     * sbagliato i conti. Il divario lo stimiamo noi, dalla distanza vera —
     * ed e' una stima, quindi va detta dove compare invece di passare per un
     * orario di tabella come gli altri.
     */
    val spreadStops: Int = 0,
) {
    class NextStop(
        val name: String,
        val idHashHex: String,
        val lat: Double,
        val lon: Double,
        /** L'orario di tabella. Quello che l'app promette se il live manca. */
        val scheduledEpoch: Long,
        /** L'orario corretto col ritardo di QUESTA fermata. */
        val effectiveEpoch: Long,
        /** Da dove viene la correzione: null = nessun dato live. */
        val certainty: Certainty?,
        /** Il feed dichiara che questa corsa, oggi, qui non ferma. */
        val skipped: Boolean,
        val isLast: Boolean,
    )

    companion object {
        fun build(
            reader: BundleReader,
            ref: TripRef,
            now: Instant,
            delaySec: Int?,
            canceled: Boolean,
            live: LiveTimes? = null,
        ): TripInfo {
            val shortName = if (ref.routeIndex >= 0) {
                reader.routeShortName(ref.routeIndex).ifEmpty { reader.routeLongName(ref.routeIndex) }
            } else {
                "?"
            }
            val color = if (ref.routeIndex >= 0) reader.routeDisplayColor(ref.routeIndex) else 0x8A8A93

            if (ref.tripIndex < 0) {
                return TripInfo(ref, shortName, color, "", delaySec, canceled, emptyList(), 0)
            }

            val pattern = reader.tripPattern(ref.tripIndex)
            val profile = reader.tripProfile(ref.tripIndex)
            val dep0 = reader.tripDeparture0(ref.tripIndex)
            val n = reader.patternStopCount(pattern)
            val today = now.atZone(Ftb.ROME).toLocalDate()

            // Il giorno di servizio della corsa IN CORSO: quasi sempre oggi,
            // ma una notturna dopo mezzanotte appartiene a ieri (le "25:30").
            var dayStartSec = 0L
            var found = false
            for (offset in 0 downTo -1) {
                val date = today.plusDays(offset.toLong())
                val dayIndex = ChronoUnit.DAYS.between(reader.feedStart, date).toInt()
                if (dayIndex < 0 || dayIndex >= reader.dayCount) continue
                if (!reader.serviceActive(reader.tripService(ref.tripIndex), dayIndex)) continue
                val start = Ftb.serviceDayStart(date).epochSecond + dep0
                // Corrente = partita da meno di 12 ore o in partenza entro 2.
                if (now.epochSecond in (start - 2 * 3600)..(start + 12 * 3600)) {
                    dayStartSec = Ftb.serviceDayStart(date).epochSecond
                    found = true
                    break
                }
            }
            if (!found) dayStartSec = Ftb.serviceDayStart(today).epochSecond

            // Le fermate gemelle: il feed da' lo stesso minuto a due fermate
            // vicine, e la scheda mostrava due righe identiche una sotto
            // l'altra. Il divario lo stimiamo noi, dalla distanza vera.
            val offsets = StopTimes.offsets(reader, pattern, profile)
            val grezzi = IntArray(n) { reader.profileOffset(profile, it) }
            val ripartite = StopTimes.twinCount(grezzi)
            val stops = ArrayList<NextStop>(n)
            for (i in 0 until n) {
                val scheduled = dayStartSec + dep0 + offsets[i]
                // Il ritardo di QUESTA fermata. Prima era lo stesso intero su
                // tutte, e la scheda prometteva gli stessi otto minuti di
                // ritardo al capolinea di un'ora dopo.
                // `now`, quello passato: leggere di nuovo l'orologio qui
                // dentro voleva dire calcolare una stessa scheda con due
                // istanti diversi.
                val at = live?.at(ref.tripIndex, i, n, now.epochSecond)
                // Il feed dice fin dove il bus e' arrivato: piu' affidabile
                // dell'orologio quando la corsa e' in anticipo.
                if (at?.certainty == Certainty.SERVED) continue
                // Il ritardo del mezzo intero vale come ripiego, ma e' una
                // stima nostra e va detto: e' una fermata che il feed non
                // copre, non una previsione che ha dichiarato.
                val certainty = at?.certainty
                    ?: if (delaySec != null) Certainty.ESTIMATED else null
                val skipped = live?.skipped(ref.tripIndex, i) == true
                val eff = scheduled + (at?.delaySeconds ?: delaySec ?: 0)
                if (eff < now.epochSecond - 60) continue // gia' passata
                val s = reader.patternStop(pattern, i)
                stops.add(
                    NextStop(
                        name = reader.stopName(s),
                        idHashHex = java.lang.Long.toHexString(reader.stopIdHash(s)),
                        lat = reader.stopLat(s),
                        lon = reader.stopLon(s),
                        scheduledEpoch = scheduled,
                        effectiveEpoch = eff,
                        certainty = certainty,
                        skipped = skipped,
                        isLast = i == n - 1,
                    ),
                )
            }
            return TripInfo(
                ref = ref,
                shortName = shortName,
                colorRgb = color,
                headsign = reader.patternDestination(pattern),
                delaySec = delaySec,
                canceled = canceled,
                stops = stops,
                stopsTotal = n,
                spreadStops = ripartite,
            )
        }
    }
}

/** Il verde del "live", leggibile su entrambi i temi. */
@Composable
fun liveGreen(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Color(0xFF53D28C)
    } else {
        Color(0xFF128A45)
    }

/** Il pallino pulsante accanto ai minuti veri: la convenzione decisa. */
@Composable
fun LiveDot(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "live")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "liveAlpha",
    )
    Box(
        modifier = modifier
            .size(7.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(color, CircleShape),
    )
}

/**
 * La frase del ritardo, nel tono diretto dell'app. L'arrotondamento e'
 * quello di [Times] e non uno suo: la capsula qui e il conteggio nella lista
 * sotto dicevano numeri diversi perche' una troncava e l'altra arrotondava.
 */
fun delayLabel(delaySec: Int?, canceled: Boolean): String = when {
    canceled -> "Corsa cancellata"
    else -> Times.delayLabel(delaySec).replaceFirstChar { it.uppercase() }
}

/**
 * Il mini della corsa: prende il posto della tab bar come quello della
 * linea — stessa altezza, stessa capsula — ma parla del bus vivo.
 */
@Composable
fun TripMiniContent(info: TripInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(FluidTabBarDefaults.Height)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RoutePill(info.shortName, info.colorRgb)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (info.headsign.isNotEmpty()) "→ ${info.headsign}" else "Bus in servizio",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val red = MaterialTheme.colorScheme.error
                if (info.delaySec != null && !info.canceled) LiveDot(liveGreen())
                Text(
                    text = buildString {
                        append(delayLabel(info.delaySec, info.canceled))
                        if (info.stops.isNotEmpty()) {
                            append(" · ")
                            append(Words.count(info.stops.size, "fermata rimasta", "fermate rimaste"))
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        info.canceled -> red
                        info.delaySec != null -> liveGreen()
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * La scheda corsa espansa: testata, stato live, prossime fermate coi minuti
 * gia' corretti dal ritardo. Sotto, il tasto in
 * vetro "sono su questo bus" con la guardia GPS.
 */
@Composable
fun TripFullContent(
    info: TripInfo,
    fixAgeSec: Int?,
    onStopTap: (TripInfo.NextStop) -> Unit,
    backdrop: dev.antigravity.fluidengine.ui.fluid.GlassBackdropState? = null,
    /** null = niente sezione; "ok" = pulsante; "far" = riga "non disponibile". */
    boardGuard: String? = null,
    onBoardBus: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    /** Gli avvisi in corso sulla linea di questa corsa, gia' filtrati. */
    alerts: List<String> = emptyList(),
    onOpenAlerts: (() -> Unit)? = null,
) {
    // Il battito dell'app, uno solo.
    //
    // Ogni riga di questa scheda leggeva l'orologio per conto suo, dentro la
    // propria composizione: due righe della stessa scheda potevano essere
    // calcolate a istanti diversi, e nessuna si aggiornava al passare del
    // minuto finche' qualcos'altro non faceva ricomporre. Il resto dell'app
    // sta sul battito condiviso dalla Fase 9; questa scheda era rimasta
    // fuori, ed e' proprio quella che si guarda mentre si aspetta.
    val nowSec by dev.antigravity.fluidtransit.data.time.UiClock.ticks()
        .collectAsStateWithLifecycle(initialValue = System.currentTimeMillis() / 1000)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoutePill(info.shortName, info.colorRgb)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (info.headsign.isNotEmpty()) info.headsign else "Bus in servizio",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (fixAgeSec != null && fixAgeSec >= 0) {
                        "Posizione live · aggiornata " +
                            dev.antigravity.fluidtransit.routing.Words.age(fixAgeSec) + " fa"
                    } else {
                        "Posizione live"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // La X, come in fermata, luogo, linea, viaggi e "qui intorno".
            //
            // Era l'ultima scheda senza: si chiudeva trascinando giu' o col
            // tasto Indietro, cioe' con due gesti che le altre non chiedono.
            // Il parametro per chiuderla arrivava fin qui da settimane e non
            // lo usava nessuno, e la X era perfino gia' importata.
            if (onDismiss != null) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Chiudi",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(38.dp)
                        .clickable(
                            interactionSource = androidx.compose.runtime.remember {
                                androidx.compose.foundation.interaction.MutableInteractionSource()
                            },
                            indication = null,
                            role = androidx.compose.ui.semantics.Role.Button,
                            onClick = onDismiss,
                        )
                        .padding(7.dp),
                )
            }
        }

        // Una deviazione in corso cambia i piani di chi e' sul bus o lo
        // aspetta piu' di qualunque numero che c'e' qui sotto.
        dev.antigravity.fluidtransit.ui.common.AlertRows(alerts, onOpenAlerts, tail = "su questa linea")

        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (info.delaySec != null && !info.canceled) LiveDot(liveGreen())
            Text(
                text = delayLabel(info.delaySec, info.canceled),
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    info.canceled -> MaterialTheme.colorScheme.error
                    info.delaySec != null -> liveGreen()
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        // "Sono su questo bus" (Fase 7): il pulsante compare solo se il GPS
        // conferma che sei plausibilmente a bordo — o se il GPS e' spento,
        // che rende la modalita' anche provabile da fermi, come deciso.
        if (backdrop != null && boardGuard != null && info.ref.tripIndex >= 0) {
            when (boardGuard) {
                "ok" -> Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    GlassActionButton(
                        text = "Sono su questo bus",
                        icon = null,
                        backdrop = backdrop,
                        emphasized = true,
                        onClick = { onBoardBus?.invoke() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                else -> Text(
                    text = "\"Sono su questo bus\" non e' disponibile: la tua posizione " +
                        "non coincide con quella del mezzo.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }

        if (info.ref.tripIndex < 0) {
            Text(
                text = "Questa corsa non e' negli orari di oggi: la posizione resta " +
                    "live, ma fermate e minuti non si possono calcolare.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = panelListMax(340.dp))
                    .fadeVerticalEdges()
                    .padding(horizontal = 8.dp),
            ) {
                items(info.stops.size) { i ->
                    val stop = info.stops[i]
                    if (i > 0) FluidHairline(modifier = Modifier.padding(start = 44.dp, end = 12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onStopTap(stop) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(
                                    color = Color(0xFF000000 or info.colorRgb.toLong()),
                                    shape = CircleShape,
                                ),
                        )
                        // Le stesse parole, gli stessi toni e la stessa
                        // regola del pallino del resto dell'app: qui c'erano
                        // un "previsto" che voleva dire un'altra cosa, il
                        // verde anche sulle stime e un 60 scritto a mano.
                        val phrase = DepartureText.alongTrip(
                            scheduledEpoch = stop.scheduledEpoch,
                            delaySeconds = (stop.effectiveEpoch - stop.scheduledEpoch)
                                .toInt().takeIf { stop.certainty != null },
                            certainty = stop.certainty,
                            skipped = stop.skipped,
                            nowEpoch = nowSec,
                        )
                        // La provenienza sotto il nome, non sotto il numero.
                        //
                        // Stava a destra, e cosi' la colonna dei minuti era
                        // larga quanto "dal bus - da tabella alle 05:05":
                        // restavano diciassette caratteri per il nome della
                        // fermata, e "BESLAN T1 FORTE..." non e' un nome. Il
                        // tabellone della fermata era gia' stato sistemato
                        // cosi'; questa lista era rimasta indietro.
                        Column(modifier = Modifier.weight(1f)) {
                            if (stop.isLast) {
                                Text(
                                    text = "Capolinea",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = stop.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = phrase.support,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            if (phrase.pulse) LiveDot(toneColor(phrase.tone))
                            Text(
                                text = phrase.headline,
                                style = MaterialTheme.typography.titleSmall,
                                color = toneColor(phrase.tone),
                            )
                        }
                    }
                }
            }
        }

        // Quando gli orari di qualche fermata li abbiamo ripartiti noi, si
        // dice. Il feed pubblica al minuto tondo, quindi due fermate a
        // duecento metri escono con lo stesso identico minuto; il divario e'
        // una nostra stima dalla distanza vera, e una stima che si presenta
        // come un orario di tabella e' la cosa che questa campagna sta
        // togliendo dall'app.
        if (info.spreadStops > 0) {
            Text(
                text = "Fra fermate vicinissime il divario lo stimiamo noi: " +
                    "il feed pubblica lo stesso minuto per tutte.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
