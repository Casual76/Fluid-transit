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

**Meta' degli snapshot realtime sono fotocopie.** L'origine si rigenera ogni
~2 minuti, l'app polla ogni 30 s. Riapplicare lo stesso rilevamento non e'
innocuo: l'eta' del fix e' congelata, quindi il bersaglio resta fermo mentre il
mezzo simulato avanza, e la "correzione" lo tira indietro.

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

Due conseguenze che si dimenticano:

**Zero `parent_station`** e' il motivo per cui `StopGroups` deduce le banchine
da nome + distanza invece di leggere un campo. Non e' una scelta estetica: il
campo non c'e'.

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
- `index.json` della release `dati`: cosa sta servendo l'app in questo momento.
