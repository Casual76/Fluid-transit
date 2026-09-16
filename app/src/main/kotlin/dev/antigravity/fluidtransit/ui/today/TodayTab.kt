package dev.antigravity.fluidtransit.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidSectionTitle
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState
import dev.antigravity.fluidtransit.data.routines.RoutineScheduler
import dev.antigravity.fluidtransit.data.routines.Routines
import dev.antigravity.fluidtransit.ui.map.MapIntent
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import dev.antigravity.fluidtransit.routing.DepartureText
import dev.antigravity.fluidtransit.routing.Ftb
import dev.antigravity.fluidtransit.routing.Times
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * La scheda Oggi, com'e' stata decisa: le prossime partenze (live) dalle
 * fermate preferite, le routine di oggi col loro stato, gli avvisi che
 * toccano le tue linee. E' "la mia giornata coi bus", non un'altra mappa.
 */
@Composable
fun TodayTab(
    app: FluidTransitApp,
    onOpenOnMap: (MapIntent) -> Unit,
    onOpenAlerts: () -> Unit = {},
    onOpenDataStatus: () -> Unit = {},
) {
    // "Perche' questo numero", anche qui.
    //
    // Sulla mappa la provenienza si tocca e si apre; in Oggi le stesse
    // parole — "dal bus", "stimato", "orario da tabella" — erano un vicolo
    // cieco. Lo stesso numero spiegabile da una parte e non dall'altra e'
    // precisamente la sensazione che l'app si comporti in modo diverso a
    // seconda di dove la guardi.
    //
    // Qui la riga e' una `FluidListRow`, che non ha un testo da sottolineare:
    // l'appiglio e' il menu della tenuta premuta, che e' il modo in cui
    // l'engine offre le azioni di una riga e che in questa scheda c'e' gia'.
    var whyRow by remember {
        mutableStateOf<dev.antigravity.fluidtransit.routing.NextDeparture?>(null)
    }
    var whyAt by remember { mutableStateOf(0L) }
    val bundleState by app.bundleManager.state.collectAsStateWithLifecycle()
    val ready = bundleState as? BundleState.Ready
    val favVersion by app.favorites.version.collectAsStateWithLifecycle()
    val routinesVersion by app.routines.version.collectAsStateWithLifecycle()
    val routines = remember(routinesVersion) { app.routines.list() }
    val favStops = remember(favVersion) { app.favorites.stops() }
    val favRoutes = remember(favVersion) { app.favorites.routes() }

    // Le fermate stellate, tradotte in indici del bundle. Gli hash sono
    // l'identita' stabile fra un bundle e l'altro; gli indici no, e infatti
    // si ricavano ogni volta che il bundle cambia.
    val stopIndexes = remember(favVersion, ready?.buildId) {
        val reader = ready?.reader ?: return@remember emptyList()
        favStops.mapNotNull { fav ->
            fav.idHashHex.toULongOrNull(16)?.toLong()
                ?.let { reader.findStopByIdHash(it) }
                ?.takeIf { it >= 0 }
        }
    }

    // Il tabellone e' quello di tutta l'app: stesso calcolo, stesso battito,
    // stessi numeri della scheda fermata e dei Preferiti. Prima qui c'era un
    // calcolo suo con un battito da venti secondi, e la stessa fermata poteva
    // dire un minuto diverso da una scheda all'altra.
    val board by remember(stopIndexes) {
        app.departureBoards.merged(stopIndexes, limit = 8)
    }.collectAsStateWithLifecycle()
    val departures = board.rows

    // Gli avvisi delle TUE linee (piu' quelli di rete, che riguardano tutti).
    //
    // "Tue" comprende le linee che passano dalle tue fermate, non solo quelle
    // con la stella: la stella sulle linee quasi nessuno la mette, e senza
    // questo la scheda diceva "nessun avviso sulle tue linee" con un avviso
    // in corso proprio sulla linea della fermata sotto casa.
    // Tre stati e non uno: sto leggendo, non ci sono riuscito, ecco la lista.
    //
    // Prima il fallimento diventava una lista vuota, e qui una lista vuota si
    // legge "Nessun avviso sulle tue linee" — una frase rassicurante. Con il
    // telefono senza rete, provato in aereo sull'emulatore, la scheda diceva
    // esattamente questo: un'affermazione sul mondo mentre il guasto era
    // nostro. La schermata degli avvisi l'aveva gia' imparato; questa, che e'
    // la piu' letta, no.
    // "Le tue linee" serve due volte: a filtrare gli avvisi e a metterle
    // davanti nella riga di ognuno. Si calcola una volta sola.
    val mine = remember(favVersion, stopIndexes, ready?.buildId) {
        dev.antigravity.fluidtransit.data.favorites.MyRoutes.hashes(
            reader = ready?.reader,
            starredRoutes = favRoutes.mapNotNull { it.idHashHex.toULongOrNull(16)?.toLong() }
                .toSet(),
            starredStops = stopIndexes,
        )
    }
    val esitoAvvisi by produceState<
        Result<List<dev.antigravity.fluidtransit.data.rt.GtfsRtLite.RtAlert>>?,
        >(null, favVersion, stopIndexes, ready?.buildId) {
        val all = app.realtime.fetchAlertsOrNull()
        if (all == null) {
            value = Result.failure(java.io.IOException("avvisi non scaricati"))
            return@produceState
        }
        val now = Instant.now().epochSecond
        // Anche quelli di domani.
        //
        // Un avviso attivo lo scopri quando ti tocca; uno sciopero annunciato
        // per domani serve oggi, ed e' questa la scheda che si guarda per
        // sapere com'e' la giornata. Due giorni di orizzonte, non di piu':
        // "Oggi" resta oggi. Il periodo lo dice gia' ogni riga, quindi non si
        // confonde un avviso in corso con uno che comincia.
        val orizzonte = now + 2 * 24 * 3600
        value = Result.success(
            all
                .filter { a -> a.routeHashes.isEmpty() || a.routeHashes.any { it in mine } }
                .filter { a ->
                    val giaFinito = a.endEpoch != 0L && a.endEpoch < now
                    val troppoInLa = a.startEpoch > orizzonte
                    !giaFinito && !troppoInLa
                }
                // Prima quelli in corso: chi apre la scheda vuole sapere cosa
                // sta succedendo adesso, e poi cosa succedera'.
                .sortedBy { a -> if (a.startEpoch == 0L || a.startEpoch <= now) 0 else 1 },
        )
    }
    val alerts = esitoAvvisi?.getOrNull().orEmpty()

    val today = LocalDate.now(Ftb.ROME).dayOfWeek.value

    // L'orologio comune: si muove col battito delle schede, cosi' il
    // consiglio di una routine smette di comparire quando e' ora, non alla
    // prossima ricomposizione che capita.
    val battito = remember { dev.antigravity.fluidtransit.data.time.UiClock.ticks() }
    val adesso by battito
        .collectAsStateWithLifecycle(initialValue = Instant.now().epochSecond)

    // Tira giu' per aggiornare.
    //
    // I numeri si rinfrescano da soli ogni trenta secondi, quindi questo
    // gesto quasi non cambia niente — ed e' proprio per questo che serve. Una
    // lista di orari che non si lascia tirare sembra ferma, e uno resta li' a
    // chiedersi se stia guardando dati vivi. Qui la risposta e' un gesto.
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val refresh: () -> Unit = {
        if (!refreshing) {
            refreshing = true
            scope.launch {
                runCatching { app.realtime.refreshVehicles() }
                runCatching { app.realtime.refreshDelays() }
                runCatching { app.realtime.refreshPredictions() }
                app.bundleManager.refreshOnForeground()
                // Mezzo secondo di cortesia: un aggiornamento che sparisce
                // prima di essere visto non e' una risposta.
                kotlinx.coroutines.delay(500)
                refreshing = false
            }
        }
    }

    FluidScreen(
        title = "Oggi",
        isRefreshing = refreshing,
        onRefresh = refresh,
    ) {
        // --- le partenze dai preferiti ---------------------------------
        if (favStops.isEmpty() && routines.isEmpty()) {
            item {
                FluidEmptyState(
                    title = "Oggi si costruisce dai preferiti",
                    detail = "Stella una fermata o crea una routine da un viaggio: " +
                        "questa scheda diventa la tua giornata coi bus.",
                )
            }
        }

        val reader = ready?.reader
        if (favStops.isNotEmpty()) {
            // Con una fermata sola il nome sta nel titolo e non su ogni
            // riga: ripeterlo otto volte in maiuscolo copriva la linea e la
            // destinazione, che sono l'informazione. Con piu' di una fermata
            // resta riga per riga, perche' li' dice quale.
            // Il nome viene dal bundle, non dalla stella: quello salvato
            // accanto alla stella e' un ripiego per quando gli orari non ci
            // sono ancora, e puo' essere piu' vecchio.
            //
            // E si chiede al lettore, non al tabellone: quando non passa
            // niente il tabellone e' vuoto e non ha un nome da dare —
            // succede ogni notte, ed era il momento in cui il nome vecchio
            // tornava fuori.
            val unicaFermata = if (favStops.size == 1) {
                stopIndexes.firstOrNull()?.let { reader?.stopName(it) }?.ifEmpty { null }
                    ?: departures.firstOrNull()?.stopName?.ifEmpty { null }
                    ?: favStops.first().name
            } else {
                null
            }
            // Il prossimo passaggio, grande e del colore della sua linea.
            //
            // Oggi era una lista e basta: otto righe tutte uguali, e quella
            // che conta — la prima — si doveva cercare. Questa e' la scheda
            // che si apre per prima, e la domanda che ci si fa aprendola e'
            // una sola: "quanto manca al prossimo?".
            //
            // La carta satura e' il vocabolario dell'engine per l'elemento
            // che sta da solo nella pagina, e prende il colore della linea:
            // il 6 arancione si riconosce prima di aver letto una parola.
            // Il ritardo, qui, si dice a parole — su una superficie colorata
            // il verde e l'ambra non si leggerebbero.
            val prossimo = departures.firstOrNull()
            if (prossimo != null && board.computedAtEpoch > 0L) {
                item {
                    ProssimoPassaggio(
                        row = prossimo,
                        nowEpoch = board.computedAtEpoch,
                        mostraFermata = unicaFermata == null,
                        onClick = {
                            onOpenOnMap(
                                MapIntent.Stop(
                                    java.lang.Long.toHexString(
                                        reader?.stopIdHash(prossimo.stopIndex) ?: 0L,
                                    ),
                                    prossimo.stopName,
                                ),
                            )
                        },
                    )
                }
            }
            item {
                FluidSectionTitle(
                    // "Poi" in maiuscolo diventa "POI", che in mezzo a
                    // un'app di mappe si legge come l'acronimo inglese dei
                    // punti di interesse.
                    eyebrow = if (prossimo != null) "Dopo" else "Adesso",
                    title = unicaFermata ?: "Dalle tue fermate",
                )
            }
            item {
                FluidListGroup {
                    if (reader != null && stopIndexes.isEmpty()) {
                        // Le stelle ci sono ma nessuna trova una fermata
                        // negli orari di oggi: capita dopo un cambio
                        // d'orario che rinomina o toglie una fermata. Senza
                        // questa riga la scheda diceva che dalle tue fermate
                        // non parte niente, cioe' dava la colpa ai bus.
                        val parole = dev.antigravity.fluidtransit.routing.DepartureText.empty(
                            dev.antigravity.fluidtransit.routing.DepartureText
                                .Trouble.FERMATA_SCONOSCIUTA,
                            oneStop = favStops.size == 1,
                        )
                        FluidListRow(title = parole.title, subtitle = parole.detail)
                    } else if (board.computedAtEpoch == 0L) {
                        // Zero vuol dire che il primo calcolo non c'e' ancora
                        // stato: e' diverso da "non passa niente", e dirlo
                        // sbagliato e' il difetto che i Preferiti avevano.
                        FluidListRow(title = "Un attimo…", subtitle = "Leggo gli orari")
                    } else if (departures.isEmpty()) {
                        // Le stesse parole della scheda fermata, dallo stesso
                        // posto: qui erano una copia quasi uguale, e "quasi"
                        // e' il modo in cui due schermate finiscono per
                        // spiegare la stessa cosa in due modi.
                        val parole = dev.antigravity.fluidtransit.routing.DepartureText.empty(
                            dev.antigravity.fluidtransit.routing.DepartureText.trouble(board),
                            oneStop = unicaFermata != null,
                        )
                        FluidListRow(title = parole.title, subtitle = parole.detail)
                    } else {
                        // La riga delle partenze e' quella di tutta l'app.
                        //
                        // Qui erano righe di lista generiche: la linea era un
                        // pallino colorato senza numero, il numero finiva
                        // dentro il titolo insieme alla destinazione, e i
                        // minuti — la sola cosa che si cerca — stavano in
                        // fondo, piccoli e grigi come il resto. La stessa
                        // partenza, nel pannello di una fermata, ha la
                        // pastiglia della linea a sinistra e i minuti grandi
                        // a destra. Due grammatiche visive per lo stesso
                        // dato, e questa era quella della schermata che si
                        // apre per prima.
                        // La prima e' gia' nella carta qui sopra.
                        for ((i, d) in departures.drop(1).withIndex()) {
                            if (i > 0) {
                                dev.antigravity.fluidengine.ui.theme.FluidListDivider()
                            }
                            dev.antigravity.fluidtransit.ui.common.DepartureRowUi(
                                row = d,
                                nowEpoch = board.computedAtEpoch,
                                stopLabel = if (unicaFermata == null) d.stopName else null,
                                onSupportTap = {
                                    whyAt = board.computedAtEpoch
                                    whyRow = d
                                },
                                modifier = Modifier
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        role = androidx.compose.ui.semantics.Role.Button,
                                        onClickLabel = "Apri la fermata ${d.stopName}",
                                        onClick = {
                                            onOpenOnMap(
                                                MapIntent.Stop(
                                                    java.lang.Long.toHexString(
                                                        reader?.stopIdHash(d.stopIndex) ?: 0L,
                                                    ),
                                                    d.stopName,
                                                ),
                                            )
                                        },
                                    )
                                    .padding(horizontal = 16.dp),
                            )
                        }
                    }
                }
            }
        }

        // --- le routine --------------------------------------------------
        if (routines.isNotEmpty()) {
            item { FluidSectionTitle(eyebrow = "Routine", title = "Le tue routine") }
            item {
                FluidListGroup {
                    for (r in routines) {
                        val isToday = today in r.days
                        // Finche' non e' passata l'ora di uscire: prima
                        // bastava che il consiglio fosse di oggi, e alle
                        // dieci del mattino la riga diceva ancora "Esci alle
                        // 07:25".
                        val adviceToday = Routines.adviceStillGood(r, adesso)
                        FluidListRow(
                            title = r.label.ifEmpty { "→ ${r.toName}" },
                            subtitle = buildString {
                                append(daysShort(r.days))
                                append(" · ")
                                append(if (r.anchor == "arrive") "entro le " else "parti alle ")
                                append("%02d:%02d".format(r.anchorMinutes / 60, r.anchorMinutes % 60))
                                if (isToday && adviceToday) {
                                    append("\n")
                                    append(r.lastAdviceText)
                                }
                            },
                            meta = when {
                                !r.enabled -> "in pausa"
                                isToday -> "oggi"
                                else -> "attiva"
                            },
                            // Il tocco apre IL VIAGGIO di questa routine.
                            //
                            // Prima metteva in pausa la routine, e non lo
                            // diceva da nessuna parte: chi toccava la riga
                            // per vedere il viaggio si spegneva la sveglia
                            // senza accorgersene, e se ne accorgeva il
                            // giorno dopo alla fermata. La pausa e'
                            // un'azione, e le azioni di questa app stanno
                            // nel menu della tenuta premuta, dove sta gia'
                            // "Elimina".
                            onClick = {
                                onOpenOnMap(
                                    MapIntent.Journey(
                                        fromLat = r.fromLat,
                                        fromLon = r.fromLon,
                                        toLat = r.toLat,
                                        toLon = r.toLon,
                                        toName = r.toName.ifEmpty { r.label.ifEmpty { "Arrivo" } },
                                    ),
                                )
                            },
                            contextActions = {
                                listOf(
                                    FluidContextAction(
                                        label = if (r.enabled) {
                                            "Metti in pausa"
                                        } else {
                                            "Riattiva"
                                        },
                                        onClick = {
                                            app.routines.update(r.id) {
                                                Routines.Routine(
                                                    it.id, it.label, it.fromLat, it.fromLon,
                                                    it.toLat, it.toLon, it.toName, it.days,
                                                    it.anchor, it.anchorMinutes, !it.enabled,
                                                    it.lastAdviceEpoch, it.lastAdviceText,
                                                )
                                            }
                                            val updated = app.routines.list()
                                                .first { it.id == r.id }
                                            if (updated.enabled) {
                                                RoutineScheduler.scheduleNextCompute(app, updated)
                                            } else {
                                                RoutineScheduler.cancel(app, r.id)
                                            }
                                        },
                                    ),
                                    FluidContextAction(
                                        label = "Elimina la routine",
                                        destructive = true,
                                        onClick = {
                                            RoutineScheduler.cancel(app, r.id)
                                            app.routines.remove(r.id)
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        } else if (favStops.isNotEmpty()) {
            item {
                FluidListGroup {
                    FluidListRow(
                        title = "Nessuna routine, per ora",
                        subtitle = "Nel dettaglio di un viaggio trovi \"Fanne una routine\": " +
                            "l'app calcolera' da sola quando devi uscire",
                    )
                }
            }
        }

        // --- gli avvisi --------------------------------------------------
        //
        // Qui ne stanno tre, con la porta per gli altri: sei avvisi tagliati a
        // 220 caratteri in fondo alla giornata erano tanto testo e poca
        // informazione, e non c'era modo di leggerne uno per intero.
        // La porta per gli avvisi c'e' SEMPRE, anche quando non ce n'e'
        // nessuno sulle tue linee.
        //
        // Prima la sezione compariva solo con almeno un avviso, e la
        // schermata degli avvisi — l'unica che risponde a "c'e' uno
        // sciopero?" — non si poteva raggiungere da nessuna parte: ne' da
        // Impostazioni, ne' dalla mappa. Restava un indirizzo interno che
        // sapeva solo chi aveva scritto l'app. E il filtro qui e' sulle TUE
        // linee, mentre la schermata li mostra tutti: il caso "niente sulle
        // tue, ma qualcosa in giro" e' esattamente quello in cui la porta
        // serve di piu'.
        item { FluidSectionTitle(eyebrow = "Avvisi", title = "Sulle tue linee") }
        if (esitoAvvisi == null) {
            item { dev.antigravity.fluidengine.ui.fluid.FluidLoadingBlock() }
        } else if (esitoAvvisi?.isFailure == true) {
            item {
                FluidListGroup {
                    FluidListRow(
                        title = "Non sappiamo se ci sono avvisi",
                        subtitle = "Non siamo riusciti a scaricarli: non e' la stessa cosa " +
                            "che non ce ne siano. Apri per riprovare",
                        onClick = onOpenAlerts,
                    )
                }
            }
        } else if (alerts.isEmpty()) {
            item {
                FluidListGroup {
                    FluidListRow(
                        title = "Nessun avviso sulle tue linee",
                        subtitle = "Apri per vedere quelli di tutta la Toscana",
                        onClick = onOpenAlerts,
                    )
                }
            }
        }
        if (alerts.isNotEmpty()) {
            item {
                FluidListGroup {
                    for (a in alerts.take(3)) {
                        // Quali linee tocca, sopra il titolo.
                        //
                        // Un avviso senza le linee e' una notizia su qualcun
                        // altro: la scheda della fermata lo dice da settimane
                        // ("12 - Deviazione..."), qui no. Sono le TUE linee a
                        // venire per prime, per la stessa ragione per cui lo
                        // fanno nella schermata degli avvisi.
                        val linee = remember(a, reader, mine) {
                            val r = reader ?: return@remember ""
                            a.routeHashes
                                .sortedByDescending { it in mine }
                                .mapNotNull { h ->
                                    val idx = r.findRouteByIdHash(h)
                                    if (idx < 0) {
                                        null
                                    } else {
                                        r.routeShortName(idx).ifEmpty { r.routeLongName(idx) }
                                    }
                                }
                                .distinct()
                                .take(6)
                                .joinToString(" · ")
                        }
                        FluidListRow(
                            eyebrow = linee.ifEmpty { null },
                            title = a.header.ifEmpty { "Avviso di servizio" },
                            // Il periodo se c'e'; altrimenti l'inizio del
                            // testo, ripulito dalla riga di hashtag con cui
                            // il gestore comincia i suoi messaggi: le prime
                            // undici lettere che si leggevano erano
                            // "#at_Firenze".
                            subtitle = dev.antigravity.fluidtransit.routing.AlertText
                                .period(a.startEpoch, a.endEpoch, Instant.now().epochSecond)
                                ?: dev.antigravity.fluidtransit.routing.AlertText
                                    .body(a.description).take(120),
                            onClick = onOpenAlerts,
                        )
                    }
                    FluidListRow(
                        title = if (alerts.size > 3) {
                            "Tutti gli avvisi (${alerts.size})"
                        } else {
                            "Apri gli avvisi"
                        },
                        subtitle = "Col periodo e le linee toccate",
                        onClick = onOpenAlerts,
                    )
                }
            }
        }
    }

    // Il pop-up sta fuori dalla lista e alla radice della scheda: si apre
    // sopra tutto, e senza un rettangolo da cui nascere — la tenuta premuta
    // ha gia' il suo menu, e farlo partire da li' sarebbe una seconda
    // animazione sopra la prima.
    dev.antigravity.fluidtransit.ui.common.WhyThisNumberPortal(
        row = whyRow,
        nowEpoch = whyAt,
        origin = { null },
        onDismiss = { whyRow = null },
        onOpenDataStatus = onOpenDataStatus,
    )
}

private fun daysShort(days: Set<Int>): String {
    if (days.size == 7) return "Tutti i giorni"
    if (days == setOf(1, 2, 3, 4, 5)) return "Lun–Ven"
    val names = listOf("Lun", "Mar", "Mer", "Gio", "Ven", "Sab", "Dom")
    return days.sorted().joinToString(" ") { names[it - 1] }
}

/**
 * Il prossimo passaggio, in una carta sola.
 *
 * Prende il colore della linea perche' e' l'unico elemento della pagina che
 * sta da solo — la regola dell'engine e' proprio questa: dentro una lista
 * raggruppata il colore resta sulla piastrella, fuori puo' prendersi tutta
 * la superficie. Il contrasto del testo lo sceglie `FluidVividColors.from`
 * contro l'estremo peggiore del gradiente, cosi' una linea gialla non
 * diventa bianco su giallo.
 */
@Composable
private fun ProssimoPassaggio(
    row: dev.antigravity.fluidtransit.routing.NextDeparture,
    nowEpoch: Long,
    mostraFermata: Boolean,
    onClick: () -> Unit,
) {
    val phrase = DepartureText.phrase(row, nowEpoch)
    val tinta = Color(0xFF000000 or row.colorRgb.toLong())
    dev.antigravity.fluidengine.ui.fluid.FluidVividCard(
        colors = dev.antigravity.fluidengine.ui.fluid.FluidVividColors.from(tinta),
        effect = dev.antigravity.fluidengine.ui.fluid.FluidVividEffect.Sheen,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = row.line,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "→ ${row.destination}",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = phrase.headline,
            style = MaterialTheme.typography.displaySmall,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            // La provenienza c'e' anche qui: un numero grande senza da dove
            // viene e' esattamente il genere di cosa che non si puo' piu'
            // fare in quest'app.
            text = if (mostraFermata) "da ${row.stopName} · ${phrase.support}" else phrase.support,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
