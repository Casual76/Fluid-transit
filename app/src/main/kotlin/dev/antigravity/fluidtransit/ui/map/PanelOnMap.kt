package dev.antigravity.fluidtransit.ui.map

/**
 * Cosa deve stare acceso SULLA MAPPA perche' un pannello abbia senso.
 *
 * Il pannello sopravvive a una ricreazione dell'Activity — il telefono che
 * passa al tema scuro al tramonto, una rotazione — e a una visita ad Avvisi o
 * a Oggi, perche' e' salvabile. La mappa no: il controller nasce vuoto. Si
 * tornava sulla mappa con la scheda della linea 23 al suo posto e la rete
 * intera dietro, senza la tratta accesa, senza le sue fermate, con tutti i
 * bus disegnati e quello seguito non ingrandito. La modalita' linea, il bus
 * scelto e il segnaposto li accendeva solo chi apriva il pannello, non chi lo
 * ritrovava: quello che il pannello implica sulla mappa si deriva dal
 * pannello, cosi' vale in tutt'e due i casi.
 *
 * I viaggi (elenco e dettaglio) non ci sono di proposito: il dettaglio si
 * riaccende da solo dal suo pannello, e il segnaposto di un elenco puo' non
 * essere mai stato acceso — dipende da come ci si e' arrivati, e il pannello
 * non lo dice.
 */
internal class PanelOnMap(
    /** La linea da accendere, o -1 se il pannello non ne implica una. */
    val routeIndex: Int,
    /** Il bus da tenere in evidenza, se il pannello e' la scheda di una corsa. */
    val vehKey: Int?,
    /** Il luogo da segnare, se il pannello e' un luogo. */
    val place: PlaceRef?,
) {
    companion object {
        val NONE = PanelOnMap(-1, null, null)

        fun of(panel: Panel?): PanelOnMap = when (panel) {
            is Panel.RouteMini -> PanelOnMap(panel.routeIndex.coerceAtLeast(-1), null, null)
            is Panel.RouteFull -> PanelOnMap(panel.routeIndex.coerceAtLeast(-1), null, null)
            // Una corsa la cui linea il bundle non riconosce ha il bus da
            // evidenziare e nessuna tratta da accendere.
            is Panel.TripMini -> PanelOnMap(panel.ref.routeIndex.coerceAtLeast(-1), panel.ref.vehKey, null)
            is Panel.TripFull -> PanelOnMap(panel.ref.routeIndex.coerceAtLeast(-1), panel.ref.vehKey, null)
            is Panel.Place -> PanelOnMap(-1, null, panel.ref)
            else -> NONE
        }
    }
}
