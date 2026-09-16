# Fluid Transit

L'app per muoversi coi mezzi in Toscana. Dati di Regione Toscana e Autolinee
Toscane (CC-BY), rielaborati.

Questo file e' il contesto che non si ricava dal codice: i vincoli che sembrano
arbitrari e non lo sono, le decisioni gia' prese, e le trappole che hanno gia'
fatto perdere tempo una volta. Il *cosa* sta nel codice; i commenti dicono il
*perche'*; qui c'e' quello che non ha una casa.

## Come e' fatto

| modulo | cos'e' | vincolo |
|---|---|---|
| `:app` | l'app Android: Compose, MapLibre, widget Glance | |
| `:core-routing` | formato `.ftb`, lettore mmap, RAPTOR, orari | **Kotlin/JVM puro, niente Android**: i golden test girano in CI sulla JVM |
| `:core-ai` | l'assistente (provider, strumenti, voce) | niente Compose |
| `:bundler` (`tools/bundler`) | GTFS CSV -> `.ftb`, overlay PMTiles, luoghi | gira in CI di notte |
| `engine/` | Fluid Engine, submodule | nove moduli, versione in `engine.properties` |
| `worker/` | proxy realtime su Cloudflare, JavaScript | nessuna dipendenza a runtime |

Due backend, non uno:

- **il bundle degli orari** lo costruisce GitHub Actions ogni notte
  (`build-bundle.yml`) e lo pubblica sulla release a tag fisso `dati`;
  `index.json`, scritto per ultimo, e' lo switch atomico;
- **il realtime** passa dal Worker `fluid-transit-rt`, che ogni minuto scarica
  i tre feed GTFS-RT, li riduce a record binari e li mette su R2.

L'app non parla mai GTFS ne' GTFS-RT: riceve byte gia' pronti da leggere con un
`ByteBuffer`. Gli identificatori viaggiano come **hash FNV-1a a 64 bit**, la
stessa funzione da tutte e tre le parti (`Ftb.hash64` in Kotlin, `fnv64` in
JavaScript). E' il contratto centrale del sistema: se le due implementazioni
divergessero, il sintomo sarebbe "nessun bus ha una linea" e si cercherebbe la
causa dappertutto tranne che li'. Per questo gli stessi valori sono inchiodati
in `HashCompatTest.kt` e in `worker/test/snapshot.test.js`.

## Vincoli che sembrano arbitrari e non lo sono

**Niente version catalog.** Due `libs.versions.toml` nello stesso build
litigano sull'accessor `libs`, quindi l'engine usa `engine/versions.gradle` e
l'app dichiara le stesse versioni a mano. Un ospite con plugin diversi dai
moduli che include non configura nemmeno.

**`:app` e `:core-ai` esistono solo se c'e' `engine/engine-ui`.** La guardia in
`settings.gradle.kts` serve al job notturno, che fa checkout senza submodule
perche' gli servono solo `:bundler` e `:core-routing`.

**Niente Play Store.** `engine-update` dichiara `REQUEST_INSTALL_PACKAGES`, che
rende l'app non pubblicabile senza una motivazione: la distribuzione passa dal
Pampa Store, via `manifest.json` in questo repo.

**`manifest.json` e' il piano di controllo.** Letto da `EngineRemoteConfig` via
raw.githubusercontent: da li' si cambiano i feature flag, la versione minima,
gli avvisi e il kill switch **senza una release**. Il codice no: quello non si
aggiorna da remoto.

**I colori delle linee vengono da ieri.** Il job notturno scarica il bundle
gia' pubblicato e lo passa ai DUE comandi che colorano — `:bundler:run` e
`:bundler:overlay` — con la variabile `FT_BUNDLE_PRECEDENTE`. Senza, ognuno
ricolora da zero e le stesse linee cambiano tinta da una notte all'altra (98
su 946, misurate); e se la variabile arrivasse a uno solo dei due, le
pastiglie nell'app e le tratte sulla mappa direbbero due colori diversi per
la stessa linea.

**Firma e Crashlytics sono condizionali.** `keystore.properties` e
`app/google-services.json` non sono versionati; senza, il build riesce
identico, solo non firmato e senza Crashlytics. Serve a far compilare un clone
fresco e il job notturno senza trattamenti speciali.

**Solo `arm64-v8a` e `armeabi-v7a`.** Le due ABI x86 di MapLibre pesavano 22,5
dei 50 MB dell'APK e servono solo agli emulatori, che usano la build di debug.

## Trappole gia' pagate

