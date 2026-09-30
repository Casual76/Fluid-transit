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

**La navigazione a bordo: provata sull'emulatore, non ancora su un bus.**
Il 30/09/2026 un viaggio Piazza Dalmazia -> Fiesole con un cambio (20, poi
7) e' partito, ha mostrato la camminata, poi l'attesa e il viaggio a bordo
con la scia tagliata sul bus, e ha retto il cambio di tema a meta' strada; a
viaggio chiuso la camera si e' liberata. Restano da vedere dal vero tre
cose che l'emulatore non puo' dare: la posizione (`geo fix` non arriva
all'app, quindi la camminata e la soglia dei 300 m per "stai per arrivare"
non sono state provate con un GPS vero), il cambio di tappa in strada, e lo
scambio degli orari a viaggio in corso, provato solo nei test su due bundle
di prova. — 30/09/2026

**Posizione spenta, microfono senza riconoscimento e notifiche negate: scritti e provati nei test, non su un telefono.**
Il 30/09/2026 sono stati corretti tre tasti che non facevano niente: il
mirino con la Posizione di Android spenta (adesso lo dice e porta
all'interruttore), il microfono della barra dove non c'e' un servizio di
riconoscimento vocale (adesso apre la barra con la tastiera) e il permesso
delle notifiche negato dopo aver creato una routine (adesso lo dice e, se
Android non chiede piu', porta alle impostazioni). Le decisioni a tre rami
hanno il loro test sulla JVM, ma l'emulatore non ha dato modo di vedere i
tre casi veri: la Posizione spenta dal pannello rapido col permesso gia'
concesso, un telefono senza Google (l'eccezione arriva dal lancio
dell'intent, e senza `<queries>` non si puo' chiedere prima), e la seconda
negazione delle notifiche su Android 13. Da vedere su un telefono vero. —
30/09/2026

**Le pastiglie delle linee: il bianco regge il contrasto su 3 tinte su 12.**
Misurato il 30/09/2026 sulle dodici tinte di `RouteColoring.PALETTE`, col
testo delle pastiglie (14 sp in grassetto, che per le linee guida WCAG e'
testo normale: serve 4,5:1). Col bianco arrivano a 4,5 solo viola (5,2), blu
(5,3) e magenta (4,6); arancio e ciano stanno a 3,0, verde acqua a 3,1.
Scegliendo per ogni tinta il testo migliore fra bianco e quasi nero, otto
pastiglie su dodici passerebbero al testo scuro, e rosso (3,9 / 4,4), rosa
(4,1 / 4,2) e indaco (4,4 / 3,9) non arrivano a 4,5 in nessuno dei due modi.
Non e' una correzione ma una scelta d'aspetto — cambia la faccia di ogni
linea in tutta l'app, widget compresi — quindi si decide, non si fa di
passaggio. Le strade possibili: testo scuro dove rende di piu', oppure tinte
un poco piu' scure (che pero' sono anche il colore delle tratte sulla mappa,
e sulla basemap scura si leggerebbero peggio). — misurato il 30/09/2026

**Il vetro dei pannelli lascia leggere le etichette della mappa sotto.**
Visto su un Galaxy S25 il 30/09/2026, pannello "Qui intorno" sopra Sesto
Fiorentino: la destinazione "CALENZANO UNIVERSITÀ" finiva a ridosso
dell'etichetta "Cimitero Maggiore" della basemap, e si leggeva
"CALENZANO UNIVERSITÀggiore". Il vetro e' dell'engine e la trasparenza e'
voluta; ma sopra una mappa piena di scritte il testo del pannello e quello
della mappa sono dello stesso colore e della stessa famiglia. Le strade: una
velatura un po' piu' coprente per i pannelli con righe di testo, o le
etichette della basemap attenuate quando un pannello e' aperto. Scelta
d'aspetto, quindi da decidere. — 30/09/2026

**"Perche' questo numero" da' la colpa al feed anche quando la rete e' nostra.**
Col telefono offline una riga "orario da tabella" apre una spiegazione che
dice che la Regione non pubblica quella corsa: e' falso, non sappiamo niente
perche' non arriviamo al proxy. Serve che la spiegazione sappia com'e' il
collegamento (una piccola sorgente comune in core-routing, passata dalla
mappa e da Oggi) — trovato dal giro di scoperta, rimandato perche' tocca tre
schermate. — 30/09/2026

**L'offerta di aggiornare gli orari sui dati mobili si vede solo in Stato dei
dati.** Con orari in scadenza e rete a consumo l'app non scarica da sola e
chiede, come deciso; ma la domanda sta in Impostazioni > Stato dei dati, e il
tabellone intanto dice "orari scaduti... non ne arrivano di nuovi", che con
un'offerta aperta non e' vero. Da fare: la frase di `DepartureText` che
conosce l'offerta, e un invito sulla mappa. — 30/09/2026

**Negli itinerari "dal vivo" e il ritardo non si leggono a voce per intero.**
La capsula "qui intorno" ha la sua descrizione per TalkBack; le righe delle
soluzioni di viaggio no, e un pallino che pulsa e un colore restano muti.
— 30/09/2026

**I tasti dei posti nominati dall'assistente vanno a capo senza limite.** La
fila adesso va a capo invece di schiacciarli, ma un nome di fermata molto
lungo occupa piu' righe: serve un `maxLines` sul tasto di vetro. — 30/09/2026

**Mentre si cammina verso la fermata il bus puo' essere gia' passato, e la
card non lo dice.** Visto sull'emulatore il 30/09: camminando verso Piazza
Dalmazia per la 20 delle 14:00, alle 13:58 il mezzo assegnato a quella corsa
era gia' un chilometro oltre la fermata (la scia grigia lo mostrava), e la
card diceva ancora "Cammina verso PIAZZA DALMAZIA". Nella fase di attesa
`NavApproach` se ne accorge; in quella a piedi nessuno guarda. Non e' chiaro
se fosse un anticipo vero o un'assegnazione sbagliata del feed. — visto il
30/09/2026

**I colori delle linee cambiavano a ogni notte, e adesso no.** Il 16/09, con
il primo bundle nuovo dopo giorni, alla stessa fermata quattro pastiglie su
sette avevano cambiato tinta. Misurato fra due build a novanta minuti di
distanza, con conteggi identici di fermate, linee, corse e pattern: **98 linee
su 946** di un altro colore. Ora il costruttore parte dai colori dell'ultimo
bundle pubblicato, che il job scarica dalla release. Rimisurato allo stesso
modo dopo: **0 su 946**. — misurato e chiuso il 16/09/2026

**Il velo sulla basemap, guardato.** Sotto la nostra rete c'e' un velo
(`MapCatalog.VELO_OPACITA`, 0,22) che abbassa la mappa stradale e lascia
intatte le tratte e le etichette. Visto su Firenze a schermo pulito: le
strade restano gialle chiare e leggibili, il fiume e i verdi si riconoscono,
le etichette sono nitide, e le tratte colorate sono senza dubbio la cosa
principale. Prima erano una fra tante. — verificato il 16/09/2026

**L'emulatore `codex_api35_pixel` ha 2 GB di RAM, e va in swap.** Il
30/09/2026 l'app andava in ANR a ogni avvio col bundle presente, con le
tracce dentro `HardwareRenderer.setStopped`: `top` dava 1,87 GB occupati su
2 e quasi 800 MB di swap, e il tempo di sistema dell'app erano page fault.
Con `-memory 4096 -cores 4 -gpu host -feature -Vulkan` parte. Ma quello che
l'emulatore non da' — la rete che si apre in ritardo all'avvio, i dati di
lingua del produttore, i font veri — l'ha dato solo il telefono: la build di
debug sul telefono si installa accanto a PampAI rifirmandola con la chiave
di release (`apksigner sign --ks ...`), perche' il permesso AI_TOOLS
dichiarato da tutte e due vuole la stessa firma (vedi "Due app Pampa").
— 30/09/2026

**Per guardare l'app su un emulatore che va in ANR di continuo**, i dialoghi
di sistema si tolgono con `adb shell settings put global hide_error_dialogs
1` (e si rimettono con `0`). Senza, ogni schermata si guarda attraverso il
grigio del dialogo, e i tocchi finiscono su "Wait". — 16/09/2026

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

**Il proxy a volte vede l'origine una generazione indietro rispetto agli
altri.** Il 30/09/2026 il banco di fedelta' su GitHub ha trovato il proxy
indietro di 116 secondi per quattro giri di fila, circa tre minuti, e dieci
minuti dopo `/rt/v1/health` dava ai feed 155-166 secondi mentre la stessa
origine, letta da qui, ne aveva 36-48. Letta dieci volte di fila da qui,
l'origine rispondeva sempre con la stessa generazione, quindi non e' provato
che serva copie diverse a client diversi: e' solo quello che si osserva da
Cloudflare. Il banco lo assorbe riprovando; se diventasse frequente, l'app
passerebbe piu' spesso l'avviso "il feed della Regione e' fermo". —
osservato il 30/09/2026

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
quanto ne ordina un'app accesa. Chi conoscesse l'URL non
ottiene una leva su qualcun altro, solo su di noi, e nemmeno tanta.
— aperto dal 15/09/2026, ridotto il 16/09/2026

**E nemmeno i cron di GitHub sono un metronomo, su questo repo.** Misurato
il 16/09 sui run veri: `rt-keepalive` chiede di girare ogni cinque minuti e
negli ultimi avvii e' partito alle 05:57, 01:09, 23:00 e 20:28 — uno ogni due
o cinque ore; il bundle notturno, che chiede le 03:40 UTC, e' partito alle
08:49 (e il giorno prima alle 08:57); il banco di fedelta', che chiede le
08:15 UTC, alle 09:07 UTC non era ancora partito. Non sono ritardi di minuti:
sono ore, e la maggior parte delle occorrenze viene saltata.

Due sintomi diversi hanno quindi la stessa causa: gli orari nuovi che
arrivano a meta' mattina invece che all'alba (a fine settembre verso le
12:00 italiane), e il verdetto del banco che nell'app resta fermo a quello
della notte. Niente di tutto questo si aggiusta da qui — e' come GitHub
pianifica i lavori gratuiti — ma va saputo prima di cercare la causa altrove.
Il terzo sintomo, il proxy che nessuno sveglia, non dipende piu' da qui: dal
30/09 lo sveglia la lettura stessa. L'app intanto fa
la cosa giusta: dice sempre di QUANDO e' il dato che mostra, invece di
promettere una frequenza. — misurato il 16/09/2026

**Il giro del proxy sta nei 10 ms del piano gratuito solo per tolleranza.**
Il Cron Trigger "che non scattava" in realta' partiva e moriva: un giro
costa 70-90 ms di CPU e il Free ne concede 10 per invocazione (misurato il
30/09/2026). Il cron e' stato tolto e il giro lo fa la richiesta che trova lo
snapshot vecchio, aspettandolo prima di rispondere. Funziona perche' sulle
richieste HTTP Cloudflare oggi non applica il limite alla lettera; lo stesso
limite, sulla carta, vale anche li'. Se un giorno le richieste cominciassero
a fallire con l'errore 1102 (CPU superata), la risposta e' Workers Paid
(5 $/mese, 30 s di CPU): ridurre un parse da 70 ms a meno di 10 in
JavaScript non e' realistico. Il segnale da guardare e' il banco di
fedelta', che adesso esce con errore quando il proxy resta indietro.
— aperto il 30/09/2026

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


**L'ANR all'avvio: era il widget, e adesso non c'e' piu'.** Sull'emulatore
comparivano "Fluid Transit isn't responding" a ogni avvio a freddo, motivo
`No response to onStartJob`. Le tracce dicono che il thread principale non
era bloccato da noi: una volta era dentro `HardwareRenderer.setStopped` in
attesa del RenderThread che compilava shader GL attraverso la pipe
dell'emulatore, un'altra era dentro la prima composizione. Ma il lavoro di
sistema che non riusciva a partire era nostro: all'avvio l'app ridisegnava
subito i due widget e programmava il loro rinfresco, cioe' ricostruiva due
tabelloni e consegnava dei RemoteViews proprio mentre quel thread stava
disegnando la prima schermata. Ora quel blocco aspetta quattro secondi.
Misurato dopo: due avvii a freddo di fila senza ANR, dove prima capitava
quasi sempre. Non e' sparito del tutto, ma la colpa non e' piu' nostra: con
la macchina ospite occupata da un build Gradle, lo stesso dialogo e'
comparso per **System UI**, che non e' codice di quest'app. E' lo stato
dell'emulatore, non dell'app. L'avvio resta lento sull'emulatore — 9,5 e 12,2 secondi al primo
fotogramma, con GL software e build di debug non ottimizzata — e quel
numero non dice niente su un telefono vero.
— diagnosticato e corretto il 16/09/2026

**E la causa vera dell'ANR che restava: la build di debug non e'
compilata.** La traccia dell'ennesimo blocco lo dice senza ambiguita': il
thread principale sta dentro `ClassVerifier::VerifyClass`, cioe' ART sta
verificando le classi una per una mentre l'app parte, perche' il dex non e'
mai stato compilato in anticipo ("Failed to determine oat file name ...
Dalvik cache directory does not exist" nei log). Forzandola a mano
(`adb shell pm compile -m speed -f <pkg>`) l'avvio a freddo e' passato da
10,5 a **6,5 secondi** e il dialogo e' sparito. Su un telefono vero, con una
build di release e il suo baseline profile, questo passaggio non esiste.
— misurato il 16/09/2026

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
