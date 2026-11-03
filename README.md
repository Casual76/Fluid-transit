# Fluid Transit

L'app per muoversi coi mezzi pubblici in Toscana: una mappa con i bus che si muovono davvero, le
partenze di ogni fermata con il ritardo detto da chi guida e non solo dall'orario di tabella, gli
itinerari, le routine ("esci tra dodici minuti") e un assistente a cui si puo' chiedere a voce o per
iscritto. Dati di Regione Toscana e Autolinee Toscane (CC-BY), rielaborati.

Android, Kotlin e Jetpack Compose sopra il [Fluid Engine](engine/) (il design system condiviso delle
app Pampa), mappa con MapLibre. Si distribuisce dal Pampa Store, non dal Play Store.

## Struttura

| cartella | cosa c'e' |
|---|---|
| `app/` | l'app Android: schermate, mappa, widget Glance, navigazione a bordo |
| `core-routing/` | formato degli orari (`.ftb`), lettore, motore degli itinerari: Kotlin/JVM puro |
| `core-ai/` | l'assistente: gli strumenti dei trasporti, il prompt, la sessione |
| `tools/bundler/` | costruisce il bundle degli orari dal GTFS, ogni notte in CI |
| `worker/` | il proxy realtime su Cloudflare |
| `engine/` | il Fluid Engine, submodule |

`CLAUDE.md` raccoglie i vincoli che non si ricavano dal codice e `APERTO.md` quello che e' osservato e
ancora aperto.

## Compilare

```bash
git clone --recurse-submodules https://github.com/Casual76/Fluid-transit.git
cd Fluid-transit
./gradlew :core-routing:test :core-ai:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

Senza `keystore.properties` e senza `app/google-services.json` la build riesce lo stesso, solo non
firmata e senza Crashlytics. Il Worker si prova con `cd worker && npm test`.

## Licenza

Fluid Transit e' software libero: si distribuisce sotto la **GNU General Public License, versione 3**
(testo completo in [`LICENSE`](LICENSE)). Il repository e' pubblico, e **chi riceve l'APK ha diritto ai
sorgenti**: sono questo repository, <https://github.com/Casual76/Fluid-transit>, all'etichetta della
versione che ha installato. Si possono leggere, modificare e ridistribuire alle condizioni della
GPL-3.0, con la stessa licenza.

**Perche' GPL-3.0.** Dalla versione 3.0 il Fluid Engine contiene codice di
[LibrePods](https://github.com/librepods-org/librepods), GPL-3.0: il comportamento dei pulsanti e delle
pagine non e' piu' riscritto, e' portato. Un'app che distribuisce l'engine e' un'opera derivata, e va
distribuita con la stessa licenza (fino alla 1.3.2 Fluid Transit era Apache-2.0; quelle versioni
restano disponibili con la licenza con cui sono uscite). Vale per tutte le app Pampa che usano l'engine.

Le opere di altri che l'APK porta con se' (il vetro di Kyant, Apache-2.0; i caratteri Inter, SIL OFL
1.1; LibrePods; MapLibre, BSD-2-Clause) sono elencate in **Impostazioni > Informazioni** con gli
avvisi che le loro licenze chiedono; i testi sono in [`engine/LICENSES/`](engine/LICENSES).

I **dati** non sono sotto la GPL: gli orari e le posizioni sono di Regione Toscana e Autolinee Toscane
(CC-BY 4.0) e la mappa e' di OpenStreetMap, servita da OpenFreeMap (ODbL), con le attribuzioni che si
vedono sulla mappa. Crashlytics, che e' proprietario, entra nella build solo se esiste
`app/google-services.json`, che non e' nel repository: la build che si ricostruisce da questi
sorgenti ne e' libera.
