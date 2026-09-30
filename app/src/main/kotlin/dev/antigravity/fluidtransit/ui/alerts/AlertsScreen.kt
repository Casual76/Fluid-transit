package dev.antigravity.fluidtransit.ui.alerts

import androidx.lifecycle.repeatOnLifecycle
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidSectionTitle
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState
import dev.antigravity.fluidtransit.data.rt.GtfsRtLite
import dev.antigravity.fluidtransit.routing.AlertText
import dev.antigravity.fluidtransit.ui.common.titoloDiSezione
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
    // I nomi e i colori delle linee, per hash, una volta per bundle e fuori
    // dal thread della UI. Ogni scheda li cercava per conto suo con una
    // scansione lineare delle linee — 946 a settembre — per ogni hash di
    // ogni avviso, dentro la composizione.
    val linee by produceState(initialValue = emptyMap<Long, Pair<String, Int>>(), reader) {
        val r = reader ?: return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                (0 until r.routeCount).associate { i ->
                    r.routeIdHash(i) to
                        (r.routeShortName(i).ifEmpty { r.routeLongName(i) } to r.routeDisplayColor(i))
                }
            }.getOrDefault(emptyMap())
        }
    }
    val favVersion by app.favorites.version.collectAsStateWithLifecycle()
    // "Le tue linee" comprende quelle che passano dalle tue fermate: la
    // stella sulle linee quasi nessuno la mette, e senza questo la sezione
    // in cima restava vuota proprio per chi ha stellato la fermata sotto
    // casa — cioe' quasi tutti.
    val mine = remember(favVersion, reader) {
        dev.antigravity.fluidtransit.data.favorites.MyRoutes.hashes(
            reader = reader,
            starredRoutes = app.favorites.routes()
                .mapNotNull { it.idHashHex.toULongOrNull(16)?.toLong() }.toSet(),
            starredStops = app.favorites.stops().mapNotNull { s ->
                s.idHashHex.toULongOrNull(16)?.toLong()
                    ?.let { reader?.findStopByIdHash(it) }
                    ?.takeIf { it >= 0 }
            },
        )
    }

    // Tre stati e non due: sto leggendo, non ci sono riuscito, ecco la lista.
    //
    // Prima il fallimento diventava una lista vuota, e una lista vuota qui
    // si legge "Nessun avviso in corso" — cioe' una frase rassicurante.
    // Con la rete giu' durante uno sciopero l'app diceva esattamente il
    // contrario del vero, e lo diceva con sicurezza.
    // Da quando la lista e' vecchia, se l'ultimo download non e' riuscito:
    // la lista si mostra lo stesso, ma dice di quando e'.
    var vecchiDa by remember { mutableStateOf<Long?>(null) }
    var esito by remember { mutableStateOf<Result<List<GtfsRtLite.RtAlert>>?>(null) }
    // Il download e' una funzione e non un `produceState` con un contatore:
    // chi riprova deve poter ASPETTARE la risposta. Col contatore il gesto
    // partiva e lo "sto aggiornando" si spegneva dopo 600 ms qualunque cosa
    // fosse successa, quindi un secondo tentativo fallito non cambiava niente
    // sullo schermo — e per chi usa il lettore di schermo, che non ha altro
    // segnale, era come non averlo fatto.
    //
    // `force` salta la cache di cinque minuti: chi tocca "Riprova" o tira giu'
    // vuole una risposta nuova. L'apertura della schermata no, si accontenta
    // della cache.
    val scarica: suspend (Boolean) -> Unit = { force ->
        val lista = app.realtime.fetchAlertsOrNull(force)
        vecchiDa = app.realtime.alertsStaleSinceEpoch()
        esito = lista
            ?.let { Result.success(it) }
            ?: Result.failure(java.io.IOException("avvisi non scaricati"))
    }
    // Si riscaricano finche' la schermata e' davanti, e subito quando ci si
    // torna: e' la schermata che si apre per sapere se c'e' uno sciopero, e
    // lasciata aperta alle 07:00 alle 07:40 mostrava ancora la lista delle
    // 07:00, senza un'eta' perche' il download era riuscito. La cache di
    // cinque minuti rende il giro leggero.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                scarica(false)
                kotlinx.coroutines.delay(dev.antigravity.fluidtransit.data.rt.RealtimeClient.ALERTS_POLL_MS)
            }
        }
    }
    val alerts = esito?.getOrNull()

    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val vista = androidx.compose.ui.platform.LocalView.current

    // Riprovare e' un tasto, e il gesto di tirare giu' fa la stessa cosa.
    //
    // Il fallimento diceva "tira giu' per riprovare", e quel gesto e' un
    // overscroll del dito che il lettore di schermo non produce: con
    // TalkBack acceso e la rete giu' durante uno sciopero il testo chiedeva
    // una cosa che chi lo leggeva non poteva fare, e l'unica uscita era
    // chiudere la schermata e riaprirla senza che nessuno lo dicesse.
    val riprova: () -> Unit = {
        if (!refreshing) {
            refreshing = true
            scope.launch {
                try {
                    // Un fallimento che si ripete deve SI VEDERE: si torna
                    // alle sagome, e poi o arriva la lista o ricompare il
                    // messaggio. La lista che c'e' gia' invece resta dov'e'.
                    if (esito?.getOrNull() == null) esito = null
                    scarica(true)
                    // Com'e' andata, a voce. Col lettore di schermo il tasto
                    // spariva insieme al messaggio, il fuoco cadeva, e un
                    // secondo fallimento non lo diceva nessuno.
                    @Suppress("DEPRECATION")
                    vista.announceForAccessibility(
                        if (esito?.getOrNull() != null) AlertText.RETRY_OK else AlertText.RETRY_FAILED,
                    )
                    // Mezzo secondo di cortesia: un aggiornamento che sparisce
                    // prima di essere visto non e' una risposta.
                    kotlinx.coroutines.delay(600)
                } finally {
                    refreshing = false
                }
            }
        }
    }

    // L'orologio comune, non uno suo: "da oggi alle 10:15" e l'elenco degli
    // avvisi in corso si aggiornano col battito di tutta l'app. Con
    // `Instant.now()` qui dentro restavano fermi finche' qualcos'altro non
    // faceva ricomporre la schermata.
    val battito = remember { dev.antigravity.fluidtransit.data.time.UiClock.ticks() }
    val now by battito.collectAsStateWithLifecycle(initialValue = Instant.now().epochSecond)
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

    // Quelli che devono ancora cominciare.
    //
    // Un avviso attivo lo scopri quando ti tocca; uno sciopero annunciato per
    // giovedi' serve mercoledi'. La schermata li buttava via tutti — il
    // filtro teneva solo cio' che e' gia' cominciato — e cosi' l'unica cosa
    // che un avviso di servizio puo' fare davvero, cioe' farti cambiare
    // programma prima, non la faceva.
    //
    // Due settimane di orizzonte: piu' in la' e' un annuncio, non un avviso,
    // e riempirebbe la schermata di cose che non riguardano questa settimana.
    val futuri = (alerts ?: emptyList())
        .filter { it.startEpoch > now && it.startEpoch < now + PROSSIMI_GIORNI * 24 * 3600 }
        .filter { it.endEpoch == 0L || it.endEpoch > now }
        .sortedBy { it.startEpoch }

    FluidScreen(
        title = "Avvisi",
        subtitle = "Deviazioni, scioperi e lavori dichiarati dal gestore",
        onBack = onBack,
        isRefreshing = refreshing,
        onRefresh = riprova,
    ) {
        if (esito == null) {
            // Le sagome di quello che sta arrivando, invece di una rotellina
            // in mezzo a una pagina bianca: gli avvisi ci mettono qualche
            // secondo a scaricarsi, e per tutto quel tempo la schermata non
            // diceva nemmeno che forma avra'.
            item {
                dev.antigravity.fluidtransit.ui.common.CardSkeleton(lines = 3)
                dev.antigravity.fluidtransit.ui.common.CardSkeleton(lines = 2)
                dev.antigravity.fluidtransit.ui.common.CardSkeleton(lines = 4)
            }
            return@FluidScreen
        }
        if (alerts == null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FluidEmptyState(
                        title = AlertText.UNAVAILABLE_TITLE,
                        detail = AlertText.UNAVAILABLE_DETAIL + ".",
                    )
                    // Il tasto sta DOPO il messaggio, nello stesso elemento:
                    // per chi naviga a swipe l'ordine e' cosa e' successo, poi
                    // cosa si puo' fare. Il testo non dice "il tasto qui
                    // sotto": con il carattere grande la posizione cambia.
                    FluidButton(
                        text = AlertText.RETRY,
                        onClick = riprova,
                        style = FluidButtonStyle.Tinted,
                        loading = refreshing,
                    )
                }
            }
            return@FluidScreen
        }
        vecchiDa?.let { da ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        // Mentre riprova il testo cambia: e' una regione viva,
                        // quindi il lettore di schermo lo annuncia, e alla
                        // fine annuncia com'e' andata.
                        text = if (refreshing) AlertText.RETRYING else AlertText.stale(da, now),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    FluidButton(
                        text = AlertText.RETRY,
                        onClick = riprova,
                        style = FluidButtonStyle.Plain,
                        loading = refreshing,
                    )
                }
            }
        }
        if (attivi.isEmpty() && futuri.isEmpty()) {
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
            item {
                FluidSectionTitle(
                    eyebrow = "Avvisi",
                    title = "Sulle tue linee",
                    modifier = Modifier.titoloDiSezione(),
                )
            }
            alertItems("tuoi", tuoi, linee, now, mine)
        }
        if (altri.isNotEmpty()) {
            item {
                FluidSectionTitle(
                    eyebrow = "Avvisi",
                    title = if (tuoi.isEmpty()) "In corso" else "Sul resto della rete",
                    modifier = Modifier.titoloDiSezione(),
                )
            }
            alertItems("altri", altri, linee, now, mine)
        }
        if (futuri.isNotEmpty()) {
            item {
                FluidSectionTitle(
                    eyebrow = "Avvisi",
                    title = "Nei prossimi giorni",
                    modifier = Modifier.titoloDiSezione(),
                )
            }
            alertItems("futuri", futuri, linee, now, mine)
        }
    }
}

