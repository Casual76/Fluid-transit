package dev.antigravity.fluidtransit.ai.tools

/**
 * Il catalogo completo.
 *
 * Undici strumenti, divisi nei cinque gruppi che lo stadio 1 sceglie. Sono
 * pochi di proposito: ogni strumento in piu' e' descrizione da mandare a
 * ogni giro, e un modello che ha troppa scelta ne fa cattivo uso.
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
        ),
    )
}
