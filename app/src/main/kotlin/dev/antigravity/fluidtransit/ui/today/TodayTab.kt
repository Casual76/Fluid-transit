package dev.antigravity.fluidtransit.ui.today

import androidx.compose.runtime.Composable
import dev.antigravity.fluidengine.ui.fluid.FluidScreen

/**
 * La scheda "Oggi": routine, prossimi passaggi alle tue fermate, avvisi
 * sulle tue linee. Il contenuto arriva in Fase 6; fino ad allora resta
 * deliberatamente vuota — deciso cosi', perche' nessuna release esce prima
 * della fine delle fasi e nessuno la vedra' spenta.
 */
@Composable
fun TodayTab() {
    FluidScreen(title = "Oggi") {}
}
