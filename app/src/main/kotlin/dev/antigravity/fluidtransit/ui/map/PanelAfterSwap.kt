package dev.antigravity.fluidtransit.ui.map

/**
 * Cosa succede a un pannello aperto quando cambia il bundle degli orari.
 *
 * Il guardiano buttava sempre tutto: pensato per un'app sfrattata dalla
 * memoria e ripresa dopo lo scambio notturno, girava anche con l'app in uso,
 * quando il nuovo bundle arriva 5-15 s dopo che si e' aperto qualcosa. Il
 * pannello spariva — una fermata, un luogo, l'elenco dei viaggi, che non
 * portano nessun indice del bundle — e, peggio, la mappa restava ridotta a
 * quella linea, a quelle fermate e a quel bus, con la tab bar tornata e nessun
 * modo visibile di uscirne. Qui si decide pannello per pannello.
 */
internal sealed interface PanelFate {
    /** Il pannello tiene solo hash o posti: e' valido anche nel bundle nuovo. */
    data object Keep : PanelFate

    /** Il pannello porta un indice che nel bundle nuovo non si ritrova: si torna alla mappa. */
    data object Drop : PanelFate

    /** Il pannello si riscrive con gli indici del bundle nuovo. */
    class Replace(val panel: Panel) : PanelFate
}

/**
 * Il destino di [panel] dopo uno scambio di bundle.
 *
 * - fermata, luogo, vicino, elenco viaggi: si tengono. Portano hash o
 *   [PlaceRef], e le loro schede si ricalcolano gia' sulla chiave del bundle;
 * - corsa: gli indici si ricavano di nuovo dagli hash ([findTrip] e
 *   [findRoute] rispondono -1 se il nuovo bundle non li conosce, come quando
 *   si apre un bus di cui il bundle non sa niente: la scheda lo regge);
 * - linea: il pannello ha solo l'indice, non l'hash, quindi non c'e' da cosa
 *   ritrovarla e si butta;
 * - dettaglio di un viaggio: il suo indice punta a un elenco che si ricalcola
 *   col bundle, quindi si torna all'elenco.
 */
internal fun panelAfterSwap(
    panel: Panel?,
    findTrip: (Long) -> Int,
    findRoute: (Long) -> Int,
): PanelFate = when (panel) {
    null -> PanelFate.Keep
    is Panel.Stop, is Panel.Place, Panel.Nearby, is Panel.Journeys -> PanelFate.Keep
    is Panel.TripMini -> PanelFate.Replace(Panel.TripMini(reresolve(panel.ref, findTrip, findRoute)))
    is Panel.TripFull -> PanelFate.Replace(Panel.TripFull(reresolve(panel.ref, findTrip, findRoute)))
    is Panel.RouteMini, is Panel.RouteFull -> PanelFate.Drop
    is Panel.JourneyDetail -> PanelFate.Replace(Panel.Journeys(panel.to))
}

private fun reresolve(ref: TripRef, findTrip: (Long) -> Int, findRoute: (Long) -> Int) = TripRef(
    vehKey = ref.vehKey,
    tripHash = ref.tripHash,
    routeHash = ref.routeHash,
    tripIndex = if (ref.tripHash != 0L) findTrip(ref.tripHash) else -1,
    routeIndex = if (ref.routeHash != 0L) findRoute(ref.routeHash) else -1,
)
