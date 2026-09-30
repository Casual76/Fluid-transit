package dev.antigravity.fluidtransit.ui.widget

import androidx.compose.ui.unit.dp
import dev.antigravity.fluidtransit.routing.Ftb
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * L'ora del disegno sul widget piccolo.
 *
 * Sotto i 240 dp di larghezza (o i 150 di altezza) il kit dei widget nasconde
 * il sottotitolo, e con lui "Aggiornato alle 07:35": un 4x2 e' gia' cosi'. I
 * minuti restavano fermi all'istante del disegno, senza un'ora accanto, e su
 * una home che si ridisegna ogni cinque minuti quando va bene non c'era modo
 * di sapere quanto fossero vecchi.
 */
class OraDelDisegnoTest {

    private val settemezza: Long =
        ZonedDateTime.of(2026, 9, 30, 7, 35, 0, 0, Ftb.ROME).toEpochSecond()

    @Test
    fun `sul widget piccolo e largo come un 4x2 c'e' l'ora, a due cifre`() {
        assertEquals("alle 07:35", oraDelDisegno(settemezza, compact = true, larghezza = 250.dp))
    }

    @Test
    fun `sul widget grande non si ripete, perche' la dice gia' il sottotitolo`() {
        assertNull(oraDelDisegno(settemezza, compact = false, larghezza = 320.dp))
    }

    @Test
    fun `su un 2x2 il nome della fermata vale piu' dell'ora`() {
        assertNull(oraDelDisegno(settemezza, compact = true, larghezza = 110.dp))
    }

    @Test
    fun `la soglia di larghezza e' inclusiva`() {
        assertEquals("alle 07:35", oraDelDisegno(settemezza, compact = true, larghezza = 200.dp))
        assertNull(oraDelDisegno(settemezza, compact = true, larghezza = 199.dp))
    }

    @Test
    fun `un tabellone mai calcolato non inventa un'ora`() {
        // Lo zero e' "non lo so": scritto con l'orologio sarebbe "01:00".
        assertNull(oraDelDisegno(0L, compact = true, larghezza = 250.dp))
    }
}
