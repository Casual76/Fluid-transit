package dev.antigravity.fluidtransit.data.places

import dev.antigravity.fluidtransit.data.places.PlacesRefreshPolicy.Last
import dev.antigravity.fluidtransit.data.places.PlacesRefreshPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quando il file dei luoghi si prova a scaricare.
 *
 * Il difetto era un tentativo solo per processo: su dati mobili tornava in
 * silenzio, su un errore pure, e un Wi-Fi arrivato a app aperta non scaricava
 * niente. Con piu' inneschi (avvio, ritorno in primo piano, rete che compare,
 * giro dopo un errore) quello che tiene a bada i download e' questa soglia, e
 * quello che non deve mai fare e' far aspettare un'ora chi stava aspettando
 * proprio il Wi-Fi.
 */
class PlacesRefreshPolicyTest {

    private val minuto = 60_000L

    @Test
    fun `il primo tentativo e' sempre dovuto`() {
        assertTrue(PlacesRefreshPolicy.due(now = 0, lastAttemptAt = 0, last = Last.Never))
        assertTrue(PlacesRefreshPolicy.due(now = 1, lastAttemptAt = 1, last = Last.Never))
    }

    @Test
    fun `dopo un giro andato bene si aspetta un'ora`() {
        val t0 = 10 * minuto
        assertFalse(PlacesRefreshPolicy.due(now = t0 + 59 * minuto, lastAttemptAt = t0, last = Last.Fine))
        assertTrue(PlacesRefreshPolicy.due(now = t0 + 60 * minuto, lastAttemptAt = t0, last = Last.Fine))
    }

    @Test
    fun `dopo un errore si riprova dopo cinque minuti, non dopo un'ora`() {
        val t0 = 10 * minuto
        assertFalse(PlacesRefreshPolicy.due(now = t0 + 4 * minuto, lastAttemptAt = t0, last = Last.Failed))
        assertTrue(PlacesRefreshPolicy.due(now = t0 + 5 * minuto, lastAttemptAt = t0, last = Last.Failed))
    }

    @Test
    fun `un errore ricade sotto la soglia breve anche se il giro prima era andato bene`() {
        // `last` e' l'esito dell'ultimo tentativo, non del migliore.
        val t0 = 10 * minuto
        assertTrue(PlacesRefreshPolicy.due(now = t0 + 6 * minuto, lastAttemptAt = t0, last = Last.Failed))
        assertFalse(PlacesRefreshPolicy.due(now = t0 + 6 * minuto, lastAttemptAt = t0, last = Last.Fine))
    }

    @Test
    fun `due inneschi vicini fanno un solo download`() {
        // Il caso: l'avvio e il ritorno in primo piano arrivano insieme. Il
        // primo giro lavora e chiude a t0; il secondo, in coda sul mutex,
        // trova la soglia e torna senza toccare la rete.
        val t0 = 5_000L
        assertFalse(PlacesRefreshPolicy.due(now = t0 + 50, lastAttemptAt = t0, last = Last.Fine))
        assertFalse(PlacesRefreshPolicy.due(now = t0 + 50, lastAttemptAt = t0, last = Last.Failed))
    }

    @Test
    fun `senza rete non a consumo e senza file si aspetta il Wi-Fi`() {
        assertEquals(Verdict.WaitForWifi, PlacesRefreshPolicy.verdict(metered = true, ready = false))
        // "Non lo so": nessuna rete per noi. Anche li' si aspetta, e lo stato lo dice.
        assertEquals(Verdict.WaitForWifi, PlacesRefreshPolicy.verdict(metered = null, ready = false))
    }

    @Test
    fun `con il file in tasca la rete a consumo non dice niente`() {
        assertEquals(Verdict.Leave, PlacesRefreshPolicy.verdict(metered = true, ready = true))
        assertEquals(Verdict.Leave, PlacesRefreshPolicy.verdict(metered = null, ready = true))
    }

    @Test
    fun `su rete non a consumo si prova, col file o senza`() {
        assertEquals(Verdict.Attempt, PlacesRefreshPolicy.verdict(metered = false, ready = false))
        assertEquals(Verdict.Attempt, PlacesRefreshPolicy.verdict(metered = false, ready = true))
    }

    @Test
    fun `undici megabyte non partono mai su una rete che non sappiamo non a consumo`() {
        for (metered in listOf(true, null)) {
            for (ready in listOf(true, false)) {
                assertNotEquals(
                    "metered=$metered ready=$ready",
                    Verdict.Attempt,
                    PlacesRefreshPolicy.verdict(metered, ready),
                )
            }
        }
    }

    @Test
    fun `l'attesa del Wi-Fi non consuma la soglia`() {
        // Il tentativo che aspettava un Wi-Fi non ha toccato la rete, quindi
        // non lascia traccia: `last` resta Never e il Wi-Fi che arriva parte
        // subito, invece di aspettare un'ora per un tentativo mai fatto.
        assertTrue(PlacesRefreshPolicy.due(now = 100 * minuto, lastAttemptAt = 0, last = Last.Never))
    }
}
