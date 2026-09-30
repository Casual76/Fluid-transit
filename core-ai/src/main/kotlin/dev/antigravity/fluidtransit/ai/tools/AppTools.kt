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
        val t = Resolve.target(ctx, q) ?: return Resolve.notFound(ctx, "non trovo \"$q\"")
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
        val t = Resolve.target(ctx, q) ?: return Resolve.notFound(ctx, "non trovo \"$q\"")
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
        "Salva un posto con un'etichetta (Casa, Lavoro, Scuola o un nome libero). " +
            "Senza `dove` salva il posto in cui si trova l'utente adesso. Chiede conferma."
    override val parameters = Schema.obj(
        mapOf(
            "nome" to Schema.str("l'etichetta con cui salvarlo"),
            "dove" to Schema.place,
        ),
        // `dove` non e' obbligatorio: lo schema stesso dice che vuoto vale "dove si trova
        // adesso", e "salva questo posto come Casa" e' proprio quel caso.
        required = listOf("nome"),
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val label = args.str("nome") ?: return "errore: manca l'etichetta"
        val q = args.str("dove")?.takeUnless { Resolve.isHere(it) }
        val point = if (q == null) {
            // "Qui" e' la posizione e basta. Non si ripiega sul centro della mappa, come fa
            // Resolve.target: salverebbe un punto che l'utente stava solo guardando con il
            // nome "La tua posizione", e "Casa" finirebbe dove non abita.
            val here = ctx.transit.here
                ?: return "errore: non so dove ti trovi (la posizione non e' disponibile): " +
                    "chiedi all'utente un indirizzo o una fermata"
            NamedPoint("la tua posizione", "", here.first, here.second)
        } else {
            (Resolve.target(ctx, q) ?: return Resolve.notFound(ctx, "non trovo \"$q\"")).point
        }
        // Un'etichetta esiste una volta sola: salvarla di nuovo sposta quella che c'e', e la
        // scheda di conferma lo deve dire.
        val replaces = ctx.transit.savedPlaces()
            .firstOrNull { it.name.equals(label, ignoreCase = true) }?.name
        return outcomeText(
            ctx.actions.perform(AssistantAction.SavePlace(label, point, replaces)),
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
        val t = Resolve.target(ctx, q) ?: return Resolve.notFound(ctx, "non trovo \"$q\"")
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
        val to = Resolve.target(ctx, toText) ?: return Resolve.notFound(ctx, "non trovo \"$toText\"")
        // Una partenza detta e non trovata non diventa "dove ti trovi": la routine partirebbe
        // da un posto che nessuno ha nominato, e la conferma direbbe "da dove ti trovi".
        val fromText = args.str("da")?.takeUnless { Resolve.isHere(it) }
        val from = fromText?.let {
            Resolve.target(ctx, it) ?: return Resolve.notFound(ctx, "non trovo il punto di partenza \"$it\"")
        }
        val days = parseRoutineDays(args.str("giorni"))
        if (days.isEmpty()) return "non ho capito in che giorni: dimmi \"feriali\", \"tutti\", \"weekend\" o l'elenco"
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

    // La stessa lettura dell'ora di tutti gli strumenti: "8:30 di sera" non e' le 08:00.
    private fun parseMinutes(text: String): Int? =
        Resolve.clockOf(text)?.let { it.hour * 60 + it.minute }
}

/**
 * I giorni di una routine come li scrive il modello.
 *
 * Le parole si SOMMANO: "feriali e sabato" e' dal lunedi' al sabato. Prima la
 * prima parola che combaciava vinceva e le altre si perdevano, e chi dettava
 * "feriali e sabato" confermava una routine da lunedi' a venerdi' senza
 * accorgersene. Si leggono parole intere (non sottostringhe: "domani" non e'
 * la domenica, "giorno" non e' il giovedi'); una parola che non si capisce fa
 * tornare l'insieme vuoto e lo strumento chiede di ripetere, invece di creare
 * una routine su una parte soltanto di cio' che e' stato detto.
 */
internal fun parseRoutineDays(text: String?): Set<Int> {
    val t = text?.lowercase()?.replace('ì', 'i')?.trim() ?: return emptySet()
    val words = t.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptySet()
    val out = mutableSetOf<Int>()
    // Gli intervalli prima: "dal lunedi al venerdi", "lun-ven". Le parole dei
    // due estremi si rileggono poi da sole, e sono gia' dentro.
    val dayWord = "(lun|mar|mer|gio|ven|sab|dom)[a-z]*"
    Regex("(?:dal? )?$dayWord\\s*(?:-|al? )\\s*$dayWord").findAll(t).forEach { m ->
        val from = ROUTINE_DAY_PREFIX.getValue(m.groupValues[1])
        val to = ROUTINE_DAY_PREFIX.getValue(m.groupValues[2])
        if (from <= to) out += from..to
    }
    var i = 0
    while (i < words.size) {
        val w = words[i]
        when {
            w.startsWith("ferial") -> out += 1..5
            w == "tutti" || w == "tutte" || w == "sempre" -> out += 1..7
            w == "ogni" && words.getOrNull(i + 1)?.startsWith("giorn") == true -> {
                out += 1..7
                i++
            }
            w == "weekend" || w == "festivi" -> out += 6..7
            w == "fine" && words.getOrNull(i + 1) == "settimana" -> {
                out += 6..7
                i++
            }
            w in ROUTINE_FILLER -> Unit
            else -> {
                val day = ROUTINE_DAY_FULL.indexOfFirst { w.length >= 3 && it.startsWith(w) }
                if (day < 0) return emptySet()
                out += day + 1
            }
        }
        i++
    }
    return out
}

private val ROUTINE_DAY_PREFIX = mapOf(
    "lun" to 1, "mar" to 2, "mer" to 3, "gio" to 4, "ven" to 5, "sab" to 6, "dom" to 7,
)
private val ROUTINE_DAY_FULL =
    listOf("lunedi", "martedi", "mercoledi", "giovedi", "venerdi", "sabato", "domenica")
private val ROUTINE_FILLER =
    setOf("dal", "al", "a", "e", "i", "il", "le", "la", "di", "giorni", "giorno", "mattine", "ogni")
