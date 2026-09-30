package dev.antigravity.fluidtransit.ui.map

import dev.antigravity.fluidtransit.data.places.PlacesManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Cosa dice la barra di ricerca quando gli indirizzi non ci sono.
 *
 * La frase era una sola, "si stanno ancora scaricando", e con il file che si
 * riprova da solo (WaitingForWifi, Failed) sarebbe stata falsa in due casi su
 * quattro: con la rete a consumo non parte niente, e dopo un errore il
 * download e' fermo fino al prossimo giro.
 */
class PlacesWaitTest {

    @Test
    fun `si dice che si sta scaricando solo se si sta scaricando`() {
        assertEquals(PlacesWait.DOWNLOADING, placesWaitOf(PlacesManager.State.Downloading))
        for (altro in listOf(
            PlacesManager.State.Missing,
            PlacesManager.State.WaitingForWifi,
            PlacesManager.State.Failed("timeout"),
        )) {
            assertNotEquals(PlacesWait.DOWNLOADING, placesWaitOf(altro))
        }
    }

    @Test
    fun `ogni ragione ha la sua frase`() {
        assertEquals(PlacesWait.WIFI, placesWaitOf(PlacesManager.State.WaitingForWifi))
        assertEquals(PlacesWait.FAILED, placesWaitOf(PlacesManager.State.Failed("timeout")))
        // Non provato o senza luoghi nell'ultimo aggiornamento: niente da promettere.
        assertEquals(PlacesWait.ABSENT, placesWaitOf(PlacesManager.State.Missing))
    }
}
