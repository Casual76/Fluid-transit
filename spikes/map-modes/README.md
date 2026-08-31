# spikes/map-modes — banco di prova della mappa

> ⚠️ **Questo non è una funzionalità.** È servito a rispondere a una domanda
> sola — *le sorgenti scelte funzionano davvero su un telefono?* — e la
> risposta è sì. La mappa dell'app non è iniziata: manca tutto quello che la
> renderebbe utile (posizione dell'utente, fermate, linee, bus, comportamento
> senza rete, stato che sopravvive alla rotazione) e **nessuna delle scelte di
> esperienza utente che si vedono qui è stata decisa**. Sono messe lì per
> poterle guardare.
>
> Esempio concreto di cosa non va preso per buono: **in modalità ibrida gli
> edifici 3D non dovrebbero essere disponibili**. Sopra una foto aerea sono
> volumi di un colore inventato che coprono la foto. Qui l'interruttore resta
> acceso apposta, perché il punto era vedere che effetto fa.
>
> A implementare le cose come vanno fatte si penserà durante la Fase 3, e le
> scelte di esperienza utente si chiedono prima — non si ereditano da qui.

```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.antigravity.fluidtransit.spike.map/.MainActivity
```

## Cosa si può provare

| modalità | sorgente |
|---|---|
| Stradale | OpenFreeMap *Liberty* — colori pieni, POI, edifici 3D già nello stile |
| Minima | OpenFreeMap *Positron* — quasi bianca, è la base su cui le nostre linee si vedranno |
| Scura | OpenFreeMap *dark* |
| Satellite | ortofoto Regione Toscana, WMS GEOscopio |
| Ibrida | ortofoto con strade e nomi sopra, stile composto da noi |

Più: edifici estrusi, inclinazione della camera fino a 60°, rotazione, e
quattro inquadrature di prova (Duomo, Firenze, Siena, Toscana).

## Cosa si sposta in Fase 3 e cosa si butta

Si sposta: `MapCatalog` (tutte le sorgenti in un posto solo), `FluidMapView`
(la `MapView` avvolta in Compose, con il ciclo di vita corretto e le opzioni),
`NetworkStats` (misura del traffico e cache delle ortofoto).

Si butta: `MapModesScreen`. Al suo posto vanno i pannelli in vetro
dell'engine — ricerca, scheda fermata, foglio itinerari.

## Le scelte fatte, e perché

**Il layer delle ortofoto è `rt_ofc.10k13`, il volo 2013 a 1:10.000.** Le
annate a 20 cm (2015, 2016, 2017) sono dichiarate a *copertura del territorio:
parziale* e su Firenze non ci sono: chiedendole si ottengono riquadri vuoti. Il
2013 copre l'intera regione, isole comprese — verificato renderizzando la
Toscana in un colpo solo. Il layer di gruppo `rt_ofc` prenderebbe il meglio
disponibile ma disegna sopra la griglia dei quadri di unione, quindi è
inservibile come basemap.

**Il 3D sopra la foto aerea è tenuto trasparente al 35%.** Pieno copre
l'ortofoto e si ottiene il peggio dei due mondi: si perde la foto e in cambio
si hanno scatoloni di un colore inventato. Senza fotogrammetria non abbiamo
texture da mettere sui volumi, e fingere il contrario si vede.

**Le altezze vengono da `render_height` di OpenFreeMap**, calcolato dalle
altezze OSM e, dove mancano, dal numero di piani. In centro è affidabile; in
periferia molti edifici finiscono a un'altezza di comodo. Il 3D è una lettura
del territorio, non un rilievo.

## La misura che conta

Il satellite è l'unica funzione dell'app il cui traffico cresce con gli utenti.
Le ortofoto arrivano **senza alcun header di cache** — niente `Cache-Control`,
niente `ETag`, niente `Last-Modified`, esattamente come il feed realtime — e
quindi né OkHttp né la cache interna di MapLibre le conserverebbero.

`NetworkStats` riscrive `Cache-Control` in ingresso a 30 giorni. È legittimo:
quel dato è un volo aereo del 2013 e non cambierà. Misurato sul telefono, con
lo stesso gesto (Satellite → Duomo → azzera → Stradale → Satellite) e partendo
da dati applicazione azzerati in entrambi i casi:

| | richieste | di cui ortofoto | rete |
|---|---|---|---|
| senza riscrittura | 16 | 10 | 800 KB |
| **con riscrittura** | 6 | **0** | 204 KB |

Le sei che restano sono di OpenFreeMap (stile, sprite, glifi) e la riscrittura
non le tocca. Il traffico ripetuto verso il server della Regione sparisce.

Nota sulla lettura della diagnostica: le richieste contate sono quelle che
arrivano a OkHttp. MapLibre ha una propria cache a monte, quindi *zero
richieste* con la mappa disegnata significa "servita in locale", non "non
caricata".

## Da fare in Fase 3

Tecnico:

- Sostituire la UI di prova con i pannelli in vetro dell'engine.
- Posizione dell'utente, fermate, linee, bus vivi sopra la mappa.
- Comportamento senza rete, errori delle sorgenti, stato che sopravvive alla
  rotazione e al ritorno da background: qui non c'è niente di tutto questo.
- Verificare la mappa su un dispositivo Android 10-12: qui è provata solo su
  Android 16 di fascia alta, dove tutto va per definizione.

Da **chiedere**, non da decidere guardando questo prototipo:

- quante modalità offrire davvero, e con che nomi;
- quale sia la modalità di partenza;
- se il satellite sia attivo di default — il tetto della Worker è 100.000
  richieste/giorno e questa è l'unica voce che scala con gli utenti; la cache
  lo rende molto meno preoccupante ma non azzera il primo caricamento;
- se offrire gli edifici 3D, e in quali modalità (nell'ibrida no);
- se l'inclinazione sia un cursore, un gesto, o non sia esposta affatto;
- dove vada il selettore delle modalità, e se sia sempre visibile;
- se esista una modalità notturna, e se segua l'ora o l'impostazione di sistema.
