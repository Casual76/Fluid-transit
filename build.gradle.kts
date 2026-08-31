// Radice del build. Le versioni dei plugin coincidono con quelle dell'engine
// (engine/versions.gradle): un ospite con plugin diversi dai moduli che include
// non configura nemmeno.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.20" apply false
}