**Il giorno di servizio non e' il giorno.** `Ftb.serviceDayStart` fa
`atTime(NOON).atZone(zone).minusHours(12)`, non la mezzanotte locale: GTFS
definisce i tempi come scostamenti da "mezzogiorno meno dodici ore". E il
giorno da 25 ore e' quello **precedente** al ritorno all'ora solare, perche'
l'ora ripetuta cade nella sua estensione 24:00-27:00.

**Le corse oltre le 24:00 esistono.** 2.709 nel feed, e l'ultima finisce alle
30:10. Il limite di quanto guardare indietro sta nell'header del bundle, non in
una costante. Stampare modulo 24 senza dirlo nasconde all'utente che quel bus
passa domani mattina: `Times.serviceTime` scrive "01:30 (domani)".

**Lo `stop_sequence` del feed non e' la posizione nel pattern.** E' 1-based
(misurato: valori 1..76, lo zero non compare mai), il bundle indicizza da 0, e
i numeri originali il builder li butta dopo aver ordinato. Confrontarli come se
fossero la stessa cosa sposta tutto di una fermata, e a sparire e' proprio
quella verso cui il bus sta andando.

**L'indice fermata -> pattern ha una voce per PASSAGGIO.** Una linea ad
anello tocca la stessa fermata due volte, e il builder scriveva il suo pattern
due volte sotto quella fermata. Chi legge le partenze scandisce da se' tutte le
posizioni in cui la fermata compare nel pattern, quindi ogni voce in piu'
raddoppia ogni partenza: al RISTORANTE LA BIANCA il tabellone dava la stessa
corsa due volte di fila allo stesso minuto, e la quinta partenza vera finiva
fuori dalla lista. L'ha trovato il golden gate, non una persona. Ora
`patternsAtStop` deduplica in lettura — cosi' valgono anche i bundle gia'
pubblicati — e il builder non scrive piu' il doppione.

**Meta' degli snapshot realtime sono fotocopie.** L'origine si rigenera ogni
~2 minuti, l'app polla ogni 30 s. Riapplicare lo stesso rilevamento non e'
innocuo: l'eta' del fix e' congelata, quindi il bersaglio resta fermo mentre il
mezzo simulato avanza, e la "correzione" lo tira indietro.

**Gli avvisi non nominano mai le fermate.** GTFS-RT prevede che un avviso
indichi le entita' toccate, e fra queste c'e' `stop_id`. Contati sul feed vero
del 16/09/2026: **746 riferimenti, tutti a linee, zero a fermate**. Quindi
"questa fermata oggi e' spostata" non si puo' sapere, anche quando il testo
dell'avviso lo dice a parole. Cio' che si puo' fare — ed e' fatto — e' mostrare
alla fermata gli avvisi delle LINEE che ci passano.

**Gli indici del bundle non sopravvivono alla notte.** Sono assegnati
nell'ordine in cui il builder scandisce gli orari: dopo lo scambio, un ritardo
di ieri appiccicato allo stesso indice finisce su un'altra corsa. Per questo
`DelayModel.clear()` a ogni cambio di `buildId`, e per questo i preferiti
salvano hash e non indici.

**La Cache API toglie `content-encoding` a una risposta `encodeBody:
'manual'`.** Il Worker comprime da se' e dichiara `content-encoding: gzip`;
rileggendo la voce dalla cache, il runtime considera il corpo gia' decodificato
e l'intestazione sparisce — ma il corpo e' ancora compresso. Misurato il
15/09: prima richiesta (MISS) con intestazione, tutte le successive (HIT)
senza, corpo identico. Il telefono trovava "magic sbagliato", lo contava come
errore del proxy, e dopo tre giri scendeva sulla strada diretta perdendo tutti
i ritardi — a intermittenza, perche' dipendeva dal cache HIT. Per questo
`serveSection` adesso **riscrive sempre** l'intestazione, e per questo l'app
scompatta comunque quando vede il magic di gzip.

**MapLibre va inizializzato in un ordine solo.** `MapLibre.getInstance(context,
null, WellKnownTileServer.MapLibre)` a tre argomenti, e
`HttpRequestUtil.setOkHttpClient` **dopo**. Invertendoli si ottiene la stessa
eccezione dell'altro errore, che indica il posto sbagliato.

**MapLibre rilegge la testa dei PMTiles a ogni tile**, da piu' thread, senza
memoria: 40 richieste su 64 erano riletture degli stessi 525 byte. Il rimedio
sta in `MapHttp.kt`, che serve i primi 16 kB dalla memoria.

