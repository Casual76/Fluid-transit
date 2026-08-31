// :core-routing — formato .ftb, writer e lettore mmap. Kotlin/JVM puro.
//
// Niente Android qui dentro, per contratto: i golden test del bundle notturno
// girano in CI sulla JVM, e un solo import androidx li renderebbe impossibili.
// Il builder CSV->bundle sta in tools/bundler e dipende da questo modulo;
// l'app Android lo consuma cosi' com'e'.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
