package dev.antigravity.fluidtransit.ui.settings

import dev.antigravity.fluidengine.ui.fluid.FluidLicense

/**
 * Le opere che l'APK porta oltre a quelle dell'engine, e dove stanno i
 * sorgenti di Fluid Transit.
 *
 * L'attribuzione e' una condizione di licenza, non una cortesia: la CC-BY dei
 * dati chiede che si vedano, la Apache-2.0 e la BSD chiedono che l'avviso
 * viaggi con la distribuzione, e la GPL-3.0 di quest'app (dal Fluid Engine 3.0,
 * che porta codice di LibrePods) chiede di dire a chi riceve l'APK dove sono i
 * sorgenti. Sta in questa lista perche' e' la pagina che l'utente puo' aprire.
 *
 * Crashlytics non c'e': entra nella build solo se esiste `google-services.json`,
 * che non e' nel repository, e la build che si ricostruisce da qui ne e' libera.
 */
val TransitLicenses: List<FluidLicense> = listOf(
    FluidLicense(
        name = "Fluid Transit",
        author = "Alessio Pampaloni",
        licence = "GPL-3.0",
        url = "https://github.com/Casual76/Fluid-transit",
        role = "Quest'app. I sorgenti sono pubblici e a disposizione di chi ne riceve l'APK.",
    ),
    FluidLicense(
        name = "MapLibre Native",
        author = "MapLibre contributors",
        licence = "BSD-2-Clause",
        url = "https://github.com/maplibre/maplibre-native",
        role = "La mappa: disegno delle tessere, camera, posizione.",
    ),
    FluidLicense(
        name = "OkHttp",
        author = "Square, Inc.",
        licence = "Apache License 2.0",
        url = "https://github.com/square/okhttp",
        role = "La rete: orari, tessere della mappa, aggiornamenti.",
    ),
    FluidLicense(
        name = "OpenStreetMap",
        author = "Contributori di OpenStreetMap",
        licence = "ODbL 1.0",
        url = "https://www.openstreetmap.org/copyright",
        role = "I dati della mappa, serviti da OpenFreeMap.",
    ),
    FluidLicense(
        name = "Orari e posizioni dei mezzi",
        author = "Regione Toscana, Autolinee Toscane",
        licence = "CC-BY",
        url = "https://www.regione.toscana.it",
        role = "Fermate, linee, orari e posizioni in tempo reale, rielaborati.",
    ),
)