## Numeri misurati, non stimati

Servono prima di progettare, e sono costati tempo: qui per non rimisurarli.
La data conta, perche' il feed cambia.

| cosa | quanto | quando |
|---|---|---|
| `parent_station` nelle fermate | **0%** | 15/09/2026 |
| fermate che condividono il nome con un'altra | 53% del totale | 15/09/2026 |
| di quelle, entro 100 m fra loro | 87% | 15/09/2026 |
| veicoli vivi col `vehicle.id` | **100%** (556 su 556) | 15/09/2026 |
| corse col ritardo dichiarato, la sera | 262 | 15/09/2026 |
| punti di previsione per fermata | ~3.300, 30 kB (15 kB gz) | 15/09/2026 |
| ogni quanto si rigenera l'origine | ~120 s | 03/09/2026 |
| tratte agganciate alla strada (Valhalla) | 76% (era 56%) | 12/09/2026 |
| avvio a freddo fino alla mappa (debug, emulatore) | ~3,6 s | 16/09/2026 |
| avvio a freddo fino a Oggi (stessa build) | ~1,85 s | 16/09/2026 |
| pattern che toccano due volte la stessa fermata | 215 su 8.331 | 16/09/2026 |
| fermate entro 700 m dal Duomo di Firenze | 44 | 16/09/2026 |

Due conseguenze che si dimenticano:

**Zero `parent_station`** e' il motivo per cui `StopGroups` deduce le banchine
da nome + distanza invece di leggere un campo. Non e' una scelta estetica: il
campo non c'e'.

**La mappa costa un secondo e tre quarti dell'avvio.** La differenza fra i due
avvii qui sopra e' tutta MapLibre: creare la superficie in `textureMode` —
serve al vetro — e caricare lo stile. Misurato su una build di debug e su un
emulatore, quindi il numero vero su un telefono e' piu' basso; quello che
conta e' la proporzione. Lo stile arriva da `tiles.openfreemap.org` e vive
nella cache HTTP: a freddo con la cache vuota, quel pezzo dipende dalla rete.

**Il `vehicle.id` c'e' sempre**, quindi il ripiego di `vehicleKey` sul
`tripHash` non scatta mai su questo feed, e il timore che un mezzo cambi
identita' al cambio corsa — che avrebbe l'aspetto di un teletrasporto — non si
verifica. Se un giorno il feed cambiasse, si vedrebbe da qui.

## Compilare e verificare

```bash
./gradlew :core-routing:test :app:testDebugUnitTest :app:assembleDebug
cd worker && npm test
```

La CI fa lo stesso a ogni push, piu' `:app:assembleRelease` per far girare R8.

**Su Windows Gradle fallisce a caso con `Unable to delete directory`**: e' il
limite di 260 caratteri sui percorsi, non un errore di compilazione. Si
riconosce dall'assenza di righe `e:` nel log. Si sblocca cancellando le
`build/` **da Git Bash** (`rm -rf`, che gestisce i percorsi lunghi) e
rilanciando con `--no-build-cache --no-daemon`.

Il punto in cui capita quasi sempre sono i risultati dei test, da soli o
attraverso la cache del build (`Failed to load cache entry ... Could not load
from local cache`). Prima di far girare i test conviene togliere di mezzo
quelle due cartelle e basta, invece dell'intera `build/`:

```bash
rm -rf app/build/test-results app/build/reports core-routing/build/test-results core-routing/build/reports
```

Capita anche che il demone Kotlin muoia da solo
(`Connection to the Kotlin daemon has been unexpectedly lost`): non e' un
errore di codice, si rilancia e basta.

## Pubblicare

**L'app**: build firmata in locale, poi la skill `pampa-store-publish-direct`,
che crea il tag e aggiorna `manifest.json`. Le release le decide una persona.

**Il Worker**: automatico al push su `worker/**`, ma solo se i test passano.
Non si deploya da locale.

