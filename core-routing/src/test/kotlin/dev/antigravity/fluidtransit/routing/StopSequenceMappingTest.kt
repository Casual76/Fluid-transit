package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Lo scarto fra lo `stop_sequence` del feed e la posizione nel pattern.
 *
 * L'errore contro cui questi test esistono e' quello che NON si vede: i
 * minuti restano plausibili, solo appartengono a un'altra fermata. Meglio
 * rispondere "non lo so" e ripiegare sulla stima.
 */
class StopSequenceMappingTest {

    /** Una fermata per posizione, con hash finti e distinti. */
    private fun pattern(vararg ids: Int): (Int) -> Int = { p -> ids.getOrElse(p) { 0 } }

    @Test
    fun `il caso normale, sequenze da uno e pattern da zero`() {
        val p = pattern(11, 22, 33, 44, 55)
        val offset = StopSequenceMapping.offset(
            stopCount = 5,
            firstSeq = 3, firstStopId32 = 33,
            lastSeq = 5, lastStopId32 = 55,
            stopId32At = p,
        )
        assertEquals(1, offset)
        // Controprova: con questo scarto, la sequenza 3 e' la posizione 2.
        assertEquals(33, p(3 - offset!!))
    }

    @Test
    fun `una sequenza che parte da zero si riconosce lo stesso`() {
        // Non e' il caso di at, ma GTFS lo permette: se lo scarto si
        // verifica, e' quello giusto, e non serve sapere quale convenzione
        // usi l'origine.
        val offset = StopSequenceMapping.offset(
            stopCount = 4,
            firstSeq = 0, firstStopId32 = 11,
            lastSeq = 3, lastStopId32 = 44,
            stopId32At = pattern(11, 22, 33, 44),
        )
        assertEquals(0, offset)
    }

    @Test
    fun `una sequenza con salti si aggancia sugli estremi`() {
        // Il feed puo' numerare 10, 20, 30: gli estremi bastano a fissare lo
        // scarto solo se sono coerenti. Qui non lo sono, e si risponde null
        // invece di inventare.
        val offset = StopSequenceMapping.offset(
            stopCount = 3,
            firstSeq = 10, firstStopId32 = 11,
            lastSeq = 30, lastStopId32 = 33,
            stopId32At = pattern(11, 22, 33),
        )
        assertNull(offset, "10 e 30 non possono essere posizioni 0 e 2 con lo stesso scarto")
    }

    @Test
    fun `senza l'ancora di testa non si verifica niente`() {
        assertNull(
            StopSequenceMapping.offset(
                stopCount = 3,
                firstSeq = 1, firstStopId32 = 0,
                lastSeq = 3, lastStopId32 = 33,
                stopId32At = pattern(11, 22, 33),
            ),
        )
    }

    @Test
    fun `con una previsione sola basta l'ancora di testa`() {
        // Non c'e' un "in mezzo" da sbagliare: lo scarto trovato in testa e'
        // l'unica cosa che serve.
        assertEquals(
            1,
            StopSequenceMapping.offset(
                stopCount = 5,
                firstSeq = 2, firstStopId32 = 22,
                lastSeq = 2, lastStopId32 = 22,
                stopId32At = pattern(11, 22, 33, 44, 55),
            ),
        )
    }

    @Test
    fun `una fermata che compare due volte la decide la coda`() {
        // Una tratta ad anello passa due volte dalla stessa fermata: l'ancora
        // di testa da' due candidati, e solo uno porta anche la coda nel
        // posto giusto.
        val p = pattern(11, 22, 33, 22, 44)
        val offset = StopSequenceMapping.offset(
            stopCount = 5,
            firstSeq = 4, firstStopId32 = 22,
            lastSeq = 5, lastStopId32 = 44,
            stopId32At = p,
        )
        assertEquals(1, offset, "la seconda occorrenza e' quella giusta")
        assertEquals(22, p(4 - offset!!))
        assertEquals(44, p(5 - offset))
    }

    @Test
    fun `se l'ancora di testa non sta nel pattern si risponde non lo so`() {
        // La corsa e' stata risolta male, o il bundle e' cambiato sotto: in
        // entrambi i casi attribuire quel ritardo sarebbe peggio che non
        // attribuirlo.
        assertNull(
            StopSequenceMapping.offset(
                stopCount = 3,
                firstSeq = 1, firstStopId32 = 99,
                lastSeq = 3, lastStopId32 = 33,
                stopId32At = pattern(11, 22, 33),
            ),
        )
    }

    @Test
    fun `una coda fuori dal pattern invalida lo scarto`() {
        assertNull(
            StopSequenceMapping.offset(
                stopCount = 3,
                firstSeq = 1, firstStopId32 = 11,
                lastSeq = 90, lastStopId32 = 33,
                stopId32At = pattern(11, 22, 33),
            ),
        )
    }

    @Test
    fun `un pattern vuoto o una sequenza assurda non fanno saltare niente`() {
        assertNull(StopSequenceMapping.offset(0, 1, 11, 2, 22) { 0 })
        assertNull(StopSequenceMapping.offset(3, -1, 11, 2, 22, pattern(11, 22, 33)))
    }
}
