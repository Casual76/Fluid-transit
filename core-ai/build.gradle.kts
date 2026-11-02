// L'assistente di Fluid Transit: il dominio sopra engine-ai.
//
// Il trasporto (provider, chiavi, stream, failover), il router, l'orchestratore,
// la voce e gli stati sono quelli dell'engine; qui restano gli strumenti dei
// trasporti, il prompt, la sessione con le sue azioni e le scelte di prima
// (alias delle chiavi, preferenze di partenza). Niente Compose ne' MapLibre:
// la UI vive in app/ui/assistant.
//
// Le versioni sono quelle dell'engine, come per :app: un ospite che negozia
// versioni diverse dai moduli che include non configura nemmeno.

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.antigravity.fluidtransit.ai"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Gli strumenti leggono il bundle: fermate, linee, orari, itinerari.
    api(project(":core-routing"))

    // L'assistente senza dominio. Porta come api DataStore, kotlinx-serialization
    // (solo il runtime: i JSON si navigano a mano) e le coroutine.
    api(project(":engine-ai"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
