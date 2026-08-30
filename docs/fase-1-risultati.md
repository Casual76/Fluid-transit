# Fase 1 — esiti degli spike

Misurato il 30 agosto 2026, sul feed pubblicato quella notte alle 04:07 e su un
Galaxy S25 (Android 16, API 36, arm64).

| spike | domanda | esito |
|---|---|---|
| 1 | l'origine risponde alla rete Cloudflare? | **sì**, e il proxy resta pienamente giustificato |
| 2 | `pmtiles://` remoto gira su MapLibre Android? | **sì**, con una scoperta che pesa sulla Fase 3 |
| 3 | il formato `.ftb` regge, e il lettore lo rilegge? | **sì**, e il piano era pessimista di 3-5× |

Nessuno dei tre ha prodotto un no. Due hanno prodotto correzioni che nessuna
stima avrebbe dato.

---

## Spike 1 — l'origine e la rete Cloudflare

Worker di probe pubblicata su `fluid-transit-probe.fluid-transit.workers.dev`,
codice in [`worker/`](../worker). Il deploy passa dalla Action perché in locale
non c'è Node.

**L'origine non filtra gli IP datacenter.** Dalla rete Cloudflare (colo MXP e
FCO) i tre feed rispondono 200 con dati validi; dal runner GitHub, cioè da IP
Azure, pure. Il design del proxy sta in piedi, e in più il piano B — spostare
il cron sulla Action — resta disponibile invece di essere già escluso.

Il probe non si ferma alla raggiungibilità: valida il protobuf ed estrae
`header.timestamp`, che è il valore che in Fase 4 diventa `X-Feed-Age`.

| feed | byte | entity | versione | età alla lettura |
|---|---|---|---|---|
| vehicle-positions | 46.051 | 539 | 1.0 | 20 s |
| trip-updates | 217.440 | 434 | 1.0 | 24 s |
| alerts | 298.152 | 277 | **2.0** | 7 s |

### Cosa cambia rispetto al piano

**L'origine si rigenera ogni ~2 minuti, non ogni minuto.** Misurato con 22
letture a 15 s di distanza: i timestamp cambiano a 115, 121 e 120 secondi. Otto
richieste consecutive restituiscono byte identici.

Il cron al minuto resta la scelta giusta — dimezza la staleness nel caso
peggiore — ma metà delle letture sono ridondanti e, non essendoci validatori,
non c'è modo di evitarle. **Si può però evitare la scrittura**: se il
`header.timestamp` non è cambiato, il cron non deve riscrivere `rt/latest.bin`.
Dimezza le operazioni di classe A su R2 e costa un confronto.

Confermato che le richieste condizionali non sono possibili: `If-None-Match: *`
e `If-Modified-Since` ricevono 200 con corpo integrale. Nessun `ETag`, nessun
`Last-Modified`, nessuna compressione neanche chiedendola. Nessun rate limit su
raffiche di otto richieste.

**Lo zip statico da 129 MB non supporta le richieste `Range`**: risponde 200
invece di 206 e non manda `Accept-Ranges`. Un download interrotto in CI riparte
da zero, quindi il job notturno ha bisogno di retry sull'intero scaricamento.

Verificato che il feed statico è esattamente quello atteso: 129.523.290 byte
zip, 583.892.131 estratti, 5.839.150 righe di `stop_times`, 28.966 fermate, 766
linee, 213.583 corse, validità 30/08 → 19/09.

Utile e non previsto: `stops.txt` porta `area_id` e `area.txt` elenca le dieci
province. La partizione geografica esiste già nel feed e non va inferita.

---

## Spike 2 — PMTiles remoto su MapLibre Android

App di spike in [`spikes/pmtiles-android/`](../spikes/pmtiles-android),
eseguita sul telefono con MapLibre **11.11.0** e il campione Firenze di
pmtiles.io letto remoto.

**Funziona.** 64 richieste, tutte con header `Range`, 1,5 MB scaricati su un
archivio da 6,6 MB. La sequenza è quella corretta: header di 127 byte, root
directory di 398 byte, poi le singole tile. Le geometrie disegnate contate sul
viewport (807 strade, 220 aree d'uso del suolo, 120 elementi di trasporto)
provano che le tile sono state lette, decompresse e rese — non solo scaricate.
Stile caricato in 198 ms, primo frame a 208 ms.

### La scoperta che pesa sulla Fase 3

**MapLibre rilegge header e root directory a ogni tile, da più thread, senza
memoria.** Nella sessione misurata, 40 richieste su 64 erano riletture degli
stessi 525 byte.

Non è un problema di banda — sono byte — ma di **numero di richieste e di
latenza**: ogni rilettura è un round-trip completo, qui circa 400 ms. La
conseguenza è che l'opzione 2 della Fase 0 (PMTiles servite dalla Worker con
binding R2) è più rischiosa di quanto sembrasse: il tetto di 100.000
richieste/giorno si consuma circa tre volte più in fretta del previsto.

La mitigazione è alla nostra portata proprio perché il client OkHttp è nostro:
un intercettore che memorizzi header e root directory in memoria elimina quelle
riletture. Da fare in Fase 3, misurando prima e dopo.

**GitHub Releases regge le range request**: 302 verso Azure Blob dietro Fastly,
206 con `Content-Range` corretto, `Accept-Ranges: bytes` e `X-Cache: HIT`. Il
redirect viene seguito conservando l'header `Range`. L'opzione 1 resta la
candidata principale. `r2.dev` non è stato misurato — il bucket è vuoto — e
resta da verificare in Fase 3 se lo si vuole tenere anche solo per i test.

### Due trappole di inizializzazione, documentate perché costano tempo

