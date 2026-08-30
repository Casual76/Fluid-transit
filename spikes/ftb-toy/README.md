# spikes/ftb-toy — formato `.ftb` e lettore mmap

Spike 3 della Fase 1. Costruisce un bundle `.ftb` da un feed GTFS e lo rilegge,
per rispondere a cio' che il piano dava per assodato senza averlo misurato su
questo feed.

## Come si esegue

```
curl -o work/gtfs-at.zip https://regionetoscana.smartregion.toscana.it/mobility/artifacts/gtfs
unzip work/gtfs-at.zip -d work/gtfs
./gradlew run    --args="work/gtfs work/toscana.ftb -"    # tutta la Toscana
./gradlew verify --args="work/gtfs work/toscana.ftb"
```

Il terzo argomento e' il codice provincia di `area.txt` (`SI`, `FI`, `AR`, ...)
oppure `-` per l'intera regione. `work/` non e' versionata.

## Che cosa dimostra `verify`

Il valore del controllo sta nel ricalcolare le stesse risposte per una strada
completamente diversa - rileggendo i CSV - e pretendere che coincidano. Nessun
controllo interroga il bundle contro se stesso.

- CRC32 di ogni sezione, integrita' referenziale, ordinamento delle corse
- aritmetica del giorno di servizio, incluso il cambio d'ora in entrambi i versi
- 500 `trip_id` reali risolti attraverso l'indice hash
- 60 query "prossimi passaggi" identiche a quelle ricalcolate dai CSV
- la corsa piu' notturna del feed trovata interrogando il mattino dopo
- 40 ricerche spaziali identiche alla scansione completa

## Dove finisce questo codice

E' uno spike, non codice di prodotto. In Fase 2 `FtbReader` e `FtbFormat` si
spostano in `:core-routing` come `BundleReader`, Kotlin/JVM puro; il builder si
sposta in `tools/bundler`, dove pero' la trasformazione la fara' DuckDB.

Sezioni non implementate qui, e volutamente: `TRANSFERS` (serve la fusione
delle banchine in stazioni e le polilinee-barriera da OSM), `POLYLINES`,
`SEARCH`. I loro identificatori sono gia' riservati in `FtbFormat`.
