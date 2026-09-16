# Quello che resta aperto

La lista viva delle cose viste e non ancora fatte. Non e' un backlog di idee:
ogni riga e' qualcosa che è stato **osservato** — sul telefono, in un test, o
leggendo il codice con un motivo — e che non è stato chiuso.

Una riga esce da qui solo quando è risolta, o quando si è deciso e scritto
**perché** non va risolta.

---

## Deciso di lasciare com'e'

**In orizzontale la ricerca mostra una riga sola, ed e' aritmetica.** Misurato
il 16/09 con lo schermo girato: la finestra e' alta **411 dp** e la tastiera
ne prende **266**. Restano 145 dp per la barra (52) e l'elenco: una riga e
mezzo. Non e' un difetto di layout — un pannello laterale, l'elenco sopra la
barra, le righe compatte: qualunque cosa si faccia, quei 145 dp restano
quelli.

L'unico modo per vederne di piu' e' che la tastiera non ci sia, ed e'
esattamente quello che fa la modalita' estratta di Android: provata, toglie
l'app di mezzo e mostra un campo di testo suo con un tasto CERCA, quindi
**zero righe mentre scrivi** e tutte e sei dopo aver confermato. Cambia il
modo di cercare, non lo migliora.

Deciso con Alessio il 16/09: si lascia una riga che pero' si aggiorna a ogni
lettera. Questa riga resta qui coi numeri, perche' senza numeri sembra una
svista e qualcuno ci ritorna sopra. — misurato e deciso il 16/09/2026

## Si vede

**Il gate degli orari bloccava le pubblicazioni da giorni, e aveva ragione.**
La divergenza era sempre la stessa query: RISTORANTE LA BIANCA, alle 12:00,
con la stessa corsa contata due volte e la quinta partenza vera spinta fuori
dalla lista. Dopo che il gate ha imparato a stampare da quale corsa viene
ogni riga, si e' letta in un colpo: due righe identiche, stesso indice di
corsa, stessa posizione, stesso pattern. L'indice fermata -> pattern porta
una voce per PASSAGGIO, e una linea ad anello che tocca la stessa fermata
all'andata e al ritorno ce la scriveva due volte; chi legge scandisce da se'
tutte le posizioni, quindi contava tutto doppio. Corretto in lettura (vale
sui bundle gia' pubblicati) e in scrittura (vale per le versioni gia'
installate). Il job del 16/09 alle 12:55 e' passato con **0 divergenze** e ha
pubblicato buildId 79aadfc3c4bebf5b, il primo bundle nuovo dopo giorni.
— trovato e chiuso il 16/09/2026

**Un quarto dei bus in strada il feed non li nomina, e adesso si vede.** La
domanda veniva da un tabellone delle 09:30 con sette righe su otto che
dicevano "orario da tabella". Misurato con una sonda temporanea: quelle corse
avevano `covers=false`, cioe' non erano nel feed ne' come previsione ne' come
mezzo — non erano un aggancio fallito. Gli agganci, anzi, riescono quasi
sempre: su 1641 gruppi di previsioni, 2 erano di una linea sconosciuta al
bundle e 57 perdevano lo scarto delle sequenze perche' il feed di quella
corsa elenca piu' fermate del pattern (per esempio una corsa della 8 con
sequenze 1..23 su un pattern da 20 fermate: sono due generazioni di dati
diverse, e ripiegare sulla stima e' giusto).

Il numero che ne e' uscito sta adesso in Stato dei dati: **612 corse seguite
su 809 in strada, il 76%**. Il 24% che manca non dipende da noi — e' la
copertura AVL della fonte — ma finche' non si misurava sembrava un difetto
dell'app. Resta aperto se e quando dirlo anche fuori da quella schermata:
una riga "qui il tempo reale copre tre corse su quattro" sulla mappa
spiegherebbe l'unica cosa che l'app non spiega, ma e' rumore per chi non ha
il problema. — misurato il 16/09/2026


**La camera della mappa NON riparte da capo cambiando scheda.** Era scritto
qui come difetto; riprovato due volte, e' falso: si apre una fermata, si va su
Oggi, si torna, e la mappa e' dov'era, allo stesso zoom, col pannello ancora
aperto. Lo stato salvabile fa il suo lavoro. Tenuto qui per non riaprire
l'indagine. — verificato il 15 e il 16/09/2026

