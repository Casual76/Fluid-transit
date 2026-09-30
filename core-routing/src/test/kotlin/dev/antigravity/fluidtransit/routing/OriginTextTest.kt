package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Dal centro della mappa" dice perche'. Il difetto: un nuovo utente, senza
 * aver mai dato il permesso, leggeva "GPS spento" — falso — e se il viaggio
 * partiva dalla campagna fra Siena e Colle gli si consigliava di cambiare
 * orario.
 */
class OriginTextTest {

    @Test
    fun `senza permesso si dice che la posizione non e' concessa, non che il GPS e' spento`() {
        val frase = OriginText.fromMapCenter(permissionGranted = false, systemLocationOn = true)
        assertEquals("Dal centro della mappa (posizione non concessa)", frase)
        assertFalse(frase.contains("GPS"))
    }

    @Test
    fun `col permesso e la posizione del telefono spenta lo dice`() {
        assertEquals(
            "Dal centro della mappa (posizione del telefono spenta)",
            OriginText.fromMapCenter(permissionGranted = true, systemLocationOn = false),
        )
    }

    @Test
    fun `con tutto in ordine il rilevamento e' solo in ritardo`() {
        assertEquals(
            "Il centro della mappa (posizione non ancora trovata)",
            OriginText.rowMapCenter(permissionGranted = true, systemLocationOn = true),
        )
    }

    @Test
    fun `il permesso mancante vince sulla posizione di sistema spenta`() {
        assertEquals(
            OriginText.why(false, true),
            OriginText.why(false, false),
        )
    }

    @Test
    fun `nessun viaggio dal centro della mappa dice prima la partenza e poi anche l'orario`() {
        val frase = OriginText.noJourneyDetail(fromMapCenter = true)
        assertTrue(frase.contains("centro della mappa"))
        assertTrue(frase.contains("cambiare orario"))
        assertTrue(OriginText.noJourneyDetail(fromMapCenter = false).contains("orario"))
    }

    @Test
    fun `sei gia' li' dal centro della mappa propone la via d'uscita giusta`() {
        assertTrue(OriginText.samePlaceDetail(true).contains("centro della mappa"))
        assertFalse(OriginText.samePlaceDetail(false).contains("centro della mappa"))
    }
}
