package dev.antigravity.fluidtransit.pampai

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import dev.antigravity.fluidtransit.FluidTransitApp
import dev.antigravity.fluidtransit.ai.tools.ActionOutcome
import dev.antigravity.fluidtransit.ai.tools.ActionSink
import dev.antigravity.fluidtransit.ai.tools.AssistantAction
import dev.antigravity.fluidtransit.ai.tools.ToolContext
import dev.antigravity.fluidtransit.ai.tools.ToolGroup
import dev.antigravity.fluidtransit.ui.map.SearchIndex
import dev.antigravity.fluidengine.ai.bridge.AiToolHostProvider
import dev.antigravity.fluidengine.ai.bridge.ReadyState
import dev.antigravity.fluidengine.ai.bridge.RemoteCall
import dev.antigravity.fluidengine.ai.tools.AiTool as EngineTool
import dev.antigravity.fluidengine.ai.tools.AiToolGroup
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import dev.antigravity.fluidengine.ai.tools.ToolRegistry
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** I gruppi degli autobus nel vocabolario dell'engine: stessi id, stessi suggerimenti. */
private enum class BridgeGroup(
  override val id: String,
  override val statusKey: String,
  override val hint: String,
  override val loadsWithCategory: Boolean = false,
) : AiToolGroup {
  PLACES("luogo", "places", "fermate, linee, luoghi e indirizzi, i posti salvati, le fermate vicine"),
  SCHEDULE("orari", "schedule", "quando passa un mezzo: prossimi passaggi, orari di una linea o di una fermata in un giorno, il prossimo bus verso un posto", loadsWithCategory = true),
  LIVE("live", "live", "cosa succede adesso: dov'e' un bus, quanto ritardo ha, avvisi di servizio, stato dei dati dal vivo"),
  JOURNEY("viaggio", "journey", "come si va da un posto a un altro, e a che ora conviene uscire", loadsWithCategory = true),
  APP("app", "app", "azioni nell'app: mostrare sulla mappa, navigazione, posti salvati, stelle, preferiti, stato dei dati offline"),
  ROUTINE("routine", "routine", "le routine: i viaggi ricorrenti dell'utente, con giorni e ora"),
  ;

  companion object {
    fun of(group: ToolGroup): BridgeGroup = entries.first { it.id == group.id }
  }
}

/**
 * Uno strumento di Fluid Transit visto dall'engine: la firma tradotta (testo dentro, [ToolOutput]
 * fuori), il resto identico. I ventisette strumenti restano quelli dell'assistente di casa.
 */
private class BridgedTool(private val inner: dev.antigravity.fluidtransit.ai.tools.AiTool) : EngineTool<ToolContext> {
  override val name: String = inner.name
  override val group: AiToolGroup = BridgeGroup.of(inner.group)
  override val description: String = inner.description
  override val parameters: JsonObject = inner.parameters
  override val isAction: Boolean = inner.group == ToolGroup.APP || inner.group == ToolGroup.ROUTINE
  override val needsConfirmation: Boolean = inner.name in CONFIRMED

  override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
    val text = inner.run(args, ctx)
    return if (text.startsWith("errore")) ToolOutput.error(text.removePrefix("errore:").trim()) else ToolOutput(text)
  }

  private companion object {
    val CONFIRMED = setOf(
      "avvia_navigazione", "salva_posto", "metti_stella", "crea_routine",
      "togli_stella", "posto_rimuovi", "routine_modifica", "routine_rimuovi", "stato_dati",
    )
  }
}

/**
 * Gli autobus visti da PampAI/Aria. Il punto delicato e' che l'assistente interno vive dentro la
 * mappa: l'indice di ricerca e la posizione arrivavano da li'. Chiamati da fuori, con l'app
 * chiusa, quelli non ci sono — quindi l'indice si costruisce qui la prima volta che serve, e la
 * posizione la si chiede al sistema.
 */
class TransitToolHostProvider : AiToolHostProvider<ToolContext>() {

  private val app: FluidTransitApp get() = context!!.applicationContext as FluidTransitApp

  private val bridged: ToolRegistry<ToolContext> by lazy {
    val tools = dev.antigravity.fluidtransit.ai.tools.AllTools.registry().tools.map { BridgedTool(it) }
    ToolRegistry(tools, BridgeGroup.entries.toList(), actionGroup = null)
  }

  override fun registry(): ToolRegistry<ToolContext> = bridged

  override suspend fun context(call: RemoteCall): ToolContext {
    ensureSearchIndex()
    ensureLocation()
    return ToolContext(
      transit = app.assistantBridge,
      locale = Locale.getDefault(),
      zone = ZoneId.systemDefault(),
      nowMillis = System.currentTimeMillis(),
      actionsEnabled = true,
      actions = BridgeActionSink(app, context!!),
    )
  }

  /** L'indice di fermate e linee: lo costruisce la mappa, ma se non e' mai stata aperta lo facciamo qui. */
  private suspend fun ensureSearchIndex() {
    if (app.assistantBridge.searchIndex != null) return
    val reader = app.assistantBridge.reader ?: return
    val index = withContext(Dispatchers.Default) { runCatching { SearchIndex.build(reader) }.getOrNull() }
    if (index != null && app.assistantBridge.searchIndex == null) app.assistantBridge.searchIndex = index
  }

  /**
   * La posizione: la mappa la prende da MapLibre, che qui non c'e'. L'ultima nota del sistema
   * basta per "la fermata piu' vicina", e se manca il permesso resta semplicemente null.
   */
  private fun ensureLocation() {
    if (app.assistantBridge.location != null) return
    val manager = context?.getSystemService(LocationManager::class.java) ?: return
    app.assistantBridge.location = {
      runCatching {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
          .filter { manager.isProviderEnabled(it) }
          .mapNotNull { manager.getLastKnownLocation(it) }
          .maxByOrNull { it.time }
          ?.let { it.latitude to it.longitude }
      }.getOrNull()
    }
  }

  override fun domain(): String = "bus"

  override fun appLabel(): String = "Fluid Transit"

  override fun routerHint(): String =
    "gli autobus e i mezzi pubblici: quando passa il prossimo, le fermate vicine, dov'e' un bus adesso, come si arriva da un posto a un altro, a che ora uscire, avvisi di servizio"

  /** I posti salvati e le linee preferite: le parole che l'utente dice a voce. */
  override fun vocabulary(): List<String> = runCatching {
    app.assistantBridge.savedPlacesWithId().map { it.label } + app.assistantBridge.starredRoutes().map { it.shortName }
  }.getOrDefault(emptyList())

  override fun ready(): ReadyState {
    val reader = app.assistantBridge.reader
    return if (reader != null) ReadyState(true) else ReadyState(false, "l'orario degli autobus non e' ancora scaricato: apri Fluid Transit una volta")
  }

  override fun partsAuthority(): String? = null
}

/** Le azioni chieste da fuori: gia' confermate. Quelle che vogliono la mappa la portano davanti. */
private class BridgeActionSink(private val app: FluidTransitApp, private val context: Context) : ActionSink {
  override suspend fun perform(action: AssistantAction): ActionOutcome {
    val done = app.assistantBridge.execute(action)
    if (action is AssistantAction.ShowPlace || action is AssistantAction.ShowStop || action is AssistantAction.ShowRoute ||
      action is AssistantAction.ShowJourneys || action is AssistantAction.StartNavigation
    ) {
      context.packageManager.getLaunchIntentForPackage(context.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let {
        runCatching { context.startActivity(it) }
      }
    }
    return if (done) ActionOutcome.DONE else ActionOutcome.UNAVAILABLE
  }
}
