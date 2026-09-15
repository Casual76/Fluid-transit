# Quello che resta aperto

La lista viva delle cose viste e non ancora fatte. Non e' un backlog di idee:
ogni riga e' qualcosa che è stato **osservato** — sul telefono, in un test, o
leggendo il codice con un motivo — e che non è stato chiuso.

Una riga esce da qui solo quando è risolta, o quando si è deciso e scritto
**perché** non va risolta.

---

## Si vede

**Il tocco su "perche' questo numero" non ha un segno che lo annunci.** Chi non
prova non lo trova. Un punto interrogativo su ogni riga sarebbe rumore su
righe che sono già dense; serve un'idea migliore. — visto il 15/09/2026

**La camera della mappa riparte da capo cambiando scheda.** La mappa è un
`AndroidView` che il `when` della shell smonta quando si va su Oggi; al ritorno
lo stato salvabile c'è, ma la superficie MapLibre si ricrea. Si vede come un
salto della camera. Costoso da risolvere bene: vorrebbe dire tenere la mappa
sempre composta e mettere le altre schede sopra. — visto il 15/09/2026

**In orizzontale, con la tastiera aperta, la ricerca non ha dove mettere i
risultati.** Misurato: schermo 411 dp, tastiera 265, restano 146 — e la sola
barra di ricerca con la barra di stato e i margini ne prende 84. Restano meno
di sessanta punti, cioe' meno di una riga. Non e' un difetto di calcolo:
l'elenco adesso chiede esattamente quello che c'e' (la tastiera viene
sottratta), e quello che c'e' non basta. La strada vera e' lasciare che la
tastiera vada a schermo intero in orizzontale, com'e' il comportamento
normale di Android quando l'app non lo impedisce. In verticale, con e senza
tastiera, non cambia niente. — misurato il 15/09/2026

**La camera della mappa NON riparte da capo cambiando scheda.** Era scritto
qui come difetto; riprovato, e' falso: si apre una fermata, si va su Oggi, si
torna, e la mappa e' dov'era, allo stesso zoom. Lo stato salvabile fa il suo
lavoro. Riga tolta dalla lista e tenuta qui per non riaprire l'indagine.
— verificato il 15/09/2026

**Il widget della fermata non si ridisegna subito dopo la configurazione.**
Scegli la fermata, torni alla home, e il widget continua a dire "Tocca per
configurare" pur avendo la fermata gia' scritta nel suo stato (verificato
leggendo `files/datastore/appWidget-N.preferences_pb`: hash e nome ci sono).
Escluso: non e' il permesso, non e' lo stato mancante, non e' un'eccezione
(nessuna nei log), e non lo risolvono ne' un `update()` diretto ne' un
`updateAll()` da una sveglia. Una volta ridisegnato mostra i numeri giusti,
identici a quelli delle schermate. Dopo la configurazione parte comunque una
sveglia a due secondi, che e' il meccanismo giusto per riprovarci.
— visto il 15/09/2026

## Non si vede, ma conta

**`/rt/v1/refresh` e' aperto.** Il codice che controlla il segreto c'è e si
accende da solo, ma `REFRESH_SECRET` non è configurato. Si chiude con
`wrangler secret put REFRESH_SECRET` e la corrispondente variabile nel
workflow `rt-keepalive.yml`: serve chi ha le chiavi di Cloudflare. Nel
frattempo l'endpoint è limitato a un giro ogni venti secondi per isolate.
— aperto dal 15/09/2026

**Il Cron Trigger di Cloudflare non e' un metronomo.** `crons = ["* * * * *"]`
è configurato, ma `/rt/v1/health` ha riportato battiti a 26 minuti e a 102
minuti di distanza. Non fa danni — il refresh pigro e quello bloccante coprono
il buco — ma vuol dire che la freschezza dipende dal traffico, non dal
programma. — misurato il 15/09/2026

**La chiave Google Maps e' dentro gli APK 1.0.0, 1.0.1 e 1.1.0 gia'
distribuiti.** È stata tolta da `local.properties`, ma quello che è stato
pubblicato resta pubblicato: l'unico rimedio è revocarla dalla console Google
Cloud, e può farlo solo il proprietario del progetto. — aperto

**Due app Pampa sullo stesso telefono litigano.** `dev.pampa.pampai.debug` e
Fluid Transit dichiarano entrambe il permesso
`dev.antigravity.fluidengine.permission.AI_TOOLS`, e l'installazione della
seconda fallisce con `INSTALL_FAILED_DUPLICATE_PERMISSION`. Sull'emulatore si
risolve disinstallando; in distribuzione no. — visto il 15/09/2026

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

**L'indirizzo `trip/<hash>`.** Era nel piano dei deep link. Nessuno lo emette,
e senza un emettitore non si può provare davvero. Sono quattro righe il giorno
che servirà. — 15/09/2026
