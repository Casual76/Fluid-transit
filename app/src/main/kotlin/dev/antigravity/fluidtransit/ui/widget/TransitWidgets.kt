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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.datastore.preferences.core.Preferences
import androidx.glance.currentState
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.layout.Spacer
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider
import androidx.glance.GlanceModifier
import androidx.glance.state.PreferencesGlanceStateDefinition
import dev.antigravity.fluidengine.widget.EngineWidgetGroup
import dev.antigravity.fluidengine.widget.EngineWidgetHairline
import dev.antigravity.fluidengine.widget.EngineWidgetHeader
import dev.antigravity.fluidengine.widget.EngineWidgetLayout
import dev.antigravity.fluidengine.widget.EngineWidgetPalette
import dev.antigravity.fluidengine.widget.EngineWidgetRow
import dev.antigravity.fluidengine.widget.EngineWidgetShape
import dev.antigravity.fluidengine.widget.EngineWidgetSurface
import dev.antigravity.fluidengine.widget.engineWidgetPalette
import dev.antigravity.fluidengine.widget.engineWidgetTextStyle
import dev.antigravity.fluidengine.widget.resolveEngineWidgetLayout
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.MainActivity
import dev.antigravity.fluidtransit.data.bundle.BundleManager
import dev.antigravity.fluidtransit.routing.DelayModel
import dev.antigravity.fluidtransit.routing.DepartureBoard
import dev.antigravity.fluidtransit.routing.DepartureText
import dev.antigravity.fluidtransit.routing.Ftb
import dev.antigravity.fluidtransit.routing.NextDeparture
import dev.antigravity.fluidtransit.routing.Times
import dev.antigravity.fluidtransit.ui.nav.Deeplink
import dev.antigravity.fluidtransit.ui.theme.TransitBrand
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
 * La pastiglia della linea, sulla home.
 *
 * Il widget era l'ultima superficie che scriveva la linea come testo dentro
 * il titolo — "12 → LE PIAGGE" — mentre in app la linea e' un rettangolo del
 * suo colore ovunque la si nomini, e quel colore e' lo stesso della tratta
 * sulla mappa. Stessa informazione, due grammatiche, e quella della home e'
 * la piu' vista: e' la superficie che si guarda senza aprire l'app.
 *
 * Il bianco non e' una scommessa: i colori delle linee escono tutti dalle
 * dodici tinte di `RouteColoring.PALETTE`, sature e scelte per leggersi su
 * basemap chiara e scura, quindi non esiste il caso della pastiglia gialla
 * con la scritta bianca. Glance sa arrotondare gli angoli solo da Android 12
 * in su e non conosce le curve continue del design system: il colore e le
 * proporzioni restano gli stessi, la forma e' quella che l'host sa disegnare.
 */
