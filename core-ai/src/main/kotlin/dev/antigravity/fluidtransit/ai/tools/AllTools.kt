package dev.antigravity.fluidtransit.ai.tools

import dev.antigravity.fluidengine.ai.tools.ToolRegistry

/**
 * Il catalogo completo.
 *
 * Ventisette strumenti, divisi nei sei gruppi che lo stadio 1 sceglie: al
 * modello ne arrivano solo quelli dei gruppi scelti, mai tutti insieme —
 * ogni strumento in piu' e' descrizione da mandare a ogni giro.
 *
 * Il registro e' quello dell'engine: APP e' il gruppo che sparisce quando
 * l'utente spegne le azioni (ROUTINE resta: elencare le routine e' lettura,
 * e quelle che scrivono rispondono da se' che le azioni sono spente).
 */
object AllTools {

    /** Gli strumenti cosi' come sono: li usa anche il ponte verso PampAI. */
    fun tools(): List<TransitTool> = listOf(
        // luogo
        SearchTool(),
        SavedPlacesTool(),
        // orari
        NextDeparturesTool(),
        RouteScheduleTool(),
        // live
        LiveBusesTool(),
        AlertsTool(),
        // viaggio
        JourneyTool(),
        // app
        ShowTool(),
        StartNavigationTool(),
        SavePlaceTool(),
        StarTool(),
        CreateRoutineTool(),
    ) + scheduleExtraTools() + appExtraTools()

    /**
     * Il catalogo dell'assistente di casa: ogni strumento col tetto del suo
     * gruppo ([ToolLimits.timed]), che l'orchestratore dell'engine da solo
     * non sa mettere.
     */
    fun registry(clock: () -> Long = System::currentTimeMillis): ToolRegistry<ToolContext> = ToolRegistry(
        tools = tools().map { tool -> ToolLimits.timed(tool, clock) },
        groups = ToolGroup.entries,
        actionGroup = ToolGroup.APP,
    )
}
