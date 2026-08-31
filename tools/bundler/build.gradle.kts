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

tasks.test {
    useJUnitPlatform()
}
