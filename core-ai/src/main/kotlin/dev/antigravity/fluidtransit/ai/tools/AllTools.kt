package dev.antigravity.fluidtransit.ai.tools

/**
 * Il catalogo completo.
 *
 * Ventisette strumenti, divisi nei sei gruppi che lo stadio 1 sceglie: al
 * modello ne arrivano solo quelli dei gruppi scelti, mai tutti insieme —
 * ogni strumento in piu' e' descrizione da mandare a ogni giro.
 */
object AllTools {

    fun registry(): ToolRegistry = ToolRegistry(
        listOf(
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
        ) + scheduleExtraTools() + appExtraTools(),
    )
}