**Gli orari**: automatici ogni notte. Se un gate fallisce non si pubblica e
l'app tiene il bundle di ieri — che e' il comportamento voluto, ma va
guardato: a settembre 2026 il gate ha bloccato sette notti di fila un feed
sano, perche' l'inizio dell'anno scolastico aveva aggiunto il 53% di corse.
I gate sono ora asimmetrici (stretti in giu', larghi in su) e c'e' un
interruttore manuale `ignora_gate`.

## Il vocabolario sta in un posto solo

Le parole con cui l'app dice le cose vivono in `:core-routing`, che e' l'unico
modulo che vedono tutti: le schermate, i widget Glance e gli strumenti
dell'assistente. Non e' pedanteria — ogni volta che una di queste e' stata
riscritta in casa da qualcuno, la copia e' divergita:

| dove | cosa dice |
|---|---|
| `DepartureText` | una partenza: "dal bus", "stimato", "orario da tabella", il tono, il pallino |
| `Times.delayLabel` | il ritardo: "+3 min di ritardo", "in orario" |
| `Times.durationLabel` | una durata: "43 min", "4 h 10 min" — mai "250 min" |
| `Times.serviceTime` | un orario oltre le 24: "01:13 di notte" — mai un modulo 24 secco |
| `Times.hhmm` | l'orologio, sempre a due cifre |
| `AlertText.period` / `.moment` | un periodo e un istante: "oggi alle 10:15", "28 febbraio 2027" |
| `Words.count` | il singolare: "1 fermata" e non "1 fermate" |
| `Words.distance` | "350 m", "2,4 km" |
| `Words.age` | l'eta' di un dato: "18s", "6 min" |
| `Times.dateLabel` | un giorno: "oggi", "domani", "5 ottobre" — mai `2026-10-05` |
| `DepartureText.empty` | un tabellone senza righe: le cinque ragioni, distinte |
| `FidelityText` | il confronto con la fonte |
| `BundleFailure` (in `:app`) | perche' gli orari non sono arrivati, senza inglese |
| `TripProgress` | dov'e' arrivato un mezzo: qual e' la sua prossima fermata |
| `DepartureText.Tone` | il colore di un orario: verde, ambra, rosso — la PUNTUALITA' |
| `Times.durationBetween` | la durata fra due orari scritti: torna con la sottrazione |
| `Reference` | da dove si misura "vicino": dove sei, o la mappa se l'hai portata lontano |

Se serve una frase che una di queste quasi dice, si cambia quella: una seconda
copia scritta in casa e' come nascono i difetti che si vedono solo in una
schermata su quattro.

**Una lista vuota non e' mai una sola cosa.** E' la trappola che si e' ripetuta
in ogni superficie: il tabellone senza righe, gli avvisi che non si scaricano,
il confronto con la fonte che non risponde, la fermata che negli orari di oggi
non esiste piu'. Ogni volta il fallimento era diventato una lista vuota, e ogni
volta la lista vuota si leggeva come una buona notizia — "Nessun passaggio a
breve", "Nessun avviso in corso" — cioe' un'affermazione sul mondo mentre il
guasto era nostro. Quando una funzione puo' non riuscire, il suo tipo di
ritorno deve poterlo dire: `null` o un `Result`, non una lista vuota.

**E la sorgente dei minuti e' una sola.** Il tabellone di una fermata, la
scheda di una corsa e il motore degli itinerari chiedono tutti a `LiveTimes`,
che risponde "il ritardo di QUESTA corsa a QUESTA fermata" con la provenienza
accanto. Fino al 16/09 il motore faceva eccezione: prendeva un numero per
corsa e lo applicava a tutto il percorso. Misurato sul feed delle 07:30, su
2.346 corse con piu' di una previsione, lo scarto fra la prima e la piu'
lontana e' in media 78 s e supera il minuto su un terzo delle corse — cioe'
la scheda e l'itinerario dicevano due orari diversi per lo stesso bus.
Chiunque aggiunga una superficie che mostra orari chiede a `LiveTimes`: e' la
stessa regola del vocabolario, applicata ai numeri invece che alle parole.

**Il colore dice la puntualita', il pallino dice la provenienza.** Verde
entro cinque minuti di ritardo, ambra fino a un quarto d'ora, rosso oltre; il
colore del testo tace quando un ritardo non lo sappiamo. Fino al 16/09 il
colore diceva la PROVENIENZA — verde uguale "lo dice il mezzo" — ed era
coerente col resto dell'app e sbagliato per chi guarda: su un bus con mezz'ora
di ritardo usciva "+33 min di ritardo" scritto in verde. La provenienza ha
gia' due modi di dirsi che non si fraintendono, il pallino che pulsa e le
parole sotto. Verde, ambra e rosso stanno accanto a `liveGreen` e non nella
palette dell'engine perche' sono semafori: devono restare quei tre colori
anche se l'accento dell'app diventa arancione.

**Un numero vecchio si mostra, ma dice di quando e'.** L'origine della Regione
si ferma per quarti d'ora anche di mattina (misurato). Fino al 16/09 dopo
dieci minuti il ritardo si buttava e tutte le righe tornavano all'orario di
tabella: in quella finestra l'app sapeva meno delle ufficiali. Adesso il
numero resta e la riga della provenienza dice "dal bus - visto 15 min fa";
oltre i tre quarti d'ora si butta davvero. L'eta' viaggia dentro
`LiveTimes.At`, prodotta da chi sa quando e' stata fatta l'osservazione.

**E anche "dov'e' arrivato" si chiede a uno solo.** `TripProgress` risponde a
"qual e' la prossima fermata di questa corsa" per la scheda della corsa, per
"sono su questo bus", per la navigazione a bordo e per l'assistente. La regola
e' che il feed batte l'orologio quando parla — una corsa in anticipo ha le
fermate servite mentre i loro orari di tabella sono ancora nel futuro — e che
l'orologio decide quando il feed tace. Prima erano tre risposte diverse, e
sullo stesso schermo la lista delle fermate e il tasto sotto ne indicavano due
distinte.

**E l'orologio e' uno solo.** `UiClock.ticks()` batte ogni dieci secondi
allineato al muro, non all'istante in cui una schermata si e' aperta: due
schermate mostrano lo stesso minuto perche' partono dallo stesso istante, non
perche' chi le ha scritte e' stato attento. Una schermata che chiama
`Instant.now()` dentro la propria composizione e' fuori dal battito, e si
vede: i minuti restano fermi finche' qualcos'altro non la fa ricomporre.

## Regole di stile

Commenti e messaggi di commit in **italiano**, con l'apostrofo ASCII al posto
degli accenti finali (`e'`, `cosi'`, `perche'`) come nel resto del codice.

I commenti dicono **perche'**, non cosa. Quelli buoni di questo repo raccontano
il sintomo visto, la causa vera e la misura: si scrive cosi' anche il prossimo.

Un messaggio di commit ha un titolo in linguaggio comune che dice l'effetto per
chi usa l'app, e un corpo che spiega sintomo, causa e rimedio. Niente elenchi
puntati.

Per la UI valgono le regole del Fluid Engine (`engine/skill/fluid-engine/`):
`ContinuousCornerShape` e mai `RoundedCornerShape`, niente `fontSize` a mano,
palette generata da un solo accento, ruoli sempre in coppia.

## Il banco di fedelta'

```bash
cd worker && npm run fedelta
```

Scarica il feed GTFS-RT **grezzo** della Regione e la sezione che il proxy
serve all'app, e confronta i ritardi uno per uno. E' l'unico modo di
rispondere a "i nostri minuti sono quelli della fonte?": guardando l'app si
confronterebbe l'app con se' stessa.

Ultima misura: **873 punti confrontati, 0 differenze** (15/09/2026).

Gira da solo in CI due volte al giorno, nelle ore di punta — a notte fonda il
feed ha una manciata di corse e il confronto non direbbe niente. Esce 1 se le
differenze superano cinque per mille, 2 se i due lati vengono da due
generazioni diverse (non e' un difetto: si riprova).

## La lista di quello che resta aperto

`APERTO.md` alla radice. Ogni riga e' qualcosa di **osservato** e non chiuso,
con la data; e le cose che si e' deciso di NON fare, col perche' e con la
misura che ha deciso. Si legge prima di ricominciare a lavorare, e si aggiorna
quando una riga si chiude.

## Dove guardare quando qualcosa non torna

- `/rt/v1/health` sul Worker: eta' dei feed, conteggi, battito del cron,
  conteggi delle previsioni per fermata.
- **Impostazioni -> Stato dei dati** nell'app: validita' del bundle, sorgente
  realtime, percentuale di corse riconosciute, stato dei luoghi.

La catena dal feed al telefono e' controllata in due punti, e servono
tutt'e due: il **banco di fedelta'** (`worker/tools/fedelta.mjs`, notturno)
confronta ritardo per ritardo l'origine della Regione con quello che il
proxy serve, e la riga **"Previsioni agganciate"** dello stato dei dati dice
quante di quelle previsioni l'app riesce davvero ad attaccare alla corsa e
alla fermata giuste. Il primo copre il tratto fuori, la seconda il tratto
dentro; la regola di propagazione in mezzo ha i suoi test in
`LiveFromPredictionsTest`. Chi cerca "i nostri numeri sono quelli della
fonte?" guarda questi tre, non l'app.
- `index.json` della release `dati`: cosa sta servendo l'app in questo momento.
