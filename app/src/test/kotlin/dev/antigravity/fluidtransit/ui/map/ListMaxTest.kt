package dev.antigravity.fluidtransit.ui.map

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quanto puo' essere alto un elenco dentro un pannello.
 *
 * Due difetti, gia' pagati tutti e due, e tutti e due di aritmetica.
 *
 * Il primo: senza un minimo, in orizzontale l'elenco chiedeva 121 dp di
 * un'area che ne aveva sessanta, e il pannello restava vuoto.
 *
 * Il secondo: la tastiera veniva tolta due volte — una qui, una come
 * spaziatura interna alla stessa scatola — e in verticale si vedevano due
 * righe e mezzo di otto, con sotto un pannello di vetro alto quanto la
 * tastiera.
 *
 * Le misure sono quelle vere di questo telefono: 800 dp in verticale, 411 in
 * orizzontale, 265 di tastiera.
 */
class ListMaxTest {

    private val schermoVerticale = 800.dp
    private val schermoOrizzontale = 411.dp
    private val tastiera = 265.dp
    private val riservaRicerca = 110.dp
    private val riservaLista = 290.dp

    @Test
    fun `senza tastiera si prende quello che si e' chiesto`() {
        assertEquals(
            480.dp,
            listMax(480.dp, schermoVerticale, ime = 0.dp, reserve = riservaRicerca),
        )
    }

    @Test
    fun `con la tastiera si prende quello che avanza`() {
        // 800 - 265 - 110 = 425, che e' meno dei 480 chiesti.
        assertEquals(
            425.dp,
            listMax(480.dp, schermoVerticale, ime = tastiera, reserve = riservaRicerca),
        )
    }

    @Test
    fun `la tastiera si sottrae una volta sola`() {
        // Il difetto: chi usava il risultato aggiungeva un `imePadding()`
        // sulla stessa scatola, togliendo la tastiera una seconda volta
        // dall'interno. Qui si dice la proprieta' per intero: fra "senza
        // tastiera" e "con tastiera" c'e' esattamente la tastiera, ne' piu'
        // ne' meno. Si chiede un'altezza assurda per non farsi tagliare dal
        // limite e misurare solo la sottrazione.
        val senza = listMax(10_000.dp, schermoVerticale, ime = 0.dp, reserve = riservaRicerca)
        val con = listMax(10_000.dp, schermoVerticale, ime = tastiera, reserve = riservaRicerca)
        assertEquals(tastiera, senza - con)
    }

    @Test
    fun `in orizzontale resta il minimo, non zero`() {
        // 411 - 265 = 146, meno 110 di riserva fa 36: sotto il minimo. Si
        // sacrifica quello che sta intorno e si tiene un elenco.
        val alto = listMax(480.dp, schermoOrizzontale, ime = tastiera, reserve = riservaRicerca)
        assertEquals(LIST_FLOOR, alto)
        assertTrue("l'elenco non puo' sparire", alto > 0.dp)
    }

    @Test
    fun `quando non c'e' proprio niente non si chiede piu' del niente`() {
        // Uno schermo piu' piccolo della tastiera non esiste, ma chiedere
        // 120 dp di uno spazio che ne ha 40 e' il difetto originale: si
        // otteneva un pannello vuoto invece di quaranta punti di elenco.
        assertEquals(40.dp, listMax(480.dp, 40.dp, ime = 0.dp, reserve = riservaLista))
        assertEquals(0.dp, listMax(480.dp, 200.dp, ime = 265.dp, reserve = riservaLista))
    }

    @Test
    fun `la scheda fermata, che e' la piu' carica, in verticale sta comoda`() {
        // 800 - 290 = 510, piu' dei 380 che il pannello chiede.
        assertEquals(
            380.dp,
            listMax(380.dp, schermoVerticale, ime = 0.dp, reserve = riservaLista),
        )
    }
}
