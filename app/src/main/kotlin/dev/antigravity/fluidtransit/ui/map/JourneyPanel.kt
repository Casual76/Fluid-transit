package dev.antigravity.fluidtransit.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidHairline
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Ftb
import dev.antigravity.fluidtransit.routing.LiveTimes
import dev.antigravity.fluidtransit.routing.TripProgress
import dev.antigravity.fluidtransit.routing.Raptor
import dev.antigravity.fluidtransit.routing.Times
import dev.antigravity.fluidtransit.routing.Words
import java.time.Instant

/** Un viaggio gia' tradotto in stringhe: la UI non tocca il reader. */
class UiJourney(
    val depTime: String,
    val arrTime: String,
    /**
     * La durata gia' detta a parole: "43 min", "4 h", "4 h 3 min".
     *
     * Prima erano minuti e basta, e un viaggio notturno con tre ore di attesa
     * in mezzo si presentava come "240 min" — un numero che si deve
     * convertire in testa prima di sapere se conviene.
     */
    val durationLabel: String,
    val transfers: Int,
    val walkMin: Int,
    val walkOnly: Boolean,
    val hasLive: Boolean,
    val pills: List<Pair<String, Int>>, // (nome linea, colore)
    val legs: List<UiLeg>,
    val raw: Raptor.Journey,
) {
    companion object {
        fun of(
            reader: BundleReader,
            j: Raptor.Journey,
            /** Le corse che il realtime sta davvero seguendo. */
            liveTrips: Set<Int> = emptySet(),
        ): UiJourney {
            val legs = j.legs.mapIndexed { i, leg ->
                when (leg) {
                    is Raptor.Leg.Walk -> UiLeg.Walk(
                        minutes = (leg.seconds + 30) / 60,
                        toName = if (leg.toStop >= 0) reader.stopName(leg.toStop) else "destinazione",
                        depTime = hm(leg.departure),
                    )

                    is Raptor.Leg.Ride -> UiLeg.Ride(
                        waitSeconds = j.legs.getOrNull(i - 1)?.let { prima ->
                            (leg.departure.epochSecond - prima.arrival.epochSecond)
                                .coerceAtLeast(0L).toInt()
                        } ?: 0,
                        line = reader.routeShortName(leg.route)
                            .ifEmpty { reader.routeLongName(leg.route) },
                        colorRgb = reader.routeDisplayColor(leg.route),
                        headsign = reader.patternDestination(leg.pattern),
                        boardName = reader.stopName(leg.boardStop),
                        alightName = reader.stopName(leg.alightStop),
                        depTime = hm(leg.departure),
                        arrTime = hm(leg.arrival),
                        stops = leg.alightPosition - leg.boardPosition,
                        delaySeconds = leg.delaySeconds,
                        live = leg.trip in liveTrips,
                    )
                }
            }
            return UiJourney(
                depTime = hm(j.departure),
                arrTime = hm(j.arrival),
                // Contata fra i due orari scritti, non fra i due istanti:
                // altrimenti "10:26 -> 10:51" si porta accanto "24 min".
                durationLabel = Times.durationBetween(
                    j.departure.epochSecond, j.arrival.epochSecond,
                ),
                transfers = j.transfers,
                walkMin = (j.walkSeconds + 30) / 60,
                walkOnly = j.isWalkOnly,
                hasLive = j.legs.any { it is Raptor.Leg.Ride && it.trip in liveTrips },
                pills = j.legs.filterIsInstance<Raptor.Leg.Ride>().map { r ->
                    reader.routeShortName(r.route).ifEmpty { reader.routeLongName(r.route) } to
                        reader.routeDisplayColor(r.route)
                },
                legs = legs,
                raw = j,
            )
        }

        /** L'orologio e' quello del vocabolario, non una copia locale. */
        private fun hm(i: Instant): String = Times.hhmm(i.epochSecond)
    }
}

