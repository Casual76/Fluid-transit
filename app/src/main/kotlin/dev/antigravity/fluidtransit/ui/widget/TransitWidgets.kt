package dev.antigravity.fluidtransit.ui.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.height
import androidx.glance.layout.Spacer
import androidx.glance.GlanceModifier
import androidx.glance.state.PreferencesGlanceStateDefinition
import dev.antigravity.fluidengine.widget.EngineWidgetGroup
import dev.antigravity.fluidengine.widget.EngineWidgetHairline
import dev.antigravity.fluidengine.widget.EngineWidgetHeader
import dev.antigravity.fluidengine.widget.EngineWidgetRow
import dev.antigravity.fluidengine.widget.EngineWidgetSurface
import dev.antigravity.fluidengine.widget.engineWidgetPalette
import dev.antigravity.fluidengine.widget.resolveEngineWidgetLayout
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.MainActivity
import dev.antigravity.fluidtransit.data.bundle.BundleManager
import dev.antigravity.fluidtransit.routing.DelayModel
import dev.antigravity.fluidtransit.routing.DepartureBoard
import dev.antigravity.fluidtransit.routing.DepartureText
import dev.antigravity.fluidtransit.routing.Ftb
import dev.antigravity.fluidtransit.routing.Times
import dev.antigravity.fluidtransit.ui.nav.Deeplink
import dev.antigravity.fluidtransit.ui.theme.TransitBrand
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Il tocco su un widget porta dove il widget guarda.
 *
 * Prima portava all'app e basta: toccavi "SODERINI · 12 fra 3 minuti" e ti
 * trovavi la Toscana intera a zoom 7,6, con la fermata da ricercare a mano.
 * Un widget che non sa aprire la cosa che mostra e' un cartello, non una
 * scorciatoia.
 */
private fun openLink(context: Context, link: String?): Intent =
    Intent(context, MainActivity::class.java).apply {
        if (link != null) {
            action = Intent.ACTION_VIEW
            data = Uri.parse(link)
        }
    }

/**
 * I due widget decisi (entrambi, per volonta' dell'utente): le partenze di
 * una fermata preferita e il consiglio della routine di oggi. Tutto il
 * vestito viene dal kit dell'engine — palette dagli STESSI settings del
 * tema, budget di layout, componenti — cosi' la home e l'app dicono la
 * stessa cosa con la stessa voce.
 */

val KEY_STOP_HASH = stringPreferencesKey("stopHash")
val KEY_STOP_NAME = stringPreferencesKey("stopName")

class StopWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StopWidget()
}

class RoutineWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RoutineWidget()
}

/**
 * Come e' andata la costruzione del tabellone di un widget.
 *
 * Un widget vive il tempo di disegnarsi e non puo' chiedere niente a
 * nessuno: se torna indietro un tabellone nullo e basta, l'unica frase
 * possibile e' quella generica, e "Nessun passaggio a breve" e' la lettura
 * piu' sbagliata di una fermata che non esiste piu'. Qui il motivo viaggia
 * insieme al risultato.
 */
private sealed interface StopBoard {
    /** Il widget non e' stato configurato. */
    data object NoStop : StopBoard

    /** Gli orari non si sono aperti entro il budget del disegno. */
    data object NoTimetable : StopBoard

    /** La fermata salvata non compare negli orari di oggi. */
    data object UnknownStop : StopBoard

    class Ready(val board: DepartureBoard) : StopBoard
}

class StopWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as FluidTransitApp
        val settings = app.settingsStore.current()
        val palette = engineWidgetPalette(context, settings, TransitBrand)

        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val stopHash = prefs[KEY_STOP_HASH]
        val stopName = prefs[KEY_STOP_NAME] ?: ""
        // Il numero del widget, per poter riaprire la sua configurazione.
        //
        // Un widget senza fermata scriveva "Tocca per configurare" e poi,
        // toccato, apriva l'app: la configurazione non si vedeva da nessuna
        // parte, e l'unico modo per arrivarci era togliere il widget e
        // rimetterlo. Una frase che dice di fare una cosa e un tocco che ne
        // fa un'altra e' peggio di nessuna frase.
        val widgetId = runCatching {
            androidx.glance.appwidget.GlanceAppWidgetManager(context).getAppWidgetId(id)
        }.getOrDefault(android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID)

        val esito = loadBoard(app, stopHash)
        val board = (esito as? StopBoard.Ready)?.board
        val rows = board?.rows
        // Il nome che vale e' quello degli orari.
        //
        // Quello salvato nella configurazione e' un ripiego per quando il
        // bundle non e' ancora pronto — un avvio freddo, un widget disegnato
        // prima dell'app — e resta com'era il giorno in cui si e' scelta la
        // fermata. Misurato su questo telefono: il widget diceva "SODERINI" e
        // la fermata si chiama "SODERINI TORRINO SANTA ROSA". Stessa cosa,
        // due nomi, a seconda di dove la guardi.
        val nome = board?.stopName?.ifEmpty { null } ?: stopName

        provideContent {
            val layout = resolveEngineWidgetLayout(LocalSize.current, hasFooter = false)
            EngineWidgetSurface(
                palette = palette,
                layout = layout,
                onClick = actionStartActivity(
                    if (stopHash == null && widgetId != android.appwidget.AppWidgetManager
                            .INVALID_APPWIDGET_ID
                    ) {
                        android.content.Intent(context, StopWidgetConfigActivity::class.java)
                            .putExtra(
                                android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,
                                widgetId,
                            )
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    } else {
                        openLink(context, stopHash?.let { Deeplink.stop(it, nome) })
                    },
                ),
            ) {
                EngineWidgetHeader(
                    title = nome.ifEmpty { "Fluid Transit" },
                    palette = palette,
                    layout = layout,
                    subtitle = when {
                        stopHash == null -> "Tocca per configurare"
                        board == null -> "Orari in arrivo…"
                        // Un widget dice sempre di quando sono i suoi
                        // numeri con le stesse parole del resto dell'app:
                        // e' l'unico posto dove l'utente non puo' chiedere.
                        // Sulle righe che si vedono, non su quelle
                        // calcolate: il widget ne chiede cinque e ne disegna
                        // due o tre, e il piede prometteva "in parte dal bus"
                        // sopra due righe che dicevano "orario da tabella".
                        else -> "Prossimi passaggi · " +
                            DepartureText.boardSource(rows.orEmpty().take(layout.rowLimit))
                    },
                )
                Spacer(GlanceModifier.height(if (layout.compact) 6.dp else 8.dp))
                EngineWidgetGroup(palette) {
                    when {
                        // Cinque situazioni diverse finivano tutte in
                        // "Nessun passaggio a breve": nessuna fermata scelta,
                        // orari non aperti in tempo, fermata sparita dagli
                        // orari di oggi, orari scaduti, e il caso vero. Le
                        // parole sono le stesse del resto dell'app.
                        rows.isNullOrEmpty() -> {
                            val guaio = if (board != null) {
                                DepartureText.trouble(board)
                            } else {
                                when (esito) {
                                    is StopBoard.NoStop -> DepartureText.Trouble.NESSUNA_FERMATA
                                    is StopBoard.UnknownStop ->
                                        DepartureText.Trouble.FERMATA_SCONOSCIUTA
                                    else -> DepartureText.Trouble.ORARI_NON_PRONTI
                                }
                            }
                            val parole = DepartureText.empty(guaio)
                            EngineWidgetRow(
                                title = parole.title,
                                subtitle = parole.short,
                                palette = palette,
                                layout = layout,
                            )
                        }

                        else -> {
                            rows.take(layout.rowLimit).forEachIndexed { i, r ->
                                if (i > 0) EngineWidgetHairline(palette, layout)
                                val phrase = DepartureText.phrase(r, board.computedAtEpoch)
                                EngineWidgetRow(
                                    title = "${r.line} → ${r.destination}",
                                    subtitle = phrase.support,
                                    palette = palette,
                                    layout = layout,
                                    tone = palette.primaryTone,
                                    trailing = phrase.headline,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Il tabellone della fermata del widget.
     *
     * Un widget non puo' iscriversi a un flusso — vive il tempo di
     * disegnarsi — quindi chiede uno scatto. Ma lo chiede alla stessa fonte
     * di tutte le schermate, con le stesse regole e le stesse parole: era
     * l'unica superficie che mostrava i minuti nudi, senza dire da dove
     * venissero se non nel titolo.
     */
    private suspend fun loadBoard(app: FluidTransitApp, stopHashHex: String?): StopBoard {
        if (stopHashHex == null) return StopBoard.NoStop
        val ready = withTimeoutOrNull(BUNDLE_WAIT_MS) {
            app.bundleManager.state.filterIsInstance<BundleManager.BundleState.Ready>().first()
        } ?: return StopBoard.NoTimetable
        val hash = stopHashHex.toULongOrNull(16)?.toLong() ?: return StopBoard.UnknownStop
        val stop = ready.reader.findStopByIdHash(hash)
        if (stop < 0) return StopBoard.UnknownStop

        // I ritardi, che il widget prima non guardava affatto: mostrava gli
        // orari di tabella come se fossero certi, e per mezz'ora di fila.
        //
        // In parallelo, e con poco tempo. Questo codice gira anche dentro il
        // `goAsync` di una ricevente, che il sistema si aspetta finisca in
        // una decina di secondi: in fila, sei secondi di attesa del bundle
        // piu' quattro piu' quattro fanno quattordici, e un disegno che non
        // arriva in tempo lascia sulla home la schermata di prima. E' il
        // sospetto piu' probabile dietro al widget che restava a "Tocca per
        // configurare" dopo la configurazione.
        //
        // Se la rete non risponde in tempo si mostrano gli orari di tabella,
        // dicendolo: e' il comportamento giusto, non una rinuncia.
        runCatching {
            withTimeoutOrNull(REALTIME_WAIT_MS) {
                coroutineScope {
                    launch { runCatching { app.realtime.refreshDelays() } }
                    launch { runCatching { app.realtime.refreshPredictions() } }
                }
            }
        }
        return StopBoard.Ready(app.departureBoards.snapshot(listOf(stop), limit = 5))
    }

    private companion object {
        /** Quanto si aspetta il bundle: aprirlo e' una mmap, non un download. */
        const val BUNDLE_WAIT_MS = 5_000L

        /**
         * Quanto si aspettano i ritardi.
         *
         * Il totale con l'attesa del bundle deve stare sotto la decina di
         * secondi che il sistema concede a una ricevente.
         */
        const val REALTIME_WAIT_MS = 3_000L
    }
}

class RoutineWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as FluidTransitApp
        val settings = app.settingsStore.current()
        val palette = engineWidgetPalette(context, settings, TransitBrand)

        val oggi = LocalDate.now(Ftb.ROME)
        val today = oggi.dayOfWeek.value
        val routines = dev.antigravity.fluidtransit.data.routines.Routines(context).list()
        val todayRoutine = routines.firstOrNull { it.enabled && today in it.days }
        val adesso = Instant.now().epochSecond

        // Un consiglio vale finche' non e' passata l'ora di uscire.
        //
        // `lastAdviceEpoch` e' l'ora in cui USCIRE, non l'ora in cui il
        // consiglio e' stato calcolato: bastava che fosse di oggi perche' il
        // widget lo tenesse in vetrina fino a mezzanotte. Visto sulla home
        // alle 09:47: "Esci alle 07:25 — linea 23 alle 07:28", scritto come
        // se fosse la cosa da fare adesso, sotto una riga che prometteva
        // "coi ritardi live di adesso". Due ore e mezza prima era vero.
        //
        // Cinque minuti di grazia: chi guarda il telefono appena dopo essere
        // uscito vuole ancora vedere qual era il piano.
        val consiglioValido = todayRoutine != null &&
            dev.antigravity.fluidtransit.data.routines.Routines
                .adviceStillGood(todayRoutine, adesso)

        // L'ora della routine di oggi: serve a distinguere "il consiglio deve
        // ancora arrivare" da "per oggi e' andata", che prima erano la stessa
        // frase.
        val ancoraOggi = todayRoutine != null &&
            adesso < oggi.atStartOfDay(Ftb.ROME)
                .plusMinutes(todayRoutine.anchorMinutes.toLong()).toEpochSecond()

        provideContent {
            val layout = resolveEngineWidgetLayout(LocalSize.current, hasFooter = false)
            EngineWidgetSurface(
                palette = palette,
                layout = layout,
                onClick = actionStartActivity(
                    openLink(context, todayRoutine?.let { Deeplink.journey(it.id) }),
                ),
            ) {
                EngineWidgetHeader(
                    title = "La tua routine",
                    palette = palette,
                    layout = layout,
                    subtitle = todayRoutine?.label?.ifEmpty { todayRoutine.toName },
                )
                Spacer(GlanceModifier.height(if (layout.compact) 6.dp else 8.dp))
                EngineWidgetGroup(palette) {
                    when {
                        todayRoutine == null -> EngineWidgetRow(
                            title = "Oggi niente routine",
                            subtitle = "Le crei dal dettaglio di un viaggio",
                            palette = palette,
                            layout = layout,
                        )

                        consiglioValido -> EngineWidgetRow(
                            title = todayRoutine.lastAdviceText.ifEmpty { "Calcolo in corso" },
                            // Non "di adesso": un consiglio si calcola tre
                            // quarti d'ora prima, e quei ritardi erano di
                            // allora.
                            subtitle = "calcolato coi ritardi live",
                            palette = palette,
                            layout = layout,
                            tone = palette.primaryTone,
                            trailing = dev.antigravity.fluidtransit.routing.Times
                                .hhmm(todayRoutine.lastAdviceEpoch),
                        )

                        ancoraOggi -> EngineWidgetRow(
                            title = "Il consiglio arriva da solo",
                            subtitle = "circa 45 minuti prima dell'orario",
                            palette = palette,
                            layout = layout,
                        )

                        else -> EngineWidgetRow(
                            title = "Per oggi e' andata",
                            subtitle = "il prossimo consiglio al prossimo giorno della routine",
                            palette = palette,
                            layout = layout,
                        )
                    }
                }
            }
        }
    }
}
