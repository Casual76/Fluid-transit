# spikes/pmtiles-android — `pmtiles://` remoto su MapLibre

Spike 2 della Fase 1. Una app minima, usa-e-getta, che apre una basemap
PMTiles ospitata su un altro server e misura come la legge.

## Come si esegue

```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.antigravity.fluidtransit.spike.pmtiles/.MainActivity
adb logcat -s PmtilesSpike
```

Il file di prova e' il campione Firenze di pmtiles.io (6,6 MB, ODbL): non va
scaricato, l'app lo legge remoto — che e' esattamente il punto.

## Che cosa misura

Ogni richiesta HTTP passa da un intercettore OkHttp installato via
`HttpRequestUtil`, quindi a schermo e in logcat si vedono richieste, header
`Range`, byte scaricati e host. Il conteggio delle geometrie effettivamente
disegnate (`queryRenderedFeatures`) e' la prova che le tile sono state lette,
decompresse e rese: uno stile puo' caricarsi benissimo su una mappa vuota, e a
occhio non si distingue da un archivio che non si apre.

## Due trappole trovate, che valgono per l'app vera

1. **`MapLibre.getInstance(context)` a un argomento non basta.** Serve la
   variante `(context, apiKey, WellKnownTileServer)`, con chiave `null`: senza,
   `MapLibreConfigurationException` alla costruzione della `MapView`.

2. **`HttpRequestUtil.setOkHttpClient` va chiamato DOPO `getInstance`**, non
   prima. Passa dal module provider, che pretende l'istanza gia' creata, e
   invertendo i due si ottiene la stessa eccezione del punto 1 — che indica il
   posto sbagliato e fa perdere tempo.

## Il risultato che cambia una decisione di Fase 3

MapLibre rilegge l'header PMTiles (127 B) e la root directory (398 B) a ogni
tile, da piu' thread, senza memoria: in una sessione di 64 richieste, 40 erano
riletture degli stessi 525 byte. Su una basemap regionale la conseguenza non e'
la banda — sono byte — ma il numero di richieste e la latenza.

Questo pesa sulla scelta dell'hosting: rende la Worker con binding R2
(opzione 2 del piano) piu' rischiosa di quanto sembrasse, perche' il tetto di
100k richieste/giorno si consuma tre volte piu' in fretta. La mitigazione e'
alla nostra portata proprio perche' il client OkHttp e' nostro: un intercettore
che memorizzi header e root directory in memoria elimina quelle riletture. Da
fare in Fase 3, misurando prima e dopo.
