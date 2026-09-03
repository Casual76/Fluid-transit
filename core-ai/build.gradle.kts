// L'assistente: provider, ciclo degli strumenti, chiavi, voce.
//
// Sta in un modulo suo e non dentro :app perche' non sa niente di Compose ne'
// di MapLibre: parla coi provider, esegue gli strumenti e produce stati. La
// UI vive in app/ui/assistant.
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

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Solo il runtime (JsonElement): i JSON dei provider si navigano a mano,
    // e ogni campo mancante e' una decisione, non una eccezione.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