/**
 * Sotto questa attesa non vale la pena dirlo.
 *
 * Due minuti: e' il margine con cui si arriva a una fermata, non un'attesa.
 * Sopra, invece, cambia cosa fai — se corri o se ti siedi.
 */
private const val WAIT_WORTH_SAYING_S = 120

sealed class UiLeg {
    class Walk(val minutes: Int, val toName: String, val depTime: String) : UiLeg()
    class Ride(
        /**
         * Quanto si aspetta alla fermata prima di salire.
         *
         * Non era scritto da nessuna parte. Un viaggio notturno diceva
         * "Cammina 12 min fino a LEOPOLDA 01:41" e poi "30 ... 05:02
         * LEOPOLDA": in mezzo ci sono tre ore e ventuno di attesa a una
         * pensilina, e per saperlo bisognava fare la sottrazione da soli.
         * Anche i sette minuti di un'attesa normale sono un'informazione che
         * cambia cosa fai — se corri o se prendi un caffe'.
         */
        val waitSeconds: Int,
        val line: String,
        val colorRgb: Int,
        val headsign: String,
        val boardName: String,
        val alightName: String,
        val depTime: String,
        val arrTime: String,
        val stops: Int,
        val delaySeconds: Int,
        /**
         * Il feed sta seguendo QUESTA corsa. Diverso da "ha un ritardo":
         * una corsa monitorata e puntuale ha ritardo zero, e prima veniva
         * mostrata identica a una di cui non si sa niente.
         */
        val live: Boolean = false,
    ) : UiLeg()
}

/**
 * Le soluzioni di viaggio nel pannello dal basso, come deciso: orari,
 * durata, la sequenza delle pillole colorate, i minuti live. Un tocco apre
 * il dettaglio nella stessa superficie.
 */
