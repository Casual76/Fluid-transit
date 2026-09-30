package dev.antigravity.fluidtransit.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidHairline
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.DepartureText
import dev.antigravity.fluidtransit.routing.ServiceDays
import dev.antigravity.fluidtransit.routing.Times
import dev.antigravity.fluidtransit.routing.TripProgress
import java.time.Instant
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info

/**
 * Tutto quello che la scheda linea sa dire, calcolato dal bundle in un
 * passaggio su Dispatchers.Default. I blocchi sono quelli decisi: testata,
 * elenco fermate per direzione, prima/ultima corsa e frequenza di oggi.
 * Gli orari accanto alle fermate sono quelli della prossima corsa, corretti
 * col ritardo live quando il feed la sta seguendo.
 */
class RouteInfo(
    val routeIndex: Int,
    val shortName: String,
    val longName: String,
    val agency: String,
    val category: String, // "Urbano" | "Extraurbano"
    val colorRgb: Int,
    val directions: List<Direction>,
    val firstDepToday: String?,
    val lastDepToday: String?,
    val headwayMinutes: Int?,
    /**
     * L'ultima corsa di IERI che deve ancora partire: "Stanotte: ultima corsa
     * alle 00:40". Null quando la coda della notte e' finita.
     *
     * Alle 00:30 la linea notturna non e' "senza corse oggi" e il suo bus
     * delle 00:40 non e' domani: e' il giorno di servizio di ieri che sta
     * finendo. La scheda guardava solo la data di oggi, quindi mostrava gli
     * orari della prima corsa del mattino e leggeva l'"ultima 00:40 di
     * notte" del giorno che comincia come "l'ultimo bus e' fra dieci minuti".
     */
    val tailNote: String? = null,
    /**
     * Oggi non e' dentro la validita' degli orari che abbiamo.
     *
     * Senza questo, gli orari scaduti si leggevano "Oggi questa linea non ha
     * corse" su ogni scheda linea, nello stesso istante in cui il tabellone
     * di una fermata diceva "Gli orari sono scaduti": una lista vuota che
     * passa per "niente servizio" mentre il guasto e' nostro.
     */
    val outsideValidity: Boolean = false,
) {
    class Direction(
        val headsign: String,
        val stops: List<StopRef>,
        val durationMinutes: Int,
        /** La corsa a cui si riferiscono gli orari accanto alle fermate. */
        val nextTripLive: Boolean = false,
        /**
         * Quante fermate hanno l'orario ripartito da noi.
         *
         * Il feed pubblica al minuto tondo, quindi due fermate vicine escono
         * con lo stesso minuto e il divario e' una nostra stima dalla
         * distanza vera. Va detto dove compare, come nella scheda della corsa.
         */
        val spreadStops: Int = 0,
    )

    class StopRef(
        val stopIndex: Int,
        val name: String,
        val idHashHex: String,
        val lat: Double,
        val lon: Double,
        /**
         * Quando ci passa la prossima corsa. Zero se oggi non ne resta
         * nessuna. Fino alla Fase 8 la scheda linea non mostrava NESSUN
         * orario: era la mappa della linea e basta, e per sapere quando
         * passa bisognava uscire e toccare la fermata.
         */
        val timeEpoch: Long = 0L,
        /** L'orario di tabella di quella corsa qui. Zero come sopra. */
        val scheduledEpoch: Long = 0L,
        /** Da dove viene la correzione: null = nessun dato live. */
        val certainty: dev.antigravity.fluidtransit.routing.Certainty? = null,
        /**
         * Il mezzo di quella corsa e' gia' passato di qui.
         *
         * Serve a spiegare una sequenza che altrimenti sembra rotta. Sul
         * telefono, linea 12: 15:13, 15:15, 15:16, 15:18, e poi 15:15. Non
         * era un errore di calcolo — la corsa viaggiava tre minuti in
         * anticipo, e le prime fermate mostrano l'orario di TABELLA perche'
         * un ritardo riferito a dove si trova il mezzo adesso non dice
         * niente su una fermata che ha alle spalle. Ma un elenco di orari
         * che torna indietro, senza dire perche', e' esattamente il genere
         * di cosa che fa pensare che l'app sbagli i conti.
         */
        val served: Boolean = false,
        /**
         * Da quanti secondi e' vecchia l'osservazione da cui viene il ritardo.
         *
         * Serve a dirlo a parole: un ritardo che si mostra ma e' di un quarto
         * d'ora fa deve dire "visto 15 min fa", come nel tabellone e nella
         * scheda della corsa. Zero quando e' fresco o quando non c'e' un dato.
         */
        val ageSeconds: Int = 0,
    )

    companion object {
        /**
         * Cinque minuti di tolleranza: una corsa appena partita e' ancora
         * quella che interessa a chi sta guardando le fermate piu' avanti.
         */
        private const val JUST_LEFT_SECONDS = 300L

        /** Un'ora e mezza prima e dopo adesso: la finestra da cui si ricava la frequenza. */
        private const val HEADWAY_WINDOW_SECONDS = 5400L

        fun build(
            reader: BundleReader,
            routeIndex: Int,
            now: Instant,
            live: dev.antigravity.fluidtransit.routing.LiveTimes? = null,
        ): RouteInfo {
            val patterns = reader.patternsOfRoute(routeIndex)
            val today = now.atZone(dev.antigravity.fluidtransit.routing.Ftb.ROME).toLocalDate()
            val nowEpoch = now.epochSecond

            // Le corse di ieri e di oggi, ognuna col suo giorno di servizio.
            //
            // Prima si scandiva solo la data di oggi: alle 00:30 il bus delle
            // "24:40" di ieri non esisteva, e le fermate mostravano gli orari
            // della prima corsa del mattino. Un giorno da 25 ore o da 23 resta
            // giusto perche' ogni corsa e' datata da `serviceDayStart` del suo
            // giorno, non dalla mezzanotte.
            val days = ServiceDays.at(reader, now)
            val trips = ServiceDays.tripsOfRoute(reader, routeIndex, days)

            // Per direzione, il pattern con piu' corse rappresenta la linea.
            val directions = (0..1).mapNotNull { dir ->
                val best = patterns
                    .filter { reader.patternDirection(it) == dir }
                    .maxByOrNull { reader.patternTripCount(it) }
                    ?: return@mapNotNull null
                val n = reader.patternStopCount(best)

                // La prossima corsa di questa direzione: e' quella a cui si
                // riferiscono gli orari mostrati accanto alle fermate. Vale
                // la prima partenza da adesso, di ieri o di oggi che sia.
                var nextTrip = -1
                var nextDep = Long.MAX_VALUE
                for (candidate in trips) {
                    if (candidate.patternIndex != best) continue
                    val dep = candidate.departureEpoch
                    if (dep >= nowEpoch - JUST_LEFT_SECONDS && dep < nextDep) {
                        nextDep = dep
                        nextTrip = candidate.tripIndex
                    }
                }
                val offsets = if (nextTrip >= 0) {
                    dev.antigravity.fluidtransit.routing.StopTimes
                        .offsets(reader, best, reader.tripProfile(nextTrip))
                } else {
                    null
                }
                // "Il feed sta seguendo questa corsa" e' una domanda che
                // LiveTimes sa rispondere da se', e che e' diversa da "ha un
                // ritardo": una corsa monitorata e puntuale ha ritardo zero.
                val tripLive = nextTrip >= 0 &&
                    (
                        live?.monitored(nextTrip, now.epochSecond) == true ||
                            live?.at(nextTrip, 0, n, now.epochSecond) != null
                        )

                val stops = (0 until n).map { i ->
                    val s = reader.patternStop(best, i)
                    // Come sopra: il filtro delle fermate gia' servite
                    // mancava solo qui e in Preferiti.
                    val grezzo = if (offsets != null) {
                        live?.at(nextTrip, i, n, now.epochSecond)
                    } else {
                        null
                    }
                    val at = grezzo?.takeIf {
                        it.certainty != dev.antigravity.fluidtransit.routing.Certainty.SERVED
                    }
                    StopRef(
                        scheduledEpoch = if (offsets != null) nextDep + offsets[i] else 0L,
                        timeEpoch = if (offsets != null) {
                            nextDep + offsets[i] + (at?.delaySeconds ?: 0)
                        } else {
                            0L
                        },
                        certainty = at?.certainty,
                        ageSeconds = at?.ageSeconds ?: 0,
                        stopIndex = s,
                        name = reader.stopName(s),
                        idHashHex = java.lang.Long.toHexString(reader.stopIdHash(s)),
                        lat = reader.stopLat(s),
                        lon = reader.stopLon(s),
                        // La stessa domanda che si fa la scheda di una corsa,
                        // con la stessa risposta: o il feed dice che la
                        // fermata e' alle spalle, o l'orario e' passato da
                        // piu' del minuto di cortesia.
                        served = offsets != null &&
                            dev.antigravity.fluidtransit.routing.TripProgress.served(
                                at = grezzo,
                                effectiveEpoch = nextDep + offsets[i] +
                                    (at?.delaySeconds ?: 0),
                                nowEpoch = now.epochSecond,
                            ),
                    )
                }
                // La durata: il profilo di una corsa mediana del pattern.
                val mid = reader.patternFirstTrip(best) + reader.patternTripCount(best) / 2
                val duration = reader.profileOffset(reader.tripProfile(mid), n - 1) / 60
                Direction(
                    headsign = reader.patternDestination(best),
                    spreadStops = if (nextTrip >= 0) {
                        dev.antigravity.fluidtransit.routing.StopTimes.twinCount(
                            IntArray(n) { reader.profileOffset(reader.tripProfile(nextTrip), it) },
                        )
                    } else {
                        0
                    },
                    stops = stops,
                    durationMinutes = duration,
                    nextTripLive = tripLive,
                )
            }

            // Prima/ultima corsa su tutta la linea; la frequenza su UNA sola
            // direzione — sommare i due sensi dimezzerebbe l'intervallo vero
            // (trovato sul device: "ogni 5 min" per una linea da 10).
            //
            // Prima e ultima sono del giorno di servizio di OGGI. La frequenza
            // si conta sugli istanti veri, non sugli scostamenti da oggi: alle
            // 00:30 le corse attorno a ora sono in parte "24:40" di ieri e in
            // parte "00:50" di oggi, e sommate come secondi dello stesso giorno
            // non tornerebbero.
            var first = Int.MAX_VALUE
            var last = Int.MIN_VALUE
            val depsAroundNow = ArrayList<Long>()
            val headwayDirection = patterns.firstOrNull()?.let { reader.patternDirection(it) } ?: 0
            for (t in trips) {
                if (t.day.isToday) {
                    if (t.departureSeconds < first) first = t.departureSeconds
                    if (t.departureSeconds > last) last = t.departureSeconds
                }
                if (t.direction == headwayDirection &&
                    t.departureEpoch in (nowEpoch - HEADWAY_WINDOW_SECONDS)..(nowEpoch + HEADWAY_WINDOW_SECONDS)
                ) {
                    depsAroundNow.add(t.departureEpoch)
                }
            }
            val headway = if (depsAroundNow.size >= 3) {
                depsAroundNow.sort()
                val gaps = (1 until depsAroundNow.size)
                    .map { depsAroundNow[it] - depsAroundNow[it - 1] }
                    .filter { it > 0 }
                    .sorted()
                if (gaps.isEmpty()) null else (gaps[gaps.size / 2] / 60).toInt().coerceAtLeast(1)
            } else {
                null
            }

            // La coda della notte: l'ultima partenza di ieri che non e'
            // ancora passata. Con la stessa tolleranza delle fermate, cosi'
            // "Stanotte: ultima corsa alle 00:40" e la prima riga della lista
            // parlano della stessa corsa.
            val tail = ServiceDays.tail(trips, nowEpoch - JUST_LEFT_SECONDS)

            // L'orologio di una corsa sta in `Times`, dove stava gia': queste
            // parole sono nate qui e adesso le usano anche gli strumenti
            // dell'assistente.
            fun fmt(sec: Int): String = Times.serviceTime(sec)

            return RouteInfo(
                routeIndex = routeIndex,
                shortName = reader.routeShortName(routeIndex)
                    .ifEmpty { reader.routeLongName(routeIndex) },
                longName = reader.routeLongName(routeIndex),
                agency = reader.routeAgency(routeIndex),
                category = if (reader.routeAgency(routeIndex).contains("extraurbano", ignoreCase = true)) {
                    "Extraurbano"
                } else {
                    "Urbano"
                },
                colorRgb = reader.routeDisplayColor(routeIndex),
                directions = directions,
                firstDepToday = if (first == Int.MAX_VALUE) null else fmt(first),
                lastDepToday = if (last == Int.MIN_VALUE) null else fmt(last),
                headwayMinutes = headway,
                tailNote = tail?.let { Times.serviceTail(it) },
                // Lo stesso confronto del tabellone di una fermata: cosi' la
                // scheda linea e la scheda fermata dicono la stessa cosa.
                outsideValidity = !ServiceDays.covers(reader, today),
            )
        }
    }
}

