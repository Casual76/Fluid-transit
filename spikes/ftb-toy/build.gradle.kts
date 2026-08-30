plugins {
    kotlin("jvm") version "2.2.20"
    application
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.antigravity.fluidtransit.ftb.BuildToyBundleKt")
}

// Il rischio numero 5 del piano e' la memoria: 584 MB di CSV letti in modo
// ingenuo superano i 10 GB e il runner CI li uccide. Il builder e' scritto in
// streaming e con array primitivi apposta, e questo tetto basso e' li' per
// accorgersene subito se qualcuno introduce una struttura che accumula.
tasks.withType<JavaExec>().configureEach {
    maxHeapSize = "2g"
}

tasks.register<JavaExec>("verify") {
    group = "verification"
    description = "Riapre il .ftb prodotto e ne verifica il contenuto contro il GTFS di partenza."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.antigravity.fluidtransit.ftb.VerifyToyBundleKt")
    maxHeapSize = "1g"
}

tasks.test {
    useJUnitPlatform()
}