@Composable
fun JourneysContent(
    toName: String,
    journeys: List<UiJourney>?,
    /** Il calcolo non e' riuscito: diverso da "non c'e' nessun viaggio". */
    failed: Boolean = false,
    /**
     * Il calcolo sta ancora andando e quello che si vede e' parziale.
     *
     * Un piano sono fino a otto scansioni in fila: la prima da' gia' un
     * viaggio, l'ultima quello che parte fra un'ora. Mostrarli mentre
     * arrivano vuol dire pero' dire anche che ne stanno arrivando altri,
     * altrimenti una lista di due righe sembra la risposta finita.
     */
    searching: Boolean = false,
    /** Partenza e arrivo sono lo stesso posto: non c'e' niente da calcolare. */
    samePlace: Boolean = false,
    fromLabel: String,
    timeLabel: String,
    backdrop: GlassBackdropState,
    onTimeTap: () -> Unit,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "→ $toName",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = fromLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

    Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        GlassActionButton(
            text = timeLabel,
            icon = Icons.Rounded.Schedule,
            backdrop = backdrop,
            onClick = onTimeTap,
        )
    }

    when {
        journeys == null -> {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FluidSpinner()
                Text(
                    text = "Cerco i prossimi viaggi…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Un calcolo fallito non e' "nessun viaggio". Il primo dice che non
        // abbiamo saputo rispondere, il secondo che la risposta e' no — ed
        // erano la stessa schermata, che invitava a cambiare orario quando
        // cambiare orario non poteva servire a niente.
        failed -> {
            FluidEmptyState(
                title = "Non sono riuscito a calcolare il viaggio",
                detail = "Non e' che non ci sia: e' che il calcolo si e' " +
                    "interrotto. Riprova fra un momento.",
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(12.dp))
        }

        // "Zero minuti, solo a piedi" e' vero e non serve a niente.
        //
        // Col GPS spento la partenza e' il centro della mappa, e dopo una
        // ricerca il centro della mappa E' il posto cercato: quindi ogni
        // "Portami qui" finiva in un viaggio da qui a qui, "00:55 -> 00:55,
        // 0 min". Nessuno sbaglio nel calcolo — il calcolo ha risposto
        // esattamente alla domanda sbagliata.
        samePlace -> {
            FluidEmptyState(
                title = "Sei gia' li'",
                detail = "Partenza e arrivo sono lo stesso posto. Se non e' " +
                    "quello che volevi, scegli da dove parti: senza GPS la " +
                    "partenza e' il centro della mappa, che dopo una ricerca " +
                    "e' proprio il posto trovato.",
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(12.dp))
        }

        journeys.isEmpty() -> {
            FluidEmptyState(
                title = "Nessun viaggio trovato",
                detail = "In questa finestra il bus non ci arriva. Prova a cambiare orario.",
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(12.dp))
        }

        else -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = panelListMax(400.dp))
                    .fadeVerticalEdges()
                    .padding(horizontal = 20.dp),
            ) {
                items(journeys.size) { i ->
                    if (i > 0) FluidHairline()
                    JourneyRow(journeys[i]) { onPick(i) }
                }
                // Ne stanno ancora arrivando: senza questa riga una lista di
                // due viaggi sembra la risposta finita, e chi la legge se ne
                // va prima che arrivi quello che gli serviva.
                if (searching) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FluidSpinner()
                            Text(
                                text = "Cerco anche i prossimi…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun JourneyRow(j: UiJourney, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "${j.depTime} → ${j.arrTime}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (j.hasLive) LiveDot(liveGreen())
            }
            Spacer(Modifier.height(6.dp))
            StrisciaTappe(j)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = j.durationLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = when {
                    j.walkOnly -> "${j.walkMin} min a piedi"
                    j.transfers == 0 -> "diretto"
                    j.transfers == 1 -> "1 cambio"
                    else -> "${j.transfers} cambi"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Il dettaglio tappa per tappa, col percorso gia' acceso sulla mappa. */
@Composable
fun JourneyDetailContent(
    j: UiJourney,
    toName: String,
    onDismiss: () -> Unit,
    backdrop: GlassBackdropState? = null,
    /** (giorni 1-7, "arrive"|"depart", minuti dalla mezzanotte). */
    onCreateRoutine: ((Set<Int>, String, Int) -> Unit)? = null,
    onStart: (() -> Unit)? = null,
    /** Gli avvisi in corso sulle linee di QUESTO viaggio, gia' filtrati. */
    alerts: List<String> = emptyList(),
    onOpenAlerts: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "${j.depTime} → ${j.arrTime}",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (j.hasLive) LiveDot(liveGreen())
            }
            Text(
                text = "${j.durationLabel} · ${
                    when (j.transfers) {
                        0 -> "diretto"
                        1 -> "1 cambio"
                        else -> "${j.transfers} cambi"
                    }
                } · ${j.walkMin} min a piedi · → $toName",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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

    // Gli avvisi delle linee di questo viaggio, prima delle tappe.
    //
    // Il motore calcola sul percorso di tabella: se una delle linee oggi e'
    // deviata, il viaggio proposto puo' non esistere come e' scritto. E'
    // l'unica cosa che puo' rendere sbagliato tutto quello che c'e' sotto.
    dev.antigravity.fluidtransit.ui.common.AlertRows(
        alerts, onOpenAlerts, tail = "sulle linee di questo viaggio",
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = panelListMax(380.dp))
            .fadeVerticalEdges()
            .padding(horizontal = 20.dp),
    ) {
        items(j.legs.size) { i ->
            val leg0 = j.legs[i]
            // La spina del viaggio.
            //
            // Le tappe erano righe separate da una riga grigia: si leggevano
            // come voci di un elenco invece che come i pezzi di un percorso
            // che continua. Adesso a sinistra corre una barra che cambia
            // colore con la tappa — grigia dove si cammina, del colore della
            // linea dove si e' a bordo — e i separatori se ne vanno, perche'
            // erano proprio loro a spezzare quello che qui deve sembrare
            // continuo.
            val tintaTappa = when (leg0) {
                is UiLeg.Ride -> Color(0xFF000000 or leg0.colorRgb.toLong())
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val primaTappa = i == 0
            val ultimaTappa = i == j.legs.size - 1
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(10.dp)
                        .fillMaxHeight()
                        .drawBehind {
                            val x = size.width / 2f
                            val spessore = 6.dp.toPx()
                            drawLine(
                                color = tintaTappa.copy(alpha = 0.45f),
                                start = Offset(x, if (primaTappa) spessore / 2f else 0f),
                                end = Offset(
                                    x,
                                    if (ultimaTappa) size.height - spessore / 2f else size.height,
                                ),
                                strokeWidth = spessore,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            )
                        },
                )
            when (val leg = j.legs[i]) {
                is UiLeg.Walk -> Row(
                    modifier = Modifier.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.DirectionsWalk,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        // Zero minuti a piedi non e' una camminata: quando la
                        // partenza e' gia' sul marciapiede della fermata
                        // usciva "Cammina 0 min fino a PORTA SAN FREDIANO",
                        // che e' un'istruzione a non fare niente.
                        text = if (leg.minutes == 0) {
                            "Meno di un minuto a piedi fino a ${leg.toName}"
                        } else {
                            "Cammina ${leg.minutes} min fino a ${leg.toName}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = leg.depTime,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is UiLeg.Ride -> Column {
                    if (leg.waitSeconds >= WAIT_WORTH_SAYING_S) {
                        Row(
                            modifier = Modifier.padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "Aspetti ${Times.durationLabel(leg.waitSeconds)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                    modifier = Modifier.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .size(12.dp)
                            .background(
                                color = Color(0xFF000000 or leg.colorRgb.toLong()),
                                shape = CircleShape,
                            ),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RoutePill(text = leg.line, colorRgb = leg.colorRgb)
                            Text(
                                text = "→ ${leg.headsign}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${leg.depTime}  ${leg.boardName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = Words.count(leg.stops, "fermata", "fermate"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                        Text(
                            text = "${leg.arrTime}  ${leg.alightName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        // La provenienza c'e' SEMPRE, come in ogni altro
                        // posto dove l'app scrive un orario.
                        //
                        // Prima: pallino verde e ritardo quando il feed
                        // seguiva la corsa, e assolutamente niente quando non
                        // la seguiva. Il silenzio in un viaggio calcolato si
                        // legge come "questo orario e' sicuro", che e' il
                        // contrario di quello che vuol dire.
                        //
                        // Le parole e il grigio del sottotitolo sono gli
                        // stessi della riga di un tabellone: e' lo stesso
                        // fatto, scritto nello stesso modo.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            if (leg.live) LiveDot(liveGreen())
                            Text(
                                text = if (leg.live) {
                                    "dal bus · " +
                                        dev.antigravity.fluidtransit.routing.Times
                                            .delayLabel(leg.delaySeconds)
                                } else {
                                    "orario da tabella"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    }
                }
            }
            }
        }
    }

    // --- "Avvia": la navigazione a bordo (Fase 7) -------------------------
    if (backdrop != null && onStart != null && !j.walkOnly) {
        Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            GlassActionButton(
                text = "Avvia il viaggio",
                icon = Icons.AutoMirrored.Rounded.DirectionsWalk,
                backdrop = backdrop,
                emphasized = true,
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // --- "Fanne una routine": il percorso naturale deciso -----------------
    if (backdrop != null && onCreateRoutine != null && !j.walkOnly) {
        RoutineForm(j = j, backdrop = backdrop, onCreateRoutine = onCreateRoutine)
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun RoutineForm(
    j: UiJourney,
    backdrop: GlassBackdropState,
    onCreateRoutine: (Set<Int>, String, Int) -> Unit,
) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var created by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var days by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(setOf(1, 2, 3, 4, 5))
    }
    var anchor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("arrive") }

    if (created) {
        Text(
            text = "Routine creata: la trovi nella scheda Oggi. Nei giorni scelti " +
                "ti diro' io quando uscire.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
        return
    }

    if (!open) {
        Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            GlassActionButton(
                text = "Fanne una routine",
                icon = Icons.Rounded.Schedule,
                backdrop = backdrop,
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }

    // I giorni: sette lettere, tocco per accendere.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val letters = listOf("L", "M", "M", "G", "V", "S", "D")
        for (d in 1..7) {
            val on = d in days
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = { days = if (on) days - d else days + d },
                    )
                    .background(
                        color = if (on) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        },
                        shape = CircleShape,
                    )
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = letters[d - 1],
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) Color.White else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl(
        options = listOf("arrive", "depart"),
        selected = anchor,
        onSelect = { anchor = it },
        label = { if (it == "arrive") "Arriva entro ${j.arrTime}" else "Parti alle ${j.depTime}" },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    )

    Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        GlassActionButton(
            text = "Crea la routine",
            icon = null,
            backdrop = backdrop,
            emphasized = true,
            onClick = {
                if (days.isEmpty()) return@GlassActionButton
                val time = if (anchor == "arrive") j.arrTime else j.depTime
                val minutes = time.split(':').let { it[0].toInt() * 60 + it[1].toInt() }
                // "Arriva entro" si arrotonda in su ai 5 minuti: un margine
                // onesto, non una promessa al secondo.
                val anchorMinutes = if (anchor == "arrive") ((minutes + 4) / 5) * 5 else minutes
                onCreateRoutine(days, anchor, anchorMinutes)
                created = true
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * La geometria del viaggio per la mappa: corse ritagliate dalla polilinea
 * del pattern (v4; senza sezione si ripiega sulle fermate), camminate in
 * linea retta tratteggiata. Ritorna anche il riquadro da inquadrare.
 *
 * Il risultato non nomina nessun motore di mappa: prima usciva di qui gia'
 * impacchettato come FeatureCollection di MapLibre, e cambiare mappa avrebbe
 * voluto dire riscrivere anche questa funzione.
 */
fun buildJourneyGeometry(
    reader: BundleReader,
    j: Raptor.Journey,
): Pair<JourneyShape, DoubleArray> {
    var minLat = 90.0
    var maxLat = -90.0
    var minLon = 180.0
    var maxLon = -180.0

    fun grow(lat: Double, lon: Double) {
        if (lat < minLat) minLat = lat
        if (lat > maxLat) maxLat = lat
        if (lon < minLon) minLon = lon
        if (lon > maxLon) maxLon = lon
    }

    val lines = ArrayList<MapLine>(j.legs.size)
    for (leg in j.legs) {
        when (leg) {
            is Raptor.Leg.Walk -> {
                grow(leg.fromLat, leg.fromLon)
                grow(leg.toLat, leg.toLon)
                // Due punti: la retta fra dove sei e la fermata. E' il
                // ripiego, e resta finche' non c'e' un percorso pedonale
                // vero da seguire.
                lines.add(
                    MapLine(
                        lat = doubleArrayOf(leg.fromLat, leg.toLat),
                        lon = doubleArrayOf(leg.fromLon, leg.toLon),
                        dashed = true,
                    ),
                )
            }

            is Raptor.Leg.Ride -> {
                val la = ArrayList<Double>(64)
                val lo = ArrayList<Double>(64)
                val poly = reader.patternPolyline(leg.pattern)
                if (poly != null) {
                    val a = reader.patternStopVertex(leg.pattern, leg.boardPosition)
                        .coerceIn(0, poly.size - 1)
                    val b = reader.patternStopVertex(leg.pattern, leg.alightPosition)
                        .coerceIn(0, poly.size - 1)
                    for (v in minOf(a, b)..maxOf(a, b)) {
                        grow(poly.lat[v], poly.lon[v])
                        la.add(poly.lat[v])
                        lo.add(poly.lon[v])
                    }
                } else {
                    for (pos in leg.boardPosition..leg.alightPosition) {
                        val s = reader.patternStop(leg.pattern, pos)
                        grow(reader.stopLat(s), reader.stopLon(s))
                        la.add(reader.stopLat(s))
                        lo.add(reader.stopLon(s))
                    }
                }
                if (la.size >= 2) {
                    lines.add(
                        MapLine(
                            lat = la.toDoubleArray(),
                            lon = lo.toDoubleArray(),
                            colorRgb = reader.routeDisplayColor(leg.route) and 0xFFFFFF,
                        ),
                    )
                }
            }
        }
    }
    return JourneyShape(lines) to doubleArrayOf(minLat, minLon, maxLat, maxLon)
}

/**
 * Il piano che il servizio di navigazione segue, precalcolato dal viaggio:
 * tappe in numeri puri, cosi' il servizio corregge i tempi coi ritardi
 * senza rifare il calcolo.
 */
fun buildNavPlan(
    reader: BundleReader,
    j: Raptor.Journey,
    destName: String,
): dev.antigravity.fluidtransit.data.nav.NavPlan {
    val legs = j.legs.map { leg ->
        when (leg) {
            is Raptor.Leg.Walk -> dev.antigravity.fluidtransit.data.nav.NavLeg.Walk(
                seconds = leg.seconds,
                toName = if (leg.toStop >= 0) reader.stopName(leg.toStop) else destName,
                startEpoch = leg.departure.epochSecond,
                toLat = leg.toLat,
                toLon = leg.toLon,
            )

            is Raptor.Leg.Ride -> {
                val profile = reader.tripProfile(leg.trip)
                val dep0 = reader.tripDeparture0(leg.trip)
                val dayStart = leg.departure.epochSecond - dep0 -
                    reader.profileOffset(profile, leg.boardPosition) - leg.delaySeconds
                dev.antigravity.fluidtransit.data.nav.NavLeg.Ride(
                    trip = leg.trip,
                    pattern = leg.pattern,
                    route = leg.route,
                    boardPosition = leg.boardPosition,
                    alightPosition = leg.alightPosition,
                    dayStartEpoch = dayStart,
                    dep0 = dep0,
                    profile = profile,
                    lineName = reader.routeShortName(leg.route)
                        .ifEmpty { reader.routeLongName(leg.route) },
                    alightName = reader.stopName(leg.alightStop),
                    stopNames = (leg.boardPosition..leg.alightPosition).map {
                        reader.stopName(reader.patternStop(leg.pattern, it))
                    },
                )
            }
        }
    }
    return dev.antigravity.fluidtransit.data.nav.NavPlan("journey", destName, legs)
}

/**
 * Il mini di navigazione: prende il posto della tab bar mentre si viaggia.
 * "Scendi a X · 4 fermate · 12 min", e Termina sempre a portata.
 */
@Composable
fun NavMiniContent(
    state: dev.antigravity.fluidtransit.data.nav.NavState,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(dev.antigravity.fluidengine.ui.fluid.FluidTabBarDefaults.Height)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.phase == "ride") LiveDot(liveGreen())
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.headline,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.detail,
                style = MaterialTheme.typography.labelMedium,
                color = if (state.phase == "ride") liveGreen() else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                // Senza i puntini il nome si taglia e basta: "fino a PISANA"
                // per PISANA MONTICELLI si legge come un'altra fermata, non
                // come un nome accorciato.
                overflow = TextOverflow.Ellipsis,
            )
        }
        androidx.compose.material3.Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "Termina la navigazione",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(40.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onStop,
                )
                .padding(8.dp),
        )
    }
}

/**
 * Il piano per "sono su questo bus": una sola tappa in vettura, dalla
 * prossima fermata al capolinea.
 *
 * Dove sia arrivato il mezzo lo dice [TripProgress], che e' la stessa regola
 * della lista di fermate mostrata subito sopra: qui si guardava solo
 * l'orologio, col ritardo della corsa intera, e su una corsa in anticipo il
 * viaggio partiva da una fermata che il pannello aveva gia' tolto dalla
 * lista.
 *
 * Ed era anche il posto dove "questa corsa e' finita" non si riconosceva
 * mai: il ciclo cercava la prima fermata futura e, non trovandone, lasciava
 * la posizione a zero — cioe' proponeva di salire al capolinea di partenza
 * di ore prima, e il messaggio scritto apposta per quel caso non e' mai
 * comparso a nessuno.
 */
fun buildBusNavPlan(
    reader: BundleReader,
    tripIndex: Int,
    delaySec: Int,
    live: LiveTimes?,
): dev.antigravity.fluidtransit.data.nav.NavPlan? {
    val pattern = reader.tripPattern(tripIndex)
    val profile = reader.tripProfile(tripIndex)
    val dep0 = reader.tripDeparture0(tripIndex)
    val n = reader.patternStopCount(pattern)
    val now = Instant.now()
    val dayStart = TripProgress.serviceDayStart(reader, tripIndex, now.epochSecond)

    val next = TripProgress.nextPosition(live, tripIndex, n, now.epochSecond, delaySec) { pos ->
        dayStart + dep0 + reader.profileOffset(profile, pos)
    }
    if (next < 0) return null // corsa gia' finita
    // Si sale dalla fermata che il mezzo ha appena lasciato: e' li' che sta
    // la persona che dice "sono su questo bus".
    val boardPos = (next - 1).coerceAtLeast(0)
    if (boardPos >= n - 1) return null

    val route = reader.patternRoute(pattern)
    val destName = reader.patternDestination(pattern)
    return dev.antigravity.fluidtransit.data.nav.NavPlan(
        kind = "bus",
        destName = destName,
        legs = listOf(
            dev.antigravity.fluidtransit.data.nav.NavLeg.Ride(
                trip = tripIndex,
                pattern = pattern,
                route = route,
                boardPosition = boardPos,
                alightPosition = n - 1,
                dayStartEpoch = dayStart,
                dep0 = dep0,
                profile = profile,
                lineName = reader.routeShortName(route).ifEmpty { reader.routeLongName(route) },
                alightName = destName,
                stopNames = (boardPos until n).map {
                    reader.stopName(reader.patternStop(pattern, it))
                },
            ),
        ),
    )
}

/**
 * La forma del viaggio in una riga: si cammina, si sale, si cammina.
 *
 * La carta mostrava un'icona di pedone e poi tutte le pastiglie delle linee,
 * sempre in quest'ordine: l'icona non stava dove sta la camminata, e un
 * viaggio con due cambi si leggeva come "a piedi, e poi tre bus". Qui le
 * tappe sono nell'ordine vero e separate da un segno di passaggio, quindi la
 * striscia si legge come si legge il viaggio.
 */
@Composable
private fun StrisciaTappe(j: UiJourney) {
    // Con due cambi la striscia diventa lunga e i minuti delle camminate
    // sono la prima cosa che si puo' togliere: l'icona dice gia' che li' si
    // cammina, e quanto lo dice il dettaglio. Senza questa regola l'ultima
    // tappa finiva sotto la colonna della durata.
    val compatta = j.legs.count { it is UiLeg.Ride } >= 3
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (j.walkOnly) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.DirectionsWalk,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = "solo a piedi",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Row
        }
        for ((i, leg) in j.legs.withIndex()) {
            if (i > 0) {
                Text(
                    text = "›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (leg) {
                is UiLeg.Walk -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.DirectionsWalk,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                    // Zero minuti non si scrivono: sarebbe un'icona con
                    // accanto un numero che non vuol dire niente.
                    if (leg.minutes > 0 && !compatta) {
                        Text(
                            text = "${leg.minutes}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is UiLeg.Ride -> RoutePill(text = leg.line, colorRgb = leg.colorRgb)
            }
        }
    }
}