**Il tocco su "perche' questo numero" adesso si annuncia.** La provenienza e'
sottolineata dove si puo' aprire, che e' il modo in cui da sempre si dice
"questo si tocca", e in Oggi — dove la riga e' una riga di lista senza un
testo da sottolineare — la porta e' il menu della tenuta premuta. Tenuto qui
perche' la riga di prima diceva il contrario. — chiuso il 16/09/2026

**Il widget non configurato adesso apre la sua configurazione.** Diceva
"Tocca per configurare" e, toccato, apriva l'app: la configurazione non si
vedeva da nessuna parte, e l'unico modo per arrivarci era togliere il widget
e rimetterlo. Sistemato e provato sull'emulatore il 16/09: si tocca il
widget spento sulla home, si sceglie SODERINI TORRINO SANTA ROSA, si torna
alla home e il widget si e' gia' ridisegnato coi passaggi giusti. Il caso
che resta davvero aperto e' solo quello di un widget appena TRASCINATO dal
lanciatore, che non si e' potuto ricreare a comando.
— sistemato e riprovato il 16/09/2026

**Le parole del pannello corsa, viste su un bus vivo.** Aspettate le ore di
servizio e fatte alle 05:10 sulla linea 23 verso CROCE A VARLIANO: "Posizione
live - aggiornata 2 min fa", "+2 min di ritardo" col pallino verde, e tutte
le fermate della lista con "dal bus - da tabella alle 05:08". I nomi pero'
erano tagliati a meta' — "BESLAN T1 FORTE..." — perche' la provenienza stava
sotto il numero e allargava la colonna di destra: sistemato nello stesso
giro, com'era gia' stato fatto per il tabellone di una fermata.
— verificato il 16/09/2026

**Un bus seguito per sei minuti e mezzo non e' mai tornato indietro.** Il
sintomo da cui e' partito tutto, misurato su un mezzo vero: la linea 23
dalle 05:12 alle 05:18, un campione ogni quarantacinque secondi. La prima
fermata della lista e' avanzata sempre nello stesso verso — FRATELLI
ROSSELLI, BESLAN T1 FORTEZZA, INDIPENDENZA XXVII APRILE, XXVII APRILE SANTA
REPARATA, SAN MARCO PIAZZA, COLONNA LICEO MICHELANGIOLO — senza mai
riguadagnare una fermata. E' la progressione del DATO; quella del marker
sulla mappa e' tenuta dalle righe di BusPathTest.
— misurato il 16/09/2026

**Le posizioni finte dell'emulatore non arrivano all'app.** `adb emu geo fix`
risponde `OK` ma la posizione resta quella predefinita di Mountain View: per
provare qualcosa che dipende da dove sei, la strada che funziona e' togliere
il permesso di posizione, cosi' il riferimento diventa il centro della mappa,
e portare la mappa dove serve. — visto il 15/09/2026

## Non si vede, ma conta

**Il feed della Regione si ferma anche in piena mattina, e dopo dieci minuti
l'app resta senza ritardi.** Misurato il 16/09 alle 10:35: `/rt/v1/health`
dava `vehicles.feedAgeSeconds` 1108 e `updates.feedAgeSeconds` 1117 — cioe'
l'origine non pubblicava da diciotto minuti — mentre il nostro snapshot aveva
104 secondi. Non e' il proxy: e' la fonte. L'app se ne accorge e lo dice
("Il feed della Regione e' fermo" sulla mappa), e questo e' giusto.

Cosa fare dei ritardi gia' noti e' stato deciso: si tengono, dicendo quanto
sono vecchi ("dal bus - visto 15 min fa"), e si buttano solo oltre i tre
quarti d'ora. Prima si buttavano dopo dieci minuti, e in quella finestra
l'app sapeva meno delle ufficiali.
— misurato il 16/09/2026


**`/rt/v1/refresh` e' aperto.** Il codice che controlla il segreto c'è e si
accende da solo, ma `REFRESH_SECRET` non è configurato. Si chiude con
`wrangler secret put REFRESH_SECRET` e la corrispondente variabile nel
workflow `rt-keepalive.yml`: serve chi ha le chiavi di Cloudflare.

