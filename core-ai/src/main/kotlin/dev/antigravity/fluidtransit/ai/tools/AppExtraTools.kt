package dev.antigravity.fluidtransit.ai.tools

import dev.antigravity.fluidtransit.ai.tools.Args.bool
import dev.antigravity.fluidtransit.ai.tools.Args.int
import dev.antigravity.fluidtransit.ai.tools.Args.list
import dev.antigravity.fluidtransit.ai.tools.Args.str
import kotlinx.serialization.json.JsonObject

/** Lo stato dell'orario offline, e con `aggiorna` lo riscarica (chiede conferma: consuma rete). */
class DataStatusTool : AiTool {
  override val name = "stato_dati"
  override val group = ToolGroup.APP
  override val description = "Se l'orario offline e' scaricato e aggiornato; con aggiorna=si lo riscarica (chiede conferma). Da usare quando gli orari sembrano vecchi o mancano."
  override val parameters = Schema.obj(mapOf("aggiorna" to Schema.bool("true per riscaricare l'orario")))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    if (args.bool("aggiorna") != true) {
      return ToolText.build {
        line("orario offline", ctx.transit.dataStatus())
        line("dati dal vivo", ctx.transit.realtimeStatus())
      }
    }
    if (!ctx.actionsEnabled) return ACTIONS_OFF
    val outcome = ctx.actions.perform(AssistantAction.RefreshData)
    if (outcome != ActionOutcome.DONE) return outcomeText(outcome, "")
    return "fatto: " + ctx.transit.refreshData()
  }
}

/** I preferiti dell'utente: fermate con la stella, linee con la stella, posti salvati. */
class FavouritesTool : AiTool {
  override val name = "preferiti_elenco"
  override val group = ToolGroup.APP
  override val description = "I preferiti dell'utente: le fermate e le linee con la stella, e i posti salvati con il loro nome."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val stops = ctx.transit.starredStops()
    val routes = ctx.transit.starredRoutes()
    val places = ctx.transit.savedPlacesWithId()
    if (stops.isEmpty() && routes.isEmpty() && places.isEmpty()) return "non ci sono preferiti: niente stelle e nessun posto salvato"
    return ToolText.build {
      if (stops.isNotEmpty()) line("fermate con la stella", stops.joinToString(", ") { it.name })
      if (routes.isNotEmpty()) line("linee con la stella", routes.joinToString(", ") { it.shortName })
      if (places.isNotEmpty()) line("posti salvati", places.joinToString(", ") { it.label })
    }
  }
}

/** Toglie la stella a una fermata o a una linea (con conferma). */
class UnstarTool : AiTool {
  override val name = "togli_stella"
  override val group = ToolGroup.APP
  override val description = "Toglie la stella a una fermata o a una linea che ce l'ha. Chiede conferma."
  override val parameters = Schema.obj(mapOf("cosa" to Schema.str("il nome della fermata o il numero della linea")), required = listOf("cosa"))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    if (!ctx.actionsEnabled) return ACTIONS_OFF
    val query = args.str("cosa")?.lowercase()?.trim() ?: return "errore: dimmi cosa"
    ctx.transit.starredStops().firstOrNull { it.name.lowercase().contains(query) }?.let { stop ->
      return outcomeText(ctx.actions.perform(AssistantAction.UnstarStop(stop.idHashHex, stop.name)), "fatto: stella tolta a ${stop.name}")
    }
    ctx.transit.starredRoutes().firstOrNull { it.shortName.lowercase() == query || it.shortName.lowercase().contains(query) }?.let { route ->
      return outcomeText(ctx.actions.perform(AssistantAction.UnstarRoute(route.idHashHex, route.shortName)), "fatto: stella tolta alla linea ${route.shortName}")
    }
    val have = (ctx.transit.starredStops().map { it.name } + ctx.transit.starredRoutes().map { it.shortName })
    return if (have.isEmpty()) "non c'e' nessuna stella da togliere" else "\"$query\" non ha la stella; ce l'hanno: ${have.joinToString(", ")}"
  }
}

/** Toglie un posto salvato (con conferma). */
class RemoveSavedPlaceTool : AiTool {
  override val name = "posto_rimuovi"
  override val group = ToolGroup.APP
  override val description = "Toglie un posto salvato dall'elenco (casa, lavoro, un indirizzo). Chiede conferma."
  override val parameters = Schema.obj(mapOf("nome" to Schema.str("il nome del posto salvato")), required = listOf("nome"))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    if (!ctx.actionsEnabled) return ACTIONS_OFF
    val query = args.str("nome")?.lowercase()?.trim() ?: return "errore: dimmi quale posto"
    val places = ctx.transit.savedPlacesWithId()
    if (places.isEmpty()) return "non ci sono posti salvati"
    val place = places.firstOrNull { it.label.lowercase() == query } ?: places.firstOrNull { it.label.lowercase().contains(query) }
      ?: return "\"$query\" non e' fra i posti salvati; ci sono: ${places.joinToString(", ") { it.label }}"
    return outcomeText(ctx.actions.perform(AssistantAction.RemoveSavedPlace(place.id, place.label)), "fatto: ${place.label} tolto dai posti salvati")
  }
}

