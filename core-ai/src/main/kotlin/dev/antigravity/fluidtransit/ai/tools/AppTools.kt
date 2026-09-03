package dev.antigravity.fluidtransit.ai.tools

import dev.antigravity.fluidtransit.ai.tools.Args.str
import kotlinx.serialization.json.JsonObject

/**
 * Le azioni nell'app.
 *
 * "Mostra subito, agisci con conferma": [ShowTool] porta la mappa dove
 * serve senza chiedere niente, perche' e' un gesto che si annulla guardando
 * altrove; tutto quello che SCRIVE — un posto salvato, una stella, una
 * routine — o che accende la navigazione passa da un tocco dell'utente.
 */
class ShowTool : AiTool {
    override val name = "mostra"
    override val group = ToolGroup.APP
    override val description =
        "Porta la mappa su una fermata, una linea o un luogo e ne apre la scheda. " +
            "Usalo quando l'utente vuole VEDERE qualcosa, non solo saperlo."
    override val parameters = Schema.obj(
        mapOf("cosa" to Schema.str("fermata, linea o luogo da mostrare")),
        required = listOf("cosa"),
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val q = args.str("cosa") ?: return "errore: manca cosa mostrare"
        val t = Resolve.target(ctx, q) ?: return "non trovo \"$q\""
        val action = when {
            t.stop != null -> AssistantAction.ShowStop(t.stop.idHashHex, t.stop.name)
            t.route != null -> AssistantAction.ShowRoute(t.route.routeIndex, t.route.shortName)
            else -> AssistantAction.ShowPlace(t.point)
        }
        return outcomeText(ctx.actions.perform(action), "mostrato sulla mappa: ${t.point.name}")
    }
}

class StartNavigationTool : AiTool {
    override val name = "avvia_navigazione"
    override val group = ToolGroup.APP
    override val description =
        "Avvia la navigazione passo passo verso una destinazione. Chiede conferma."
    override val parameters = Schema.obj(
        mapOf("a" to Schema.place),
        required = listOf("a"),
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val q = args.str("a") ?: return "errore: manca la destinazione"
        val t = Resolve.target(ctx, q) ?: return "non trovo \"$q\""
        return outcomeText(
            ctx.actions.perform(AssistantAction.StartNavigation(t.point)),
            "navigazione avviata verso ${t.point.name}",
        )
    }
}

class SavePlaceTool : AiTool {
    override val name = "salva_posto"
    override val group = ToolGroup.APP
    override val description =
        "Salva un posto con un'etichetta (Casa, Lavoro, Scuola o un nome libero). Chiede conferma."
    override val parameters = Schema.obj(
        mapOf(
            "nome" to Schema.str("l'etichetta con cui salvarlo"),
            "dove" to Schema.place,
        ),
        required = listOf("nome", "dove"),
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val label = args.str("nome") ?: return "errore: manca l'etichetta"
        val q = args.str("dove") ?: return "errore: manca il posto"
        val t = Resolve.target(ctx, q) ?: return "non trovo \"$q\""
        return outcomeText(
            ctx.actions.perform(AssistantAction.SavePlace(label, t.point)),
            "salvato come \"$label\"",
        )
    }
}

class StarTool : AiTool {
    override val name = "metti_stella"
    override val group = ToolGroup.APP
    override val description =
        "Mette una fermata o una linea fra i preferiti, cosi' compare in Preferiti e in Oggi. " +
            "Chiede conferma."
    override val parameters = Schema.obj(
        mapOf("cosa" to Schema.str("la fermata o la linea da stellare")),
        required = listOf("cosa"),
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val q = args.str("cosa") ?: return "errore: manca cosa stellare"
        val t = Resolve.target(ctx, q) ?: return "non trovo \"$q\""
        val action = when {
            t.stop != null -> AssistantAction.StarStop(t.stop.idHashHex, t.stop.name)
            t.route != null -> AssistantAction.StarRoute(t.route.routeIndex, t.route.shortName)
            else -> return "\"$q\" non e' una fermata ne' una linea: le stelle valgono solo per quelle"
        }
        return outcomeText(ctx.actions.perform(action), "aggiunto ai preferiti: ${t.point.name}")
    }
}

class CreateRoutineTool : AiTool {
    override val name = "crea_routine"
    override val group = ToolGroup.APP
    override val description =
        "Crea una routine: nei giorni scelti l'app calcola da sola quando uscire e avvisa. " +
            "Chiede conferma."
    override val parameters = Schema.obj(
        mapOf(
            "a" to Schema.place,
            "da" to Schema.str("da dove si parte; vuoto per dove si trova di solito"),
            "giorni" to Schema.str(
                "i giorni: \"feriali\", \"tutti\", \"weekend\", oppure elenco tipo \"lun,mer,ven\"",
            ),
            "ora" to Schema.str("l'ora di riferimento, formato 8:30"),
            "tipo" to Schema.str("cosa significa quell'ora", listOf("arriva", "parti")),
        ),
        required = listOf("a", "giorni", "ora"),
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val toText = args.str("a") ?: return "errore: manca la destinazione"
        val to = Resolve.target(ctx, toText) ?: return "non trovo \"$toText\""
        val from = args.str("da")?.let { Resolve.target(ctx, it) }
        val days = parseDays(args.str("giorni"))
        if (days.isEmpty()) return "non ho capito in che giorni: dimmi \"feriali\", \"tutti\" o l'elenco"
        val timeText = args.str("ora") ?: return "errore: manca l'ora"
        val minutes = parseMinutes(timeText) ?: return "non ho capito l'ora \"$timeText\""
        val anchor = if (args.str("tipo") == "parti") "depart" else "arrive"
        return outcomeText(
            ctx.actions.perform(
                AssistantAction.CreateRoutine(
                    label = to.point.name,
                    from = from?.point,
                    to = to.point,
                    days = days,
                    anchor = anchor,
                    anchorMinutes = minutes,
                ),
            ),
            "routine creata per ${to.point.name}",
        )
    }

    private fun parseDays(text: String?): Set<Int> {
        val t = text?.lowercase()?.trim() ?: return emptySet()
        return when {
            t.contains("ferial") -> setOf(1, 2, 3, 4, 5)
            t.contains("tutti") || t.contains("ogni giorno") -> setOf(1, 2, 3, 4, 5, 6, 7)
            t.contains("weekend") || t.contains("fine settimana") -> setOf(6, 7)
            else -> {
                val map = mapOf(
                    "lun" to 1, "mar" to 2, "mer" to 3, "gio" to 4,
                    "ven" to 5, "sab" to 6, "dom" to 7,
                )
                map.filterKeys { t.contains(it) }.values.toSet()
            }
        }
    }

    private fun parseMinutes(text: String): Int? {
        val parts = text.replace('.', ':').split(':')
        val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }
}
