// Fluid Transit — l'app.
//
// Le versioni sono quelle dell'engine (engine/versions.gradle): un ospite che
// negozia versioni diverse dai moduli che include non configura nemmeno.
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firma di release condivisa con le altre app Pampa Store (pampa.jks):
// il file coi segreti non e' versionato, si compila a mano in locale.
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "dev.antigravity.fluidtransit"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.antigravity.fluidtransit"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Una build di lavoro convive sul telefono con quella dello store.
            applicationIdSuffix = ".debug"
        }
        release {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Fluid Engine. engine-ui esporta Compose, Material 3 ed engine-foundation
    // come api: non vanno ridichiarati.
    implementation(project(":engine-ui"))
    implementation(project(":engine-storage")) // impostazioni su DataStore
    implementation(project(":engine-config")) // feature flag remoti, kill switch
    implementation(project(":engine-net")) // HTTP minimale per il manifest
    // engine-update arriva con la prima release sul Pampa Store: porta con se'
    // REQUEST_INSTALL_PACKAGES e non va incluso prima che serva.
    implementation(project(":engine-widget")) // i widget Glance della Fase 6

    // Il formato .ftb e il lettore mmap.
    implementation(project(":core-routing"))

    // L'assistente: provider, strumenti, voce. Niente Compose la dentro.
    implementation(project(":core-ai"))

    // Le icone estese (sole, mappa): artefatto stabile di soli vettori,
    // fuori dal BOM dal 2024, per questo la versione e' fissata qui.
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    // La mappa. MapLibre porta gia' OkHttp; la versione esplicita serve per
    // condividere il client (cache tile + trucchi PMTiles) con il resto.
    implementation("org.maplibre.gl:android-sdk:11.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
