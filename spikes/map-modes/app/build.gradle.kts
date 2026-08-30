plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.antigravity.fluidtransit.spike.map"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.antigravity.fluidtransit.spike.map"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release { isMinifyEnabled = false }
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

// Le versioni sono le stesse che usa il Fluid Engine (`versions.gradle`):
// il prototipo deve compilare dentro l'app vera senza negoziare dipendenze.
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.5.0-alpha16")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    implementation("org.maplibre.gl:android-sdk:11.11.0")
    // MapLibre porta con se' OkHttp. Usare lo stesso client e' cio' che rende
    // possibile sia misurare il traffico sia, in Fase 3, metterci una cache.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