Nel frattempo il danno possibile e' stato ridotto a zero per aritmetica: il
limite e' passato da venti a cinquantacinque secondi per isolate, cioe' al
massimo poco piu' di tre fetch al minuto verso l'origine della Regione —
esattamente quello che ordina il nostro cron. Chi conoscesse l'URL non
ottiene una leva su qualcun altro, solo su di noi, e nemmeno tanta.
— aperto dal 15/09/2026, ridotto il 16/09/2026

**E nemmeno i cron di GitHub sono un metronomo, su questo repo.** Misurato
il 16/09 sui run veri: `rt-keepalive` chiede di girare ogni cinque minuti e
negli ultimi avvii e' partito alle 05:57, 01:09, 23:00 e 20:28 — uno ogni due
o cinque ore; il bundle notturno, che chiede le 03:40 UTC, e' partito alle
08:49 (e il giorno prima alle 08:57); il banco di fedelta', che chiede le
08:15 UTC, alle 09:07 UTC non era ancora partito. Non sono ritardi di minuti:
sono ore, e la maggior parte delle occorrenze viene saltata.

Tre sintomi diversi hanno quindi la stessa causa: il proxy che non viene
svegliato, gli orari nuovi che arrivano a meta' mattina invece che all'alba,
e il verdetto del banco che nell'app resta fermo a quello della notte. Niente
di tutto questo si aggiusta da qui — e' come GitHub pianifica i lavori
gratuiti — ma va saputo prima di cercare la causa altrove. L'app intanto fa
la cosa giusta: dice sempre di QUANDO e' il dato che mostra, invece di
promettere una frequenza. — misurato il 16/09/2026

**Il Cron Trigger di Cloudflare non e' un metronomo, e adesso si vede anche
nell'app.** `crons = ["* * * * *"]` e' configurato, ma `/rt/v1/health` ha
riportato battiti a 26 e a 102 minuti di distanza; stamattina alle 07:40, in
piena ora di punta, l'ultimo battito era di **un'ora prima** e lo snapshot
restava fresco solo perche' il refresh pigro lo riscriveva a ogni richiesta.

La conseguenza visibile e' arrivata oggi: l'app e' passata alla sorgente
diretta — "Ritardi non disponibili" sulla mappa, quindi nessun ritardo e
nessuna previsione — perche' ha letto tre volte di fila uno snapshot vecchio.
Si rimette da sola dopo cinque minuti, ed e' il comportamento voluto, ma il
motivo per cui succede non e' un guasto del proxy: e' che senza traffico
nessuno lo sveglia, e il cron che dovrebbe farlo non scatta.

Non si chiude da qui: serve chi ha le chiavi di Cloudflare per guardare
perche' il trigger non parte. — misurato di nuovo il 16/09/2026

**La chiave Google Maps e' dentro gli APK 1.0.0, 1.0.1 e 1.1.0 gia'
distribuiti.** È stata tolta da `local.properties`, ma quello che è stato
pubblicato resta pubblicato: l'unico rimedio è revocarla dalla console Google
Cloud, e può farlo solo il proprietario del progetto. — aperto

**Due app Pampa sullo stesso telefono litigano.** `dev.pampa.pampai.debug` e
Fluid Transit dichiarano entrambe il permesso
`dev.antigravity.fluidengine.permission.AI_TOOLS`, e l'installazione della
seconda fallisce con `INSTALL_FAILED_DUPLICATE_PERMISSION`. Sull'emulatore si
risolve disinstallando; in distribuzione no.

Il difetto sta nell'engine, non qui: `engine-ai-bridge` dichiara il
`<permission>` in ogni app che include il modulo, e il suo commento dice
"la prima installata lo definisce per le altre: stesso nome, stesso livello,
nessun conflitto". **Quell'assunzione non vale su Android moderno**: un
secondo pacchetto che dichiara un permesso gia' definito viene rifiutato se
non e' firmato con lo stesso certificato del primo — ed e' esattamente quello
che succede fra una build di debug e una firmata con la chiave di release, o
fra due macchine diverse.

Le strade sono due, e sono tutt'e due dell'engine: dichiarare il permesso in
UNA sola app della famiglia e lasciare alle altre il solo `<uses-permission>`,
oppure dare a ogni app un nome di permesso suo. Va deciso li' e committato
nel repo dell'engine: il codice dentro `engine/` non e' codice di quest'app.
— diagnosticato il 16/09/2026

