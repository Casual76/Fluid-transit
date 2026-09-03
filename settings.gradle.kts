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
include(":core-routing")
include(":bundler")
project(":bundler").projectDir = file("tools/bundler")

// L'app c'e' solo se c'e' l'engine: il job notturno del bundle fa il checkout
// senza submodule (gli serve solo :bundler) e senza questa guardia la sola
// configurazione di :app, che dipende da :engine-ui, farebbe fallire tutto.
// L'assistente ha bisogno solo di Android e core-routing, ma sta con :app:
// il job del bundle non lo compila.
if (file("engine/engine-ui").exists()) {
    include(":app")
    include(":core-ai")
}

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
