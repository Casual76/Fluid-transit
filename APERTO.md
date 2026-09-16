# Quello che resta aperto

La lista viva delle cose viste e non ancora fatte. Non e' un backlog di idee:
ogni riga e' qualcosa che è stato **osservato** — sul telefono, in un test, o
leggendo il codice con un motivo — e che non è stato chiuso.

Una riga esce da qui solo quando è risolta, o quando si è deciso e scritto
**perché** non va risolta.

---

## Si vede

**In orizzontale, con la tastiera aperta, la ricerca ha spazio per una riga.**
Era per meno di una riga: la tastiera veniva sottratta due volte, una
dall'altezza massima dell'elenco e una come spaziatura interna alla stessa
scatola (vedi il commit del 16/09). Tolto il doppione, in verticale l'elenco
e' passato da due righe e mezzo a otto, e in orizzontale da niente a una.
Resta poco: schermo 411 dp, tastiera 265, e la barra di ricerca con la barra
di stato e i margini prende il resto. La strada vera e' sempre quella —
lasciare che la tastiera vada a schermo intero in orizzontale, com'e' il
comportamento normale di Android quando l'app non lo impedisce — oppure fare
della ricerca un pannello intero invece di una tendina sotto la barra.
— riprovato il 16/09/2026

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

**Il widget della fermata dopo la configurazione: non si riproduce piu', ma
la prova non e' quella giusta.** Riprovato il 16/09 lanciando la schermata di
configurazione a mano su un widget gia' agganciato: si sceglie la fermata, si
torna alla home, e il widget si ridisegna con i numeri giusti. Il caso
originale pero' e' un widget appena AGGIUNTO, che il lanciatore aggancia dopo
che la schermata di configurazione si e' chiusa, e quello non si e' potuto
ricreare a comando. Nel frattempo il budget di tempo del disegno e' sceso da
quattordici secondi a otto, che era il sospetto piu' probabile. Resta da
guardare la prossima volta che si aggiunge un widget da zero.
— riprovato il 16/09/2026

**Da verificare con un bus vivo: le parole del pannello corsa.** Le altre due
verifiche in attesa sono state fatte all'una di notte, quando il feed si e'
riempito abbastanza: un tabellone con righe "dal bus · da tabella alle 01:09"
accanto a righe di tabella, e "Qui intorno" con cinque righe da quattro
fermate e nessun doppione. Resta il pannello di una corsa aperto su un mezzo
vivo: alle 00:50 nel raggio della mappa non ce n'era nessuno da toccare, e
con duecentoquattordici mezzi in tutta la Toscana la strada e' aspettare le
ore di servizio. — aggiornato il 16/09/2026

**Le posizioni finte dell'emulatore non arrivano all'app.** `adb emu geo fix`
risponde `OK` ma la posizione resta quella predefinita di Mountain View: per
provare qualcosa che dipende da dove sei, la strada che funziona e' togliere
il permesso di posizione, cosi' il riferimento diventa il centro della mappa,
e portare la mappa dove serve. — visto il 15/09/2026

## Non si vede, ma conta

**`/rt/v1/refresh` e' aperto.** Il codice che controlla il segreto c'è e si
accende da solo, ma `REFRESH_SECRET` non è configurato. Si chiude con
`wrangler secret put REFRESH_SECRET` e la corrispondente variabile nel
workflow `rt-keepalive.yml`: serve chi ha le chiavi di Cloudflare. Nel
frattempo l'endpoint è limitato a un giro ogni venti secondi per isolate.
— aperto dal 15/09/2026

**La cache dei tabelloni non butta mai niente.** `DepartureBoards` tiene una
`ConcurrentHashMap` da chiave (fermate, limite, orizzonte) a flusso, e non
toglie mai una riga. "Qui intorno" fabbrica una chiave nuova ogni volta che ci
si sposta di duecento metri, quindi girando per la regione le chiavi si
accumulano per tutta la vita del processo. Non e' un difetto che si vede — un
tabellone da dodici righe sono pochi kilobyte — ma e' senza tetto. Un tetto
fatto male sarebbe peggio del difetto: togliere un flusso che qualcuno sta
ancora guardando vuol dire che il prossimo che chiede la stessa fermata ne
crea un secondo, e due flussi sulla stessa fermata sono esattamente la cosa
che questa classe esiste per impedire.

Provato a pensarlo con i riferimenti deboli, che sarebbe la soluzione
elegante: **non funziona.** Il flusso lo tiene vivo la coroutine che
`stateIn` lancia nello scope dell'Application, e quella coroutine non finisce
mai — `WhileSubscribed` la lascia in attesa di iscritti per sempre. Quindi il
riferimento debole non si svuota mai e il tetto non scatta. Per farlo davvero
bisogna contare gli iscritti per chiave, come la classe fa gia' per il giro
dei ritardi, e cancellare la coroutine quando restano a zero da un po'.
— letto il 16/09/2026

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
