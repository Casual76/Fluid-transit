// tools/bundler — il costruttore del bundle .ftb, eseguito dal job notturno.
//
// Kotlin/JVM puro come :core-routing, da cui prende formato, writer e le
// costanti degli hash. Qui vivono il parser CSV e la trasformazione
// GTFS -> pattern/profili; la' il layout binario e il lettore.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-routing"))
    testImplementation(kotlin("test"))
}

application {
    mainClass = "dev.antigravity.fluidtransit.bundler.MainKt"
}

// Tetto basso di proposito: e' la sentinella del rischio "runner CI ucciso
// per memoria". Il build dell'intera Toscana sta in 2 GB perche' legge in
// streaming; se un giorno non ci sta piu', deve fallire qui e non in CI.
tasks.withType<JavaExec> {
    maxHeapSize = "2g"
}

// Il golden gate: riapre il bundle e lo mette contro i CSV di partenza.
tasks.register<JavaExec>("verify") {
    group = "verification"
    description = "Verifica un .ftb contro il feed GTFS che lo ha generato."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.antigravity.fluidtransit.bundler.VerifyKt"
    maxHeapSize = "1g"
}

// L'overlay della rete per la mappa: GeoJSONL di linee (coi colori assegnati)
// e fermate, che il workflow passa a tippecanoe.
tasks.register<JavaExec>("overlay") {
    group = "build"
    description = "Genera linee.geojsonl e fermate.geojsonl per tippecanoe."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.antigravity.fluidtransit.bundler.BuildOverlayKt"
    maxHeapSize = "2g"
}

// Il map matching delle shape, fatto una volta e riusato da bundle e overlay.
tasks.register<JavaExec>("matchshapes") {
    group = "build"
    description = "Riproietta shapes.txt sulla strada OSM via Valhalla."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.antigravity.fluidtransit.bundler.MatchShapesKt"
    maxHeapSize = "2g"
}

tasks.test {
    useJUnitPlatform()
}

// Il banco di prova di RAPTOR sul bundle vero, da riga di comando.
tasks.register<JavaExec>("raptorSmoke") {
    group = "verification"
    description = "Query di itinerario contro un bundle .ftb reale."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.antigravity.fluidtransit.bundler.RaptorSmokeKt"
    maxHeapSize = "1g"
}

// Il geocoding offline: dagli estratti OSM al file luoghi.bin.
tasks.register<JavaExec>("places") {
    group = "build"
    description = "Costruisce luoghi.bin dai geojsonseq di osmium."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.antigravity.fluidtransit.bundler.BuildPlacesKt"
    maxHeapSize = "3g"
}

tasks.register<JavaExec>("placesSmoke") {
    group = "verification"
    description = "Ricerche di prova contro un luoghi.bin reale."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.antigravity.fluidtransit.bundler.PlacesSmokeKt"
    maxHeapSize = "1g"
}
