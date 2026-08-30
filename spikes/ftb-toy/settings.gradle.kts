// Build indipendente: lo spike non fa parte del build dell'app.
// In Fase 2 lettore e formato si spostano in :core-routing (Kotlin/JVM puro,
// nessuna dipendenza Android, perche' i golden test RAPTOR in CI girano su
// quello); il builder si sposta in tools/bundler. Qui restano solo per non
// mescolare uno spike con il codice di prodotto.
rootProject.name = "ftb-toy"
