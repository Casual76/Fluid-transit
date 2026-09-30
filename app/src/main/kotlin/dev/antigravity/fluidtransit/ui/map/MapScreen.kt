package dev.antigravity.fluidtransit.ui.map

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocationSearching
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.luminance
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.navigationBarsPadding
import java.time.Instant
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidNotification
import dev.antigravity.fluidengine.ui.fluid.FluidNotificationTone
import dev.antigravity.fluidengine.ui.fluid.FluidTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.ui.nav.NavOverlay
import dev.antigravity.fluidtransit.data.bundle.BundleManager.BundleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * La schermata mappa: nessun titolo, mappa a tutto schermo, chrome in vetro
 * sopra — barra di ricerca col microfono, chip dei filtri, cambio livello in
 * basso a sinistra sopra l'attribuzione, tasto posizione in basso a destra.
 * Tutto come da spec decisa con l'utente il 31/08.
 */
/**
 * Oltre questa eta' del feed i bus non si disegnano: meglio una mappa senza
 * mezzi per due secondi che mezzi dove non sono. Il tetto vero dell'origine
 * e' ~120 s, quindi tre minuti separano "normale" da "il proxy dormiva".
 *
 * Il numero e' quello del vocabolario, perche' la scheda di un bus smette di
 * dire "live" allo stesso istante in cui la mappa lo toglie.
 */
private const val STALE_HIDE_SECONDS =
    dev.antigravity.fluidtransit.routing.Words.POSITION_STALE_SECONDS.toLong()

/**
 * Sotto questa distanza partenza e arrivo sono lo stesso posto.
 *
 * Sessanta metri: la larghezza di un incrocio. Chi deve fare sessanta metri
 * li fa a piedi senza chiederlo a un'app, e un viaggio da qui a qui e' la
 * risposta giusta a una domanda che nessuno ha fatto.
 */
private const val SAME_PLACE_M = 60.0

