package dev.antigravity.fluidtransit.ai.orchestrator

import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidtransit.ai.tools.OpenTarget

/**
 * I chip che Fluid Transit capisce, fra quelli che il modello scrive a fine
 * risposta. L'engine li legge come `[[id]]` o `[[id:valore]]` e non sa cosa
 * siano; qui si dice quali valgono: un posto (`[[luogo:Nome]]`, o `place:`
 * quando il modello scivola in inglese) e una pagina dell'app (`[[mappa]]`).
 * Gli altri spariscono dal testo e basta.
 */
object TransitChips {

    /** Il filtro da dare a `AskInput.chipFilter`. */
    fun accepts(chip: AnswerChip): Boolean = place(chip) != null || page(chip) != null

    /** Il nome del posto, se il chip ne nomina uno. */
    fun place(chip: AnswerChip): String? =
        chip.value?.takeIf { chip.id == "luogo" || chip.id == "place" }

    /** La pagina dell'app, se il chip ne indica una. */
    fun page(chip: AnswerChip): OpenTarget? =
        if (chip.value == null) OpenTarget.fromId(chip.id) else null

    /**
     * I posti da mostrare sotto la risposta, senza doppioni: `[[luogo:X]]` e
     * `[[place:X]]` per l'engine sono due chip diversi, per chi legge lo
     * stesso posto.
     */
    fun places(chips: List<AnswerChip>): List<String> = chips.mapNotNull(::place).distinct()
}