**Il verde di un ritardo grosso: deciso.** Si leggeva "+33 min di ritardo"
in verde, perche' il verde diceva la provenienza. Deciso con Alessio il
16/09: il colore passa a dire la puntualita' — verde entro cinque minuti,
ambra fino a un quarto d'ora, rosso oltre — e la provenienza resta al pallino
che pulsa e alle parole. Fatto, e verificato su tre bus veri.
— deciso e fatto il 16/09/2026


**L'ANR sull'emulatore non e' dell'app.** Provando i widget e' comparso
"Fluid Transit isn't responding", motivo `No response to onStartJob`. La
traccia dice dove: il thread principale era fermo dentro
`HardwareRenderer.setStopped`, in attesa del RenderThread, che a sua volta
stava compilando programmi GL (`GrGLProgramBuilder::CreateProgram`) attraverso
la pipe dell'emulatore. E' la lentezza della GL emulata al primo disegno, non
un blocco nostro: sul telefono vero le stesse schermate si disegnano senza
questa attesa. Scritto qui perche' la prossima volta che compare non venga
inseguito nel codice dell'app. — diagnosticato il 16/09/2026

## Deciso di non fare

**ktlint o detekt.** Il piano li chiedeva, con "un commit di riformattazione
separato". Contato: su un codice scritto senza, quel commit tocca quasi ogni
file e seppellisce la storia di tutto il resto — e lo stile qui e' gia'
coerente, perche' e' stato tenuto tale a mano. Al loro posto e' stato acceso
**Android Lint come cancello** in CI, che al primo giro ha trovato un difetto
vero (una chiamata alla posizione senza controllo del permesso) invece di
centinaia di differenze di spaziatura. — 15/09/2026

**Spezzare MapScreen fino in fondo.** Ne sono usciti due blocchi con
un'interfaccia piccola davvero — "qui intorno" e i risultati della ricerca — e
il file e' passato da 2.149 a 2.012 righe. Il resto (i pannelli, il
pianificatore, le azioni dell'assistente) condivide troppo stato: estrarlo
adesso vorrebbe dire funzioni con venti parametri, che e' peggio di dove si
parte. Va fatto quando lo stato sara' raccolto in oggetti, non prima.
— 15/09/2026


**Deduplicare la risoluzione dei ritardi.** `resolveRt` e il collettore
dell'Application risolvono gli stessi hash. Contati: novecento veicoli, 97%
risolto al primo colpo, quindi ~30 scansioni da 946 letture su un buffer
mappato in memoria, ogni trenta secondi. Sono microsecondi. Riscrivere quella
catena aggiungeva rischio a un pezzo delicato per un guadagno non misurabile.
— misurato il 15/09/2026

**Dare continuita' ai mezzi fra corse consecutive.** Il timore era che al
cambio corsa un mezzo cambiasse identità e la sua traccia morisse, che
all'occhio è un teletrasporto. Misurato: il `vehicle.id` c'è sul 100% dei
veicoli, quindi la chiave è già stabile fra le corse, e `attachMotion`
riaggancia la geometria nuova ripartendo dalla posizione disegnata. Il difetto
non esiste su questo feed. — misurato il 15/09/2026

**Una tolleranza nel matcher secondario.** Il piano la chiedeva: il matcher
per `(linea, direzione, ora di partenza)` pretende che l'orario del feed
combaci **al secondo** con quello del bundle, e bastava un minuto di scarto
fra le due generazioni di dati perche' la corsa restasse orfana. Misurato
stamattina con centosessantasette mezzi vivi: **corse riconosciute 100%**. Il
matcher primario, quello sul `trip_id`, aggancia tutto, e il secondario non
entra quasi mai in gioco. Una tolleranza aggiungerebbe il rischio di
agganciare la corsa sbagliata su una linea ad alta frequenza, per risolvere
un problema che su questo feed non si misura. C'e' una riga di test che
fissa il comportamento attuale e che diventera' rossa il giorno in cui si
decidesse di cambiarlo. — misurato il 16/09/2026

**L'indirizzo `trip/<hash>`.** Era nel piano dei deep link. Nessuno lo emette,
e senza un emettitore non si può provare davvero. Sono quattro righe il giorno
che servirà. — 15/09/2026
