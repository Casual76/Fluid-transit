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

// Prototipo della mappa, non ancora l'app. Sta fuori dal build dell'app
// perche' l'app non esiste: nasce in Fase 2. Il codice dentro `map/` e' pero'
// scritto per essere spostato li' cosi' com'e' — Compose, nessuna dipendenza
// da questo progetto, niente scorciatoie da prototipo nella parte che conta.
rootProject.name = "map-modes"
include(":app")
