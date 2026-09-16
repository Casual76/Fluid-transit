// :core-routing — formato .ftb, writer e lettore mmap. Kotlin/JVM puro.
//
// Niente Android qui dentro, per contratto: i golden test del bundle notturno
// girano in CI sulla JVM, e un solo import androidx li renderebbe impossibili.
// Il builder CSV->bundle sta in tools/bundler e dipende da questo modulo;
// l'app Android lo consuma cosi' com'e'.
plugins {
    id("org.jetbrains.kotlin.jvm")
    // La rete di prova (`TestBundle`) serviva anche ai test di :app, che
    // hanno bisogno di un vero lettore di orari per provare l'aggancio del
    // realtime. Copiarla di la' voleva dire due copie del formato .ftb
    // scritto a mano, cioe' la cosa che TestBundle esiste per evitare.
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testFixturesImplementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
