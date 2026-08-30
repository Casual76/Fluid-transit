plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.antigravity.fluidtransit.spike.pmtiles"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.antigravity.fluidtransit.spike.pmtiles"
        // Gli stessi livelli dell'app vera: su Android 10 il vetro ricade su
        // superficie solida, ma la mappa deve funzionare identica.
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("org.maplibre.gl:android-sdk:11.11.0")
    // MapLibre porta con se' OkHttp: e' lo stesso client che in Fase 4 servira'
    // per PMTiles e realtime, quindi l'intercettore qui misura esattamente il
    // traffico che l'app vera produrra'.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
