package dev.antigravity.fluidtransit.data.rt

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "Stato dei dati" scriveva l'eta' dell'ultimo giro come se fosse di adesso:
 * lasciata la mappa a "40 s", dieci minuti dopo la schermata diceva ancora
 * "40 s". L'eta' di adesso e' quella dell'ultimo giro piu' il tempo trascorso
 * da quando e' stata calcolata — e quel "quando" NON e' `lastSuccessAt`, che
 * non avanza nei giri col feed fermo e conterebbe l'intervallo due volte.
 */
class StatusAgeTest {

    private val t0 = Instant.ofEpochSecond(1_700_000_000L)

    private fun status(age: Long?, polledAt: Instant?, lastSuccessAt: Instant? = null) =
        RealtimeClient.Status(
            source = RealtimeClient.Source.PROXY,
            feedAgeSeconds = age,
            lastSuccessAt = lastSuccessAt,
            lastError = null,
            vehicleCount = 0,
            delayCount = 0,
            polledAt = polledAt,
        )

    @Test
    fun `l'eta' cresce col tempo trascorso dall'ultimo giro`() {
        val s = status(age = 40, polledAt = t0)
        assertEquals(40L, s.ageAt(t0))
        assertEquals(40L + 600, s.ageAt(t0.plusSeconds(600)))
    }

    @Test
    fun `col feed fermo il tempo non si conta due volte`() {
        // L'origine e' ferma da 18 minuti e il giro appena fatto lo dice:
        // l'eta' gia' comprende quei 18 minuti. Il giro e' di 5 s fa, mentre
        // lastSuccessAt e' di 18 minuti fa (non avanza coi giri "fermo").
        val s = status(
            age = 18 * 60,
            polledAt = t0,
            lastSuccessAt = t0.minusSeconds(18 * 60),
        )
        assertEquals(18L * 60 + 5, s.ageAt(t0.plusSeconds(5)))
    }

    @Test
    fun `senza eta' non si inventa un numero`() {
        assertNull(status(age = null, polledAt = t0).ageAt(t0.plusSeconds(100)))
    }

    @Test
    fun `senza l'istante del giro si dice quello che si sa`() {
        assertEquals(40L, status(age = 40, polledAt = null).ageAt(t0.plusSeconds(100)))
    }

    @Test
    fun `un orologio tornato indietro non da' un'eta' negativa`() {
        assertEquals(40L, status(age = 40, polledAt = t0).ageAt(t0.minusSeconds(30)))
    }
}
