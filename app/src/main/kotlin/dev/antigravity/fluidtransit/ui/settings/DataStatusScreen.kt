package dev.antigravity.fluidtransit.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidSectionTitle
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.data.bundle.BundleFailure
import dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState
import dev.antigravity.fluidtransit.data.bundle.BundleManager.UpdateOffer
import dev.antigravity.fluidtransit.data.bundle.BundleRefreshPolicy
import dev.antigravity.fluidtransit.data.places.PlacesManager
import dev.antigravity.fluidtransit.routing.Words
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    // Le spiegazioni lunghe si aprono, non stanno sempre aperte.
    //
    // Ogni riga di questa schermata portava sotto il titolo un paragrafo di
    // tre o quattro righe: messe in fila facevano un muro di testo, e una
    // schermata fatta di muri sembra un file di log anche quando dice cose
    // giuste. Il paragrafo non e' sparito — serve, ed e' il motivo per cui
    // questa schermata esiste — ma adesso lo si chiede toccando la riga.
    var aperta by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }

    // Questa schermata e' quella che dice se il tempo reale e' vivo, quindi
    // non puo' limitarsi a rileggere l'ultimo giro di qualcun altro. Con una
    // sola scheda alla volta la mappa, che e' chi scarica, esce di scena
    // appena si aprono le Impostazioni: il numero restava quello di quando
    // si era lasciata la mappa ("40 s") per quanto si restasse qui, e un
    // proxy morto nel frattempo non si poteva vedere. Si interroga all'apertura
    // e poi al ritmo della mappa, solo finche' la schermata e' visibile.
    val rt = app.realtime
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                // Un errore di rete non deve fermare il giro: e' proprio
                // quello che la schermata sta cercando di mostrare, e lo
                // mostra lo stato del client, non un'eccezione qui.
                runCatching { rt.refreshVehicles() }
                runCatching { rt.refreshDelays() }
                runCatching { rt.refreshPredictions() }
                kotlinx.coroutines.delay(rt.vehiclesIntervalMs())
            }
        }
    }

    FluidScreen(title = "Stato dei dati", onBack = onBack) {
        item { FluidSectionTitle(eyebrow = "Orari", title = "Il bundle") }
        item {
            FluidListGroup {
                when (val s = state) {
                    is BundleState.Ready -> {
                        val r = s.reader
                        // Quanto manca alla fine della validita'. Oltre quella
                        // data l'app rispondeva "nessun passaggio" a
                        // qualunque domanda, senza mai dire che il motivo era
                        // che gli orari erano finiti: sembrava un guasto.
                        val today = java.time.LocalDate.now(
                            dev.antigravity.fluidtransit.routing.Ftb.ROME,
                        )
                        val daysLeft = java.time.temporal.ChronoUnit.DAYS
                            .between(today, r.feedEnd)
                        // Le date a parole: qui c'era `LocalDate.toString()`,
                        // cioe' "Validi dal 2026-09-15 al 2026-10-05". Si
                        // capisce, ma e' la data di un file di log, e per
                        // sapere se scadono presto bisogna contare sulle dita.
                        // Dentro "dal … al …" i nomi dei giorni vicini non
                        // stanno: "Validi dal ieri al 5 ottobre".
                        val dal = dev.antigravity.fluidtransit.routing.Times
                            .dateLabel(r.feedStart, today, relative = false)
                        val al = dev.antigravity.fluidtransit.routing.Times
                            .dateLabel(r.feedEnd, today, relative = false)
                        FluidListRow(
                            title = "Orari caricati",
                            subtitle = when {
                                daysLeft < 0 ->
                                    "SCADUTI il $al. Gli orari non coprono piu' oggi: " +
                                        "finche' non arriva un bundle nuovo, molte ricerche " +
                                        "non troveranno passaggi."
                                daysLeft <= 3 ->
                                    "Validi dal $dal al $al — in scadenza"
                                else -> "Validi dal $dal al $al"
                            },
                            meta = when {
                                daysLeft < 0 -> "scaduti"
                                // "2 g" era un'abbreviazione che non usa nessuno.
                                daysLeft <= 3 -> dev.antigravity.fluidtransit.routing.Words.count(daysLeft.toInt(), "giorno", "giorni")
                                else -> "ok"
                            },
                        )
                        // Gli orari nuovi che aspettano una decisione.
                        //
                        // Con gli orari in scadenza e la rete a consumo l'app non
                        // scarica da sola: chiede. La domanda sta qui, sotto la
                        // riga che dice che gli orari stanno finendo, e non sulla
                        // schermata di benvenuto: l'app gli orari li ha gia', e
                        // mentre si decide deve restare in piedi com'e'.
                        val offerta by app.bundleManager.updateOffer.collectAsStateWithLifecycle()
                        val motivo = if (daysLeft < 0) {
                            "Gli orari in tasca sono scaduti."
                        } else {
                            "Gli orari in tasca stanno per scadere."
                        }
                        when (val o = offerta) {
                            null -> Unit

                            is UpdateOffer.Offered -> {
                                FluidListRow(
                                    title = "Aggiorna gli orari, circa ${BundleRefreshPolicy.megabytes(o.bytes)} MB su rete mobile",
                                    // "L'ultimo controllo" e non "sei": la rete puo'
                                    // essere cambiata da quando la domanda e' stata
                                    // fatta, e la riga non si ricalcola da sola.
                                    subtitle = "$motivo L'ultimo controllo l'ho fatto su rete mobile, " +
                                        "e li' non scarico senza chiedere.",
                                    onClick = app.bundleManager::acceptUpdateOnMetered,
                                )
                                FluidListRow(
                                    title = "Aspetta il Wi-Fi",
                                    subtitle = "Gli orari nuovi arrivano da soli appena c'e' un Wi-Fi",
                                    onClick = app.bundleManager::waitForWifiToUpdate,
                                )
                            }

                            is UpdateOffer.WaitingForWifi -> {
                                FluidListRow(
                                    title = "Aspetto il Wi-Fi per aggiornare gli orari",
                                    subtitle = "$motivo Appena c'e' una rete non a consumo, parto da solo.",
                                    meta = "in attesa",
                                )
                                FluidListRow(
                                    title = "Aggiorna ora, circa ${BundleRefreshPolicy.megabytes(o.bytes)} MB su rete mobile",
                                    subtitle = "Non aspetto piu': scarico con la rete che c'e'",
                                    onClick = app.bundleManager::acceptUpdateOnMetered,
                                )
                            }

                            is UpdateOffer.Downloading -> FluidListRow(
                                title = "Sto scaricando gli orari nuovi",
                                subtitle = "Puoi continuare a usare l'app: a scaricamento finito gli orari si sostituiscono da soli",
                                meta = "${(o.progress * 100).toInt()}%",
                            )

                            is UpdateOffer.Failed -> {
                                FluidListRow(
                                    title = "Aggiornamento degli orari non riuscito",
                                    // La stessa frase della schermata di
                                    // benvenuto, col testo tecnico appresso.
                                    subtitle = BundleFailure.words(o.message)
                                        .let { p -> p.title + (p.technical?.let { "\n$it" } ?: "") },
                                    meta = "errore",
                                )
                                FluidListRow(
                                    title = "Riprova ora",
                                    subtitle = "Usa la rete che c'e', anche se e' mobile",
                                    onClick = app.bundleManager::acceptUpdateOnMetered,
                                )
                            }
                        }
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
                        // La ricerca per nome ha un indice suo, costruito dopo
                        // gli orari: la barra, quando non c'e', rimanda qui, e
                        // qui non c'era niente che ne parlasse.
                        val indice by app.searchIndex.collectAsStateWithLifecycle()
                        val indiceFallito by app.searchIndexFailed.collectAsStateWithLifecycle()
                        FluidListRow(
                            title = "Ricerca di fermate e linee",
                            subtitle = when {
                                indice != null -> "Trova fermate e linee per nome"
                                indiceFallito -> "Non siamo riusciti a prepararla: riapri l'app. " +
                                    "Gli orari e la mappa funzionano lo stesso"
                                else -> "Si prepara dopo gli orari, in qualche secondo"
                            },
                            meta = when {
                                indice != null -> "pronta"
                                indiceFallito -> "non riuscita"
                                else -> "in preparazione"
                            },
                        )
                        FluidListRow(
                            title = "Velocita' di ricerca",
                            subtitle = "Quanto ci ha messo l'ultima ricerca di passaggi",
                            // Microsecondi in faccia a chi apre le impostazioni non
                            // dicono niente: si dice se e' veloce, e quanto.
                            meta = if (queryMicros < 1000) "istantanea" else "${queryMicros / 1000} ms",
                        )
                    }

                    is BundleState.Failed -> FluidListRow(
                        title = "Orari non scaricati",
                        // La stessa frase della schermata di benvenuto, e
                        // col testo tecnico appresso: qui c'era il
                        // messaggio dell'eccezione nudo.
                        subtitle = dev.antigravity.fluidtransit.data.bundle.BundleFailure
                            .words(s.message)
                            .let { p -> p.title + (p.technical?.let { "\n$it" } ?: "") },
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
                // L'eta' cresce col battito comune: il numero dell'ultimo giro
                // e' "quanto era vecchio allora", e scriverlo fermo dava
                // "40 s" anche dieci minuti dopo.
                val battito = remember { dev.antigravity.fluidtransit.data.time.UiClock.ticks() }
                val adesso by battito.collectAsStateWithLifecycle(
                    initialValue = Instant.now().epochSecond,
                )
                val etaAdesso = rtStatus.ageAt(Instant.ofEpochSecond(adesso))
                val controllato = rtStatus.polledAt?.let {
                    "controllato alle " + dev.antigravity.fluidtransit.routing.Times.hhmm(it.epochSecond)
                }
                FluidListRow(
                    title = "Eta' del dato",
                    subtitle = if (aperta == "Eta' del dato") {
                        "Quanto e' vecchia l'ultima posizione, rispetto all'origine. L'origine si rigenera ogni ~2 minuti: sotto i cinque minuti e' normale." +
                            (controllato?.let { " Ultimo giro: $it." } ?: "")
                    } else {
                        "Quanto e' vecchia l'ultima posizione" + (controllato?.let { " · $it" } ?: "")
                    },
                    meta = etaAdesso?.let { Words.age(it) } ?: "—",
                    onClick = { aperta = if (aperta == "Eta' del dato") null else "Eta' del dato" },
                )
                FluidListRow(
                    title = "Veicoli e ritardi",
                    subtitle = if (aperta == "Veicoli e ritardi") {
                        "Quanti bus vivi e quante corse con un ritardo dichiarato nell'ultimo aggiornamento. Si rinnovano finche' questa schermata e' aperta."
                    } else {
                        "Bus vivi e ritardi dell'ultimo giro"
                    },
                    meta = "${rtStatus.vehicleCount} · ${rtStatus.delayCount}",
                    onClick = { aperta = if (aperta == "Veicoli e ritardi") null else "Veicoli e ritardi" },
                )
                // `liveVersion` non si legge per il suo valore: si legge perche'
                // cambia a ogni giro di tempo reale assorbito, e senza di lui
                // questa riga resterebbe ferma al primo numero letto.
                val liveVersion by app.liveVersion.collectAsStateWithLifecycle()
                val withVehicle by app.tripsWithVehicle.collectAsStateWithLifecycle()
                val tracked = remember(liveVersion) { app.delayModel.size }
                // Quanta della rete che sta viaggiando adesso e' seguita.
                //
                // "Corse seguite adesso: 1650" non vuol dire niente da solo:
                // e' tutto se le corse in strada sono milleseicento ed e' un
                // terzo se sono cinquemila. La scansione degli orari costa
                // qualche decina di millisecondi, quindi sta fuori dal filo
                // del disegno e si rifa' ogni pochi giri di tempo reale.
                val copertura by produceState<
                    dev.antigravity.fluidtransit.routing.Coverage.Stato?,
                    // Si rifa' a ogni giro di tempo reale, non ogni quattro:
                    // aprendo questa schermata a processo appena nato il primo
                    // conto cadeva prima che i ritardi fossero risolti, e la
                    // riga restava a "0 di 1274" per due minuti — cioe' diceva
                    // che il tempo reale non copre niente proprio mentre la
                    // riga sopra contava duemila ritardi scaricati.
                    >(null, ready, liveVersion) {
                    val r = ready?.reader
                    value = if (r == null) {
                        null
                    } else {
                        withContext(Dispatchers.Default) {
                            runCatching {
                                dev.antigravity.fluidtransit.routing.Coverage
                                    .now(r, Instant.now(), app.departureBoards.live())
                            }.getOrNull()
                        }
                    }
                }
                FluidListRow(
                    title = "Copertura del tempo reale",
                    subtitle = if (aperta == "Copertura del tempo reale") {
                        "Quante delle corse che gli ORARI dicono in strada in questo momento hanno qualcosa dal vivo. E' la risposta alla domanda che viene guardando un tabellone dove meta' delle righe dicono \"orario da tabella\": non e' un aggancio fallito, sono corse di cui il feed non parla."
                    } else {
                        "Quanta parte dei bus in strada e' seguita"
                    },
                    meta = copertura?.let { c ->
                        c.percento?.let { "${c.seguite} di ${c.inViaggio} · $it%" }
                            ?: "niente in strada"
                    } ?: "—",
                    onClick = { aperta = if (aperta == "Copertura del tempo reale") null else "Copertura del tempo reale" },
                )
                FluidListRow(
                    title = "Corse seguite adesso",
                    subtitle = if (aperta == "Corse seguite adesso") {
                        "Quante corse hanno un ritardo in memoria, e quante hanno un mezzo vivo. E' da queste che escono i minuti veri nei tabelloni: se la prima e' zero mentre i ritardi scaricati sono tanti, il feed e gli orari non si stanno agganciando. Comprende le corse che devono ancora partire, quindi puo' essere piu' grande del numero di corse in strada."
                    } else {
                        "Corse con un ritardo in memoria"
                    },
                    meta = "$tracked · ${withVehicle.size} coi mezzi",
                    onClick = { aperta = if (aperta == "Corse seguite adesso") null else "Corse seguite adesso" },
                )
                val predictions by app.realtime.predictions.collectAsStateWithLifecycle()
                FluidListRow(
                    title = "Previsioni fermata per fermata",
                    subtitle = if (aperta == "Previsioni fermata per fermata") {
                        "Quante corse arrivano col ritardo dichiarato a OGNI fermata, e non con un numero solo da propagare a mano. E' quello che fa combaciare i minuti con quelli ufficiali: dove non arrivano, i numeri restano una nostra stima e l'app lo dice."
                    } else {
                        "Corse col ritardo dichiarato a ogni fermata"
                    },
                    meta = predictions?.let { set ->
                        val punti = set.byTripHash.values.sumOf { it.points.size }
                        Words.count(set.byTripHash.size, "corsa", "corse") + " · " +
                            Words.count(punti, "punto", "punti") +
                            if (set.truncated) " · ridotte" else ""
                    } ?: "—",
                    onClick = { aperta = if (aperta == "Previsioni fermata per fermata") null else "Previsioni fermata per fermata" },
                )
                val agganciate by app.livePredictions.collectAsStateWithLifecycle()
                FluidListRow(
                    title = "Previsioni agganciate",
                    subtitle = if (aperta == "Previsioni agganciate") {
                        "Una previsione serve solo se si sa a quale corsa e a quale fermata appartiene: la corsa si riconosce dall'identificatore, la fermata confrontando le due estremita' della sequenza con quelle degli orari. Quelle che non si agganciano tornano a essere una nostra stima, e finche' questo numero non c'era la cosa non si vedeva da nessuna parte."
                    } else {
                        "Quante si attaccano alla corsa e alla fermata giuste"
                    },
                    meta = agganciate?.let { p ->
                        val dal = predictions?.byTripHash?.size ?: p.risolte
                        "${p.agganciate} di $dal"
                    } ?: "—",
                    onClick = { aperta = if (aperta == "Previsioni agganciate") null else "Previsioni agganciate" },
                )
                FluidListRow(
                    title = "Corse riconosciute",
                    subtitle = if (aperta == "Corse riconosciute") {
                        "Quanti bus del feed live combaciano con gli orari del bundle. Le due generazioni di dati non sono mai sincronizzate del tutto."
                    } else {
                        "Bus del feed che combaciano con gli orari"
                    },
                    meta = resolvedPct?.let { "$it%" } ?: "—",
                    onClick = { aperta = if (aperta == "Corse riconosciute") null else "Corse riconosciute" },
                )
            }
        }

        // --- il confronto con la fonte ------------------------------------
        //
        // Tutto quello che c'e' sopra l'app lo sa di se stessa: quanti mezzi
        // ha scaricato, quante corse ha agganciato, quanto e' vecchio il
        // dato. Nessuna di quelle righe puo' rispondere a "e questi numeri
        // sono giusti?", perche' per rispondere bisogna guardare da fuori.
        //
        // Due volte al giorno un banco fa proprio questo: scarica il feed
        // grezzo della Regione e la sezione che il proxy serve all'app, e
        // confronta i ritardi uno per uno. Il verdetto viveva nei log di un
        // workflow; adesso arriva qui.
        item { FluidSectionTitle(eyebrow = "Fonte", title = "I numeri, confrontati") }
        item {
            val nowEpoch = remember { System.currentTimeMillis() / 1000 }
            val esito by produceState<dev.antigravity.fluidtransit.data.fidelity.FidelityCheck.Esito?>(
                initialValue = null,
            ) {
                value = dev.antigravity.fluidtransit.data.fidelity.FidelityCheck.fetch()
            }
            // Finche' non e' arrivata risposta la riga dice "sto chiedendo",
            // non "non ancora": erano due stati raccontati come uno.
            val parole = dev.antigravity.fluidtransit.routing.FidelityText.words(
                esito?.verdict,
                nowEpoch,
                reachable = esito?.reachable ?: true,
            )
            FluidListGroup {
                FluidListRow(
                    title = parole.title,
                    subtitle = parole.detail,
                )
            }
        }

        item { FluidSectionTitle(eyebrow = "Luoghi", title = "Il geocoding offline") }
        item {
            val placesState by app.placesManager.state.collectAsStateWithLifecycle()
            FluidListGroup {
                when (val p = placesState) {
                    is PlacesManager.State.Ready -> FluidListRow(
                        title = "Luoghi caricati",
                        subtitle = "%,d fra POI e localita' · %,d vie con civici · %,d numeri"
                            .format(p.reader.fastCount, p.reader.streetCount, p.reader.civiciCount)
                            .replace(',', '.'),
                        meta = "ok",
                    )

                    is PlacesManager.State.Downloading -> FluidListRow(
                        title = "Luoghi in arrivo",
                        subtitle = "L'indice dei posti della Toscana si sta scaricando",
                    )

                    // Ognuna delle cose che non sono "in arrivo" ha la sua
                    // frase. C'era una frase sola, "Arrivano da soli col
                    // Wi-Fi", e non era vera: il file si provava una volta
                    // per processo e poi mai piu', quindi un Wi-Fi arrivato
                    // a app aperta non scaricava niente, e un errore di rete
                    // lasciava la stessa promessa a chi non aveva mai avuto
                    // una rete a consumo.
                    is PlacesManager.State.WaitingForWifi -> FluidListRow(
                        title = "Luoghi in attesa del Wi-Fi",
                        subtitle = "Sono un file grosso: li scarico da solo appena c'e' un Wi-Fi. " +
                            "Fino ad allora la ricerca trova fermate e linee",
                        meta = "in attesa",
                    )

                    is PlacesManager.State.Failed -> FluidListRow(
                        title = "Luoghi non scaricati",
                        subtitle = "Il download non e' riuscito: riprovo da solo fra qualche minuto " +
                            "e a ogni riapertura. Fino ad allora la ricerca trova fermate e linee" +
                            (BundleFailure.words(p.message).technical?.let { "\n$it" } ?: ""),
                        meta = "errore",
                    )

                    // Non ancora provato, o l'ultimo aggiornamento notturno
                    // non li comprende: in nessuno dei due casi c'e' da
                    // promettere qualcosa.
                    else -> FluidListRow(
                        title = "Luoghi non ancora scaricati",
                        subtitle = "Per ora la ricerca trova fermate e linee",
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
