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
     * Il catalogo dell'assistente di casa. Il tetto di tempo di ogni strumento
     * e' del suo gruppo ([ToolGroup.toolTimeoutMillis], [ToolGroup.awaitsConfirmation]):
     * l'orchestratore dell'engine lo legge da solo e ai gruppi che aspettano il
     * tocco dell'utente somma la finestra del cancello delle conferme.
     */
    fun registry(): ToolRegistry<ToolContext> = ToolRegistry(
        tools = tools(),
        groups = ToolGroup.entries,
        actionGroup = ToolGroup.APP,
    )
}