@androidx.compose.runtime.Composable
@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun MapScreen(
    app: FluidTransitApp,
    backdrop: GlassBackdropState,
    onTabBarHidden: (Boolean) -> Unit = {},
    intent: MapIntent? = null,
    onIntentConsumed: () -> Unit = {},
    onOpenDataStatus: () -> Unit = {},
    onOpenAlerts: () -> Unit = {},
) {
    val context = LocalContext.current
    // Lo scuro della mappa segue il tema DELL'APP, non quello di sistema:
    // chi forza "Scuro" dalle Impostazioni deve vedere anche la mappa scura.
    // La luminanza dello sfondo Material e' la verita' gia' risolta da
    // FluidTheme, qualunque sia la combinazione di impostazioni.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bundleState by app.bundleManager.state.collectAsStateWithLifecycle()
    val ready = bundleState as? BundleState.Ready

    // Le scelte fatte sulla mappa si ritrovano.
    //
    // La vista e il filtro stavano solo nello stato salvabile: sopravvivevano
    // a una rotazione e non alla chiusura dell'app. Chi metteva il filtro su
    // "Urbani" o passava al satellite li ritrovava come prima il giorno dopo.
    // Sono piccole, ma sono della stessa famiglia del "si comporta in modo
    // diverso ogni volta": una scelta fatta apposta che sparisce da sola. E
    // ricordarle non nasconde niente, perche' i chip e il tasto dicono sempre
    // in che stato sono.
    val mapPrefs = remember(context) { MapPrefs(context) }
    var mode by rememberSaveable { mutableStateOf(mapPrefs.mode) }
    var filter by rememberSaveable { mutableStateOf(mapPrefs.filter) }
    var follow by rememberSaveable { mutableStateOf(FollowMode.FREE) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var panel by rememberSaveable(stateSaver = PanelSaver) {
        mutableStateOf<Panel?>(null)
    }
    var routeDirection by rememberSaveable { mutableStateOf(0) }

    /**
     * Il bundle con cui e' stato aperto il pannello. Il guardiano che lo
     * confronta sta piu' sotto, dopo `exitRouteMode`, perche' quando butta un
     * pannello deve rimettere a posto anche la mappa.
     */
    var panelBuild by rememberSaveable { mutableStateOf(0L) }

    // Lo zoom corrente (a camera ferma): decide se i bus vivi si scaricano.
    //
    // Salvabile, non solo `remember`: e' proprio questo valore che riaccende
    // il polling, e tornandoci sopra da un'altra scheda ripartiva da
    // HOME_ZOOM (7.6), sotto la soglia. Si tornava sulla mappa e il live era
    // spento finche' non si zoomava di nuovo a mano.
    var cameraZoom by rememberSaveable { mutableStateOf(MapCatalog.HOME_ZOOM) }

    // La modalita' linea (e la scheda corsa, che vive nello stesso posto)
    // prende il posto della tab bar: la shell lo sa da qui.
    var navActive by remember { mutableStateOf(false) }

    /** In navigazione: l'utente ha spanato la mappa e adesso comanda lui. */
    var navManuale by remember { mutableStateOf(false) }

    /** Quanto e' alta la card del viaggio: la camera ci lascia lo spazio. */
    var navCardHeight by remember { mutableStateOf(0) }


    // Precisa O approssimativa. Da Android 12 chi apre la finestra del
    // permesso puo' scegliere "approssimativa", che concede solo COARSE: qui
    // si guardava soltanto FINE, e per l'app era come aver detto di no —
    // niente "qui intorno", niente mirino, e il tasto che richiedeva un
    // permesso gia' dato. Per le fermate vicine l'approssimativa basta.
    fun posizioneConcessa() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    var locationGranted by remember { mutableStateOf(posizioneConcessa()) }
    // Il permesso dell'app e la Posizione di Android sono DUE interruttori.
    //
    // Il secondo si spegne dal pannello rapido, ed e' il caso comune: con il
    // permesso concesso e la Posizione spenta il mirino cambiava icona e la
    // mappa restava ferma, senza un rilevamento in arrivo e senza una parola
    // che dicesse perche'. Si tiene qui per poterlo dire, e per non far finta
    // di seguire una posizione che non c'e'.
    var serviceOn by remember { mutableStateOf(posizioneDiSistemaAccesa(context)) }
    // La mappa e' in attesa di una posizione: o non si e' potuta seguire
    // all'avvio, o si e' mandata la persona ad accenderla. Quando torna
    // accesa si segue, ma solo se nel frattempo non ha preso in mano la
    // mappa — un gesto la butta a FREE e toglie l'attesa.
    var attendiPosizione by remember { mutableStateOf(false) }
    /** Cosa dire al ritorno dalle impostazioni di Android: vedi `avvisa`. */
    var ritornoDaImpostazioni by remember { mutableStateOf<RitornoDaImpostazioni?>(null) }
    // E si riguarda a ogni ritorno nell'app: chi va nelle Impostazioni di
    // Android e accende la posizione da li', tornando trovava l'app
    // convinta del contrario finche' non la chiudeva.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        val adesso = posizioneConcessa()
        if (adesso != locationGranted) locationGranted = adesso
        val acceso = posizioneDiSistemaAccesa(context)
        if (acceso != serviceOn) serviceOn = acceso
    }

    val controller = remember { TransitMapController(context) }
    val scope = rememberCoroutineScope()

    val notifications = dev.antigravity.fluidengine.ui.fluid.LocalFluidNotificationHostState.current

    // L'Activity dietro il Context: in Compose puo' essere un ContextWrapper.
    fun activityCorrente(): Activity? =
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .firstOrNull()

    // Il permesso negato per sempre non deve diventare un tasto morto.
    //
    // Toccando il mirino, se il permesso e' stato negato due volte, Android
    // non mostra piu' nessuna finestra: la richiesta torna indietro negata
    // all'istante e il tasto non faceva niente. Un tasto che non fa niente
    // e' peggio di un tasto che manca, perche' insegna a non fidarsi anche
    // degli altri — e questo e' il tasto con cui si chiede "dove sono".
    //
    // Dopo la finestra, negato piu' "non mostrare la spiegazione" vuol dire
    // esattamente questo, ed e' l'unico momento in cui quella combinazione
    // non e' ambigua: prima della prima richiesta e' falsa anche per chi non
    // ha mai deciso niente. Vale per ogni permesso, non solo per la posizione.
    fun negatoPerSempre(vararg permessi: String): Boolean {
        val activity = activityCorrente() ?: return false
        return permessi.none {
            androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }
    }

    // Il modo di dire "questo non parte, e questo e' il perche'".
    //
    // Sta qui e non in ogni sito di chiamata perche' erano gia' due, e
    // le due copie sarebbero divergenti al primo ritocco: il permesso della
    // posizione e quello delle notifiche negano allo stesso modo — la
    // richiesta torna indietro all'istante e il tasto sembra morto — e si
    // rimediano allo stesso modo, con una parola e con l'interruttore.
    fun mostra(id: String, titolo: String, messaggio: String) {
        scope.launch {
            notifications?.show(
                FluidNotification(
                    id = id,
                    title = titolo,
                    message = messaggio,
                    tone = FluidNotificationTone.Warning,
                ),
            )
        }
    }

    /**
     * Quando si porta la persona nelle impostazioni di Android, la parola si
     * dice al ritorno, e solo se e' ancora vera ([ancoraVero]).
     *
     * Messa in coda prima di partire, la notizia non faceva in tempo a
     * comparire — il pannello aspetta che l'app sia davanti — e compariva al
     * ritorno: la persona aveva appena acceso la posizione, la mappa
     * cominciava a seguirla, e un banner giallo diceva "La posizione del
     * telefono e' spenta... Ti porto dov'e' l'interruttore".
     */
    fun avvisa(
        id: String,
        titolo: String,
        messaggio: String,
        impostazioni: Intent? = null,
        ancoraVero: () -> Boolean = { true },
    ) {
        if (impostazioni == null) {
            mostra(id, titolo, messaggio)
            return
        }
        val aperta = runCatching { activityCorrente()?.startActivity(impostazioni) }.isSuccess
        if (aperta) {
            ritornoDaImpostazioni = RitornoDaImpostazioni(id, titolo, messaggio, ancoraVero)
        } else {
            mostra(id, titolo, messaggio)
        }
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        val r = ritornoDaImpostazioni ?: return@LifecycleEventEffect
        ritornoDaImpostazioni = null
        if (r.ancoraVero()) mostra(r.id, r.titolo, r.messaggio)
    }

    // La Posizione di Android spenta, col permesso dell'app gia' concesso.
    fun posizioneSpenta(apri: Boolean) {
        serviceOn = false
        attendiPosizione = true
        avvisa(
            id = "posizione-spenta",
            titolo = "La posizione del telefono e' spenta",
            messaggio = "Accendila dalle impostazioni di Android: e' cosi' che la " +
                "mappa ti trova.",
            impostazioni = if (apri) {
                Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            } else {
                null
            },
            ancoraVero = { !posizioneDiSistemaAccesa(context) },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { risposte ->
        val granted = risposte.values.any { it } || posizioneConcessa()
        locationGranted = granted
        if (granted) {
            // Il permesso non basta: con la Posizione di Android spenta non
            // arriva nessun rilevamento, e segnare "seguo" sarebbe dire una
            // cosa che non sta succedendo.
            if (posizioneDiSistemaAccesa(context)) {
                follow = FollowMode.FOLLOW
            } else {
                posizioneSpenta(apri = false)
            }
        } else if (
            negatoPerSempre(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        ) {
            avvisa(
                id = "posizione-negata",
                titolo = "Il permesso di posizione e' negato",
                messaggio = "Android non lo chiede piu': si accende dalle impostazioni " +
                    "dell'app. Senza, la mappa usa il centro di quello che stai guardando.",
                impostazioni = Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", context.packageName, null),
                ),
                ancoraVero = { !posizioneConcessa() },
            )
        }
    }

    // Il tocco sul mirino, e sulla capsula "qui intorno" che fa la stessa cosa.
    //
    // La Posizione di Android si rilegge ADESSO e non dallo stato: chi la
    // accende o la spegne dal pannello rapido con la mappa aperta non fa
    // scattare nessun ritorno nell'app, e il tasto deciderebbe su un dato
    // vecchio. La decisione a tre rami sta in `mirinoAction`, con il suo test.
    fun toccaPosizione(followAttuale: FollowMode) {
        val acceso = posizioneDiSistemaAccesa(context)
        serviceOn = acceso
        when (val azione = mirinoAction(locationGranted, acceso, followAttuale)) {
            MirinoAction.ChiediPermesso -> permissionLauncher.launch(POSIZIONE)
            MirinoAction.ApriImpostazioni -> posizioneSpenta(apri = true)
            is MirinoAction.Segui -> {
                attendiPosizione = false
                follow = azione.modo
            }
        }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                query = spoken
                searchOpen = true
            }
        }
    }

    // Il mic di sistema: il ripiego di sempre, quando il proxy vocale non puo'.
    fun launchSystemMic(permessoNegato: Boolean = false) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Che fermata, linea o posto cerchi?")
        }
        // Un telefono senza un servizio di riconoscimento vocale — senza
        // Google, LineageOS, certi Huawei: proprio quelli a cui l'app pensa
        // quando evita i Play Services — non ha nessuno che risponda a questo
        // intent, e il lancio lancia ActivityNotFoundException. Il runCatching
        // di prima la inghiottiva: il tasto piu' vistoso della barra non
        // faceva niente, ne' una tastiera ne' una parola. Lo stesso valeva
        // per il permesso audio negato, che finisce in questa funzione.
        //
        // Niente resolveActivity ne' isRecognitionAvailable: il manifest non
        // ha <queries>, e da Android 11 direbbero "no" anche a un telefono
        // che il riconoscimento ce l'ha. Si prova, e si cade in piedi.
        fun senzaRiconoscimentoVocale() {
            // Si apre la barra: la tastiera sale da sola e si scrive a mano.
            searchOpen = true
            if (permessoNegato) {
                // Con l'assistente acceso la voce dell'app funzionerebbe: manca
                // solo il permesso. Dire "il riconoscimento non c'e'" era la
                // frase del caso opposto, e chi la leggeva non riprovava.
                avvisa(
                    id = "mic-negato",
                    titolo = "Il microfono e' spento per l'app",
                    messaggio = "Consenti il microfono dalle impostazioni dell'app per " +
                        "parlare all'assistente, oppure scrivi nella barra.",
                )
            } else {
                avvisa(
                    id = "mic-assente",
                    titolo = "Il riconoscimento vocale non c'e' su questo telefono",
                    messaggio = "Scrivi il nome della fermata, della linea o del posto nella barra.",
                )
            }
        }
        try {
            micLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            senzaRiconoscimentoVocale()
        } catch (_: SecurityException) {
            // Un servizio che c'e' ma non si lascia avviare: per chi tocca
            // il tasto e' la stessa cosa che non averlo.
            senzaRiconoscimentoVocale()
        }
    }

    // L'assistente esiste solo se c'e' una chiave verificata e l'interruttore
    // e' acceso: senza, il microfono resta quello di sistema, che trascrive e
    // basta — cioe' com'era prima della Fase 8.
    val assistantEnabled by app.assistant.enabled.collectAsStateWithLifecycle(initialValue = false)
    var assistantOpen by remember { mutableStateOf(false) }
    /** Si sta scrivendo il nome di un posto: il pannello sale sopra la tastiera. */
    var placeEditing by remember { mutableStateOf(false) }
    // La tab bar si toglie di mezzo anche per l'assistente. Si apre nello
    // stesso posto in fondo, e la barra — disegnata dopo, sopra — gli copriva
    // la riga di scrittura e il tasto per fermarlo: con la tastiera chiusa
    // (sempre, a voce) i tocchi su "Stop" finivano su Mappa o Oggi.
    LaunchedEffect(panel, navActive, assistantOpen) {
        onTabBarHidden(
            navActive || assistantOpen ||
                panel is Panel.RouteMini || panel is Panel.RouteFull ||
                panel is Panel.TripMini || panel is Panel.TripFull,
        )
    }
    var assistantMode by remember {
        mutableStateOf(dev.antigravity.fluidtransit.ai.orchestrator.AskMode.VOICE)
    }
    var assistantQuestion by remember { mutableStateOf("") }

    fun openAssistant(
        mode: dev.antigravity.fluidtransit.ai.orchestrator.AskMode,
        question: String = "",
    ) {
        assistantMode = mode
        assistantQuestion = question
        assistantOpen = true
        searchOpen = false
        query = ""
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && assistantEnabled) {
            openAssistant(dev.antigravity.fluidtransit.ai.orchestrator.AskMode.VOICE)
        } else {
            launchSystemMic(permessoNegato = !granted && assistantEnabled)
        }
    }

    // Il permesso notifiche (Android 13+): si chiede quando nasce la prima
    // routine, cioe' quando la notifica ha un motivo di esistere.
    //
    // Il perche' si e' chiesto decide cosa si perde se la risposta e' no: la
    // routine vive di quell'avviso ("Esci alle 8:12"), il viaggio ha la sua
    // scheda dentro l'app e perde solo l'avviso a schermo spento.
    var notifMotivo by remember { mutableStateOf(NotifMotivo.ROUTINE) }

    // Il rifiuto delle notifiche va detto. Il risultato della richiesta
    // finiva in una lambda vuota: si creava la routine, il pannello diceva
    // "ti diro' io quando uscire", Oggi la elencava come attiva, e ogni
    // notifica "Esci alle..." cadeva in silenzio — chi lo scopriva lo
    // scopriva perdendo l'autobus. `perSempre` vuol dire che Android non
    // mostra piu' la finestra: e' l'unico caso in cui si porta alle
    // impostazioni, come per la posizione — e solo per la routine, che senza
    // l'avviso perde il suo scopo. Per un viaggio si dice e basta: portare
    // via dall'app chi ha appena premuto "Avvia", con l'autobus in arrivo,
    // sarebbe peggio del silenzio.
    fun notificheSpente(motivo: NotifMotivo, perSempre: Boolean) {
        val apri = perSempre && motivo == NotifMotivo.ROUTINE
        avvisa(
            id = "notifiche-spente",
            titolo = "Le notifiche sono spente",
            messaggio = when (motivo) {
                // La frase di cosa si perde e' quella del pannello della routine:
                // stanno in `RoutineText` perche' non dicano due cose diverse.
                NotifMotivo.ROUTINE ->
                    "La routine e' salvata. " +
                        dev.antigravity.fluidtransit.data.routines.RoutineText.ALERTS_OFF

                NotifMotivo.VIAGGIO ->
                    "Il viaggio lo segui restando nell'app: fuori dall'app non ti " +
                        "avviso quando scendere."
            },
            impostazioni = if (apri) {
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            } else {
                null
            },
            ancoraVero = {
                !androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
            },
        )
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            notificheSpente(
                notifMotivo,
                perSempre = negatoPerSempre(Manifest.permission.POST_NOTIFICATIONS),
            )
        }
    }

    // Da chiamare quando nasce qualcosa che vive di notifiche. Le cose che
    // dipendono dall'avviso si promettono solo se l'avviso puo' arrivare:
    // `areNotificationsEnabled` dice tutt'e due i casi — il permesso di
    // Android 13 negato e l'interruttore dell'app spento nelle impostazioni,
    // che sotto Android 13 e' l'unico modo di non riceverle.
    fun chiediNotifiche(motivo: NotifMotivo) {
        if (androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return
        }
        notifMotivo = motivo
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Il permesso c'e' (o non esiste) e le notifiche sono spente lo
            // stesso: non c'e' una finestra da mostrare, c'e' l'interruttore.
            notificheSpente(motivo, perSempre = true)
        }
    }

    // Un posto solo per far partire la navigazione.
    //
    // Il permesso notifiche lo chiedeva solo il tasto "Avvia" del dettaglio
    // viaggio; "Sono su questo bus" e "avvia la navigazione" dell'assistente
    // partivano senza. Su un'installazione nuova, chi saliva sul bus e
    // bloccava il telefono non vedeva ne' la notifica del servizio ne'
    // "Scendi alla prossima" — proprio il caso in cui l'avviso serve di piu',
    // e nessuno gli diceva che non sarebbe arrivato. Tre siti di chiamata
    // che devono fare la stessa cosa divergono: per questo e' una funzione.
    fun avviaNavigazione(plan: dev.antigravity.fluidtransit.data.nav.NavPlan) {
        app.navigation.start(context, plan)
        chiediNotifiche(NotifMotivo.VIAGGIO)
    }

    // L'indice di ricerca si costruisce una volta per bundle, fuori dal main.
    // Aspetta i gruppi di banchine: senza, uscirebbero le righe doppie che
    // l'indice esiste per evitare.
    val stopGroups by app.stopGroups.collectAsStateWithLifecycle()

    // L'indice lo costruisce l'Application: qui si guarda e basta. Prima lo
    // costruiva questa schermata e lo prestava all'assistente, che quindi
    // senza mappa aperta cercava dentro il niente.
    val searchIndex by app.searchIndex.collectAsStateWithLifecycle()
    val searchIndexFailed by app.searchIndexFailed.collectAsStateWithLifecycle()

    // La geometria delle tratte, decodificata pigramente: e' quella che fa
    // correre i bus sulla strada invece di attraversare gli isolati.
    val pathCache = remember(ready?.buildId) {
        ready?.reader?.let { PathCache(it, app.applicationScope) }
    }
    LaunchedEffect(pathCache) { controller.setPathCache(pathCache) }

    // Quello che l'assistente non puo' sapere da solo: dove sei e dove stai
    // guardando. Il resto adesso se lo prende da se'.
    LaunchedEffect(Unit) {
        app.assistantBridge.location = { controller.lastLocation() }
        app.assistantBridge.camera = { controller.cameraCenter() }
    }

    fun exitRouteMode() {
        controller.exitRouteMode()
        controller.setSelectedBus(null)
        controller.clearPlaceMarker()
        controller.clearJourney()
        panel = null
    }

    /**
     * Il guardiano del bundle.
     *
     * Un pannello porta a volte degli indici, e gli indici valgono solo nel
     * bundle che li ha prodotti: dopo lo scambio notturno puntano a un'altra
     * linea. Era scritto per l'app sfrattata dalla memoria e ripresa dopo la
     * notte, e buttava ogni pannello — ma gira anche con l'app in uso, quando
     * il nuovo bundle arriva 5-15 s dopo che si e' aperta una fermata: il
     * pannello spariva senza ragione, e con una linea o un bus aperti la mappa
     * restava ridotta a quello, con la tab bar tornata e nessuna uscita
     * visibile. Adesso ogni pannello ha il suo destino (`panelAfterSwap`), e
     * quando se ne butta uno si rimette a posto anche la mappa.
     */
    LaunchedEffect(ready?.buildId) {
        val id = ready?.buildId ?: return@LaunchedEffect
        val reader = ready?.reader
        if (panelBuild != 0L && panelBuild != id && reader != null) {
            val fate = panelAfterSwap(
                panel,
                findTrip = { runCatching { reader.findTripByIdHash(it) }.getOrDefault(-1) },
                findRoute = { runCatching { reader.findRouteByIdHash(it) }.getOrDefault(-1) },
            )
            when (fate) {
                PanelFate.Keep -> Unit
                PanelFate.Drop -> exitRouteMode()
                is PanelFate.Replace -> {
                    // L'evidenza di un viaggio scelto apparteneva al bundle
                    // vecchio: l'elenco lo riaccende quando ne viene scelto un
                    // altro.
                    if (panel is Panel.JourneyDetail) controller.clearJourney()
                    panel = fate.panel
                }
            }
        }
        panelBuild = id
    }

    // Il colore d'accento per il segnaposto: lo stesso ametista del tema.
    val accentArgb = MaterialTheme.colorScheme.primary.let {
        android.graphics.Color.argb(255, (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt())
    }

    // Il pannello del luogo: dalla ricerca, da un posto salvato o dal
    // tieni-premuto sulla mappa. Marker + volo + pannello, come deciso.
    fun showPlace(ref: PlaceRef, fly: Boolean = true) {
        controller.exitRouteMode()
        controller.setSelectedBus(null)
        controller.clearJourney()
        follow = FollowMode.FREE
        controller.showPlaceMarker(ref.lat, ref.lon, accentArgb)
        if (fly) controller.flyTo(ref.lat, ref.lon, maxOf(15.2, cameraZoom))
        panel = Panel.Place(ref)
    }

    controller.onStopTap = { tap ->
        // Aprire una fermata chiude la modalita' linea: il pannello torna scheda fermata.
        controller.exitRouteMode()
        controller.setSelectedBus(null)
        panel = Panel.Stop(tap)
    }
    controller.onEmptyTap = {
        // Il tocco a vuoto e' una delle tre uscite decise.
        exitRouteMode()
    }
    controller.onMapLongPress = { lat, lon ->
        // Tieni premuto = un posto senza nome OSM, pronto da salvare.
        searchOpen = false
        showPlace(PlaceRef("Punto sulla mappa", "", lat, lon), fly = false)
    }
    controller.onGesture = {
        // In navigazione spanare la mappa non e' un incidente: e' il modo in
        // cui si guarda avanti sul percorso. Prima il tracking riprendeva
        // subito il sopravvento e non c'era nessun tasto per uscirne, quindi
        // l'unico modo di guardare dove si stava andando era terminare il
        // viaggio.
        if (navActive) navManuale = true
        if (follow != FollowMode.FREE) follow = FollowMode.FREE
        // Chi prende in mano la mappa non sta piu' aspettando la posizione:
        // accenderla dopo non deve strapparla da dove l'ha portata.
        attendiPosizione = false
    }

    // Accende una linea sulla mappa: la tratta e le SUE fermate, il resto
    // della rete si toglie di mezzo. Restituisce il riquadro che la contiene
    // (minLat, minLon, maxLat, maxLon), o null se il bundle non le conosce
    // nessuna fermata — e allora non si accende niente: una rete spenta per
    // evidenziare il vuoto e' peggio di una rete accesa.
    //
    // Sta a se' perche' la chiamano in due: chi apre una linea o un bus, e chi
    // RITROVA un pannello. Il pannello sopravvive a una ricreazione
    // dell'Activity e a un giro in Avvisi o in Oggi, il controller no, e
    // accendere la linea la faceva solo chi apriva il pannello: tornando
    // c'era la scheda della linea 23 sopra la rete intera.
    //
    // La lettura del bundle e' fuori dal main, e in un runCatching perche' il
    // reader puo' chiudersi sotto: lo scambio notturno succede a app aperta.
    suspend fun highlightRoute(routeIndex: Int): DoubleArray? {
        val reader = ready?.reader ?: return null
        val letto = withContext(Dispatchers.Default) {
            runCatching {
                var minLat = 90.0
                var maxLat = -90.0
                var minLon = 180.0
                var maxLon = -180.0
                val hashes = LinkedHashSet<String>()
                for (p in reader.patternsOfRoute(routeIndex)) {
                    val n = reader.patternStopCount(p)
                    for (i in 0 until n) {
                        val s = reader.patternStop(p, i)
                        hashes.add(java.lang.Long.toHexString(reader.stopIdHash(s)))
                        val lat = reader.stopLat(s)
                        val lon = reader.stopLon(s)
                        if (lat < minLat) minLat = lat
                        if (lat > maxLat) maxLat = lat
                        if (lon < minLon) minLon = lon
                        if (lon > maxLon) maxLon = lon
                    }
                }
                if (minLat > maxLat) {
                    null
                } else {
                    Triple(
                        java.lang.Long.toHexString(reader.routeIdHash(routeIndex)),
                        hashes.toTypedArray(),
                        doubleArrayOf(minLat, minLon, maxLat, maxLon),
                    )
                }
            }.getOrNull()
        } ?: return null
        controller.enterRouteMode(letto.first, letto.second)
        return letto.third
    }

    // Il tap sulla pillola di una linea: la mappa si pulisce (resta la
    // tratta accesa e le SUE fermate), la camera inquadra tutto, e il
    // pannello si trasforma nello stato mini della scheda linea.
    // La stessa meccanica risponde al tap su un bus vivo.
    fun showRoute(routeIndex: Int) {
        if (ready?.reader == null) return
        follow = FollowMode.FREE
        routeDirection = 0
        panel = Panel.RouteMini(routeIndex)
        scope.launch {
            val riquadro = highlightRoute(routeIndex) ?: return@launch
            controller.flyToBounds(riquadro[0], riquadro[1], riquadro[2], riquadro[3])
        }
    }

    // --- la navigazione a bordo (Fase 7) -----------------------------------
    val navState by app.navigation.state.collectAsStateWithLifecycle()
    LaunchedEffect(navState) { navActive = navState != null }
    // La card del viaggio aperta o ridotta. Sta qui e non nella card, che
    // esce di scena quando si apre un pannello; e si riapre a ogni viaggio
    // nuovo, perche' chi ha appena premuto Parti vuole vedere cosa fare.
    var navEsteso by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(navState == null) { if (navState == null) navEsteso = true }
    // La mano dell'utente batte l'inseguimento finche' non tocca il tasto
    // per tornare a seguire. Si riarma a ogni cambio di fase: dopo essere
    // saliti sul bus, il punto di vista che serve e' un altro.
    LaunchedEffect(navState?.phase, navState != null) { navManuale = false }
    // A viaggio finito le inquadrature della navigazione si spengono tutte e
    // due. Si spegneva solo la bussola: finendo il viaggio a piedi restava
    // la camera della camminata — piatta, girata, a zoom 16 — senza piu' una
    // card che spiegasse perche'.
    LaunchedEffect(navState == null) {
        if (navState == null && (follow == FollowMode.COMPASS || follow == FollowMode.NAV_CAMMINO)) {
            follow = FollowMode.FOLLOW
        }
    }

    // --- il tempo reale ---------------------------------------------------
    val rt = app.realtime
    val rtVehicles by rt.vehicles.collectAsStateWithLifecycle()
    val rtDelays by rt.delays.collectAsStateWithLifecycle()
    val rtStatus by rt.status.collectAsStateWithLifecycle()
    val online by app.online.collectAsStateWithLifecycle()

    // Quando (orologio del telefono) e' stato risolto lo snapshot qui sotto.
    //
    // L'eta' del rilevamento dentro `BusMeta` e' quella ALL'ARRIVO, e poi
    // resta li': se il feed della Regione si ferma o la copertura cade, il
    // proxy risponde 304, lo snapshot non cambia e la scheda della corsa
    // continuava a scrivere "aggiornata 40 s fa" per venti minuti, mentre la
    // mappa i bus li aveva gia' tolti dopo tre. Con questo istante l'eta'
    // mostrata cresce col tempo. Telefono contro telefono: lo scarto degli
    // orologi si annulla.
    var resolvedAtSec by remember { mutableStateOf(0L) }

    // Lo snapshot risolto contro il bundle: hash → indici → colori. Fuori
    // dal main, a ogni poll.
    val resolved by produceState<ResolvedRt?>(
        initialValue = null,
        rtVehicles, rtDelays, ready?.buildId,
    ) {
        val reader = ready?.reader
        val v = rtVehicles
        if (reader == null || v == null) {
            value = null
            return@produceState
        }
        val nuovo = withContext(Dispatchers.Default) { resolveRt(reader, v, rtDelays) }
        // Prima l'istante, poi il valore: chi legge il secondo trova il primo
        // gia' aggiornato.
        resolvedAtSec = Instant.now().epochSecond
        value = nuovo
        nuovo.resolvedPercent?.let { rt.resolvedPercent.value = it }
    }

    // Il tocco su un bus: modalita' linea + scheda corsa, come deciso. La
    // tratta si accende, la mappa si pulisce, il bus resta evidenziato.
    fun showTrip(ref: TripRef, focus: Pair<Double, Double>?) {
        if (ready?.reader == null) return
        follow = FollowMode.FREE
        panel = Panel.TripMini(ref)
        controller.setSelectedBus(ref.vehKey)
        if (ref.routeIndex >= 0) {
            scope.launch {
                highlightRoute(ref.routeIndex)
                focus?.let { (la, lo) -> controller.flyTo(la, lo, maxOf(14.0, cameraZoom)) }
            }
        } else {
            // Linea sconosciuta al bundle: niente da accendere, ma la
            // posizione live e la scheda minima ci sono lo stesso.
            controller.exitRouteMode()
            focus?.let { (la, lo) -> controller.flyTo(la, lo, maxOf(14.0, cameraZoom)) }
        }
    }

    controller.onBusTap = { tap ->
        val meta = resolved?.busMetaByKey?.get(tap.vehKey)
        val ref = if (meta != null) {
            TripRef(meta.vehKey, meta.tripHash, meta.routeHash, meta.tripIndex, meta.routeIndex)
        } else {
            TripRef(
                vehKey = tap.vehKey,
                tripHash = tap.tripHashHex.toULongOrNull(16)?.toLong() ?: 0L,
                routeHash = tap.routeHashHex.toULongOrNull(16)?.toLong() ?: 0L,
                tripIndex = -1,
                routeIndex = -1,
            )
        }
        // Niente volo: il bus e' gia' sotto il dito.
        showTrip(ref, focus = null)
    }

    // La mappa ritrova quello che il pannello ritrovato le chiede.
    //
    // `panel` e' salvabile, `controller` no: cambiando il tema di sistema al
    // tramonto, ruotando il telefono, o passando da Avvisi e da Oggi, il
    // pannello della linea 23 tornava al suo posto e la mappa era la rete
    // intera — tratta spenta, fermate assenti, tutti i bus, quello seguito
    // non ingrandito, e un luogo cercato senza il suo segnaposto. Le tre
    // cose le accendevano solo showRoute, showTrip e showPlace, cioe' chi
    // APRE un pannello; qui si riaccendono per chi lo ritrova.
    //
    // Le chiavi sono quello che il pannello implica sulla mappa, non il
    // pannello: passare da mini a esteso non deve rifare niente. Nel flusso
    // normale l'effetto riparte dopo showRoute e showTrip e ripete quello che
    // hanno appena fatto, ed e' innocuo: la mappa e' gia' li'.
    //
    // Solo in positivo: quando il pannello non chiede niente non si spegne
    // niente. Chi cambia pannello — una fermata, un tocco a vuoto — spegne da
    // se', e un effetto che spegnesse a sua volta litigherebbe con loro.
    val suMappa = PanelOnMap.of(panel)
    LaunchedEffect(
        controller, ready?.buildId, suMappa.routeIndex, suMappa.vehKey,
        suMappa.place?.lat, suMappa.place?.lon,
    ) {
        val pronto = ready ?: return@LaunchedEffect
        // Un pannello di un altro bundle non si accende: i suoi indici
        // puntano a un'altra linea.
        if (panelBuild != pronto.buildId) return@LaunchedEffect
        // Il pannello si rilegge ADESSO e non dalle chiavi con cui l'effetto
        // e' partito: il guardiano del bundle, qui sopra, gira prima di
        // questo nella stessa passata e puo' averlo appena buttato via.
        val ora = PanelOnMap.of(panel)
        if (ora.routeIndex >= 0) highlightRoute(ora.routeIndex)
        ora.vehKey?.let { controller.setSelectedBus(it) }
        ora.place?.let { controller.showPlaceMarker(it.lat, it.lon, accentArgb) }
    }

    // I dati della scheda linea, calcolati quando serve.
    val currentRouteIndex = when (val p = panel) {
        is Panel.RouteMini -> p.routeIndex
        is Panel.RouteFull -> p.routeIndex
        else -> null
    }
    // Quale linea non si e' riusciti a calcolare, e il tasto per riprovare.
    // Distinguono "sto leggendo" da "non e' riuscito": prima erano lo stesso
    // null, e il pannello restava sullo spinner per sempre.
    var routeFailedFor by remember { mutableStateOf<Int?>(null) }
    var routeRetry by remember { mutableStateOf(0) }
    // Il battito della scheda linea: l'orologio comune, a grana di mezzo
    // minuto. La scheda porta cose che dipendono dall'ora — "Stanotte: ultima
    // corsa alle 00:40", le fermate gia' servite, l'eta' di un ritardo — e si
    // ricostruiva solo quando cambiavano i ritardi: con l'origine ferma o
    // senza rete, alle 00:55 diceva ancora "ultima corsa alle 00:40". Il
    // battito da dieci secondi rifarebbe la scansione delle corse ogni dieci
    // secondi; mezzo minuto basta, e solo mentre la scheda e' aperta.
    val battitoLinea by remember(currentRouteIndex != null) {
        if (currentRouteIndex == null) {
            kotlinx.coroutines.flow.flowOf(0L)
        } else {
            dev.antigravity.fluidtransit.data.time.UiClock.ticks()
                .map { it / 30 }
                .distinctUntilChanged()
        }
    }.collectAsStateWithLifecycle(initialValue = 0L)
    val routeInfo by produceState<RouteInfo?>(
        initialValue = null,
        currentRouteIndex, ready?.buildId,
        // Anche i ritardi: gli orari accanto alle fermate devono restare veri.
        rtDelays?.generatedAt,
        routeRetry,
        battitoLinea,
    ) {
        val reader = ready?.reader
        val idx = currentRouteIndex
        if (reader == null || idx == null) {
            value = null
            return@produceState
        }
        // Il reader puo' chiudersi sotto: lo scambio notturno del bundle
        // succede mentre l'app e' aperta, e questo calcolo gira fuori dal
        // thread della UI. Un'eccezione qui dentro portava giu' l'app; adesso
        // resta lo scheletro, e la chiave del produceState (il buildId) fa
        // ripartire il calcolo col bundle nuovo.
        val built = withContext(Dispatchers.Default) {
            runCatching {
                RouteInfo.build(reader, idx, Instant.now(), app.departureBoards.live())
            }.getOrNull()
        }
        // Un ricalcolo fallito non butta la scheda che c'era: e' la stessa
        // linea con i minuti di prima, meglio di un errore. L'errore si dice
        // solo quando non c'e' niente da mostrare.
        if (built != null) {
            routeFailedFor = null
            value = built
        } else if (value?.routeIndex != idx) {
            routeFailedFor = idx
            value = null
        }
    }

    // Gli avvisi delle linee che passano dalla fermata aperta.
    //
    // Il feed della Regione non nomina mai le fermate negli avvisi —
    // contati sul feed vero: 746 riferimenti, tutti a linee, zero a
    // fermate — quindi "questa fermata e' spostata" non si puo' sapere. Le
    // linee si', ed e' quello che serve a chi sta li' ad aspettare.
    val currentStopHash = (panel as? Panel.Stop)?.tap?.idHashHex

    // Gli avvisi delle quattro schede (fermata, linea, corsa, viaggio) si
    // scaricano da un giro solo, che continua finche' una di loro e' aperta.
    //
    // Prima ognuna li scaricava UNA volta all'apertura: chi aspettava il bus
    // alla fermata dalle 07:00 non vedeva lo sciopero annunciato alle 07:20, e
    // un download fallito in galleria lasciava "Avvisi non arrivati" anche a
    // rete tornata. Le schede sono mutuamente esclusive, quindi un giro solo
    // basta; le righe si ricavano da qui a ogni battito.
    val avvisiFeed by rememberSheetAlertsFeed(
        app,
        active = panel is Panel.Stop || panel is Panel.RouteMini || panel is Panel.RouteFull ||
            panel is Panel.TripMini || panel is Panel.TripFull || panel is Panel.JourneyDetail,
    )
    val avvisiDiFermata by produceState(
        initialValue = SheetAlerts.View.EMPTY,
        currentStopHash, ready?.buildId, avvisiFeed,
    ) {
        val reader = ready?.reader
        val hex = currentStopHash
        if (reader == null || hex == null) {
            value = SheetAlerts.View.EMPTY
            return@produceState
        }
        val stop = hex.toULongOrNull(16)?.toLong()?.let { reader.findStopByIdHash(it) } ?: -1
        if (stop < 0) {
            value = SheetAlerts.View.EMPTY
            return@produceState
        }
        val linee = HashMap<Long, String>()
        for (pattern in reader.patternsAtStop(stop)) {
            val route = reader.patternRoute(pattern)
            if (route >= 0) {
                linee[reader.routeIdHash(route)] = reader.routeShortName(route)
                    .ifEmpty { reader.routeLongName(route) }
            }
        }
        // Null = non scaricati, e si dice: una lista vuota sarebbe "nessun
        // avviso", un'affermazione sulla rete mentre il guasto e' nostro. Il
        // primo giro non e' ancora finito: si tiene quello che c'e'.
        val feed = avvisiFeed ?: return@produceState
        // In corso E annunciati, non solo quelli gia' cominciati.
        //
        // Il filtro teneva solo cio' che era partito: alle 07:40 uno sciopero
        // delle 08:30 non compariva sulla fermata, mentre Oggi e la schermata
        // degli Avvisi lo dicevano — "oggi alle 08:30". Chi aspetta un bus ha
        // bisogno dello sciopero PRIMA, non dopo. L'ordine (i "in corso" per
        // primi, poi gli annunciati) e il perche' stanno in `SheetAlerts`.
        //
        // Col battito comune: "oggi alle 08:30" smette di essere annunciato e
        // diventa un avviso in corso quando e' ora, non alla prossima
        // ricomposizione che capita. Si scarica una volta e si rifiltra a ogni
        // battito, che costa una scansione di una lista corta.
        dev.antigravity.fluidtransit.data.time.UiClock.ticks().collect { adesso ->
            value = SheetAlerts.view(
                feed, linee, adesso, withLineNames = true, maxBodyChars = 80,
            )
        }
    }

    // Gli avvisi della corsa aperta: sono quelli della sua linea.
    //
    // Chi ha in mano la scheda di un bus vivo o ci e' sopra o lo aspetta, e
    // in tutt'e due i casi una deviazione in corso e' la cosa che cambia i
    // suoi piani. La scheda gliela nascondeva.
    val currentTripRoute = (panel as? Panel.TripMini)?.ref?.routeHash
        ?: (panel as? Panel.TripFull)?.ref?.routeHash
    val avvisiDiCorsa by produceState(
        initialValue = SheetAlerts.View.EMPTY,
        currentTripRoute, avvisiFeed,
    ) {
        val hash = currentTripRoute
        if (hash == null || hash == 0L) {
            value = SheetAlerts.View.EMPTY
            return@produceState
        }
        // Null = non scaricati, e si dice: vedi la fermata.
        val feed = avvisiFeed ?: return@produceState
        // In corso e annunciati, come sulla fermata: vedi `SheetAlerts`.
        dev.antigravity.fluidtransit.data.time.UiClock.ticks().collect { adesso ->
            value = SheetAlerts.view(
                feed, mapOf(hash to ""), adesso, withLineNames = false, maxBodyChars = 90,
            )
        }
    }

    // Gli avvisi di servizio della linea aperta.
    //
    // L'app li aveva e non li diceva dove servono: aprendo la 12 mentre e'
    // deviata, il pannello raccontava orari e fermate come se niente fosse.
    // Un avviso di servizio e' l'unica cosa che puo' rendere sbagliato tutto
    // il resto di quel pannello, e saperlo dopo non serve.
    //
    // Si chiedono solo quando una scheda linea e' davvero aperta, e il
    // recupero ha cinque minuti di cache: aprirne una seconda non ricarica
    // niente.
    val avvisiDiLinea by produceState(
        initialValue = SheetAlerts.View.EMPTY,
        currentRouteIndex, ready?.buildId, avvisiFeed,
    ) {
        val reader = ready?.reader
        val idx = currentRouteIndex
        if (reader == null || idx == null) {
            value = SheetAlerts.View.EMPTY
            return@produceState
        }
        val hash = runCatching { reader.routeIdHash(idx) }.getOrNull()
        // Null = non scaricati, e si dice: vedi la fermata.
        val feed = avvisiFeed ?: return@produceState
        // In corso e annunciati, come sulla fermata: vedi `SheetAlerts`. Se
        // l'hash non si legge non c'e' nessuna linea da cui cominciare, e la
        // lista resta vuota come prima.
        val linee: Map<Long, String> = if (hash != null) mapOf(hash to "") else emptyMap()
        dev.antigravity.fluidtransit.data.time.UiClock.ticks().collect { adesso ->
            value = SheetAlerts.view(
                feed, linee, adesso, withLineNames = false, maxBodyChars = 90,
            )
        }
    }

    // I dati della scheda corsa: si ricalcolano anche quando arriva un
    // ritardo nuovo, cosi' i minuti delle fermate restano veri.
    val currentTripRef = when (val p = panel) {
        is Panel.TripMini -> p.ref
        is Panel.TripFull -> p.ref
        else -> null
    }
    // Il battito della scheda corsa: senza, le "prossime fermate" restavano
    // quelle del momento in cui si era aperta.
    var tripTick by remember { mutableStateOf(0) }
    LaunchedEffect(currentTripRef) {
        while (currentTripRef != null) {
            kotlinx.coroutines.delay(15_000)
            tripTick++
        }
    }
    var tripFailedFor by remember { mutableStateOf<Int?>(null) }
    var tripRetry by remember { mutableStateOf(0) }
    val tripInfo by produceState<TripInfo?>(
        initialValue = null,
        currentTripRef, rtDelays, ready?.buildId, tripTick, tripRetry,
    ) {
        val reader = ready?.reader
        val ref = currentTripRef
        if (reader == null || ref == null) {
            value = null
            return@produceState
        }
        // Il ritardo dell'intestazione vale finche' e' buono, non per sempre.
        //
        // Senza rete o con l'origine ferma il pacchetto dei ritardi resta
        // quello di prima, e l'intestazione continuava a dire "+3 min di
        // ritardo" con il pallino acceso un'ora dopo — mentre le righe della
        // stessa fermata, che invecchiano da sole, erano tornate a "orario da
        // tabella". Oltre i tre quarti d'ora un numero non entra piu' in
        // nessun calcolo (delaysFresh), e il battito di questa scheda
        // (`tripTick`) lo rivaluta ogni quindici secondi.
        val d = rtDelays?.byTripHash?.get(ref.tripHash)
            ?.takeIf { app.realtime.delaysFresh() }
        // Come la scheda linea: il bundle puo' cambiare mentre si calcola.
        val built = withContext(Dispatchers.Default) {
            runCatching {
                TripInfo.build(
                    reader = reader,
                    ref = ref,
                    now = Instant.now(),
                    delaySec = d?.takeIf { !it.noData }?.delaySec,
                    canceled = d?.canceled == true,
                    live = app.departureBoards.live(),
                )
            }.getOrNull()
        }
        if (built != null) {
            tripFailedFor = null
            value = built
        } else if (value?.ref?.vehKey != ref.vehKey) {
            tripFailedFor = ref.vehKey
            value = null
        }
    }

    // --- itinerari: origine, orario, calcolo -------------------------------
    val placesState by app.placesManager.state.collectAsStateWithLifecycle()
    var savedVersion by remember { mutableStateOf(0) }
    val savedSuggestions = remember(savedVersion) {
        app.savedPlaces.load().map {
            Suggestion("saved", it.id.toString(), it.label, "Il tuo posto", 0, it.lat, it.lon)
        }
    }

    // I posti salvati sulla mappa, con l'icona che dice cosa sono. Fino alla
    // Fase 8 si salvavano e sparivano: restavano nella lista dei Preferiti,
    // ma sulla mappa non c'era proprio niente.
    LaunchedEffect(savedVersion, accentArgb) {
        controller.setSavedPlaces(
            app.savedPlaces.load().map { SavedRender(it.id, it.label, it.lat, it.lon) },
            accentArgb and 0xFFFFFF,
        )
    }

    // Da dove parte il viaggio, in coordinate. Salvabile come il pannello:
    // senza, un pannello dei viaggi ripristinato non aveva piu' una partenza
    // e restava a "Cerco i prossimi viaggi..." per sempre.
    var journeyOrigin by rememberSaveable(stateSaver = LatLonSaver) {
        mutableStateOf<Pair<Double, Double>?>(null)
    }

    /**
     * Da dove parte il viaggio, detto a parole.
     *
     * Erano due soli casi, un booleano: la tua posizione oppure il centro
     * della mappa. Ma una partenza scelta — dal pianificatore, dall'assistente,
     * dalla notifica di una routine — non e' ne' l'una ne' l'altro, e finiva
     * etichettata "Dal centro della mappa (GPS spento)" mentre il calcolo
     * partiva, correttamente, dal punto scelto. Il numero era giusto e la
     * frase era falsa, che e' il modo peggiore di sbagliare.
     */
    var journeyFrom by rememberSaveable {
        mutableStateOf(dev.antigravity.fluidtransit.routing.OriginText.HERE)
    }
    var journeyTimeMode by rememberSaveable { mutableStateOf("now") } // now | depart | arrive
    var journeyTimeEpoch by rememberSaveable { mutableStateOf(0L) }
    var showTimeDialog by remember { mutableStateOf(false) }

    // Il pianificatore Da/A. `originRef` nullo vuol dire "da dove sei":
    // resta il caso normale, ma smette di essere l'unico possibile.
    var plannerOpen by rememberSaveable { mutableStateOf(false) }
    var originRef by rememberSaveable(stateSaver = PlaceRefSaver) {
        mutableStateOf<PlaceRef?>(null)
    }
    var destRef by rememberSaveable(stateSaver = PlaceRefSaver) {
        mutableStateOf<PlaceRef?>(null)
    }

    /** Quale delle due righe sta compilando la ricerca: "from", "to", o niente. */
    var plannerField by rememberSaveable { mutableStateOf<String?>(null) }

    // "Perche' questo numero": la riga toccata e il rettangolo da cui il
    // pop-up nasce. Non e' salvabile di proposito — e' una risposta a un
    // tocco, non uno stato in cui si resta.
    var whyRow by remember {
        mutableStateOf<dev.antigravity.fluidtransit.routing.NextDeparture?>(null)
    }
    var whyOrigin by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var whyAt by remember { mutableStateOf(0L) }

    /** Calcola, quando c'e' abbastanza per calcolare. */
    fun runPlanner() {
        val to = destRef ?: return
        val from = originRef
        if (from != null) {
            journeyFrom = "Da ${from.name}"
            journeyOrigin = from.lat to from.lon
        } else {
            // Dalla posizione GPS se c'e', dal centro mappa se no — e la
            // differenza si dichiara nel pannello, non si nasconde.
            val loc = controller.lastLocation()
            // Le ragioni per cui manca sono tre (permesso, Posizione di
            // Android, primo rilevamento) e si rimediano in tre modi: "GPS
            // spento" era falso per chi non aveva mai dato il permesso.
            journeyFrom = if (loc != null) {
                dev.antigravity.fluidtransit.routing.OriginText.HERE
            } else {
                dev.antigravity.fluidtransit.routing.OriginText.fromMapCenter(
                    locationGranted, serviceOn,
                )
            }
            journeyOrigin = loc ?: controller.cameraCenter()
        }
        panel = Panel.Journeys(to)
    }

    /** Il viaggio parte dal centro della mappa perche' non c'e' una posizione. */
    val partenzaDalCentro = originRef == null &&
        journeyFrom != dev.antigravity.fluidtransit.routing.OriginText.HERE

    // Il primo viaggio di chi non ha ancora dato la posizione parte dal
    // centro della mappa, e quando la posizione arriva — il permesso dato dal
    // tasto "Usa la mia posizione", o la Posizione di Android accesa — il
    // viaggio si ricalcola da li'. Senza, l'elenco restava quello calcolato
    // dalla campagna fra Siena e Colle, con l'etichetta che non diceva piu' il
    // vero. Si aspetta il primo rilevamento fino a mezzo minuto.
    LaunchedEffect(panel is Panel.Journeys, partenzaDalCentro, locationGranted, serviceOn) {
        if (panel !is Panel.Journeys || !partenzaDalCentro) return@LaunchedEffect
        if (!locationGranted || !serviceOn) return@LaunchedEffect
        repeat(30) {
            if (controller.lastLocation() != null) {
                runPlanner()
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(1_000)
        }
    }

    /** "Portami qui" da un luogo: destinazione quella, partenza da dove sei. */
    fun goToPlace(ref: PlaceRef) {
        originRef = null
        destRef = ref
        journeyTimeMode = "now"
        plannerOpen = true
        runPlanner()
    }

    /**
     * Il pianificatore, aperto sulla domanda che si stava per fare.
     *
     * Apriva due righe vuote, e per cominciare a scrivere ne serviva una
     * terza di tocchi: barra, "calcola un percorso", riga "A". Ma chi apre il
     * pianificatore sa gia' dove vuole andare — e' il "da dove" che quasi
     * sempre e' scontato, perche' e' dove sei. Quindi si va dritti a scegliere
     * la destinazione, con la tastiera gia' su; la riga "Da" resta li' sotto
     * per quando non e' scontata.
     */
    fun openPlanner() {
        query = ""
        plannerOpen = true
        plannerField = "to"
        searchOpen = true
    }

    // Le richieste da fuori: da un'altra scheda (Preferiti, Oggi) o da fuori
    // dall'app (un widget, una notifica, un deep link). Si consumano appena
    // bundle e mappa ci sono — chi arriva da una notifica con l'app spenta
    // aspetta qui il caricamento degli orari, invece di trovare la mappa
    // generica e doversi cercare la fermata a mano.
    LaunchedEffect(intent, ready?.buildId) {
        val reader = ready?.reader
        val i = intent
        if (i == null || reader == null) return@LaunchedEffect
        when (i) {
            // Arrivare qui era tutto quello che chiedeva.
            MapIntent.Home -> Unit

            is MapIntent.Stop -> {
                controller.exitRouteMode()
                controller.setSelectedBus(null)
                val hash = i.idHashHex.toULongOrNull(16)?.toLong()
                val stop = hash?.let { reader.findStopByIdHash(it) } ?: -1
                if (stop >= 0) controller.flyTo(reader.stopLat(stop), reader.stopLon(stop), 16.2)
                panel = Panel.Stop(StopTap(i.idHashHex, i.name))
            }

            is MapIntent.Route -> {
                val hash = i.idHashHex.toULongOrNull(16)?.toLong()
                val idx = hash?.let { reader.findRouteByIdHash(it) } ?: -1
                if (idx >= 0) showRoute(idx)
            }

            is MapIntent.Place -> {
                showPlace(PlaceRef(i.name, "", i.lat, i.lon, i.savedId))
            }

            is MapIntent.Journey -> {
                originRef = if (i.fromLat != null && i.fromLon != null) {
                    // La routine tiene le coordinate della partenza ma non il
                    // suo nome: meglio dire cos'e' che inventarle un posto.
                    PlaceRef("partenza abituale", "", i.fromLat, i.fromLon)
                } else {
                    null
                }
                destRef = PlaceRef(i.toName, "", i.toLat, i.toLon)
                journeyTimeMode = i.timeMode
                if (i.timeMode != "now") journeyTimeEpoch = i.timeEpoch
                plannerOpen = true
                runPlanner()
            }
        }
        onIntentConsumed()
    }

    // Le azioni dell'assistente le esegue la mappa, perche' e' l'unica che
    // puo': il modulo dell'assistente non sa niente di pannelli e di camera.
    // Il corpo si rilegge a ogni composizione, il collector no.
    //
    // `LaunchedEffect(Unit)` non riparte mai — e deve restare cosi', o
    // riavviare il collector perderebbe le azioni in coda — ma per questo si
    // portava dietro per sempre lo stato della PRIMA composizione: il bundle
    // di allora, e le funzioni locali che lo usano. Dopo lo scambio notturno
    // quel reader e' chiuso, e le azioni dell'assistente ci lavoravano sopra.
    //
    // rememberUpdatedState tiene ferma la sottoscrizione e fresco il corpo.
    val handleAction by rememberUpdatedState<
        suspend (dev.antigravity.fluidtransit.ai.tools.AssistantAction) ->
        dev.antigravity.fluidtransit.ai.tools.ActionOutcome,
        > { action ->
        val reader = ready?.reader
        // Ogni ramo torna com'e' andata: prima il corpo era un `Unit` e l'assistente
        // diceva "fatto" appena l'azione era in coda, anche quando qui non partiva niente.
        when (action) {
            is dev.antigravity.fluidtransit.ai.tools.AssistantAction.ShowPlace -> {
                assistantOpen = false
                showPlace(
                    PlaceRef(
                        action.point.name, action.point.context,
                        action.point.lat, action.point.lon,
                    ),
                )
                dev.antigravity.fluidtransit.ai.tools.ActionOutcome.DONE
            }

            is dev.antigravity.fluidtransit.ai.tools.AssistantAction.ShowStop -> {
                assistantOpen = false
                panel = Panel.Stop(StopTap(action.idHashHex, action.name))
                val hash = action.idHashHex.toULongOrNull(16)?.toLong()
                val s = if (reader != null && hash != null) reader.findStopByIdHash(hash) else -1
                if (reader != null && s >= 0) {
                    controller.flyTo(reader.stopLat(s), reader.stopLon(s), 16.0)
                }
                dev.antigravity.fluidtransit.ai.tools.ActionOutcome.DONE
            }

            is dev.antigravity.fluidtransit.ai.tools.AssistantAction.ShowRoute -> {
                assistantOpen = false
                showRoute(action.routeIndex)
                dev.antigravity.fluidtransit.ai.tools.ActionOutcome.DONE
            }

            is dev.antigravity.fluidtransit.ai.tools.AssistantAction.ShowJourneys -> {
                originRef = action.from?.let {
                    PlaceRef(it.name, it.context, it.lat, it.lon)
                }
                destRef = PlaceRef(
                    action.to.name, action.to.context, action.to.lat, action.to.lon,
                )
                journeyTimeMode = when {
                    action.arriveByEpoch != null -> "arrive"
                    action.departAtEpoch != null -> "depart"
                    else -> "now"
                }
                journeyTimeEpoch = action.arriveByEpoch ?: action.departAtEpoch ?: 0L
                plannerOpen = true
                runPlanner()
                dev.antigravity.fluidtransit.ai.tools.ActionOutcome.DONE
            }

            is dev.antigravity.fluidtransit.ai.tools.AssistantAction.StartNavigation -> {
                // Solo dove sei: il centro della mappa e' un posto che nessuno ha scelto, e con la
                // mappa portata su un'altra citta' la guida partiva da li' mentre l'assistente
                // diceva "navigazione avviata". Senza posizione il fallimento e' NO_ORIGIN.
                val origin = controller.lastLocation()
                when {
                    reader == null -> dev.antigravity.fluidtransit.ai.tools.ActionOutcome.NO_DATA
                    origin == null -> dev.antigravity.fluidtransit.ai.tools.ActionOutcome.NO_ORIGIN
                    else -> {
                        val js = app.assistantBridge.plan(
                            origin.first, origin.second,
                            action.to.lat, action.to.lon, null, null,
                        )
                        val j = js.firstOrNull()
                        if (j == null) {
                            // Di notte, o verso un posto che non si raggiunge coi mezzi: nessuna
                            // navigazione parte, e il "navigazione avviata" non si deve dire.
                            dev.antigravity.fluidtransit.ai.tools.ActionOutcome.NO_ITINERARY
                        } else {
                            assistantOpen = false
                            avviaNavigazione(buildNavPlan(reader, j, action.to.name))
                            dev.antigravity.fluidtransit.ai.tools.ActionOutcome.DONE
                        }
                    }
                }
            }

            // Posti salvati, stelle, routine, fermare la navigazione, aggiornare i dati: non
            // hanno bisogno della mappa e le esegue il ponte da se', anche quando a chiedere e'
            // un assistente esterno e questa schermata non esiste. Qui non ci dovrebbero arrivare.
            else -> dev.antigravity.fluidtransit.ai.tools.ActionOutcome.UNAVAILABLE
        }
    }
    LaunchedEffect(Unit) {
        app.assistantBridge.requests.collect { request ->
            // La risposta va sempre data, anche se l'azione si rompe o la mappa esce di scena a
            // meta': chi aspetta non deve restare appeso fino al timeout.
            val outcome = try {
                handleAction(request.action)
            } catch (e: kotlinx.coroutines.CancellationException) {
                request.result.complete(dev.antigravity.fluidtransit.ai.tools.ActionOutcome.NO_MAP)
                throw e
            } catch (e: Exception) {
                dev.antigravity.fluidtransit.ai.tools.ActionOutcome.FAILED
            }
            request.result.complete(outcome)
        }
    }
    // Quello che il ponte scrive da solo (routine, posti, stelle) arriva alla mappa come notizia.
    LaunchedEffect(Unit) {
        app.assistantBridge.routineCreated.collect {
            // Anche una routine creata a voce vive dell'avviso: la strada manuale chiedeva le
            // notifiche, questa no, e chi la dettava trovava la routine "attiva" in Oggi con la
            // notifica che non sarebbe mai arrivata.
            chiediNotifiche(NotifMotivo.ROUTINE)
        }
    }
    LaunchedEffect(Unit) {
        app.assistantBridge.dataChanged.collect { savedVersion++ }
    }

    val journeysTarget = when (val p = panel) {
        is Panel.Journeys -> p.to
        is Panel.JourneyDetail -> p.to
        else -> null
    }
    // Il calcolo e' fallito, che e' diverso da "non c'e' niente".
    var journeysFailed by remember { mutableStateOf(false) }
    /** Il calcolo sta ancora andando: la lista che si vede e' parziale. */
    var journeysSearching by remember { mutableStateOf(false) }
    // "Parti ora" invecchia. Il calcolo aveva fra le chiavi tutto tranne
    // l'orologio, e a pannello aperto il primo viaggio restava in cima anche
    // dopo la sua partenza: si leggeva un bus gia' andato come il prossimo.
    // Si ricalcola quando il primo parte, e solo con l'elenco aperto: col
    // dettaglio di un viaggio aperto, un elenco nuovo gli cambierebbe sotto
    // il viaggio che si sta guardando.
    var nowRefresh by remember { mutableStateOf(0) }

    // Il tempo reale nuovo rifa' i viaggi (vedi `JourneyLiveRefresh`).
    //
    // `liveRefresh` e' il contatore che riavvia il calcolo; `journeysCalcAtMs`
    // e `journeysHadLive` dicono quando e' partito l'ultimo e se aveva gia'
    // dei ritardi; `journeysSig` e `journeysLiveSig` permettono di rifare
    // l'elenco senza farlo sparire sotto gli occhi (vedi sotto).
    var liveRefresh by remember { mutableStateOf(0) }
    var journeysCalcAtMs by remember { mutableStateOf(0L) }
    var journeysHadLive by remember { mutableStateOf(true) }
    var journeysSig by remember { mutableStateOf<List<Any?>?>(null) }
    val rtPredictions by rt.predictions.collectAsStateWithLifecycle()
    var journeysLiveSig by remember { mutableStateOf<Pair<Any?, Any?>?>(null) }
    val journeys by produceState<List<UiJourney>?>(
        initialValue = null,
        // La PARTENZA fra le chiavi, che e' dove mancava.
        //
        // Il calcolo la leggeva ma non ci si riavviava sopra: si cambiava la
        // riga "Da" nel pianificatore, l'intestazione diceva il posto nuovo —
        // quella si aggiorna da un'altra parte — e sotto restavano i viaggi
        // calcolati dal posto vecchio. Anche il tasto che scambia partenza e
        // arrivo non cambiava niente. Un pianificatore che risponde alla
        // domanda di prima e sembra aver risposto a quella nuova e' il modo
        // piu' diretto di far perdere fiducia a chi lo usa.
        journeysTarget, journeyOrigin, journeyTimeMode, journeyTimeEpoch, ready?.buildId,
        nowRefresh, liveRefresh,
    ) {
        val reader = ready?.reader
        val to = journeysTarget
        val from = journeyOrigin
        if (reader == null || to == null || from == null) {
            value = null
            // Un calcolo abbandonato (pannello chiuso, partenza cambiata) non
            // arriva piu' in fondo a spegnere "sto cercando": lo si fa qui.
            journeysSearching = false
            journeysCalcAtMs = 0L
            journeysSig = null
            return@produceState
        }
        // Un ricalcolo per dati live nuovi non fa sparire l'elenco: la stessa
        // domanda, con ritardi piu' freschi, sostituisce i viaggi UNA volta
        // sola, a calcolo finito. Non se ne mostrano i parziali: sono
        // cumulativi dalla prima scansione, quindi la prima consegna ha uno o
        // due viaggi e la lista di cinque che si stava leggendo crollava e
        // risaliva ogni minuto, con "Cerco anche i prossimi..." in fondo
        // (misurato fino a venti secondi sull'emulatore). Se il ricalcolo
        // fallisce si tiene la lista buona di prima. Una domanda diversa
        // (partenza, orario, bundle) azzera come prima.
        val firma = listOf(to.lat, to.lon, from, journeyTimeMode, journeyTimeEpoch, ready?.buildId, nowRefresh)
        val stessaDomanda = firma == journeysSig && value != null
        journeysSig = firma
        journeysCalcAtMs = System.currentTimeMillis()
        journeysHadLive = rtDelays != null || rtPredictions != null
        journeysLiveSig = rtDelays?.generatedAt to rtPredictions
        if (!stessaDomanda) value = null
        // Il realtime entra nel calcolo: ritardi e cancellazioni di ADESSO.
        val rtNow = resolved
        val fresco = app.realtime.delaysFresh()
        // Il tempo reale si costruisce SEMPRE, non solo quando lo snapshot dei
        // mezzi e' gia' risolto. Toccando la notifica "Esci tra 12 min" con
        // l'app chiusa il calcolo parte appena il bundle e' pronto, prima del
        // primo giro dei veicoli: `resolved` era ancora nullo, il motore
        // partiva con `Realtime.NONE` anche se le previsioni e il modello dei
        // ritardi c'erano, e il dettaglio mostrava orari di tabella senza
        // pallino live — mentre la notifica aveva calcolato l'uscita coi
        // ritardi. Le previsioni stanno in `live()`, che non dipende dai mezzi.
        val liveData = dev.antigravity.fluidtransit.routing.Raptor.Realtime(
            // Il numero unico per corsa e le cancellazioni solo se il
            // pacchetto e' ancora buono (delaysFresh); le previsioni
            // fermata per fermata invecchiano da se'.
            if (fresco) rtNow?.delayByTrip ?: emptyMap() else emptyMap(),
            if (fresco) rtNow?.canceledTrips ?: app.canceledTrips.value else emptySet(),
            java.time.Instant.now().epochSecond,
            // Le stesse previsioni che usa il tabellone della fermata.
            //
            // Senza, il motore applicava a tutta la corsa il primo
            // ritardo dichiarato, e il tabellone la previsione della
            // fermata giusta: sul feed delle 07:30 le due cose
            // divergevano in media di 78 secondi, oltre il minuto su un
            // terzo delle corse. Stesso bus, stessa fermata, due orari.
            live = app.departureBoards.live(),
        )
        journeysFailed = false
        journeysSearching = !stessaDomanda
        // I viaggi si mostrano mentre arrivano.
        //
        // Un piano sono fino a otto scansioni in fila, e sull'emulatore da
        // SODERINI a Careggi ci mettevano piu' di venti secondi: per tutto
        // quel tempo il pannello diceva "Cerco i prossimi viaggi..." e
        // basta, anche se il primo viaggio era pronto molto prima. Ogni
        // scansione che finisce consegna quello che ha trovato, e la lista
        // si riempie sotto gli occhi invece di comparire tutta insieme alla
        // fine.
        val parziali = kotlinx.coroutines.channels.Channel<
            List<dev.antigravity.fluidtransit.routing.Raptor.Journey>,
            >(kotlinx.coroutines.channels.Channel.CONFLATED)
        val mostraParziali = launch {
            // Ricalcolo per soli dati live: niente parziali, vedi sopra.
            if (stessaDomanda) return@launch
            for (p in parziali) {
                val ui = withContext(Dispatchers.Default) {
                    val liveTrips = rtNow?.delayByTrip?.keys.orEmpty()
                    runCatching { p.map { UiJourney.of(reader, it, liveTrips) } }.getOrNull()
                }
                if (ui != null && ui.isNotEmpty()) value = ui
            }
        }
        val raw = withContext(app.routingDispatcher) {
            val raptor = app.raptorFor(reader)
            val fromPlace = dev.antigravity.fluidtransit.routing.Raptor.Place(from.first, from.second)
            val toPlace = dev.antigravity.fluidtransit.routing.Raptor.Place(to.lat, to.lon)
            // Il calcolo e' una chiamata bloccante su una corsia sola: se
            // questa coroutine viene cancellata (partenza, orario o
            // destinazione cambiati, pannello chiuso) deve fermarsi fra una
            // scansione e l'altra, non finire per intero mentre quello nuovo
            // aspetta dietro. Misurato: piu' di venti secondi a calcolo, e tre
            // cambi di partenza di fila ne accodavano tre.
            val abbandonato = { !isActive }
            runCatching {
            when (journeyTimeMode) {
                // "Arriva entro" no: quello scandisce all'indietro e i
                // parziali sarebbero i viaggi piu' lontani dall'ora chiesta,
                // cioe' la lista si riempirebbe dalla parte sbagliata.
                "arrive" -> raptor.planArriveBy(
                    fromPlace, toPlace, Instant.ofEpochSecond(journeyTimeEpoch), liveData,
                    // Mai un bus gia' partito.
                    notBefore = Instant.now(),
                    shouldStop = abbandonato,
                )

                "depart" -> raptor.plan(
                    fromPlace, toPlace, Instant.ofEpochSecond(journeyTimeEpoch), liveData,
                    onPartial = if (stessaDomanda) null else { { parziali.trySend(it) } },
                    shouldStop = abbandonato,
                )

                else -> raptor.plan(
                    fromPlace, toPlace, Instant.now(), liveData,
                    onPartial = if (stessaDomanda) null else { { parziali.trySend(it) } },
                    shouldStop = abbandonato,
                )
            }
            }.getOrElse {
                // Un calcolo abbandonato non e' un calcolo fallito: la
                // cancellazione risale, non diventa "non sono riuscito".
                if (it is kotlinx.coroutines.CancellationException) throw it
                null
            }
        }
        // Un calcolo fallito non e' "nessun viaggio": il primo dice che non
        // abbiamo saputo rispondere, il secondo che la risposta e' no. Erano
        // la stessa schermata — "Nessun viaggio trovato, prova a cambiare
        // orario" — e cambiare orario non serviva a niente.
        parziali.close()
        mostraParziali.join()
        journeysSearching = false
        if (raw == null) {
            // Un ricalcolo che non riesce non butta la lista buona: i ritardi
            // restano quelli di un minuto fa, che e' meglio di "non sono
            // riuscito" su viaggi che c'erano.
            if (stessaDomanda) return@produceState
            journeysFailed = true
            value = emptyList()
            return@produceState
        }
        val nuovi = withContext(Dispatchers.Default) {
            // Le corse davvero seguite dal feed: serve per distinguere
            // "monitorata e puntuale" da "non se ne sa niente".
            val liveTrips = rtNow?.delayByTrip?.keys.orEmpty()
            runCatching { raw.map { UiJourney.of(reader, it, liveTrips) } }.getOrNull()
        }
        if (nuovi == null) {
            if (!stessaDomanda) {
                journeysFailed = true
                value = emptyList()
            }
            return@produceState
        }
        // Col dettaglio di un viaggio aperto l'elenco non cambia: il dettaglio
        // punta a una riga per indice, e un elenco rifatto gli metterebbe sotto
        // un altro viaggio (il ricalcolo puo' essere partito prima del tocco).
        // Si dimentica la firma dei ritardi, cosi' quando si torna all'elenco
        // il ricalcolo riparte.
        if (stessaDomanda && panel is Panel.JourneyDetail) {
            journeysLiveSig = null
            return@produceState
        }
        value = nuovi
    }

    // I ritardi nuovi rifanno l'elenco, al massimo una volta al minuto e solo
    // con l'elenco aperto (non col dettaglio di un viaggio, che non deve
    // cambiare sotto gli occhi). Vale anche per i viaggi "parti alle" e
    // "arriva entro", cioe' quelli di una routine, che il giro di "Parti ora"
    // qui sotto non ricalcola mai: restavano con i ritardi di quando si erano
    // aperti.
    LaunchedEffect(rtDelays?.generatedAt, rtPredictions, panel is Panel.Journeys) {
        val sig = rtDelays?.generatedAt to rtPredictions
        if (sig == journeysLiveSig) return@LaunchedEffect
        val attesa = JourneyLiveRefresh.waitMs(
            listOpen = panel is Panel.Journeys,
            lastCalcAtMs = journeysCalcAtMs,
            nowMs = System.currentTimeMillis(),
            hadLive = journeysHadLive,
        ) ?: return@LaunchedEffect
        kotlinx.coroutines.delay(attesa)
        // Un calcolo ancora in corso non si interrompe per rifarlo: si
        // aspetta che finisca (al massimo un minuto) e si rifa' con i dati
        // che nel frattempo sono arrivati.
        var giri = 0
        while (journeysSearching && giri++ < 30) kotlinx.coroutines.delay(2_000)
        if (panel is Panel.Journeys && !journeysSearching) liveRefresh++
    }

    // Gli avvisi delle linee del viaggio aperto.
    //
    // Il motore calcola sul percorso di tabella: se una delle linee oggi e'
    // deviata, il viaggio proposto puo' non esistere come e' scritto.
    val currentJourneyRoutes = (panel as? Panel.JourneyDetail)
        ?.let { journeys?.getOrNull(it.index) }
        ?.raw?.legs
        ?.filterIsInstance<dev.antigravity.fluidtransit.routing.Raptor.Leg.Ride>()
        ?.map { it.route }
        ?.distinct()
    val avvisiDiViaggio by produceState(
        initialValue = SheetAlerts.View.EMPTY,
        currentJourneyRoutes, ready?.buildId, avvisiFeed,
    ) {
        val reader = ready?.reader
        val routes = currentJourneyRoutes
        if (reader == null || routes.isNullOrEmpty()) {
            value = SheetAlerts.View.EMPTY
            return@produceState
        }
        val linee = routes.associate { r ->
            reader.routeIdHash(r) to reader.routeShortName(r).ifEmpty { reader.routeLongName(r) }
        }
        // Null = non scaricati, e si dice: vedi la fermata.
        val feed = avvisiFeed ?: return@produceState
        // In corso e annunciati, come sulla fermata: vedi `SheetAlerts`. Un
        // viaggio si sceglie spesso la sera per la mattina dopo, ed e' allora
        // che uno sciopero annunciato serve.
        dev.antigravity.fluidtransit.data.time.UiClock.ticks().collect { adesso ->
            value = SheetAlerts.view(
                feed, linee, adesso, withLineNames = true, maxBodyChars = 80,
            )
        }
    }

    // Il viaggio scelto si accende sulla mappa mentre lo stai SCEGLIENDO, e
    // la camera lo inquadra.
    //
    // Mentre lo stai facendo, no: durante la navigazione il percorso non si
    // disegna. La camminata era una retta tratteggiata fra due punti — che
    // taglia palazzi, fiumi e ferrovie — e mostrarla mentre uno cammina
    // davvero e' peggio che non mostrare niente: si vede dove sei, e basta.
    // Il percorso del bus arriva dopo, quando sali, dalla modalita' linea.
    // Il giro di "Parti ora": si aspetta la partenza del primo viaggio in
    // elenco, piu' mezzo minuto, e si ricalcola. Nessun battito da dieci
    // secondi qui: farebbe ricomporre l'intera schermata per niente.
    LaunchedEffect(journeys, journeyTimeMode, panel is Panel.Journeys) {
        if (journeyTimeMode == "depart" || journeyTimeMode == "arrive") return@LaunchedEffect
        if (panel !is Panel.Journeys) return@LaunchedEffect
        // Un viaggio tutto a piedi parte "adesso" per definizione: ricalcolare
        // dopo la sua partenza vorrebbe dire ricalcolare ogni mezzo minuto.
        val primo = journeys?.firstOrNull { !it.raw.isWalkOnly }?.raw?.departure?.epochSecond
            ?: return@LaunchedEffect
        val attesa = (primo + 30) * 1000 - System.currentTimeMillis()
        if (attesa > 0) kotlinx.coroutines.delay(attesa)
        nowRefresh++
    }
    LaunchedEffect(panel, journeys, navState) {
        val p = panel
        val r2 = ready?.reader
        if (p is Panel.JourneyDetail && r2 != null) {
            val j = journeys?.getOrNull(p.index) ?: return@LaunchedEffect
            val (shape, bbox) = withContext(Dispatchers.Default) {
                buildJourneyGeometry(r2, j.raw)
            }
            controller.showJourney(shape)
            if (bbox[0] <= bbox[2]) controller.flyToBounds(bbox[0], bbox[1], bbox[2], bbox[3])
        } else {
            controller.clearJourney()
        }
    }

    // La navigazione si prende la mappa da sola.
    //
    // Prima qui si accendeva la "modalita' linea" — la stessa del tocco su
    // un bus — e solo a bordo: durante la camminata e l'attesa restava in
    // scena l'intera rete toscana, e a bordo si accendeva la linea INTERA,
    // andata e ritorno, con tutte le fermate di tutti i suoi pattern. Il
    // fuoco sa cose che la modalita' linea non puo' sapere: da dove salgo a
    // dove scendo, e quali altre linee ci arrivano lo stesso.
    val navFocus by app.navigation.focus.collectAsStateWithLifecycle()
    // Solo se il fuoco e lo snapshot risolto parlano dello stesso bundle: fra
    // lo scambio degli orari e il giro dopo del servizio, l'indice di corsa
    // del fuoco letto nella risoluzione nuova dava il bus di un'altra corsa,
    // e la scia si tagliava su di lui.
    val navVehKey = navFocus
        ?.takeIf { f -> resolved?.buildId == f.buildId }
        ?.let { f -> resolved?.vehicleByTrip?.get(f.trip) }
    // La tappa a fuoco e' gia' fatta: l'ultima camminata dopo l'ultimo bus.
    val navFocusFinished = navFocus != null && navState != null &&
        (navState?.phase == "arrived" || (navState?.legIndex ?: -1) > (navFocus?.legIndex ?: -1))
    dev.antigravity.fluidtransit.ui.nav.NavMapBinding(
        controller = controller,
        focus = navFocus,
        phase = navState?.phase,
        myVehKey = navVehKey,
        finished = navFocusFinished,
    )
    dev.antigravity.fluidtransit.ui.nav.NavCamera(
        controller = controller,
        state = navState,
        focus = navFocus,
        myVehKey = navVehKey,
        locationGranted = locationGranted,
        // Un pannello aperto e' una mano sulla mappa anche senza un gesto:
        // chi tocca una fermata durante il viaggio vuole guardare quella, e
        // la camera del viaggio gliela strappava al giro dopo. Chiuso il
        // pannello, l'inseguimento riprende da solo.
        manuale = navManuale || panel != null,
        onFollow = { follow = it },
        finished = navFocusFinished,
    )
    // Il puck si sposta in basso di quanto e' alta la card, e davanti entra
    // in scena la strada. E' il padding della mappa, non una camera nuova:
    // il LocationComponent lo rispetta, e cosi' non serve nessun
    // `animateCamera` che annullerebbe il tracking.
    //
    // Solo quando la card si vede: con un pannello aperto la card lascia il
    // posto al pannello, e il padding restava — il puck finiva spinto in alto
    // da una card che non c'era.
    val navCardVisible = navState != null && panel == null
    LaunchedEffect(navCardHeight, navCardVisible) {
        controller.setCameraPadding(if (navCardVisible) navCardHeight else 0)
    }

    // Un primo giro appena il bundle e' pronto, senza aspettare lo zoom.
    //
    // Il polling CONTINUO resta legato allo zoom, che e' giusto: i mezzi si
    // disegnano da 10.2 in su e tenerli aggiornati sotto sarebbe traffico
    // buttato. Ma il PRIMO dato deve esistere lo stesso — lo leggono la
    // scheda Oggi, i Preferiti, il widget e l'assistente, che non zoomano
    // niente — e quando poi si zooma i bus sono gia' in scena invece di
    // comparire mezzo minuto dopo. E' la meta' dell'impressione che il live
    // "si accenda solo dopo l'apertura".
    LaunchedEffect(ready?.buildId) {
        if (ready == null) return@LaunchedEffect
        runCatching { rt.refreshVehicles() }
        // E i ritardi, subito: sono l'unica cosa che distingue un tabellone
        // vero da uno di tabella, e finora partivano solo quando si apriva un
        // pannello. Chi apriva l'app, toccava una fermata e leggeva il primo
        // fotogramma vedeva "orario da tabella" per un paio di secondi, cioe'
        // esattamente il momento in cui si fa l'idea che il live non ci sia.
        runCatching { rt.refreshDelays() }
        runCatching { rt.refreshPredictions() }
    }

    // --- i cicli del realtime: vivono col ciclo di vita della schermata ----
    // Bus: solo quando lo zoom li rende visibili (o una scheda corsa e'
    // aperta). Il ritmo lo decide lo stato del client: 30 s dal proxy,
    // 3 min in diretta. Il tick a ~8 Hz fa scivolare i marker.
    //
    // Derivato e non letto dritto: `cameraZoom` cambia a ogni pizzicata, e
    // leggerlo qui dentro voleva dire ricomporre una schermata da duemila
    // righe ogni volta che la mappa si ferma. Quello che interessa e' il
    // BOOLEANO, che cambia una volta ogni tanto; derivedStateOf lascia
    // passare solo quello.
    val vehiclesActive by remember {
        androidx.compose.runtime.derivedStateOf {
            ready != null &&
                (
                    cameraZoom >= MapCatalog.BUS_MIN_ZOOM - 0.6 ||
                        panel is Panel.TripMini || panel is Panel.TripFull ||
                        // In modalita' linea i bus della tratta si vedono da
                        // qualunque zoom: il polling deve accompagnarli.
                        panel is Panel.RouteMini || panel is Panel.RouteFull ||
                        // In navigazione il mio mezzo si disegna da zoom 6:
                        // senza il battito, l'inquadratura larga dell'attesa
                        // lo lasciava fermo dov'era al primo dato.
                        navFocus != null
                    )
        }
    }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    LaunchedEffect(vehiclesActive) {
        if (!vehiclesActive) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            launch {
                while (true) {
                    rt.refreshVehicles()
                    kotlinx.coroutines.delay(rt.vehiclesIntervalMs())
                }
            }
            launch {
                while (true) {
                    controller.tickBuses()
                    kotlinx.coroutines.delay(controller.busTickDelayMs())
                }
            }
        }
    }

    // Ritardi: solo con un pannello aperto — sono i pannelli a mostrarli.
    val delaysActive = ready != null && panel != null
    LaunchedEffect(delaysActive) {
        if (!delaysActive) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                // Il giro dei tabelloni (`DepartureBoards.startPump`) e' lo
                // stesso giro, a 30 s: quando c'e' lo si lascia fare. Due
                // giri sfasati facevano circa dieci richieste al minuto
                // invece di sei, e la radio non tornava mai a riposo. Questo
                // resta solo per un pannello senza nessun tabellone, come la
                // scheda di una corsa lontana da ogni fermata.
                if (!app.departureBoards.isPumping) {
                    rt.refreshDelays()
                    rt.refreshPredictions()
                }
                kotlinx.coroutines.delay(30_000)
            }
        }
    }

    // Ogni snapshot risolto scende nella mappa: da li' parte il moto.
    //
    // Con una riserva, decisa con l'utente: se il proxy ha in pancia
    // posizioni troppo vecchie — apertura dopo qualche ora, misurate
    // ventisei minuti il 03/09 — NON si disegnano bus dove non sono. Si
    // aspetta il giro fresco, che arriva in un paio di secondi.
    LaunchedEffect(resolved, rtStatus) {
        val age = rtStatus.feedAgeSeconds
        val fresh = age == null || age <= STALE_HIDE_SECONDS
        controller.setBuses(if (fresh) resolved?.buses ?: emptyList() else emptyList())
    }

    // Cosa passa qui intorno. Il come sta in NearbyPanel.kt, insieme al
    // pannello che lo mostra: la schermata chiede il tabellone e basta.
    val nearbyBoard = rememberNearbyBoard(
        app = app,
        reader = ready?.reader,
        buildId = ready?.buildId,
        stopGroups = stopGroups,
        follow = follow,
        here = { controller.lastLocation() ?: controller.cameraCenter() },
    )

    // Le ricerche recenti e i suggerimenti del pannello.
    //
    // Da dove si misura: dove sei, e se non lo sappiamo il centro della
    // mappa. E' lo stesso riferimento della ricerca e di "qui intorno":
    // tre elenchi che si vedono insieme non possono misurare da tre posti.
    val dovePerLaDistanza = dev.antigravity.fluidtransit.routing.Reference.point(
        controller.lastLocation(), controller.cameraCenter(),
    )
    val recentStore = remember { RecentSearches(context) }
    var recentsVersion by remember { mutableStateOf(0) }
    val recents = remember(recentsVersion) { recentStore.load() }
    // I recenti come suggerimenti, col colore delle linee di OGGI.
    //
    // La voce si porta dietro la tinta del giorno in cui e' stata cercata, e
    // il bundler sposta ancora qualche linea quando ne prende una vicina
    // nuova: la pastiglia fra i recenti restava di un colore e la stessa
    // linea nel risultato di ricerca, sulla mappa e nella scheda di un altro.
    // Si rifa' solo quando cambiano i recenti, il bundle o il riferimento —
    // la ricerca di una linea per hash scandisce la tabella — e non a ogni
    // ricomposizione.
    val recentSuggestions = remember(recents, ready?.buildId, dovePerLaDistanza) {
        recents.map { it.toSuggestion(dovePerLaDistanza, ready?.reader) }
    }
    val nearby by produceState(
        initialValue = emptyList<Suggestion>(),
        searchOpen, ready?.buildId, stopGroups,
    ) {
        val reader = ready?.reader
        if (!searchOpen || reader == null) {
            value = emptyList()
            return@produceState
        }
        val center = dev.antigravity.fluidtransit.routing.Reference.point(
            controller.lastLocation(), controller.cameraCenter(),
        ) ?: return@produceState
        value = withContext(Dispatchers.Default) {
            // `stopsNear` esce gia' dalla piu' vicina: le prime fermate vere
            // sono le piu' vicine. Una riga per fermata, non per banchina
            // (vedi `nearbyStops`): titolo, chiave e coordinate sono quelli
            // del rappresentante, come nella ricerca; la distanza e' quella
            // della banchina piu' vicina, che e' quella che si raggiunge.
            nearbyStops(reader.stopsNear(center.first, center.second, 700.0), stopGroups, 5)
                .map { n ->
                    val rep = n.representative
                    Suggestion(
                        kind = "stop",
                        key = java.lang.Long.toHexString(reader.stopIdHash(rep)),
                        title = reader.stopName(rep),
                        // Con la distanza, come i recenti e i risultati: e'
                        // l'unico elenco dei tre che non ce l'aveva, ed e'
                        // quello ordinato PER distanza — quindi l'unica cosa
                        // che diceva sul suo ordine bisognava indovinarla.
                        subtitle = "Fermata · a " +
                            dev.antigravity.fluidtransit.routing.Words.distance(
                                dev.antigravity.fluidtransit.routing.BundleReader.haversine(
                                    center.first, center.second,
                                    reader.stopLat(n.nearest), reader.stopLon(n.nearest),
                                ),
                            ),
                        colorRgb = 0,
                        lat = reader.stopLat(rep),
                        lon = reader.stopLon(rep),
                    )
                }
        }
    }

    fun pick(s: Suggestion) {
        searchOpen = false
        query = ""
        follow = FollowMode.FREE
        // I posti salvati non finiscono nei recenti: sono gia' sempre in cima.
        if (s.kind != "saved") {
            recentStore.add(
                RecentSearches.Entry(s.kind, s.key, s.title, s.subtitle, s.colorRgb, s.lat, s.lon),
            )
            recentsVersion++
        }
        // Dove porta un suggerimento sta in `SuggestionTarget`, con il
        // perche' e il suo test: la riserva e' il luogo, non la linea.
        when (targetOf(s.kind)) {
            SuggestionTarget.STOP -> {
                controller.exitRouteMode()
                controller.flyTo(s.lat, s.lon, 16.2)
                panel = Panel.Stop(StopTap(s.key, s.title))
            }

            SuggestionTarget.ROUTE -> {
                // La chiave e' l'hash del route_id: stabile fra i bundle, al
                // contrario dell'indice che ogni notte cambia.
                val reader = ready?.reader
                val hash = s.key.toULongOrNull(16)?.toLong()
                if (reader != null && hash != null) {
                    val idx = reader.findRouteByIdHash(hash)
                    if (idx >= 0) showRoute(idx)
                }
            }

            SuggestionTarget.PLACE -> showPlace(
                PlaceRef(
                    name = s.title,
                    context = s.subtitle
                        .takeIf { it != "Luogo" && it != "Il tuo posto" && it != "Indirizzo" }
                        ?: "",
                    lat = s.lat,
                    lon = s.lon,
                    savedId = if (s.kind == "saved") s.key.toLongOrNull() else null,
                ),
            )
        }
    }

    // Ogni cambio di stato scende nella mappa da un punto solo.
    LaunchedEffect(mode, dark, ready?.overlayUrl, filter, locationGranted, follow) {
        controller.apply(
            mode = mode,
            dark = dark,
            overlayUrl = ready?.overlayUrl,
            filter = filter,
            locationEnabled = locationGranted,
            follow = follow,
        )
    }

    // All'avvio, col permesso gia' in tasca, la mappa parte su di te: e' il
    // comportamento da app di navigazione che la spec chiede.
    //
    // Non durante un viaggio: la schermata si ricompone da capo anche a
    // viaggio in corso (basta un cambio di tema), e questo effetto scriveva
    // FOLLOW sopra la bussola o la camminata che la navigazione aveva appena
    // deciso. E' NavCamera l'unica a scegliere, finche' si viaggia.
    //
    // Solo se la Posizione di Android e' accesa: con il permesso e la
    // Posizione spenta non arriva nessun rilevamento, e FOLLOW faceva
    // mostrare l'icona "passa alla bussola" su una mappa ferma. Se e' spenta
    // si resta liberi e si aspetta: appena si accende, sotto, si segue.
    LaunchedEffect(Unit) {
        if (locationGranted && navState == null) {
            if (serviceOn) {
                follow = FollowMode.FOLLOW
            } else {
                attendiPosizione = true
            }
        }
    }
    // La Posizione si accende mentre la mappa la aspettava: si segue, come
    // se fosse stata accesa da prima. Ha senso solo se nessuno ha toccato la
    // mappa nel frattempo (`attendiPosizione` cade al primo gesto) e non si
    // sta viaggiando: in navigazione sceglie NavCamera.
    LaunchedEffect(serviceOn, locationGranted) {
        if (serviceOn && locationGranted && attendiPosizione && navState == null) {
            attendiPosizione = false
            follow = FollowMode.FOLLOW
        }
    }

    // Logo e attribuzione MapLibre sopra tutto quello che c'e' in fondo.
    //
    // Non e' decorazione: la licenza dei dati chiede che si vedano. Il
    // margine era fisso all'altezza della tab bar, e da quando sopra la tab
    // bar c'e' anche la capsula "qui intorno" il logo finiva sotto il suo
    // vetro, tagliato a meta'. Ora lo dice lo stack stesso quanto e' alto:
    // quando la capsula non c'e', il logo torna giu' da solo.
    val density = androidx.compose.ui.platform.LocalDensity.current
    var altezzaFondo by remember { mutableStateOf(0) }
    var altezzaPannello by remember { mutableStateOf(0) }
    val copertoSotto = maxOf(altezzaFondo, if (panel != null) altezzaPannello else 0)
    LaunchedEffect(copertoSotto) {
        controller.chromeBottomPx = if (copertoSotto > 0) {
            copertoSotto + with(density) { 6.dp.toPx() }.toInt()
        } else {
            with(density) { (FluidTabBarDefaults.ContentInset + 6.dp).toPx() }.toInt()
        }
    }

    // Il selettore d'orario: "Parti alle / Arriva entro" col TimePicker.
    // Un orario gia' passato si legge come "domani a quest'ora".
    if (showTimeDialog) {
        val zone = dev.antigravity.fluidtransit.routing.Ftb.ROME
        val base = if (journeyTimeEpoch > 0) Instant.ofEpochSecond(journeyTimeEpoch) else Instant.now()
        val zdt = java.time.ZonedDateTime.ofInstant(base, zone)
        val timeState = androidx.compose.material3.rememberTimePickerState(
            initialHour = zdt.hour,
            initialMinute = zdt.minute,
            is24Hour = true,
        )
        var timeMode by remember { mutableStateOf(if (journeyTimeMode == "arrive") 1 else 0) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTimeDialog = false },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val chosen = java.time.ZonedDateTime.now(zone)
                        .withHour(timeState.hour)
                        .withMinute(timeState.minute)
                        .withSecond(0)
                    val instant = if (chosen.toInstant().isBefore(Instant.now().minusSeconds(60))) {
                        chosen.plusDays(1).toInstant()
                    } else {
                        chosen.toInstant()
                    }
                    journeyTimeEpoch = instant.epochSecond
                    journeyTimeMode = if (timeMode == 1) "arrive" else "depart"
                    showTimeDialog = false
                }) { androidx.compose.material3.Text("Fatto") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    journeyTimeMode = "now"
                    showTimeDialog = false
                }) { androidx.compose.material3.Text("Adesso") }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl(
                        options = listOf(0, 1),
                        selected = timeMode,
                        onSelect = { timeMode = it },
                        label = { if (it == 0) "Parti alle" else "Arriva entro" },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                    androidx.compose.material3.TimePicker(state = timeState)
                }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // La mappa e' la sorgente del vetro: tutto il chrome la rifrange.
        // La camera sopravvive a rotazione e ritorno dall'ultima schermata.
        val savedCamera = rememberSaveable { mutableStateOf<DoubleArray?>(null) }
        controller.onCameraIdle = {
            savedCamera.value = it
            cameraZoom = it[2]
            // Anche sul disco, per la prossima apertura: senza posizione la
            // mappa ripartiva dalla Toscana intera, che e' una vista da cui
            // non si fa niente.
            mapPrefs.camera = it
        }
        // Letta SENZA osservarla.
        //
        // Serve una volta sola, per dire alla mappa da dove partire. Ma un
        // `DoubleArray` nuovo a ogni fermata della camera non e' mai uguale
        // al precedente, quindi ogni pan — anche senza cambiare zoom —
        // ricomponeva tutto quello che l'aveva letta, cioe' questa schermata
        // intera. Leggerla fuori dall'osservazione la rende quello che e':
        // un valore iniziale, non uno stato.
        val cameraDiPartenza = androidx.compose.runtime.snapshots.Snapshot
            .withoutReadObservation { savedCamera.value }
            ?: remember { mapPrefs.camera }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassBackdropSource(backdrop),
        ) {
            TransitMap(
                controller = controller,
                modifier = Modifier.fillMaxSize(),
                initialCamera = cameraDiPartenza,
            )
        }

        // --- chrome in alto: la barra che diventa pannello, e i filtri ---
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .widthIn(max = PanelMaxWidth)
                .padding(horizontal = 14.dp)
                .padding(top = 8.dp),
        ) {
            if (searchOpen) {
                BackHandler {
                    searchOpen = false
                    query = ""
                    plannerField = null
                }
            } else if (plannerOpen) {
                BackHandler {
                    plannerOpen = false
                    plannerField = null
                    destRef = null
                    originRef = null
                    panel = null
                    controller.clearJourney()
                    controller.clearPlaceMarker()
                }
            }
            // I risultati della ricerca. Il come sta in SearchResults.kt.
            val risultati = rememberSearchResults(
                query = query,
                searchIndex = searchIndex,
                places = placesState,
                reader = ready?.reader,
                // Da dove si pesa la vicinanza: la regola sta in
                // `Reference`, ed e' la stessa dei recenti, delle fermate
                // vicine e dell'assistente.
                reference = {
                    dev.antigravity.fluidtransit.routing.Reference.point(
                        controller.lastLocation(), controller.cameraCenter(),
                    )
                },
            )

            // In navigazione la testata sparisce.
            //
            // Restavano la scheda del pianificatore — tre righe con partenza,
            // arrivo e orario — e i tre chip delle categorie, sopra una
            // schermata il cui unico scopo e' dire "scendi alla prossima".
            // Sono i comandi con cui si e' arrivati fin li', non quelli che
            // servono adesso: la stessa ragione per cui spariscono anche i
            // due cerchi in fondo.
            if (plannerOpen && !searchOpen && !navActive) {
                PlannerGlass(
                    backdrop = backdrop,
                    from = originRef,
                    to = destRef,
                    defaultFrom = if (locationGranted && controller.lastLocation() != null) {
                        dev.antigravity.fluidtransit.routing.OriginText.ROW_HERE
                    } else {
                        dev.antigravity.fluidtransit.routing.OriginText.rowMapCenter(
                            locationGranted, serviceOn,
                        )
                    },
                    // Una frase sola per le due righe che la mostrano, e col
                    // giorno quando non e' oggi. Il "domani" si decide quando
                    // si sceglie l'orario, che e' quando lo si legge.
                    timeLabel = remember(journeyTimeMode, journeyTimeEpoch) {
                        dev.antigravity.fluidtransit.routing.Times.journeyTimeLabel(
                            journeyTimeMode, journeyTimeEpoch, Instant.now().epochSecond,
                        )
                    },
                    onPickFrom = {
                        plannerField = "from"
                        query = ""
                        searchOpen = true
                    },
                    onPickTo = {
                        plannerField = "to"
                        query = ""
                        searchOpen = true
                    },
                    onSwap = {
                        // Scambiare con "la tua posizione" ha senso solo se
                        // quella posizione diventa un punto vero.
                        val here = originRef ?: controller.lastLocation()?.let {
                            PlaceRef("La tua posizione", "", it.first, it.second)
                        }
                        originRef = destRef
                        destRef = here
                        runPlanner()
                    },
                    onTime = { showTimeDialog = true },
                    onClose = {
                        plannerOpen = false
                        plannerField = null
                        destRef = null
                        originRef = null
                        panel = null
                        controller.clearJourney()
                        controller.clearPlaceMarker()
                    },
                )
            } else if (!navActive) {
            SearchGlass(
                backdrop = backdrop,
                open = searchOpen,
                query = query,
                placesReady = placesState is
                    dev.antigravity.fluidtransit.data.places.PlacesManager.State.Ready,
                placesWait = placesWaitOf(placesState),
                transitSearch = when {
                    searchIndex != null -> TransitSearch.READY
                    searchIndexFailed -> TransitSearch.FAILED
                    else -> TransitSearch.BUILDING
                },
                // I civici arrivano dopo, ma entrano nella stessa lista e si
                // ordinano insieme agli altri: stessa scala di pertinenza.
                // Riempiendo una riga del pianificatore le linee non si
                // offrono: non sono posti (vedi `isPlannerPoint`).
                results = if (plannerField != null) {
                    risultati.items.filter { it.isPlannerPoint() }
                } else {
                    risultati.items
                },
                searching = risultati.searching,
                saved = savedSuggestions,
                // Tutto tranne le linee, che hanno la loro fila.
                //
                // Il filtro nominava "stop" e "place", quindi i civici — che
                // si salvavano regolarmente fra i recenti — non si vedevano
                // MAI: cercare "via Bolognese 12" e ricercarla il giorno dopo
                // erano due ricerche identiche e complete.
                recents = recentSuggestions.filter { it.kind != "route" },
                plannerMode = plannerField != null,
                nearby = nearby,
                recentLines = if (plannerField != null) {
                    emptyList()
                } else {
                    recentSuggestions.filter { it.kind == "route" }
                },
                onOpen = { searchOpen = true },
                onClose = {
                    searchOpen = false
                    query = ""
                    // Chiudere la ricerca mentre si compilava una riga del
                    // pianificatore torna al pianificatore, non lo abbandona.
                    plannerField = null
                },
                onQueryChange = { query = it },
                onMic = {
                    // Col microfono si entra in modalita' vocale
                    // dell'assistente, se c'e' una chiave; altrimenti resta
                    // il riconoscimento di sistema di sempre, che trascrive
                    // nella barra e basta.
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    when {
                        !assistantEnabled -> launchSystemMic()
                        !granted -> audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        else ->
                            openAssistant(dev.antigravity.fluidtransit.ai.orchestrator.AskMode.VOICE)
                    }
                },
                // Scrivendo, il tasto del mic diventa "chiedi all'IA": e' la
                // scelta dell'utente, e regge perche' una frase scritta e'
                // quasi sempre una domanda, non il nome di una fermata.
                onAsk = if (assistantEnabled) {
                    {
                        openAssistant(
                            dev.antigravity.fluidtransit.ai.orchestrator.AskMode.TEXT,
                            query,
                        )
                    }
                } else {
                    null
                },
                onPick = { s ->
                    val field = plannerField
                    if (field == null) {
                        pick(s)
                    } else if (s.isPlannerPoint()) {
                        // La ricerca sta compilando una riga del
                        // pianificatore, non portando da qualche parte.
                        //
                        // Ma il posto scelto entra lo stesso nei recenti: era
                        // l'unico modo di sceglierne uno senza che l'app se lo
                        // ricordasse, e cosi' il viaggio di ieri andava
                        // ricercato per intero anche se era lo stesso di oggi.
                        if (s.kind != "saved") {
                            recentStore.add(
                                RecentSearches.Entry(
                                    s.kind, s.key, s.title, s.subtitle,
                                    s.colorRgb, s.lat, s.lon,
                                ),
                            )
                            recentsVersion++
                        }
                        val ref = PlaceRef(s.title, s.subtitle, s.lat, s.lon)
                        if (field == "from") originRef = ref else destRef = ref
                        plannerField = null
                        searchOpen = false
                        query = ""
                        plannerOpen = true
                        runPlanner()
                    }
                },
                // Mentre si compila una riga del pianificatore, l'ingresso al
                // pianificatore non ha piu' senso: ci siamo dentro.
                onPlanRoute = if (plannerField == null) ({ openPlanner() }) else null,
                onClearRecents = {
                    recentStore.clear()
                    recentsVersion++
                },
                hint = when (plannerField) {
                    "to" -> "Dove vuoi andare?"
                    "from" -> "Da dove parti?"
                    else -> "Fermata, linea o luogo…"
                },
            )
            }
            androidx.compose.animation.AnimatedVisibility(visible = !searchOpen && !navActive) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    CategoryChipsRow(
                        backdrop = backdrop,
                        selected = filter,
                        onSelect = {
                            filter = it
                            mapPrefs.filter = it
                        },
                    )
                }
            }

            // Il live degradato: silenzio finche' funziona, capsula discreta
            // quando manca davvero qualcosa — come deciso.
            //
            // Adesso comprende anche la strada diretta, che prima taceva: li'
            // i bus si muovono ma i ritardi non si scaricano, quindi OGNI
            // riga dell'app dice "orario da tabella". Sembrava che i mezzi
            // fossero tutti puntuali; invece non ne sapevamo niente.
            //
            // E non si accusa nessuno prima di aver provato: appena nato, il
            // tempo reale dichiara "solo orari" perche' non ha ancora
            // chiesto niente, e quello stato, mostrato, diventa "non risponde
            // ne' il nostro proxy ne' la Regione" — un'accusa a due servizi
            // che stanno benissimo, scritta mentre la prima richiesta e'
            // ancora in volo. Sull'emulatore non si vede, perche' il primo
            // giro vince la corsa col disegno; basta una rete lenta perche'
            // la vinca il disegno.
            val maiProvato = rtStatus.lastSuccessAt == null && rtStatus.lastError == null
            val liveDegraded = vehiclesActive && !searchOpen && !maiProvato && (
                rtStatus.source != dev.antigravity.fluidtransit.data.rt.RealtimeClient.Source.PROXY ||
                    (rtStatus.feedAgeSeconds ?: 0) >
                    dev.antigravity.fluidtransit.data.rt.RealtimeClient.STALE_SECONDS
                )
            androidx.compose.animation.AnimatedVisibility(visible = liveDegraded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(10.dp))
                    LiveDownCapsule(
                        backdrop = backdrop,
                        status = rtStatus,
                        onOpenDataStatus = onOpenDataStatus,
                        offline = !online,
                    )
                }
            }

            // L'aggiornamento disponibile, nella stessa forma dell'avviso del
            // live: e' un'app che non passa da uno store che aggiorna da solo,
            // quindi se non lo dice qui non lo dice nessuno.
            val update by app.updates.available.collectAsStateWithLifecycle()
            val updateHidden by app.updates.capsuleDismissed.collectAsStateWithLifecycle()
            androidx.compose.animation.AnimatedVisibility(visible = update != null && !updateHidden) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(10.dp))
                    update?.let { u ->
                        UpdateCapsule(
                            backdrop = backdrop,
                            version = u.version,
                            onInstall = { app.updates.install() },
                            onDismiss = { app.updates.dismissCapsule() },
                        )
                    }
                }
            }
        }

        // --- lo stack in fondo: i due comandi d'angolo, poi "qui intorno" --
        //
        // In una colonna sola, e non piu' liberi di sovrapporsi.
        //
        // Erano tre cose ancorate in fondo che non si conoscevano: i due
        // cerchi agli angoli a 14 punti dal fondo e la capsula "qui intorno"
        // a 10, cioe' praticamente alla stessa altezza. La capsula e' larga
        // quanto lo schermo, quindi li copriva: sulla mappa si vedevano due
        // mezzelune spuntare da sotto un vetro, e toccarle non si poteva.
        // Impilati, ognuno sa quanto spazio prende l'altro.
        val reader = ready?.reader
        // I comandi della mappa servono quando si guarda la MAPPA.
        //
        // Sparivano gia' sotto i pannelli grandi, perche' si vedevano in
        // trasparenza dentro il vetro; ma la stessa cosa succedeva con la
        // capsula di una linea, con la ricerca aperta e in navigazione — e
        // in nessuno di quei momenti si cambia vista satellite.
        val comandiVisibili = panel == null && !searchOpen && !plannerOpen && !navActive
        // Dove si sta guardando, dentro o fuori dalla zona coperta.
        //
        // Cinque chilometri di perdono: il confine di una regione non e' un
        // rettangolo, e chi sta appena oltre merita la risposta normale — che
        // per una fermata a due passi dal confine e' anche quella giusta.
        val fuoriArea by produceState(false, reader) {
            val r = reader
            if (r == null) {
                value = false
                return@produceState
            }
            while (true) {
                val p = dev.antigravity.fluidtransit.routing.Reference.point(
                    controller.lastLocation(), controller.cameraCenter(),
                )
                value = p != null && !r.bounds.contains(p.first, p.second, 5_000.0)
                kotlinx.coroutines.delay(2_000)
            }
        }
        // In bussola l'icona del tasto GIRA col nord: e' l'unica bussola
        // dell'app (quella di MapLibre in alto e' spenta). La scrittura di
        // stato avviene SOLO in bussola: fuori, aggiornare a ogni frame di
        // pan era lavoro regalato al garbage collector.
        var bearing by remember { mutableStateOf(0f) }
        controller.onBearing = if (follow == FollowMode.COMPASS) {
            { bearing = it.toFloat() }
        } else {
            null
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Prima di `navigationBarsPadding`, cosi' la misura comprende
                // anche l'inserto di sistema: e' l'altezza vera di cio' che
                // copre la mappa, ed e' quella che serve al logo.
                .onSizeChanged { altezzaFondo = it.height }
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = comandiVisibili,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                ) {
                    MapCornerButton(
                        icon = Icons.Rounded.Layers,
                        contentDescription = if (mode == MapCatalog.MapMode.STREETS) {
                            "Passa alla vista ibrida"
                        } else {
                            "Passa alla vista stradale"
                        },
                        backdrop = backdrop,
                        onClick = {
                            mode = if (mode == MapCatalog.MapMode.STREETS) {
                                MapCatalog.MapMode.HYBRID
                            } else {
                                MapCatalog.MapMode.STREETS
                            }
                            mapPrefs.mode = mode
                        },
                        modifier = Modifier.padding(start = 14.dp),
                    )
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = comandiVisibili,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                ) {
                    MapCornerButton(
                        icon = when (follow) {
                            FollowMode.FREE -> Icons.Rounded.LocationSearching
                            FollowMode.FOLLOW -> Icons.Rounded.MyLocation
                            FollowMode.COMPASS, FollowMode.NAV_CAMMINO -> Icons.Rounded.Explore
                        },
                        contentDescription = when (follow) {
                            FollowMode.FREE -> "Centrati sulla mia posizione"
                            FollowMode.FOLLOW -> "Passa alla bussola"
                            FollowMode.COMPASS, FollowMode.NAV_CAMMINO ->
                                "Torna alla vista normale"
                        },
                        backdrop = backdrop,
                        iconRotation = { if (follow == FollowMode.COMPASS) -bearing else 0f },
                        // Tre rami, non due: il permesso dell'app, la Posizione
                        // di Android, e solo se ci sono tutt'e due il giro
                        // delle inquadrature. Vedi `toccaPosizione`.
                        onClick = { toccaPosizione(follow) },
                        modifier = Modifier.padding(end = 14.dp),
                    )
                }
            }
            // Il primo avvio: la posizione spenta e la mappa su tutta la
            // regione. Finche' non si e' abbastanza vicini perche' "qui
            // intorno" voglia dire qualcosa, l'unica cosa utile da dire e'
            // come farglielo sapere.
            //
            // Vale anche col permesso gia' dato e la Posizione di Android
            // spenta: e' lo stesso schermo vuoto, e il mirino da solo non
            // spiegava perche'. Cambia cosa si dice e dove porta il tocco.
            androidx.compose.animation.AnimatedVisibility(
                visible = comandiVisibili && !fuoriArea &&
                    (!locationGranted || !serviceOn) &&
                    cameraZoom < MapCatalog.NEARBY_MIN_ZOOM,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
            ) {
                MapNoticeCapsule(
                    icon = Icons.Rounded.NearMe,
                    title = "Vedi cosa passa qui intorno",
                    detail = if (!locationGranted) {
                        "Tocca per attivare la posizione"
                    } else {
                        "Tocca per accendere la posizione del telefono"
                    },
                    iconTint = MaterialTheme.colorScheme.primary,
                    backdrop = backdrop,
                    // Da libero: se nel frattempo la Posizione si e' accesa
                    // il tocco comincia a seguire, non cicla oltre.
                    onClick = { toccaPosizione(FollowMode.FREE) },
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .padding(horizontal = FluidTabBarDefaults.HorizontalMargin),
                )
            }
            // Fuori dalla Toscana non c'e' niente da dire sui bus.
            //
            // "Qui intorno non passa niente a breve" e' la frase di una notte
            // tranquilla, e la si leggeva identica a mille chilometri dalla
            // zona coperta: succede a chi installa l'app in vacanza, a chi
            // scende dal treno fuori regione, e a chiunque apra l'emulatore,
            // che nasce a Mountain View. Una mappa vuota che dice che non
            // passa niente fa pensare che l'app sia rotta, invece di dire una
            // cosa semplicissima.
            androidx.compose.animation.AnimatedVisibility(
                visible = comandiVisibili && fuoriArea,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
            ) {
                MapNoticeCapsule(
                    icon = Icons.Rounded.Info,
                    title = "Qui non ci sono i nostri orari",
                    detail = "Fluid Transit copre la Toscana",
                    backdrop = backdrop,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .padding(horizontal = FluidTabBarDefaults.HorizontalMargin),
                )
            }
            // Ci sono orari nuovi e siamo sui dati mobili: l'app chiede prima di
            // scaricarli, e la domanda stava solo in Stato dei dati, dove non
            // la cerca nessuno. Qui la si vede, e toccandola ci si arriva.
            val offertaOrari by app.bundleManager.updateOffer.collectAsStateWithLifecycle()
            androidx.compose.animation.AnimatedVisibility(
                visible = comandiVisibili && panel == null &&
                    offertaOrari is dev.antigravity.fluidtransit.data.bundle.BundleManager.UpdateOffer.Offered,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
            ) {
                MapNoticeCapsule(
                    icon = Icons.Rounded.Info,
                    title = "Ci sono orari nuovi da scaricare",
                    detail = "Tocca per aggiornarli, anche sui dati mobili",
                    backdrop = backdrop,
                    onClick = onOpenDataStatus,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .padding(horizontal = FluidTabBarDefaults.HorizontalMargin),
                )
            }
            // Cosa passa qui intorno, dove la tab bar lascia spazio: si legge
            // senza toccare niente, e toccandola si apre tutto.
            androidx.compose.animation.AnimatedVisibility(
                // Da lontano "qui intorno" non vuol dire niente.
                //
                // Al primo avvio, senza permesso della posizione, la mappa si
                // apre su tutta la Toscana: il centro cade in campagna fra
                // Siena e Colle, e la capsula mostrava le partenze di un paese
                // a caso come se fossero le tue. Sotto lo zoom in cui si
                // distinguono le strade, l'unica risposta onesta e' non
                // rispondere: c'e' il mirino, ed e' li' accanto.
                visible = comandiVisibili && !fuoriArea && reader != null &&
                    nearbyBoard.computedAtEpoch != 0L &&
                    cameraZoom >= MapCatalog.NEARBY_MIN_ZOOM,
                enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it / 3 }) +
                    androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it / 3 }) +
                    androidx.compose.animation.fadeOut(),
            ) {
                NearbyCapsule(
                    board = nearbyBoard,
                    backdrop = backdrop,
                    onClick = { panel = Panel.Nearby },
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .padding(horizontal = FluidTabBarDefaults.HorizontalMargin),
                )
            }
            Spacer(Modifier.height(FluidTabBarDefaults.ContentInset + 10.dp))
        }

        // --- l'unico pannello dal basso: fermata, linea, o linea ridotta ---
        // Il passaggio fra i tre e' un morphing della stessa superficie di
        // vetro; in modalita' linea il pannello prende il posto della tab bar.
        val inRoutePanel = panel is Panel.RouteMini || panel is Panel.RouteFull ||
            panel is Panel.TripMini || panel is Panel.TripFull
        // In modalita' linea il pannello siede ESATTAMENTE dove sedeva la
        // tab bar: stessi margini, e il mini anche la stessa altezza — cosi'
        // il rimbalzo del congedo si legge come la capsula che ritorna.
        val bottomPad by androidx.compose.animation.core.animateDpAsState(
            targetValue = if (inRoutePanel) {
                FluidTabBarDefaults.BottomMargin
            } else {
                FluidTabBarDefaults.ContentInset + 10.dp
            },
            label = "panelBottomPad",
        )
        // "Perche' questo numero", dichiarato qui e disegnato alla radice: si
        // apre SUL numero toccato, non al centro dello schermo.
        dev.antigravity.fluidtransit.ui.common.WhyThisNumberPortal(
            link = app.realtime.link(),
            row = whyRow,
            nowEpoch = whyAt,
            origin = { whyOrigin },
            onDismiss = { whyRow = null },
            onOpenDataStatus = onOpenDataStatus,
            coverage = {
                val r = ready?.reader
                if (r == null) {
                    null
                } else {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        dev.antigravity.fluidtransit.routing.Coverage
                            .now(r, java.time.Instant.now(), app.departureBoards.live())
                    }
                }
            },
        )

        androidx.compose.animation.AnimatedVisibility(
            visible = panel != null && reader != null,
            enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it / 3 }) +
                androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it / 3 }) +
                androidx.compose.animation.fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Quanto alto arriva il vetro: serve al logo di MapLibre, che
                // altrimenti resta sotto e si legge in trasparenza dentro il
                // pannello. Nella scheda "Qui intorno" finiva esattamente
                // sopra la riga della provenienza: due scritte sovrapposte,
                // una dell'app e una della mappa.
                .onSizeChanged { altezzaPannello = it.height }
                .navigationBarsPadding(),
        ) {
            val p = panel
            if (p != null && reader != null) {
                BackHandler {
                    when (p) {
                        is Panel.RouteFull -> panel = Panel.RouteMini(p.routeIndex)
                        is Panel.TripFull -> panel = Panel.TripMini(p.ref)
                        is Panel.JourneyDetail -> panel = Panel.Journeys(p.to)
                        is Panel.Journeys -> panel = Panel.Place(p.to)
                        else -> exitRouteMode()
                    }
                }
                val isMini = p is Panel.RouteMini || p is Panel.TripMini
                val imeInsets = WindowInsets.ime
                val navInsets = WindowInsets.navigationBars
                BottomGlassPanel(
                    backdrop = backdrop,
                    paneTitle = when (val p = panel) {
                        is Panel.Stop -> "Fermata ${p.tap.name}"
                        is Panel.RouteMini, is Panel.RouteFull -> "Linea"
                        is Panel.TripMini, is Panel.TripFull -> "Corsa"
                        is Panel.Place -> p.ref.name.ifEmpty { "Luogo" }
                        Panel.Nearby -> "Qui intorno"
                        is Panel.Journeys -> "Come arrivare a ${p.to.name}"
                        is Panel.JourneyDetail -> "Il viaggio per ${p.to.name}"
                        null -> null
                    },
                    shape = if (isMini) {
                        dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
                    } else {
                        dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape(
                            dev.antigravity.fluidengine.ui.fluid.FluidRadius.Sheet,
                        )
                    },
                    wholeSurfaceDrag = isMini,
                    showGrabber = !isMini,
                    // L'esteso si RIDUCE nel mini e il mini TORNA tab bar:
                    // rimbalzo sul posto piu' trasformazione, mai lo
                    // scivola-via-e-riappari segnalato come "roba strana".
                    transformOnDismiss = p !is Panel.Stop && p !is Panel.Place,
                    onDragExpand = when (p) {
                        is Panel.RouteMini -> ({ panel = Panel.RouteFull(p.routeIndex) })
                        is Panel.TripMini -> ({ panel = Panel.TripFull(p.ref) })
                        else -> null
                    },
                    onDragDismiss = {
                        when (p) {
                            is Panel.RouteFull -> panel = Panel.RouteMini(p.routeIndex)
                            is Panel.TripFull -> panel = Panel.TripMini(p.ref)
                            is Panel.JourneyDetail -> panel = Panel.Journeys(p.to)
                            is Panel.Journeys -> panel = Panel.Place(p.to)
                            else -> exitRouteMode()
                        }
                    },
                    modifier = Modifier
                        .widthIn(max = PanelMaxWidth)
                        .padding(horizontal = FluidTabBarDefaults.HorizontalMargin)
                        .padding(bottom = bottomPad)
                        // Mentre si scrive il nome di un posto il pannello
                        // sale sopra la tastiera, tutto, senza cambiare le
                        // sue misure: vedi PlacePanelContent.onEditing.
                        .offset {
                            if (p is Panel.Place && placeEditing) {
                                val tastiera = imeInsets.getBottom(this)
                                val barra = navInsets.getBottom(this)
                                val sale = keyboardLift(
                                    tastiera.toDp(), barra.toDp(), PLACE_PANEL_RESTING_MARGIN,
                                )
                                androidx.compose.ui.unit.IntOffset(0, -sale.roundToPx())
                            } else {
                                androidx.compose.ui.unit.IntOffset.Zero
                            }
                        },
                ) {
                    androidx.compose.animation.AnimatedContent(
                        targetState = p,
                        transitionSpec = {
                            androidx.compose.animation.fadeIn() togetherWith
                                androidx.compose.animation.fadeOut()
                        },
                        contentKey = { state ->
                            when (state) {
                                is Panel.Stop -> "stop-${state.tap.idHashHex}"
                                is Panel.RouteMini -> "mini-${state.routeIndex}"
                                is Panel.RouteFull -> "full-${state.routeIndex}"
                                is Panel.TripMini -> "tmini-${state.ref.vehKey}"
                                is Panel.TripFull -> "tfull-${state.ref.vehKey}"
                                is Panel.Place -> "place-${state.ref.lat}-${state.ref.lon}"
                                is Panel.Nearby -> "nearby"
                                is Panel.Journeys -> "journeys-${state.to.lat}"
                                is Panel.JourneyDetail -> "jdetail-${state.index}"
                            }
                        },
                        label = "panelContent",
                    ) { state ->
                        when (state) {
                            is Panel.Stop -> Column {
                                val favVersion by app.favorites.version.collectAsStateWithLifecycle()
                                val isFav = remember(favVersion, state.tap.idHashHex) {
                                    app.favorites.isStopFavorite(state.tap.idHashHex)
                                }
                                StopPanelContent(
                                    app = app,
                                    reader = reader,
                                    stopIdHashHex = state.tap.idHashHex,
                                    fallbackName = state.tap.name,
                                    onStartHere = {
                                        val hash = state.tap.idHashHex.toULongOrNull(16)?.toLong()
                                        val s = hash?.let { reader.findStopByIdHash(it) } ?: -1
                                        if (s >= 0) {
                                            originRef = PlaceRef(
                                                reader.stopName(s), "Fermata",
                                                reader.stopLat(s), reader.stopLon(s),
                                            )
                                            openPlanner()
                                            if (destRef != null) runPlanner()
                                        }
                                    },
                                    onDismiss = { panel = null },
                                    onRouteTap = ::showRoute,
                                    alerts = avvisiDiFermata.rows,
                                    alertsNote = avvisiDiFermata.staleNote,
                                    onOpenAlerts = onOpenAlerts,
                                    onWhyTap = { r, rect ->
                                        whyRow = r
                                        whyOrigin = rect
                                        whyAt = java.time.Instant.now().epochSecond
                                    },
                                    backdrop = backdrop,
                                    isFavorite = isFav,
                                    onToggleFavorite = {
                                        app.favorites.toggleStop(state.tap.idHashHex, state.tap.name)
                                    },
                                    onFlyToBus = { tripIdx ->
                                        val meta = resolved?.vehicleByTrip?.get(tripIdx)
                                            ?.let { vk -> resolved?.busMetaByKey?.get(vk) }
                                        if (meta != null) {
                                            showTrip(
                                                TripRef(
                                                    meta.vehKey, meta.tripHash, meta.routeHash,
                                                    meta.tripIndex, meta.routeIndex,
                                                ),
                                                focus = meta.lat to meta.lon,
                                            )
                                        }
                                    },
                                )
                            }

                            is Panel.RouteMini -> Column(
                                modifier = Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClickLabel = "Espandi la scheda della linea",
                                    onClick = { panel = Panel.RouteFull(state.routeIndex) },
                                ),
                            ) {
                                val info = routeInfo
                                if (info != null && info.routeIndex == state.routeIndex) {
                                    RouteMiniContent(info, routeDirection)
                                } else if (routeFailedFor == state.routeIndex) {
                                    PanelFailed("Non sono riuscito a leggere questa linea.") {
                                        routeRetry++
                                    }
                                } else {
                                    PanelLoading("Leggo la linea\u2026", compact = true)
                                }
                            }

                            is Panel.RouteFull -> Column {
                                val info = routeInfo
                                if (info != null && info.routeIndex == state.routeIndex) {
                                    val routeHashHex = remember(state.routeIndex) {
                                        java.lang.Long.toHexString(reader.routeIdHash(state.routeIndex))
                                    }
                                    val favVersion by app.favorites.version.collectAsStateWithLifecycle()
                                    val isFav = remember(favVersion, routeHashHex) {
                                        app.favorites.isRouteFavorite(routeHashHex)
                                    }
                                    RouteFullContent(
                                        info = info,
                                        direction = routeDirection,
                                        onDirectionChange = { routeDirection = it },
                                        onStopTap = { stopRef ->
                                            controller.exitRouteMode()
                                            controller.flyTo(stopRef.lat, stopRef.lon, 16.2)
                                            panel = Panel.Stop(
                                                StopTap(stopRef.idHashHex, stopRef.name),
                                            )
                                        },
                                        isFavorite = isFav,
                                        onToggleFavorite = {
                                            app.favorites.toggleRoute(
                                                routeHashHex, info.shortName, info.colorRgb,
                                            )
                                        },
                                        onDismiss = ::exitRouteMode,
                                        alerts = avvisiDiLinea.rows,
                                        alertsNote = avvisiDiLinea.staleNote,
                                        onOpenAlerts = onOpenAlerts,
                                    )
                                } else if (routeFailedFor == state.routeIndex) {
                                    PanelFailed("Non sono riuscito a leggere questa linea.") {
                                        routeRetry++
                                    }
                                } else {
                                    PanelLoading("Leggo la linea\u2026")
                                }
                            }

                            is Panel.TripMini -> Column(
                                modifier = Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClickLabel = "Espandi la scheda della corsa",
                                    onClick = { panel = Panel.TripFull(state.ref) },
                                ),
                            ) {
                                val info = tripInfo
                                if (info != null && info.ref.vehKey == state.ref.vehKey) {
                                    TripMiniContent(info)
                                } else if (tripFailedFor == state.ref.vehKey) {
                                    PanelFailed("Non sono riuscito a leggere questa corsa.") {
                                        tripRetry++
                                    }
                                } else {
                                    PanelLoading("Leggo la corsa\u2026", compact = true)
                                }
                            }

                            is Panel.TripFull -> Column {
                                val info = tripInfo
                                // Il battito comune, per far invecchiare l'eta'
                                // della posizione fra un poll e l'altro.
                                val battito = remember {
                                    dev.antigravity.fluidtransit.data.time.UiClock.ticks()
                                }
                                val adesso by battito.collectAsStateWithLifecycle(
                                    initialValue = Instant.now().epochSecond,
                                )
                                if (info != null && info.ref.vehKey == state.ref.vehKey) {
                                    val meta = resolved?.busMetaByKey?.get(state.ref.vehKey)
                                    // La guardia GPS decisa in Fase 2: pulsante solo se la
                                    // posizione e' coerente col mezzo; GPS spento = via libera.
                                    val guard = if (meta == null || info.ref.tripIndex < 0) {
                                        null
                                    } else {
                                        val loc = controller.lastLocation()
                                        when {
                                            loc == null -> "ok"
                                            dev.antigravity.fluidtransit.routing.BundleReader.haversine(
                                                loc.first, loc.second, meta.lat, meta.lon,
                                            ) <= 300.0 -> "ok"

                                            else -> "far"
                                        }
                                    }
                                    TripFullContent(
                                        info = info,
                                        // L'eta' del rilevamento ADESSO, non
                                        // quella dell'ultimo snapshot: da
                                        // quando e' stato risolto e' passato
                                        // altro tempo, e se il feed si e'
                                        // fermato la scheda diceva "40 s fa"
                                        // per venti minuti. -1 resta ignota.
                                        fixAgeSec = meta?.fixAgeSec
                                            ?.takeIf { it >= 0 }
                                            ?.let { alRisolvere ->
                                                val passati = if (resolvedAtSec > 0L) {
                                                    (adesso - resolvedAtSec).coerceAtLeast(0L)
                                                } else {
                                                    0L
                                                }
                                                (alRisolvere + passati)
                                                    .coerceAtMost(Int.MAX_VALUE.toLong())
                                                    .toInt()
                                            } ?: meta?.fixAgeSec,
                                        onStopTap = { stop ->
                                            exitRouteMode()
                                            controller.flyTo(stop.lat, stop.lon, 16.2)
                                            panel = Panel.Stop(
                                                StopTap(stop.idHashHex, stop.name),
                                            )
                                        },
                                        backdrop = backdrop,
                                        boardGuard = guard,
                                        onBoardBus = {
                                            val delay = resolved?.delayByTrip
                                                ?.get(info.ref.tripIndex) ?: 0
                                            val plan = buildBusNavPlan(
                                                reader, info.ref.tripIndex, delay,
                                                app.departureBoards.live(),
                                            )
                                            if (plan != null) {
                                                avviaNavigazione(plan)
                                                panel = null
                                            } else {
                                                // Il piano non si costruisce quando la
                                                // corsa non ha piu' un "dopo" dove
                                                // scendere. Il tasto pero' c'era lo
                                                // stesso, e il tocco non faceva niente
                                                // — lo stesso difetto del mirino della
                                                // posizione, sullo stesso schermo.
                                                scope.launch {
                                                    notifications?.show(
                                                        dev.antigravity.fluidengine.ui.fluid
                                                            .FluidNotification(
                                                                id = "corsa-finita",
                                                                title = "Questa corsa e' finita",
                                                                message = "L'ultima fermata e' gia' " +
                                                                    "passata: non c'e' piu' un pezzo " +
                                                                    "di viaggio da seguire.",
                                                                tone = dev.antigravity.fluidengine
                                                                    .ui.fluid
                                                                    .FluidNotificationTone.Info,
                                                            ),
                                                    )
                                                }
                                            }
                                        },
                                        onDismiss = ::exitRouteMode,
                                        alerts = avvisiDiCorsa.rows,
                                        alertsNote = avvisiDiCorsa.staleNote,
                                        onOpenAlerts = onOpenAlerts,
                                    )
                                } else if (tripFailedFor == state.ref.vehKey) {
                                    PanelFailed("Non sono riuscito a leggere questa corsa.") {
                                        tripRetry++
                                    }
                                } else {
                                    PanelLoading("Leggo la corsa\u2026")
                                }
                            }

                            is Panel.Nearby -> Column {
                                NearbyPanelContent(
                                    board = nearbyBoard,
                                    onDismiss = { panel = null },
                                    onRouteTap = ::showRoute,
                                    // Da dove si guarda: dov'e' la persona,
                                    // o il centro della mappa se il GPS e'
                                    // spento. La stessa regola della ricerca.
                                    distanceOf = { stop ->
                                        val r = ready?.reader
                                        val da = controller.lastLocation()
                                            ?: controller.cameraCenter()
                                        if (r == null || da == null || stop < 0) {
                                            null
                                        } else {
                                            dev.antigravity.fluidtransit.routing.BundleReader
                                                .haversine(
                                                    da.first, da.second,
                                                    r.stopLat(stop), r.stopLon(stop),
                                                )
                                        }
                                    },
                                    onWhyTap = { r, rect ->
                                        whyRow = r
                                        whyOrigin = rect
                                        whyAt = java.time.Instant.now().epochSecond
                                    },
                                    onStopTap = { stopIndex ->
                                        // Dalla riga si va alla fermata: e' la
                                        // continuazione naturale di "cosa passa
                                        // qui intorno" quando una delle risposte
                                        // interessa davvero.
                                        panel = Panel.Stop(
                                            StopTap(
                                                java.lang.Long.toHexString(
                                                    reader.stopIdHash(stopIndex),
                                                ),
                                                reader.stopName(stopIndex),
                                            ),
                                        )
                                        controller.flyTo(
                                            reader.stopLat(stopIndex),
                                            reader.stopLon(stopIndex),
                                            16.0,
                                        )
                                    },
                                )
                            }

                            is Panel.Place -> Column {
                                PlacePanelContent(
                                    ref = state.ref,
                                    backdrop = backdrop,
                                    onEditing = { placeEditing = it },
                                    onDismiss = { exitRouteMode() },
                                    onGo = { goToPlace(state.ref) },
                                    onStartHere = {
                                        // Questo punto diventa la partenza:
                                        // dal tieni-premuto, da un posto
                                        // salvato o da un risultato di
                                        // ricerca, indifferentemente.
                                        originRef = state.ref
                                        controller.clearPlaceMarker()
                                        openPlanner()
                                        if (destRef != null) runPlanner()
                                    },
                                    onSave = { label ->
                                        app.savedPlaces.add(label, state.ref.lat, state.ref.lon)
                                        savedVersion++
                                        val id = app.savedPlaces.load()
                                            .firstOrNull { it.label.equals(label.trim(), true) }?.id
                                        panel = Panel.Place(
                                            PlaceRef(label.trim(), state.ref.name, state.ref.lat, state.ref.lon, id),
                                        )
                                    },
                                    onRemoveSaved = state.ref.savedId?.let { id ->
                                        {
                                            app.savedPlaces.remove(id)
                                            savedVersion++
                                            exitRouteMode()
                                        }
                                    },
                                )
                            }

                            is Panel.Journeys -> Column {
                                JourneysContent(
                                    toName = state.to.name,
                                    journeys = journeys,
                                    failed = journeysFailed,
                                    searching = journeysSearching,
                                    samePlace = journeyOrigin?.let { (lat, lon) ->
                                        dev.antigravity.fluidtransit.routing.BundleReader
                                            .haversine(lat, lon, state.to.lat, state.to.lon) <
                                            SAME_PLACE_M
                                    } ?: false,
                                    fromLabel = journeyFrom,
                                    fromMapCenter = partenzaDalCentro,
                                    onUseLocation = if (partenzaDalCentro) {
                                        { toccaPosizione(FollowMode.FREE) }
                                    } else {
                                        null
                                    },
                                    timeLabel = remember(journeyTimeMode, journeyTimeEpoch) {
                                        dev.antigravity.fluidtransit.routing.Times.journeyTimeLabel(
                                            journeyTimeMode, journeyTimeEpoch,
                                            Instant.now().epochSecond,
                                        )
                                    },
                                    backdrop = backdrop,
                                    onTimeTap = { showTimeDialog = true },
                                    onPick = { i -> panel = Panel.JourneyDetail(state.to, i) },
                                    onDismiss = { panel = Panel.Place(state.to) },
                                )
                            }

                            is Panel.JourneyDetail -> Column {
                                val j = journeys?.getOrNull(state.index)
                                if (j != null) {
                                    JourneyDetailContent(
                                        j = j,
                                        toName = state.to.name,
                                        onDismiss = { panel = Panel.Journeys(state.to) },
                                        backdrop = backdrop,
                                        alerts = avvisiDiViaggio.rows,
                                        alertsNote = avvisiDiViaggio.staleNote,
                                        onOpenAlerts = onOpenAlerts,
                                        onStart = {
                                            val plan = buildNavPlan(reader, j.raw, state.to.name)
                                            avviaNavigazione(plan)
                                            panel = null
                                        },
                                        // Una routine salva la partenza per sempre: il centro
                                        // della mappa, al primo avvio, e' la campagna fra
                                        // Siena e Colle, e la routine "Esci alle 8" partirebbe
                                        // da li'. Senza una partenza vera il modulo non c'e'.
                                        onCreateRoutine = { days: Set<Int>, anchor: String, minutes: Int ->
                                            val from = journeyOrigin
                                            if (from != null) {
                                                val routine =
                                                    dev.antigravity.fluidtransit.data.routines.Routines.Routine(
                                                        id = System.currentTimeMillis(),
                                                        label = "→ ${state.to.name}",
                                                        fromLat = from.first,
                                                        fromLon = from.second,
                                                        toLat = state.to.lat,
                                                        toLon = state.to.lon,
                                                        toName = state.to.name,
                                                        days = days,
                                                        anchor = anchor,
                                                        anchorMinutes = minutes,
                                                        enabled = true,
                                                    )
                                                app.routines.add(routine)
                                                dev.antigravity.fluidtransit.data.routines.RoutineScheduler
                                                    .scheduleNextCompute(context, routine)
                                                chiediNotifiche(NotifMotivo.ROUTINE)
                                            }
                                        }.takeIf { !partenzaDalCentro },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- la navigazione: si prende lo schermo mentre viaggi ------------
        //
        // Prima era una riga sola al posto della tab bar, e i numeri che il
        // servizio calcolava — fermate che mancano, avanzamento, l'ora di
        // arrivo — non li leggeva nessun composable: finivano solo nella
        // notifica. Adesso la card nasce aperta e si riduce a capsula
        // quando si vuole guardare la mappa.
        val navPlan by app.navigation.plan.collectAsStateWithLifecycle()
        if (panel == null) {
            NavOverlay(
                state = navState,
                plan = navPlan,
                focus = navFocus,
                backdrop = backdrop,
                // Il colore di una linea si chiede al bundle con l'indice del
                // piano: se i due non sono lo stesso bundle — fase "lost", o
                // l'istante dopo uno scambio — l'indice e' di un'altra linea,
                // o fuori tabella. Grigio neutro, e mai un'eccezione in
                // composizione.
                colorOf = { route ->
                    val r = ready?.reader
                    val altro = navPlan?.let { it.buildId != 0L && it.buildId != r?.buildId } == true
                    if (r == null || altro || route !in 0 until r.routeCount) {
                        0x8A8A93
                    } else {
                        r.routeDisplayColor(route) and 0xFFFFFF
                    }
                },
                esteso = navEsteso,
                onEsteso = { navEsteso = it },
                onStop = { app.navigation.stop(context) },
                onHeight = { navCardHeight = it },
            )
        }

        // Torna a seguire il viaggio.
        //
        // In navigazione il mirino sparisce insieme al resto dei comandi, e
        // finche' non c'e' stato questo tasto l'unico modo di guardare
        // avanti sul percorso era terminare il viaggio: la mappa si poteva
        // spanare, ma il tracking se la riprendeva subito.
        androidx.compose.animation.AnimatedVisibility(
            // Solo mentre c'e' qualcosa da seguire: arrivati, o persi, il
            // tasto compariva e non faceva niente.
            visible = navActive && navManuale &&
                navState?.phase.let { it == "walk" || it == "wait" || it == "ride" },
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(14.dp),
        ) {
            MapCornerButton(
                icon = Icons.Rounded.Explore,
                contentDescription = "Torna a seguire il viaggio",
                backdrop = backdrop,
                onClick = { navManuale = false },
            )
        }

        // --- l'assistente: un pannello appoggiato in basso, non un dialogo.
        // La mappa resta viva sopra e sotto, e mentre lui cerca una linea si
        // vedono i bus muoversi — che e' il momento in cui uno vuole
        // guardarli.
        if (assistantOpen) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = FluidTabBarDefaults.HorizontalMargin)
                    .padding(bottom = FluidTabBarDefaults.BottomMargin),
            ) {
                dev.antigravity.fluidtransit.ui.assistant.AssistantOverlay(
                    app = app,
                    backdrop = backdrop,
                    startMode = assistantMode,
                    initialQuestion = assistantQuestion,
                    onPlace = { name ->
                        // Il chip apre il posto con la stessa ricerca della
                        // barra: un solo criterio, una sola risposta.
                        val hit = app.assistantBridge.findStops(name, 1).firstOrNull()
                        if (hit != null) {
                            assistantOpen = false
                            panel = Panel.Stop(StopTap(hit.idHashHex, hit.name))
                            controller.flyTo(hit.lat, hit.lon, 16.0)
                        } else {
                            query = name
                            assistantOpen = false
                            searchOpen = true
                        }
                    },
                    onClose = { assistantOpen = false },
                )
            }
            BackHandler { assistantOpen = false }
        }

    }
}

/**
 * Un recente, con la distanza di ADESSO.
 *
 * Il sottotitolo si salva insieme alla ricerca, e per le fermate contiene la
 * distanza: quindi restava quella di quando l'avevi cercata. Sul telefono,
 * nello stesso istante: "STAZIONE PIAZZA ADUA · Fermata · a 0 m" fra i
 * recenti — misurata mesi fa con la mappa centrata li' sopra — e la stessa
 * fermata a un chilometro e mezzo nell'elenco sotto. La distanza e' l'unica
 * cosa che un recente non puo' ricordare, perche' e' l'unica che dipende da
 * dove sei adesso.
 */
private fun RecentSearches.Entry.toSuggestion(
    riferimento: Pair<Double, Double>?,
    reader: dev.antigravity.fluidtransit.routing.BundleReader?,
): Suggestion {
    val sub = if (kind == "stop") {
        riferimento?.let { (la, lo) ->
            dev.antigravity.fluidtransit.routing.Words.distanceNear(
                dev.antigravity.fluidtransit.routing.BundleReader.haversine(la, lo, lat, lon),
            )?.let { "Fermata · a $it" }
        } ?: "Fermata"
    } else {
        subtitle
    }
    // Lo stesso vale per il colore, e per lo stesso motivo: il salvato e' un
    // ripiego, il bundle di oggi e' la fonte.
    val colore = if (kind == "route") routeColorToday(reader, key, colorRgb) else colorRgb
    return Suggestion(kind, key, title, sub, colore, lat, lon)
}

private fun hhmm(epochSecond: Long): String {
    // Lo zero non e' mezzanotte: e' "non lo so". Per il resto l'orologio e'
    // quello del vocabolario, non una copia locale.
    if (epochSecond <= 0) return "—"
    return dev.antigravity.fluidtransit.routing.Times.hhmm(epochSecond)
}

/**
 * Perche' si chiedono le notifiche: cambia cosa si dice se la risposta e' no.
 */
private enum class NotifMotivo { ROUTINE, VIAGGIO }

/**
 * La Posizione di Android e' accesa?
 *
 * E' un interruttore diverso dal permesso dell'app, e si guarda a parte. Se
 * il sistema non risponde si presume acceso: meglio un mirino che prova che
 * un mirino che rifiuta a torto.
 */
private fun posizioneDiSistemaAccesa(context: android.content.Context): Boolean =
    runCatching {
        context.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled
    }.getOrNull() ?: true

/**
 * I due permessi di posizione, chiesti insieme: e' cosi' che Android 12 mostra
 * la scelta fra precisa e approssimativa. Chiedendo solo FINE, chi voleva
 * l'approssimativa non aveva modo di darla.
 */
private val POSIZIONE = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

/** Una parola da dire al ritorno dalle impostazioni di Android, se e' ancora vera. */
private class RitornoDaImpostazioni(
    val id: String,
    val titolo: String,
    val messaggio: String,
    val ancoraVero: () -> Boolean,
)
