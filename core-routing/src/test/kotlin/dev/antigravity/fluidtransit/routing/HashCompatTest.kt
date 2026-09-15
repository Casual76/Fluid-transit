package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Il ponte fra due linguaggi.
 *
 * `Ftb.hash64` (Kotlin, nel bundle) e `fnv64` (JavaScript, nel Worker) devono
 * dare lo stesso numero sulla stessa stringa: e' l'unica cosa che tiene
 * insieme il feed realtime e gli orari. Il `trip_id` non viaggia mai in
 * chiaro — viaggia il suo hash — quindi se le due implementazioni divergono
 * il sintomo non e' "l'hash e' cambiato", e' **nessun bus ha una linea**, e
 * si cerca la causa dappertutto tranne che qui.
 *
 * Gli stessi numeri sono inchiodati in `worker/test/snapshot.test.js`. I due
 * test insieme sono il contratto; separatamente non dimostrano niente.
 */
class HashCompatTest {

    @Test
    fun `hash64 da' i valori attesi, per sempre`() {
        assertEquals(-3750763034362895579L, Ftb.hash64(""))
        assertEquals(-5808556873153909620L, Ftb.hash64("a"))
        assertEquals(4968882906940724300L, Ftb.hash64("stopA"))
        assertEquals(-2162172861602763347L, Ftb.hash64("R1-morning"))
        assertEquals(-2081605873580141004L, Ftb.hash64("Autolinee Toscane"))
    }

    @Test
    fun `hash64 lavora sui byte UTF-8, non sui caratteri`() {
        // Le fermate toscane hanno gli accenti. Se i due lati contassero in
        // modo diverso, a sbagliare sarebbero solo quelle: un guasto a
        // macchie, che e' il peggior tipo di guasto da diagnosticare.
        assertEquals(-495594276946382062L, Ftb.hash64("città"))
        assertNotEquals(Ftb.hash64("città"), Ftb.hash64("citta"))
    }

    @Test
    fun `il valore vuoto e' l'offset di FNV-1a`() {
        assertEquals(Ftb.FNV_OFFSET, Ftb.hash64(""))
    }
}
