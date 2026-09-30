package dev.antigravity.fluidtransit.ai.tools

import dev.antigravity.fluidtransit.routing.DaysText

/**
 * Cosa dice la scheda di conferma.
 *
 * Il `when` e' esaustivo e senza `else`, apposta: stava nella schermata con
 * un ramo finale "Confermi?", e sei azioni su undici ci finivano — "togli la
 * stella a Stazione" o "cancella la routine del lavoro" mostravano un
 * "Confermi?" nudo, senza dire cosa stava per sparire. E lo strumento che
 * toglie una stella sceglie la fermata per sottostringa e puo' prendere
 * quella sbagliata: il nome sulla scheda e' l'unico modo di accorgersene.
 * Senza l'`else`, un'azione nuova non compila finche' non ha la sua frase.
 */
object ActionText {

    fun confirm(action: AssistantAction): String = when (action) {
        is AssistantAction.StartNavigation ->
            "Avvio la navigazione verso ${action.to.name}?"

        is AssistantAction.SavePlace -> {
            val sposta = if (action.replaces != null) " Sostituisce quello che c'e' adesso." else ""
            "Salvo ${action.point.name} come \"${action.label}\"?$sposta"
        }

        is AssistantAction.StarStop -> "Metto la stella alla fermata ${action.name}?"
        is AssistantAction.StarRoute -> "Metto la stella alla linea ${action.shortName}?"
        is AssistantAction.CreateRoutine -> {
            // La partenza vuota vuol dire "dove ti trovi quando la crei": lo
            // si dice, perche' e' la parte che nessuno si aspetta.
            val da = action.from?.name?.let { "da $it" } ?: "da dove ti trovi adesso"
            val ancora = if (action.anchor == "depart") "partenza" else "arrivo"
            "Routine per ${action.to.name}: ${DaysText.label(action.days)}, " +
                "$ancora alle ${clock(action.anchorMinutes)}, $da?"
        }

        is AssistantAction.UnstarStop -> "Tolgo la stella alla fermata ${action.name}?"
        is AssistantAction.UnstarRoute -> "Tolgo la stella alla linea ${action.shortName}?"
        is AssistantAction.RemoveSavedPlace -> "Tolgo ${action.label} dai posti salvati?"
        is AssistantAction.SetRoutineEnabled ->
            (if (action.enabled) "Accendo" else "Spengo") + " la routine ${action.label}?"

        is AssistantAction.RemoveRoutine -> "Elimino la routine ${action.label}?"
        AssistantAction.RefreshData -> "Riscarico gli orari? Usa i dati del telefono."

        // Queste non chiedono conferma e la scheda non le mostra mai: la frase
        // c'e' lo stesso, cosi' il `when` resta senza `else` e chi cambia
        // `needsConfirmation` non trova un "Confermi?" nudo.
        is AssistantAction.ShowPlace -> "Mostro ${action.point.name} sulla mappa?"
        is AssistantAction.ShowStop -> "Mostro la fermata ${action.name}?"
        is AssistantAction.ShowRoute -> "Mostro la linea ${action.shortName}?"
        is AssistantAction.ShowJourneys -> "Mostro gli itinerari verso ${action.to.name}?"
        AssistantAction.StopNavigation -> "Fermo la navigazione?"
    }

    private fun clock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
}