/** Quanto in la' si guarda per gli avvisi che devono ancora cominciare. */
private const val PROSSIMI_GIORNI = 14

/**
 * Un avviso per elemento della lista, non tutti in uno, e una scheda sua.
 *
 * Stavano tutti dentro un solo elemento, cioe' la lista pigra non era pigra:
 * aprendo la schermata si componevano insieme tutte le schede, un centinaio
 * nei giorni di sciopero, prima di mostrare la prima.
 *
 * Il primo tentativo li aveva tagliati in pezzi di un gruppo solo
 * ([dev.antigravity.fluidengine.ui.theme.FluidGroupSegment]), ma il pezzo non
 * richiude da se' lo spazio che la lista mette fra un elemento e l'altro:
 * ne uscivano lastre staccate da 14 dp, con gli angoli vivi verso il vuoto e
 * i divisori tagliati via dal bordo aperto. Una scheda per avviso, invece, fa
 * di quello spazio la separazione fra una scheda e l'altra.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.alertItems(
    section: String,
    alerts: List<GtfsRtLite.RtAlert>,
    linee: Map<Long, Pair<String, Int>>,
    nowEpoch: Long,
    mine: Set<Long>,
) {
    items(
        count = alerts.size,
        key = { i -> "$section-$i-${alerts[i].header.hashCode()}-${alerts[i].startEpoch}" },
    ) { i ->
        FluidListGroup {
            AlertCard(alerts[i], linee, nowEpoch, mine)
        }
    }
}

@Composable
private fun AlertCard(
    alert: GtfsRtLite.RtAlert,
    /** hash della linea -> (nome, colore), costruita una volta fuori dalla UI. */
    nomiLinee: Map<Long, Pair<String, Int>>,
    nowEpoch: Long,
    mine: Set<Long>,
) {
    // Le linee toccate, coi loro nomi e i loro colori: l'avviso arriva con
    // gli hash, che da soli non dicono niente a nessuno.
    val linee = remember(alert, nomiLinee, mine) {
        alert.routeHashes
            // Le tue davanti.
            //
            // Un avviso in cima a "Sulle tue linee" nomina anche sei linee, e
            // solo due sono tue: sapere quale delle sei ti riguarda voleva
            // dire ricordarsi a memoria cosa passa dalla fermata che hai
            // stellato. L'ordine non lo dice a parole, ma mette al primo
            // posto quella per cui la scheda si e' aperta.
            .sortedByDescending { it in mine }
            .mapNotNull { h -> nomiLinee[h] }
            .distinct()
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
        val corpo = AlertText.body(alert.description)
        if (corpo.isNotEmpty() && corpo != alert.header) {
            // Ripiegata, con la porta per aprirla.
            //
            // Gli avvisi veri contengono l'elenco completo del percorso nuovo,
            // fermata per fermata, in due direzioni: uno solo riempie tre
            // schermate. Mostrarli tutti per intero rende l'elenco illeggibile
            // e nasconde gli altri; tagliarli a 220 caratteri, com'era prima,
            // toglie proprio il pezzo che serve. Quindi: sei righe, e chi
            // vuole legge il resto.
            //
            // Salvato, non solo ricordato: da quando ogni avviso e' un elemento
            // suo della lista, uscendo dallo schermo la scheda si scompone, e
            // tornandoci l'avviso aperto si era richiuso sotto il dito. La
            // chiave dell'elemento basta a tenerli distinti.
            var aperto by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            // "Leggi tutto" compare se il testo e' stato DAVVERO tagliato.
            //
            // Prima la porta si apriva sopra i 240 caratteri, ma il taglio e'
            // a sei righe: due misure diverse per la stessa cosa. Un avviso
            // di duecento caratteri che su uno schermo stretto occupa sette
            // righe finiva con i puntini e nessun modo di aprirlo — visto
            // sull'emulatore, "Fermata spostata per lavori" tagliato a
            // meta' di "a causa di lav...".
            var troncato by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            Spacer(Modifier.height(6.dp))
            Text(
                text = corpo,
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
