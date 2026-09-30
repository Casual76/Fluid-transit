package dev.antigravity.fluidtransit.ui.widget

import androidx.compose.ui.unit.dp
import dev.antigravity.fluidtransit.routing.Certainty
import dev.antigravity.fluidtransit.routing.DepartureText
import dev.antigravity.fluidtransit.routing.NextDeparture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "visto 15 min fa" sulla riga compatta del widget.
 *
 * E' a larghezza fissa fra la destinazione e i minuti: in un 3x2 (190 dp, 146
 * utili) la riga ne chiedeva circa 180 e a uscire dal bordo erano i minuti,
 * cioe' il numero che serve. Sotto la soglia si tace la nota.
 */
class NotaEtaSulWidgetTest {

    private fun riga(age: Int) = NextDeparture(
        tripIndex = 1, patternIndex = 0, routeIndex = 0, stopIndex = 0, positionInPattern = 0,
        scheduledEpoch = 1_000_300L, delaySeconds = 120, certainty = Certainty.DECLARED,
        ageSeconds = age, canceled = false, skipped = false, monitored = true,
        line = "23", destination = "Careggi", colorRgb = 0x112233, stopName = "Piazza Alfa",
    )

    private val vecchio = riga(DepartureText.VECCHIO_SECONDS + 300)

    @Test
    fun `sul widget stretto la nota non c'e', per non tagliare i minuti`() {
        assertNull(notaEtaSulWidget(vecchio, mostraSottotitolo = false, larghezza = 190.dp))
        assertNull(notaEtaSulWidget(vecchio, mostraSottotitolo = false, larghezza = 239.dp))
    }

    @Test
    fun `sul compatto largo la nota c'e'`() {
        assertEquals(
            "visto 15 min fa",
            notaEtaSulWidget(vecchio, mostraSottotitolo = false, larghezza = 300.dp),
        )
    }

    @Test
    fun `dove il sottotitolo si disegna la nota non si ripete`() {
        assertNull(notaEtaSulWidget(vecchio, mostraSottotitolo = true, larghezza = 320.dp))
    }

    @Test
    fun `un numero fresco non ha nota`() {
        assertNull(notaEtaSulWidget(riga(60), mostraSottotitolo = false, larghezza = 320.dp))
    }
}
