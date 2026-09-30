package dev.antigravity.fluidtransit.ui.map

/**
 * Cosa fa il tocco sul mirino della posizione.
 *
 * Il tasto sapeva distinguere una cosa sola: il permesso dell'app c'e' o non
 * c'e'. Ma "dove sono" ha DUE interruttori, e il secondo e' quello di Android
 * — la Posizione spenta dal pannello rapido, che e' il caso comune. Con il
 * permesso concesso e la Posizione spenta, il tocco passava da libero a
 * segui, l'icona cambiava e la mappa restava ferma: non arrivava nessun
 * rilevamento, e nessuno diceva perche'. Un tasto che cambia faccia senza
 * fare niente e' lo stesso difetto del permesso negato per sempre, un
 * interruttore piu' in la'.
 *
 * Sta qui, e non dentro il click, perche' e' una decisione a tre rami e i
 * rami sono quattro combinazioni: si prova senza un telefono.
 */
internal sealed interface MirinoAction {

    /** Manca il permesso dell'app: si chiede. */
    data object ChiediPermesso : MirinoAction

    /** Il permesso c'e' ma la Posizione di Android e' spenta: si dice e si porta all'interruttore. */
    data object ApriImpostazioni : MirinoAction

    /** Tutto in ordine: il tasto cambia inquadratura. */
    data class Segui(val modo: FollowMode) : MirinoAction
}

/**
 * Il tocco sul mirino, dati il permesso, la Posizione di sistema e l'inquadratura di adesso.
 *
 * Con tutto in ordine l'inquadratura gira come prima: libero, segui,
 * bussola, e dalla bussola (o dal passo d'uomo della navigazione) di nuovo
 * segui.
 */
internal fun mirinoAction(
    permesso: Boolean,
    posizioneDiSistemaAccesa: Boolean,
    follow: FollowMode,
): MirinoAction = when {
    !permesso -> MirinoAction.ChiediPermesso
    !posizioneDiSistemaAccesa -> MirinoAction.ApriImpostazioni
    else -> MirinoAction.Segui(
        when (follow) {
            FollowMode.FREE -> FollowMode.FOLLOW
            FollowMode.FOLLOW -> FollowMode.COMPASS
            FollowMode.COMPASS, FollowMode.NAV_CAMMINO -> FollowMode.FOLLOW
        },
    )
}
