// Fluid Transit — l'app del trasporto pubblico toscano.
//
// Tre tipi di modulo convivono qui:
//   :app           l'app Android (Compose + Fluid Engine + MapLibre)
//   :core-routing  Kotlin/JVM puro: formato .ftb e lettore. Nessuna dipendenza Android,
//                  perche' i golden test del bundle notturno girano in CI sulla JVM.
//   engine-*       i moduli del Fluid Engine, inclusi dalla cartella del submodule
//                  (blocco gestito da engine/tools/engine-install.ps1).
//
// Gli spike di Fase 1 (spikes/) hanno ciascuno il proprio build indipendente e
// deliberatamente NON sono inclusi qui: sono banchi di prova, non parte dell'app.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "FluidTransit"
include(":app")
include(":core-routing")
include(":bundler")
project(":bundler").projectDir = file("tools/bundler")

// --- fluid-engine (inizio) ---
val engineDir = file("engine")
if (engineDir.exists()) {
  listOf(
  "engine-foundation",
  "engine-ui",
  "engine-storage",
  "engine-net",
  "engine-config",
  "engine-update",
  "engine-widget"
  ).forEach { name ->
    include(":$name")
    project(":$name").projectDir = engineDir.resolve(name)
  }
}
// --- fluid-engine (fine) ---
