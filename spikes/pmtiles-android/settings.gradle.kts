pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Build indipendente: lo spike non fa parte dell'app. Serve a rispondere a
// una domanda sola - MapLibre Android apre davvero un `pmtiles://` remoto? -
// e in Fase 3 non ne resta niente se non la risposta.
rootProject.name = "pmtiles-spike"
include(":app")