/** Ferma la navigazione in corso. */
class StopNavigationTool : AiTool {
  override val name = "ferma_navigazione"
  override val group = ToolGroup.APP
  override val description = "Ferma la navigazione passo passo in corso."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    if (!ctx.actionsEnabled) return ACTIONS_OFF
    val running = ctx.transit.navigationLabel() ?: return "non c'e' nessuna navigazione in corso"
    return outcomeText(ctx.actions.perform(AssistantAction.StopNavigation), "fatto: navigazione fermata ($running)")
  }
}

/** Le routine: i viaggi ricorrenti che l'app calcola da sola prima che serva partire. */
class RoutineListTool : AiTool {
  override val name = "routine_elenco"
  override val group = ToolGroup.ROUTINE
  override val description = "Le routine dell'utente: destinazione, giorni, ora, se sono accese, e l'ultimo consiglio calcolato."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val routines = ctx.transit.routines()
    if (routines.isEmpty()) return "non ci sono routine: si creano con crea_routine"
    return ToolText.build {
      line("routine", routines.size)
      routines.forEach { r ->
        line(
          "#${r.id} ${r.label} → ${r.destination} · ${daysWords(r.days)} · ${if (r.anchor == "arrive") "arrivo" else "partenza"} alle ${"%02d:%02d".format(r.anchorMinutes / 60, r.anchorMinutes % 60)}" +
            (if (r.enabled) "" else " · spenta") + (r.lastAdvice?.takeIf { it.isNotBlank() }?.let { " · ultimo consiglio: $it" } ?: ""),
        )
      }
    }
  }
}

/** Accende, spegne o sposta l'ora di una routine (con conferma). */
class RoutineUpdateTool : AiTool {
  override val name = "routine_modifica"
  override val group = ToolGroup.ROUTINE
  override val description = "Accende o spegne una routine. Chiede conferma."
  override val parameters = Schema.obj(
    mapOf(
      "quale" to Schema.str("l'id della routine o parole del suo nome"),
      "attiva" to Schema.bool("true per accenderla, false per spegnerla"),
    ),
    required = listOf("quale", "attiva"),
  )

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    if (!ctx.actionsEnabled) return ACTIONS_OFF
    val routine = find(args.str("quale"), ctx) ?: return "routine non trovata: guarda routine_elenco"
    val enabled = args.bool("attiva") ?: return "errore: dimmi se accenderla o spegnerla"
    if (routine.enabled == enabled) return "la routine \"${routine.label}\" e' gia' ${if (enabled) "accesa" else "spenta"}"
    return outcomeText(
      ctx.actions.perform(AssistantAction.SetRoutineEnabled(routine.id, routine.label, enabled)),
      "fatto: routine \"${routine.label}\" ${if (enabled) "accesa" else "spenta"}",
    )
  }
}

/** Toglie una routine (con conferma). */
class RoutineRemoveTool : AiTool {
  override val name = "routine_rimuovi"
  override val group = ToolGroup.ROUTINE
  override val description = "Toglie una routine dall'elenco. Chiede conferma."
  override val parameters = Schema.obj(mapOf("quale" to Schema.str("l'id della routine o parole del suo nome")), required = listOf("quale"))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    if (!ctx.actionsEnabled) return ACTIONS_OFF
    val routine = find(args.str("quale"), ctx) ?: return "routine non trovata: guarda routine_elenco"
    return outcomeText(ctx.actions.perform(AssistantAction.RemoveRoutine(routine.id, routine.label)), "fatto: routine \"${routine.label}\" tolta")
  }
}

/** Una routine come la nomina il modello: per id o per parole del nome. */
private fun find(raw: String?, ctx: ToolContext): RoutineInfo? {
  val key = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
  val routines = ctx.transit.routines()
  key.removePrefix("#").toLongOrNull()?.let { id -> routines.firstOrNull { it.id == id }?.let { return it } }
  val lower = key.lowercase()
  return routines.firstOrNull { it.label.lowercase() == lower }
    ?: routines.firstOrNull { it.label.lowercase().contains(lower) || it.destination.lowercase().contains(lower) }
}

private fun daysWords(days: Set<Int>): String = when {
  days.isEmpty() -> "mai"
  days == setOf(1, 2, 3, 4, 5) -> "dal lunedi' al venerdi'"
  days.size == 7 -> "tutti i giorni"
  else -> days.sorted().joinToString(", ") { java.time.DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ITALIAN) }
}

fun appExtraTools(): List<AiTool> = listOf(
  DataStatusTool(), FavouritesTool(), UnstarTool(), RemoveSavedPlaceTool(), StopNavigationTool(),
  RoutineListTool(), RoutineUpdateTool(), RoutineRemoveTool(),
)