/**
 * Cosa fa questa linea adesso, in poche righe sotto la testata.
 *
 * Tre casi che prima erano uno solo. La coda della notte va per prima e per
 * conto suo: alle 00:30 la corsa che sta per passare e' di ieri, e "ultima
 * 00:40 di notte" nella riga di oggi la faceva leggere come "l'ultimo bus e'
 * fra dieci minuti". E "nessuna corsa" non e' una sola cosa: con gli orari
 * scaduti la linea non ha "zero corse", siamo noi a non sapere quali siano,
 * ed e' la stessa frase che il tabellone di una fermata dice nello stesso
 * istante.
 */
internal fun RouteInfo.headerText(dir: RouteInfo.Direction?): String = buildString {
    val stanotte = tailNote
    if (stanotte != null) {
        append(stanotte)
        append('\n')
    }
    val prima = firstDepToday
    val ultima = lastDepToday
    val ritmo = headwayMinutes
    if (prima != null && ultima != null) {
        append("Oggi: prima $prima, ultima $ultima")
        if (ritmo != null) {
            append(" · circa ogni ")
            append(Times.durationLabel(ritmo * 60))
            append(" a quest'ora")
        }
    } else if (outsideValidity) {
        val scaduti = DepartureText.empty(DepartureText.Trouble.ORARI_SCADUTI)
        append(scaduti.title)
        append(". ")
        append(scaduti.detail)
    } else {
        append("Oggi questa linea non ha corse.")
    }
    if (dir?.stops?.any { it.timeEpoch > 0 } == true) {
        append("\nGli orari qui sotto sono della prossima corsa, ")
        append(if (dir.nextTripLive) "dal bus." else "da tabella.")
        if (dir.spreadStops > 0) {
            append(
                " Fra fermate vicinissime il divario lo stimiamo " +
                    "noi: il feed pubblica lo stesso minuto per tutte.",
            )
        }
    }
}

