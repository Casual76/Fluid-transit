package dev.antigravity.fluidtransit.ai.orchestrator

/**
 * Cosa si puo' chiedere all'assistente, in tre esempi.
 *
 * A vuoto l'overlay diceva solo "Chiedimi qualcosa", e chi aveva appena messo
 * la chiave non sapeva che l'assistente capisce i mezzi: cerca, calcola
 * viaggi, guarda dove sono i bus. Gli esempi sono domande vere, una per
 * famiglia di strumenti (orari, viaggio, dal vivo), scritte come le direbbe
 * una persona; toccarle le fa partire.
 */
object AssistantHints {
    val EXAMPLES: List<String> = listOf(
        "Quando passa il prossimo bus da qui?",
        "Come arrivo alla stazione?",
        "Dov'e' il 6 adesso?",
    )
}