1. `MapLibre.getInstance(context)` a un argomento non basta: serve
   `(context, apiKey, WellKnownTileServer)` con chiave `null`.
2. `HttpRequestUtil.setOkHttpClient` va chiamato **dopo** `getInstance`, non
   prima. Passa dal module provider, che pretende l'istanza già creata, e
   invertendoli si ottiene la stessa eccezione del punto 1 — che indica il
   posto sbagliato.

---

## Spike 3 — il formato `.ftb`

Codice in [`spikes/ftb-toy/`](../spikes/ftb-toy). Costruito prima il giocattolo
su Siena, poi l'intera Toscana, perché costava due secondi in più e misura
direttamente le stime del piano.

### Il piano era pessimista

| | atteso dal piano | misurato | |
|---|---|---|---|
| pattern | 8.000 – 15.000 | **5.296** | 1,5-2,8× meglio |
| voci pattern-fermata | ~324.000 | **161.865** | 2× meglio |
| deduplica dei profili | fattore 4-5× | **fattore 25,1×** | 5× meglio |
| profili distinti | 40.000 – 60.000 | **8.495** | |
| bundle su disco | 14 – 17 MB | **12,46 MB** | senza 3 sezioni |

Le 213.583 corse collassano in 5.296 pattern, 40,3 corse per pattern. I
5.839.150 orari diventano 161.865 voci pattern-fermata e 270.388 valori u16.

**`arrival == departure` non è "quasi ovunque": è ovunque.** 77 soste non nulle
su 270.388 posizioni, cioè lo 0,03%. La sezione `DWELL` sparsa è giustificata
senza riserve.

La durata massima di una corsa è 9.600 s: l'offset u16 ha 6,8× di margine.
Nessuna collisione FNV-1a a 64 bit su 213.583 `trip_id`.

I 12,46 MB non comprendono `TRANSFERS`, `POLYLINES` e `SEARCH`, fuori dallo
scope dello spike: con quelle la stima 14-17 MB del piano resta realistica.
Metà del bundle è però in due sole sezioni — `TRIPS` 4,17 MB e
`TRIP_ID_INDEX` 2,50 MB — e 1,56 MB sono i `trip_id` in chiaro dentro
`STRINGS`. Tenerli o no è una decisione di Fase 2: servono al matcher
secondario e alla schermata diagnostica, e costano il 12% del download.

**Il rischio memoria del runner CI non riguarda questa parte.** Il build gira
in 2,7 secondi entro 2 GB di heap leggendo in streaming, su tutta la regione.
DuckDB resta la scelta giusta per shapes e tippecanoe, ma la trasformazione
della timetable non ne ha bisogno.

### Due correzioni che i dati hanno imposto

**L'ultima corsa del feed finisce alle 30:10**, cioè alle 06:10 del mattino
dopo, ancora dentro il giorno di servizio precedente; 2.709 corse finiscono
oltre le 24:00. Il limite di quanto indietro guardare in una query è quindi un
dato del bundle, non una costante scelta a occhio: sta nell'header e il lettore
lo legge da lì. Con un tetto fisso a 30 ore — plausibile e sbagliato — una
query fatta alle 06:05 avrebbe perso quella corsa.

**Il giorno di servizio da 25 ore è quello *precedente* al ritorno all'ora
solare**, non quello del cambio: l'ora ripetuta cade nell'estensione 24:00-27:00
di quel giorno. Nel 2026 è il 24 ottobre, non il 25. Le prime versioni dei test
asserivano il contrario e fallivano contro un'implementazione corretta; un
lettore scritto per soddisfarle avrebbe spostato di un'ora ogni corsa notturna.

I controlli ora coprono: giorno di 25 e di 23 ore, giorno del cambio che resta
di 24, orari diurni corretti in tutti e quattro i casi, le due 02:00 distinte
della notte lunga, e l'ora inesistente della notte corta.

### Come è verificato

`verify` non interroga il bundle contro sé stesso: ricalcola le stesse risposte
rileggendo i CSV. 60 query "prossimi passaggi" su 12 fermate e 5 orari — incluse
le 23:50 e le 00:30 — coincidono esattamente. Mediana 195 µs, massimo 2,8 ms.
500 `trip_id` reali risolti attraverso l'indice hash. 40 ricerche spaziali a
500 m identiche alla scansione completa. Apertura del file in 36 µs.

---

## Cosa non è stato fatto, e perché

**La schermata diagnostica interna** che il piano colloca in Fase 1 non è stata
costruita: richiede lo scheletro dell'app, il client realtime e RAPTOR, che
sono Fase 2, 4 e 5. Va spostata alla Fase 2, dove diventa la prima schermata
utile. La Fase 1 ha però reso già calcolabile ognuno dei suoi cinque campi:
il `buildId` è nell'header del bundle, l'età del feed si ricava dal
`header.timestamp` come fa la probe, la percentuale di `trip_id` risolti esce
da `TRIP_ID_INDEX`, e il tempo dell'ultima query è già misurato.

**`engine-install.ps1`** resta rimandato alla Fase 2 come deciso in Fase 0: il
submodule `engine/` è ancora solo in staging e non c'è un `settings.gradle` in
cui inserire il blocco.

**Le sezioni `TRANSFERS`, `POLYLINES` e `SEARCH`** non sono nel `.ftb` dello
spike. `TRANSFERS` in particolare non è rimandabile a lungo: richiede la fusione
delle banchine in stazioni e le polilinee-barriera da OSM, ed è il rischio n. 3
del piano — senza, RAPTOR non produrrà mai un itinerario bus→treno e il sintomo
sarà "risultati stranamente scarsi", non un errore. Gli identificatori di
sezione sono già riservati.

**`r2.dev`** non è stato misurato per le range request.
