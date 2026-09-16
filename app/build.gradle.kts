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

// Crashlytics, con lo stesso patto della firma qui sopra: c'e' se c'e' il
// file, e se non c'e' il build non se ne accorge.
//
// Serve perche' la release e' offuscata (isMinifyEnabled) e la distribuzione
// passa dal Pampa Store, non dal Play Store: di un crash sul telefono di
// qualcuno, oggi, non resta assolutamente niente. Il plugin carica da se' il
// file di mapping di R8, quindi gli stack trace arrivano leggibili.
val googleServicesFile = rootProject.file("app/google-services.json")
if (googleServicesFile.exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

android {
    namespace = "dev.antigravity.fluidtransit"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.antigravity.fluidtransit"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "1.2.0"

        ndk {
            // MapLibre porta le librerie native per quattro ABI. Le due x86
            // esistono per gli emulatori: su un telefono vero non le carica
            // nessuno, e pesano 22,5 dei 50 MB dell'APK. Chi sviluppa usa la
            // build di debug, che non passa di qui.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
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

    // I test di :app girano sulla JVM, non su un device: coprono il codice
    // che non tocca Android (il lettore dello snapshot, il match contro il
    // bundle, la macchina a stati del client realtime). Le poche chiamate al
    // framework che restano tornano il valore di default invece di lanciare,
    // cosi' non serve Robolectric per verificare logica che di Android non sa
    // niente.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    /**
     * Lint come cancello, non come rumore.
     *
     * Gli ERRORI fermano la build: sono difetti veri, e il primo che ha
     * trovato — una chiamata alla posizione senza controllare il permesso,
     * dentro il provider che espone gli strumenti ad altre app — era una
     * SecurityException in attesa di succedere.
     *
     * Gli avvisi restano avvisi: dicono cose vere ma non urgenti (usa
     * l'estensione KTX, targetCellWidth vale solo da API 31) e trasformarli
     * in errori vorrebbe dire o ignorarli tutti o rincorrerli.
     *
     * `GradleDependency` invece si spegne: nomina versioni nuove di librerie
     * che qui sono pinnate apposta — due version catalog che litigano, vedi
     * CLAUDE.md — e un avviso che non si puo' seguire e' solo rumore.
     */
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        disable += "GradleDependency"
    }
}

dependencies {
    // Fluid Engine. engine-ui esporta Compose, Material 3 ed engine-foundation
    // come api: non vanno ridichiarati.
    implementation(project(":engine-ui"))
    implementation(project(":engine-storage")) // impostazioni su DataStore
    implementation(project(":engine-config")) // feature flag remoti, kill switch
    implementation(project(":engine-net")) // HTTP minimale per il manifest
    // engine-update: la prima release stabile c'e', quindi l'app deve sapersi
    // aggiornare da sola. Il modulo dichiara da se' REQUEST_INSTALL_PACKAGES e
    // il receiver dell'installazione: qui non serve toccare il manifest.
    implementation(project(":engine-update"))
    implementation(project(":engine-widget")) // i widget Glance della Fase 6
    implementation(project(":engine-ai")) // i tipi dei tool per il bridge
    implementation(project(":engine-ai-bridge")) // tool federati: gli autobus per PampAI/Aria

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

    // Crashlytics e basta: niente Analytics. Non serve a raccogliere i
    // crash, e' tracciamento che nessuno ha chiesto, e sono megabyte in
    // piu' su un APK che abbiamo appena dimezzato.
    if (googleServicesFile.exists()) {
        implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
        implementation("com.google.firebase:firebase-crashlytics")
    }

    // I test. Le versioni sono quelle dell'engine (engine/versions.gradle),
    // come tutto il resto qui dentro. MockWebServer e' lo stesso OkHttp gia'
    // in dipendenza: serve a mettere RealtimeClient davanti a un proxy che
    // sbaglia, che tace, o che risponde 304, senza toccare la rete vera.
    testImplementation("junit:junit:4.13.2")
    // La rete di prova di :core-routing: quattro fermate, una linea, due
    // corse, scritte in un vero .ftb. Serve ai test che devono agganciare il
    // realtime a degli orari, e senza questa riga l'unica strada era una
    // seconda copia del formato scritto a mano.
    testImplementation(testFixtures(project(":core-routing")))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // `org.json` vero nei test unitari.
    //
    // Nell'android.jar per i test e' uno stub: ogni metodo solleva, e con
    // `isReturnDefaultValues` torna zero o null. Gli archivi dell'utente —
    // stelle, routine, posti salvati — sono JSON, quindi senza questa riga
    // non si possono provare affatto, e sono l'unica cosa nell'app che
    // l'utente ha creato a mano.
    testImplementation("org.json:json:20240303")
}