@Composable
private fun PastigliaLinea(
    line: String,
    colorRgb: Int,
    layout: EngineWidgetLayout,
    onClick: Action?,
) {
    val larghezza = larghezzaPastiglia(line, layout.compact)
    Box(
        modifier = GlanceModifier
            .background(ColorProvider(Color(0xFF000000 or colorRgb.toLong())))
            .cornerRadius(EngineWidgetShape.Tile)
            .let { if (larghezza != null) it.width(larghezza) else it }
            // Un solo `padding`: i modificatori di Glance sono un insieme di
            // proprieta', non una catena ordinata, e due chiamate si
            // sovrascrivono invece di sommarsi.
            .padding(
                horizontal = if (larghezza != null) 0.dp else 7.dp,
                vertical = if (layout.compact) 3.dp else 4.dp,
            )
            .let { if (onClick != null) it.clickable(onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = line,
            style = engineWidgetTextStyle(
                color = ColorProvider(Color.White),
                size = if (layout.compact) 12.sp else 13.sp,
                weight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
    }
}

/**
 * Quanto e' larga la pastiglia, e perche' a volte non lo e'.
 *
 * Larghezza fissa: e' cosi' che le destinazioni partono tutte dalla stessa
 * colonna invece di ballare di qualche pixel a seconda che la linea si
 * chiami "6" o "131" — in app lo fa `widthIn(min = 44.dp)`, che in Glance non
 * esiste. Ma una larghezza fissa taglia i nomi lunghi, e una linea tagliata
 * e' un'altra linea: sopra i tre caratteri la pastiglia torna a stringersi
 * sul testo, perche' meglio una colonna storta di un "301A" scritto "301…".
 */
internal fun larghezzaPastiglia(line: String, compact: Boolean): Dp? =
    if (line.length <= 3) (if (compact) 34.dp else 40.dp) else null

/**
 * Una partenza sulla home: pastiglia, destinazione, provenienza, numero.
 *
 * E' la riga dell'app tradotta nei mattoni di Glance — che non puo' usare un
 * composable di Compose, ma usa lo stesso modello ([NextDeparture]), le
 * stesse parole ([DepartureText]) e le stesse regole di colore. Finche' non
 * c'e' stata, il widget diceva "12 → LE PIAGGE" in nero e "3 min" nel colore
 * dell'accento, sempre lo stesso: un bus in orario e uno con mezz'ora di
 * ritardo si scrivevano uguali.
 */
@Composable
private fun RigaPartenza(
    row: NextDeparture,
    nowEpoch: Long,
    palette: EngineWidgetPalette,
    layout: EngineWidgetLayout,
    larghezza: Dp,
    onLineClick: Action?,
) {
    val phrase = DepartureText.phrase(row, nowEpoch)
    // Un numero vecchio si presenta con la sua eta', dove c'e' posto.
    val vecchio = notaEtaSulWidget(row, layout.showSubtitle, larghezza)
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(
                horizontal = if (layout.compact) 10.dp else 12.dp,
                vertical = if (layout.compact) 7.dp else 9.dp,
            )
            // La riga detta come una persona, per TalkBack.
            //
            // Senza, pastiglia, destinazione e minuti si leggevano come tre
            // frammenti senza legame, e "in ritardo", "in orario" o "stimato"
            // non si dicevano mai: sul widget piccolo la puntualita' stava
            // nel solo colore. E' la frase che la scheda fermata usa, dalla
            // stessa funzione, quindi non puo' divergere.
            .semantics { contentDescription = DepartureText.spoken(row, nowEpoch) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PastigliaLinea(row.line, row.colorRgb, layout, onLineClick)
        Spacer(GlanceModifier.width(if (layout.compact) 8.dp else 10.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = row.destination,
                style = engineWidgetTextStyle(
                    color = palette.onSurface,
                    size = if (layout.compact) 13.sp else 15.sp,
                    weight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            if (layout.showSubtitle) {
                Text(
                    text = phrase.support,
                    style = engineWidgetTextStyle(color = palette.onSurfaceVariant, size = 12.sp),
                    maxLines = 1,
                )
            }
        }
        Spacer(GlanceModifier.width(8.dp))
        val colore = colorePartenza(phrase.tone, palette)
        // Il pallino, l'unica provenienza che sopravvive al widget piccolo.
        //
        // Sotto una certa misura il kit nasconde i sottotitoli — quelli
        // delle righe e quello della testata — e il widget tornava a
        // mostrare minuti nudi, senza modo di sapere se il feed stesse
        // guardando. Fermo invece che pulsante: un RemoteViews non anima, e
        // un pallino che sta li' e' comunque la stessa convenzione che l'app
        // usa a due dita di distanza.
        //
        // Per un numero vecchio `phrase.pulse` e' gia' falso: il pallino dice
        // "il feed sta seguendo QUESTA corsa", e dopo dieci minuti di silenzio
        // dell'origine non e' piu' vero. La regola sta in `DepartureText`,
        // cosi' vale anche per la scheda fermata.
        if (phrase.pulse) {
            Box(modifier = GlanceModifier.size(6.dp).background(colore).cornerRadius(3.dp)) {}
            Spacer(GlanceModifier.width(5.dp))
        }
        // La riga di provenienza, dove il kit la nasconde: nel widget
        // piccolo il sottotitolo non c'e', e con lui "visto 15 min fa".
        if (vecchio != null) {
            Text(
                text = vecchio,
                style = engineWidgetTextStyle(color = palette.onSurfaceVariant, size = 12.sp),
                maxLines = 1,
            )
            Spacer(GlanceModifier.width(6.dp))
        }
        Text(
            text = phrase.headline,
            style = engineWidgetTextStyle(
                color = colore,
                size = if (layout.compact) 12.sp else 13.sp,
                weight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
    }
}

/**
 * Sotto questa larghezza la riga compatta non ha posto per "visto 15 min fa".
 *
 * La nota e' a larghezza fissa fra la destinazione (che prende il resto) e i
 * minuti, quindi quando manca lo spazio a uscire dal bordo e' l'ULTIMO
 * elemento: i minuti, cioe' il numero che serve. Il conto: pastiglia 34 + 8 +
 * 8 + nota (circa 88 a 12 sp) + 6 + minuti (circa 40) = 184 dp, piu' 44 di
 * margini fra widget e riga; per lasciare almeno 40 dp alla destinazione
 * servono circa 270 dp. Sotto, si tace la nota (e con lei resta spento il
 * pallino) come `oraDelDisegno` si tace sotto il suo minimo: fra una
 * destinazione tagliata a "L..." e un minuto tagliato vale di piu' il minuto.
 */
private val LARGHEZZA_MINIMA_NOTA_ETA = 270.dp

/**
 * L'eta' di un numero vecchio per la riga di un widget, o null.
 *
 * Solo dove il sottotitolo non si disegna ([mostraSottotitolo] falso) — dove
 * si disegna la dice gia' il sostegno della frase — e solo se la riga e'
 * larga abbastanza da portarla senza tagliare i minuti.
 */
internal fun notaEtaSulWidget(
    row: NextDeparture,
    mostraSottotitolo: Boolean,
    larghezza: Dp,
): String? {
    if (mostraSottotitolo || larghezza < LARGHEZZA_MINIMA_NOTA_ETA) return null
    return DepartureText.oldAgeNote(row)
}

/**
 * Il tono della partenza, coi colori del widget.
 *
 * E' la stessa scelta che `toneColor` fa in app — verde entro i cinque
 * minuti, ambra fino al quarto d'ora, rosso oltre — detta con la palette che
 * il kit dei widget ricava dalle STESSE impostazioni del tema. I due verdi
 * non sono lo stesso valore e non devono esserlo: quello del widget e'
 * tarato per leggersi su una home screen qualsiasi, dove lo sfondo non
 * e' quello dell'app.
 */
private fun colorePartenza(
    tone: DepartureText.Tone,
    palette: EngineWidgetPalette,
): ColorProvider = when (tone) {
    DepartureText.Tone.ON_TIME -> palette.successTone.content
    DepartureText.Tone.LATE -> palette.warningTone.content
    DepartureText.Tone.VERY_LATE -> palette.dangerTone.content
    DepartureText.Tone.CANCELED -> palette.attention
    DepartureText.Tone.SCHEDULED -> palette.onSurface
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

    /**
     * @param lineLinks l'indirizzo della linea di ogni riga, nello stesso
     *   ordine: si calcola qui perche' serve il lettore del bundle, che fuori
     *   di qui puo' gia' essere chiuso dallo scambio notturno.
     */
    class Ready(val board: DepartureBoard, val lineLinks: List<String>) : StopBoard
}

/**
 * Sotto questa larghezza la testata non ha posto per l'ora del disegno.
 *
 * Un 2x2 (110 dp circa) toglierebbe al nome della fermata quasi tutto lo
 * spazio per una scritta di dieci caratteri: fra un titolo tagliato a "SOD" e
 * un'ora che manca, il nome vale di piu'. Un 4x2 (250 dp circa) invece ce la
 * fa, e a un nome molto lungo toglie soltanto qualche lettera.
 */
private val LARGHEZZA_MINIMA_ORA_DISEGNO = 200.dp

/**
 * L'ora del disegno, per la testata del widget piccolo, o null.
 *
 * Nel widget grande la dice il sottotitolo ("Aggiornato alle 07:35"); in
 * quello piccolo il kit lo nasconde, e i minuti — calcolati una volta sola,
 * al momento del disegno — restavano senza un'ora accanto: chi guardava la
 * home non poteva sapere se avevano trenta secondi o cinque minuti, e un
 * ridisegno puo' tardare anche mezz'ora a freddo. Dove il sottotitolo c'e' o
 * la testata e' troppo stretta si tace, senza inventare un'ora al suo posto.
 *
 * L'ora e' quella di [Times.hhmm], come nel sottotitolo: la stessa
 * informazione con le stesse cifre, solo in forma breve. Il ritardo
 * invece non si ripete: il colore e' la puntualita', e la sua parola sta
 * nella riga solo dove c'e' spazio per una seconda riga.
 */
internal fun oraDelDisegno(computedAtEpoch: Long, compact: Boolean, larghezza: Dp): String? {
    if (!compact) return null
    if (larghezza < LARGHEZZA_MINIMA_ORA_DISEGNO) return null
    // Lo zero non e' l'una di notte: e' "non calcolato", e non si scrive.
    if (computedAtEpoch <= 0L) return null
    return "alle " + Times.hhmm(computedAtEpoch)
}

@Composable
private fun EtichettaOraDisegno(testo: String, palette: EngineWidgetPalette) {
    Text(
        text = testo,
        style = engineWidgetTextStyle(color = palette.onSurfaceVariant, size = 12.sp),
        maxLines = 1,
    )
}

/**
 * Il tabellone di un widget e la fermata a cui appartiene.
 *
 * La fermata viaggia insieme al risultato perche' il widget la legge dal suo
 * stato dentro la composizione: se cambia mentre il tabellone si ricarica,
 * quello che c'e' in mano e' della fermata di prima e non si deve mostrare
 * sotto il nome della nuova.
 */
private class Caricato(val hash: String?, val esito: StopBoard)

class StopWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as FluidTransitApp
        val settingsIniziali = app.settingsStore.current()

        // Il contatore si legge PRIMA di caricare: un ridisegno chiesto mentre
        // si carica deve fare ricaricare, non perdersi.
        val tickIniziale = WidgetTick.ticks.value
        val prefsIniziali = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val hashIniziale = prefsIniziali[KEY_STOP_HASH]
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

        // Il primo disegno ha gia' i dati: si carica qui, prima del contenuto,
        // cosi' la home non passa da un "Orari in arrivo" di un istante.
        val iniziale = Caricato(hashIniziale, loadBoard(app, hashIniziale))

        provideContent {
            // Tutto quello che il widget mostra si legge QUI, dentro il
            // contenuto, e non prima.
            //
            // In Glance 1.1 `provideGlance` gira una volta per sessione e la
            // sessione resta viva una quarantina di secondi: in quel tempo
            // `update()` e `updateAll()` rileggono solo lo stato e non
            // rilanciano questa funzione. Con tavolozza, fermata e tabellone
            // letti qui sopra e chiusi dentro il contenuto, scegliere una
            // fermata o cambiare accento entro il minuto da un disegno non
            // si vedeva: il widget restava a "Tocca per configurare" fino
            // alla sveglia seguente, anche mezz'ora dopo.
            val settings by app.settingsStore.settings.collectAsState(initial = settingsIniziali)
            val palette = remember(settings) { engineWidgetPalette(context, settings, TransitBrand) }
            val prefs = currentState<Preferences>()
            val stopHash = prefs[KEY_STOP_HASH]
            val stopName = prefs[KEY_STOP_NAME] ?: ""
            val tick by WidgetTick.ticks.collectAsState()
            val caricato by produceState(iniziale, stopHash, tick) {
                value = if (stopHash == iniziale.hash && tick == tickIniziale) {
                    iniziale
                } else {
                    Caricato(stopHash, loadBoard(app, stopHash))
                }
            }
            // Un tabellone di un'altra fermata, o non ancora arrivato: si dice
            // "in arrivo", non si mostra quello che era.
            val esito = when {
                stopHash == null -> StopBoard.NoStop
                caricato.hash == stopHash -> caricato.esito
                else -> StopBoard.NoTimetable
            }
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

            val misura = LocalSize.current
            val layout = resolveEngineWidgetLayout(misura, hasFooter = false)
            // L'ora del disegno, dove il sottotitolo non c'e'.
            val oraDisegno = board?.let {
                oraDelDisegno(it.computedAtEpoch, layout.compact, misura.width)
            }
            // Dove porta il tocco: la scelta della fermata se non ce n'e'
            // una o se quella salvata non esiste piu' negli orari di oggi,
            // la fermata stessa altrimenti.
            //
            // La fermata sparita era un vicolo cieco: il widget diceva che
            // non c'era piu', il tocco apriva la mappa su un pannello vuoto,
            // e l'unico rimedio era togliere il widget e rimetterlo.
            val daScegliere = stopHash == null || esito is StopBoard.UnknownStop
            // Il tocco apre la scelta solo se il widget conosce il proprio
            // numero: senza, apre la scheda della fermata, e una frase che
            // promette la scelta sarebbe falsa.
            val widgetValido = widgetId != android.appwidget.AppWidgetManager
                .INVALID_APPWIDGET_ID
            EngineWidgetSurface(
                palette = palette,
                layout = layout,
                onClick = actionStartActivity(
                    if (daScegliere && widgetValido) {
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
                        // Non "tocca per...": quella frase e' della riga sotto,
                        // e scritta due volte a meta' schermo non dice di piu'.
                        esito is StopBoard.UnknownStop ->
                            DepartureText.empty(DepartureText.Trouble.FERMATA_SCONOSCIUTA).short
                        board == null -> "Orari in arrivo…"
                        // Di quando sono questi numeri.
                        //
                        // Qui c'era la provenienza del tabellone — "Prossimi
                        // passaggi · orari da tabella" — che da quando ogni
                        // riga porta la sua diceva la stessa cosa due
                        // centimetri piu' in alto, tre volte sulla stessa
                        // scheda. L'ora del calcolo invece nessuna riga la
                        // sa, ed e' la cosa che su una home screen manca di
                        // piu': un widget disegnato alle sette resta li' a
                        // mostrare le sette fino a quando il sistema decide
                        // di ridisegnarlo, e niente lo dice.
                        else -> "Aggiornato alle " + Times.hhmm(board.computedAtEpoch)
                    },
                    // Sul widget piccolo il kit nasconde il sottotitolo, e con
                    // lui l'unica cosa che diceva di quando sono i numeri:
                    // restavano minuti fermi all'istante del disegno — un "4
                    // min" scritto alle 07:35 e ancora li' alle 07:40, con il
                    // bus alla fermata — e un ritardo di dodici minuti che si
                    // vedeva solo dal colore. Il kit lo lascia invece
                    // disegnare a destra del titolo.
                    trailing = if (oraDisegno != null) {
                        { EtichettaOraDisegno(oraDisegno, palette) }
                    } else {
                        null
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
                            val parole = DepartureText.emptyOnWidget(guaio, canReconfigure = widgetValido)
                            EngineWidgetRow(
                                title = parole.title,
                                subtitle = parole.short,
                                palette = palette,
                                layout = layout,
                            )
                        }

                        else -> {
                            val links = (esito as? StopBoard.Ready)?.lineLinks.orEmpty()
                            rows.take(layout.rowLimit).forEachIndexed { i, r ->
                                if (i > 0) EngineWidgetHairline(palette, layout)
                                RigaPartenza(
                                    row = r,
                                    nowEpoch = board.computedAtEpoch,
                                    palette = palette,
                                    layout = layout,
                                    larghezza = misura.width,
                                    // La pastiglia apre la linea, come in
                                    // app: e' l'unica parte della riga che
                                    // nomina qualcosa di diverso dalla
                                    // fermata che il widget gia' apre.
                                    onLineClick = links.getOrNull(i)?.let {
                                        actionStartActivity(openLink(context, it))
                                    },
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
        // IN FILA, come fanno tutti gli altri (mappa, Oggi, preferiti,
        // routine): i ritardi per primi, perche' e' il giro dei mezzi dentro
        // `refreshDelays` a stabilire da dove arriva il tempo reale. In un
        // processo appena nato lo stato parte da "solo orari", e
        // `refreshPredictions` esce subito finche' non e' "proxy": lanciate in
        // parallelo, le previsioni per fermata non venivano mai scaricate. Il
        // widget poi disegnava ogni riga come "orario da tabella" e un bus
        // cancellato come in corsa, mentre l'app diceva "dal bus". Con un
        // telefono che uccide il processo fra una sveglia e l'altra, e' ogni
        // disegno.
        //
        // Con poco tempo, e se la rete non risponde si mostrano gli orari di
        // tabella, dicendolo: e' il comportamento giusto, non una rinuncia.
        val delays0 = app.realtime.delays.value
        val predictions0 = app.realtime.predictions.value
        val vehicles0 = app.realtime.vehicles.value
        val versione0 = app.liveVersion.value
        runCatching {
            withTimeoutOrNull(REALTIME_WAIT_MS) {
                runCatching { app.realtime.refreshDelays() }
                runCatching { app.realtime.refreshPredictions() }
            }
        }
        // E si aspetta che l'app abbia digerito quello che e' arrivato.
        //
        // I ritardi, le corse cancellate e i mezzi vivi entrano nei loro
        // modelli da collettori dell'Application, che partono DOPO che il
        // giro ha consegnato: uno scatto preso subito leggeva i modelli a
        // meta' — corse cancellate che non lo erano ancora, un verso solo
        // della fermata offline — e i gruppi di banchine, che nascono nello
        // stesso modo, potevano mancare del tutto. Ogni collettore alza
        // `liveVersion` a lavoro finito: si aspetta un rialzo per ogni fonte
        // cambiata, con un tetto, perche' se due consegne si fondono i rialzi
        // sono di meno e non si deve aspettare all'infinito.
        val cambiate = listOf(
            app.realtime.delays.value !== delays0,
            app.realtime.predictions.value !== predictions0,
            app.realtime.vehicles.value !== vehicles0,
        ).count { it }
        if (cambiate > 0) {
            withTimeoutOrNull(COLLECTORS_WAIT_MS) {
                app.liveVersion.first { it >= versione0 + cambiate }
            }
        }
        withTimeoutOrNull(COLLECTORS_WAIT_MS) { app.stopGroups.first { it != null } }
        val board = app.departureBoards.snapshot(listOf(stop), limit = 5)
        return StopBoard.Ready(
            board = board,
            lineLinks = board.rows.map {
                Deeplink.route(java.lang.Long.toHexString(ready.reader.routeIdHash(it.routeIndex)))
            },
        )
    }

    private companion object {
        /** Quanto si aspetta il bundle: aprirlo e' una mmap, non un download. */
        const val BUNDLE_WAIT_MS = 5_000L

        /**
         * Quanto si aspettano i ritardi e le previsioni, una dopo l'altra.
         *
         * Il disegno non e' dentro la decina di secondi di un `goAsync`: la
         * ricevente accoda il lavoro e lo fa Glance, in un suo worker. Ma il
         * widget deve comunque arrivare: cinque secondi bastano a un giro di
         * ritardi e uno di previsioni su una rete normale.
         */
        const val REALTIME_WAIT_MS = 5_000L

        /** Quanto si aspetta che i collettori dell'app finiscano di digerire. */
        const val COLLECTORS_WAIT_MS = 1_000L
    }
}

/**
 * Quello che il widget della routine ha letto, e quando.
 *
 * Si rilegge dal disco a ogni cambio di contatore o di routine, e l'ora si
 * prende insieme: `adesso` decide se il consiglio vale ancora, e un'ora
 * presa a parte da' una lettura che non torna con se stessa.
 */
private class FotoRoutine(
    val oggi: LocalDate,
    val adesso: Long,
    val routines: List<dev.antigravity.fluidtransit.data.routines.Routines.Routine>,
)

private fun leggiRoutine(context: Context): FotoRoutine {
    val oggi = LocalDate.now(Ftb.ROME)
    return FotoRoutine(
        oggi = oggi,
        adesso = Instant.now().epochSecond,
        routines = dev.antigravity.fluidtransit.data.routines.Routines(context).list(),
    )
}

class RoutineWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as FluidTransitApp
        val settingsIniziali = app.settingsStore.current()

        // I due segnali si leggono prima dei dati: un cambio che arriva mentre
        // si legge deve far rileggere, non perdersi. Vedi [WidgetTick].
        val tickIniziale = WidgetTick.ticks.value
        val cambiIniziali =
            dev.antigravity.fluidtransit.data.routines.Routines.changes.value
        val iniziale = leggiRoutine(context)

        provideContent {
            // Tavolozza e routine si leggono dentro il contenuto, non prima:
            // in una sessione Glance gia' aperta `updateAll` non rilancia
            // `provideGlance`, e con tutto chiuso qui sopra cancellare o
            // mettere in pausa una routine entro il minuto da un disegno
            // lasciava sulla home "Esci alle 07:25" per una routine che non
            // c'era piu', e toccarla non apriva niente. Lo stesso per un
            // consiglio appena calcolato, e per due accenti provati di fila.
            val settings by app.settingsStore.settings.collectAsState(initial = settingsIniziali)
            val palette = remember(settings) { engineWidgetPalette(context, settings, TransitBrand) }
            val tick by WidgetTick.ticks.collectAsState()
            val cambi by dev.antigravity.fluidtransit.data.routines.Routines.changes
                .collectAsState()
            val foto by produceState(iniziale, tick, cambi) {
                value = if (tick == tickIniziale && cambi == cambiIniziali) {
                    iniziale
                } else {
                    withContext(Dispatchers.IO) { leggiRoutine(context) }
                }
            }
            val oggi = foto.oggi
            val adesso = foto.adesso
            val todayRoutine = dev.antigravity.fluidtransit.data.routines.Routines.relevantToday(
                foto.routines,
                oggi,
                adesso,
            )

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

                        // Gli stati li decide Routines.adviceState e le parole
                        // RoutineText: prima qui "nessun bus utile" diventava
                        // "il consiglio arriva da solo", e un consiglio di tre
                        // quarti d'ora prima si diceva "coi ritardi live".
                        else -> {
                            val riga = dev.antigravity.fluidtransit.data.routines.RoutineText
                                .widget(todayRoutine, oggi, adesso, layout.compact)
                            EngineWidgetRow(
                                title = riga.title,
                                subtitle = riga.subtitle,
                                palette = palette,
                                layout = layout,
                                tone = if (consiglioValido) palette.primaryTone else palette.neutralTone,
                                trailing = riga.trailing,
                            )
                        }
                    }
                }
            }
        }
    }
}