/** La pillola della linea, identica ovunque. */
@Composable
fun RoutePill(text: String, colorRgb: Int, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        maxLines = 1,
        modifier = modifier
            .widthIn(min = 44.dp)
            .background(
                color = Color(0xFF000000 or colorRgb.toLong()),
                shape = ContinuousCornerShape(FluidRadius.Small),
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * Il pop-up ridotto che prende il posto della tab bar: STESSA altezza della
 * capsula di navigazione (cosi' il congedo si legge come la tab bar che
 * ritorna), pillola, capolinea, numero fermate e durata. Un tocco o un
 * trascinamento verso l'alto lo espandono; il resto dei gesti vive
 * nell'host.
 */
@Composable
fun RouteMiniContent(info: RouteInfo, direction: Int) {
    val dir = info.directions.getOrNull(direction) ?: info.directions.firstOrNull() ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(dev.antigravity.fluidengine.ui.fluid.FluidTabBarDefaults.Height)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RoutePill(info.shortName, info.colorRgb)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "→ ${dir.headsign}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // Col vocabolario: "1 fermate" e "~250 min" uscivano da qui.
                text = dev.antigravity.fluidtransit.routing.Words.count(dir.stops.size, "fermata", "fermate") +
                    " · ~" + dev.antigravity.fluidtransit.routing.Times.durationLabel(dir.durationMinutes * 60),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * La scheda linea espansa, coi blocchi decisi: testata, prima/ultima corsa
 * e frequenza di oggi, direzioni, elenco fermate col salto sulla mappa.
 */
@Composable
fun RouteFullContent(
    info: RouteInfo,
    direction: Int,
    onDirectionChange: (Int) -> Unit,
    onStopTap: (RouteInfo.StopRef) -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onDismiss: (() -> Unit)? = null,
    /**
     * Gli avvisi in corso su QUESTA linea, gia' filtrati.
     *
     * L'app li aveva e non li diceva dove servono: aprendo la 12 mentre e'
     * deviata dal 20 agosto, il pannello raccontava orari e fermate come se
     * niente fosse. Un avviso di servizio e' l'unica cosa che puo' rendere
     * sbagliato tutto il resto di quel pannello.
     */
    alerts: List<String>? = emptyList(),
    onOpenAlerts: (() -> Unit)? = null,
) {
    val dir = info.directions.getOrNull(direction) ?: info.directions.firstOrNull() ?: return

    // Il battito dell'app, uno solo.
    //
    // Gli orari delle fermate qui sotto sono colorati da quanto ci si puo'
    // fidare, e quel giudizio guarda l'ora: una fermata che il mezzo ha gia'
    // passato non si colora come una che deve ancora venire. La scheda
    // leggeva l'orologio dentro la propria composizione, quindi il colore
    // restava quello del momento in cui si era aperta finche' qualcos'altro
    // non la faceva ricomporre. La scheda della corsa sta sul battito
    // condiviso da settimane; questa era rimasta fuori.
    // Il flusso si ricorda: `ticks()` ne fabbrica uno nuovo a ogni chiamata,
    // e un flusso nuovo e' una chiave nuova — cioe' il collettore si spegneva
    // e si riaccendeva a ogni ricomposizione, che qui e' una al secondo.
    val battito = androidx.compose.runtime.remember {
        dev.antigravity.fluidtransit.data.time.UiClock.ticks()
    }
    val nowSec by battito
        .collectAsStateWithLifecycle(initialValue = System.currentTimeMillis() / 1000)

    Column(modifier = Modifier.fillMaxWidth()) {
        // --- testata -----------------------------------------------------
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
                    text = dir.headsign,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${info.category} · ${info.agency}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // La stella dei preferiti, come nella scheda fermata.
            androidx.compose.material3.Icon(
                imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = if (isFavorite) "Togli dai preferiti" else "Salva nei preferiti",
                tint = if (isFavorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(38.dp)
                    .clickable(
                        interactionSource = androidx.compose.runtime.remember {
                            androidx.compose.foundation.interaction.MutableInteractionSource()
                        },
                        indication = null,
                        onClick = onToggleFavorite,
                    )
                    .padding(7.dp),
            )
            // La X, come nelle schede fermata, luogo e viaggi.
            //
            // Qui non c'era: si chiudeva trascinando giu' o col tasto
            // Indietro, cioe' con due gesti che nessuna delle altre schede
            // chiede. Un pannello che si congeda in un modo suo e' una delle
            // cose che fanno dire "si comporta in modo diverso ogni volta",
            // e costa una icona.
            if (onDismiss != null) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Chiudi",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
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

        // --- gli avvisi di questa linea ----------------------------------
        //
        // In cima, sotto la testata: se questa linea oggi e' deviata o
        // sostituita, tutto quello che c'e' sotto — orari, fermate, minuti —
        // puo' essere sbagliato, e saperlo dopo non serve a niente.
        dev.antigravity.fluidtransit.ui.common.AlertRows(alerts, onOpenAlerts, tail = "su questa linea")

        // --- oggi: prima/ultima corsa e frequenza ------------------------
        //
        // Le parole stanno in `headerText`: sono l'unica cosa della scheda che
        // dice cosa fa la linea in questo momento, e a mezzanotte e mezza o a
        // orari scaduti la risposta cambia, quindi si prova senza un
        // dispositivo.
        Text(
            text = info.headerText(dir),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        // --- direzione ---------------------------------------------------
        if (info.directions.size > 1) {
            FluidSegmentedControl(
                options = info.directions.indices.toList(),
                selected = direction.coerceIn(info.directions.indices),
                onSelect = onDirectionChange,
                label = { i ->
                    info.directions[i].headsign.let {
                        if (it.length > 18) it.take(17) + "…" else it
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 6.dp),
            )
        }

        // --- fermate, col salto sulla mappa ------------------------------
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = panelListMax(380.dp))
                .padding(horizontal = 8.dp),
        ) {
            items(dir.stops.size) { i ->
                val stop = dir.stops[i]
                val piena = Color(0xFF000000 or info.colorRgb.toLong())
                // Il tratto gia' percorso si spegne: il filo, il pallino e
                // le parole. Cosi' l'elenco si legge in due parti — dove il
                // mezzo e' stato e dove deve ancora arrivare — e gli orari
                // che "tornano indietro" stanno tutti nella prima.
                val tinta = if (stop.served) piena.copy(alpha = 0.30f) else piena
                val primo = i == 0
                val ultimo = i == dir.stops.size - 1
                // Le parole di questa fermata, una volta sola: le stesse della
                // scheda fermata e della scheda corsa, e le usano l'orario, la
                // riga sotto il nome e il lettore di schermo.
                val frase = if (stop.timeEpoch > 0) {
                    DepartureText.alongTrip(
                        scheduledEpoch = stop.scheduledEpoch,
                        delaySeconds = (stop.timeEpoch - stop.scheduledEpoch)
                            .toInt().takeIf { stop.certainty != null },
                        certainty = stop.certainty,
                        nowEpoch = nowSec,
                        ageSeconds = stop.ageSeconds,
                    )
                } else {
                    null
                }
                // Cosa un lettore di schermo deve dire oltre all'orario.
                //
                // Il tratto gia' percorso si distingueva solo perche' si
                // spegneva, e il ritardo solo dal colore dell'orario: chi non
                // vede sentiva una lista di orari uguali, senza sapere quali
                // il bus se li fosse lasciati dietro ne' che avesse un quarto
                // d'ora di ritardo.
                val nota = TripProgress.spokenNote(stop.served, frase)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                        // Un pulsante che dice dove porta: senza ruolo e
                        // senza etichetta il lettore di schermo diceva solo
                        // "tocca due volte per attivare", e non che si apre
                        // la fermata.
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "Apri la fermata ${stop.name}",
                            onClick = { onStopTap(stop) },
                        )
                        .semantics { if (nota != null) stateDescription = nota }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Il percorso come un filo, come nella scheda di una
                    // corsa: capolinea in testa e in coda col pallino piu'
                    // grande, le fermate in mezzo sul filo del colore della
                    // linea.
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .width(14.dp)
                            .fillMaxHeight()
                            .drawBehind {
                                val x = size.width / 2f
                                val filo = tinta.copy(alpha = 0.35f)
                                val spessore = 3.dp.toPx()
                                if (!primo) {
                                    drawLine(
                                        color = filo,
                                        start = Offset(x, 0f),
                                        end = Offset(x, size.height / 2f),
                                        strokeWidth = spessore,
                                    )
                                }
                                if (!ultimo) {
                                    drawLine(
                                        color = filo,
                                        start = Offset(x, size.height / 2f),
                                        end = Offset(x, size.height),
                                        strokeWidth = spessore,
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .size(if (primo || ultimo) 14.dp else 10.dp)
                                .background(color = tinta, shape = CircleShape),
                        )
                    }
                    Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(
                            text = stop.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (stop.served) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Il ritardo che conta si legge anche, non solo si
                        // vede: l'orario accanto e' gia' quello col ritardo
                        // dentro, e il suo colore da solo non lo dice a chi
                        // distingue male l'ambra dal verde. Come nella scheda
                        // della corsa, ma solo quando c'e' qualcosa da dire:
                        // "in orario" su trenta righe di fila e' rumore.
                        //
                        // Le stesse parole le ha gia' `nota` per il lettore
                        // di schermo: questa riga le nasconde, altrimenti si
                        // sentirebbero due volte.
                        if (!stop.served && nota != null && frase != null) {
                            Text(
                                text = frase.support,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clearAndSetSemantics { },
                            )
                        }
                    }
                    if (frase != null) {
                        // L'orario della prossima corsa, col tono che dice da
                        // dove viene: prima era verde per tutta la direzione
                        // appena una corsa era seguita, comprese le fermate
                        // che il feed non copre.
                        Text(
                            text = dev.antigravity.fluidtransit.routing.Times.hhmm(stop.timeEpoch),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (stop.served) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            } else {
                                dev.antigravity.fluidtransit.ui.common.toneColor(frase.tone)
                            },
                        )
                    } else if (i == 0 || i == dir.stops.size - 1) {
                        Text(
                            text = if (i == 0) "Partenza" else "Capolinea",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
