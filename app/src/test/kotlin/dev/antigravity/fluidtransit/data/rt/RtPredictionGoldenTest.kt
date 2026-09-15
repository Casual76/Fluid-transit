package dev.antigravity.fluidtransit.data.rt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il formato delle previsioni, fissato fra le due lingue.
 *
 * `worker/src/predictions.js` scrive la sezione e `RtPredictionCodec` la
 * legge. Le due implementazioni non condividono una riga di codice: se
 * divergessero, il sintomo non sarebbe un errore ma MINUTI SBAGLIATI, e si
 * cercherebbe la causa dappertutto tranne che nel formato. E' la stessa
 * ragione per cui gli hash sono inchiodati in `HashCompatTest.kt` e in
 * `worker/test/snapshot.test.js`.
 *
 * I byte qui sotto li ha prodotti il Worker davvero, da un feed finto ma
 * completo: una corsa normale con un ritardo che cambia, una cancellata, una
 * con una fermata saltata, una con un punto senza dati. Si rigenerano con
 * `node worker/tools/golden-predictions.mjs`, e vanno rigenerati solo se il
 * formato cambia DI PROPOSITO — se cambiano per caso, e' proprio quello che
 * questo test esiste per fermare.
 */
class RtPredictionGoldenTest {

    private val golden =
        "46545254020004202af1536500f153650400000028000000070000000600000030883b1c1921099d" +
            "f7e90b8f3531643ee86101000000000001000001008000002aab0ad92aab0ad97c9e9a3785c45ea1" +
            "32b5058f35245d3e907e0000010000000000010100800000000000000000000070346acb31fdf9b8" +
            "81274056511bec5f0474000001000000030000007800000011a60ad977a90ad93a253a2cc485e0c5" +
            "81274056511bec5fa893000004000000030000003c00000011a60ad9aba20ad90400000002ff0100" +
            "780000010300e2ff0001070088ff000001003c0000010200000001ff0300f1ff0001"

    private fun bytes(): ByteArray {
        val out = ByteArray(golden.length / 2)
        for (i in out.indices) {
            out[i] = golden.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private val set = RtPredictionCodec.parse(bytes())

    // Gli hash degli identificatori GTFS, calcolati dal Worker con `fnv64`.
    private val corsaNormale = -5117781111221898128L
    private val corsaCancellata = -6818796709349777796L
    private val corsaConSalto = -4188200575852468934L
    private val corsaSenzaDati = -7131132143232907216L
    private val linea6 = 6911929564260214657L

    @Test
    fun `la sezione si legge, con tutte le sue corse`() {
        assertEquals(1700000042L, set!!.generatedAt)
        assertEquals(1700000000L, set.feedTimestamp)
        assertTrue("la sezione non doveva essere troncata", !set.truncated)
        assertEquals(4, set.byTripHash.size)
    }

    @Test
    fun `una corsa normale porta i suoi punti, coi ritardi risommati`() {
        // Il feed dichiarava 120, 120, 90 e -30. Il Worker scarta la ripetizione
        // e manda le differenze; il lettore deve rimettere insieme gli originali.
        val t = set!!.byTripHash[corsaNormale]!!
        assertEquals(linea6, t.routeHash)
        assertEquals(8 * 3600 + 15 * 60, t.startTimeSec)
        assertEquals(0, t.direction)
        assertEquals(0, t.status)
        assertEquals(120, t.tripDelaySec)

        assertEquals(3, t.points.size)
        assertEquals(listOf(1, 3, 7), t.points.map { it.stopSeq })
        assertEquals(listOf(120, 90, -30), t.points.map { it.delaySec })
        assertTrue(t.points.all { !it.skipped && !it.noData })
        // Il primo punto viene dalla partenza, l'ultimo dall'arrivo.
        assertEquals(1, t.points.first().from)
        assertEquals(0, t.points.last().from)
    }

    @Test
    fun `le due ancore sono gli stop_id del primo e dell'ultimo punto`() {
        // Servono a VERIFICARE la mappatura fra stop_sequence e posizione nel
        // pattern, non a cercare niente: se il lettore le confondesse, la
        // verifica direbbe sempre di si' e non servirebbe a niente.
        val t = set!!.byTripHash[corsaNormale]!!
        assertEquals(3641353745.toInt(), t.firstStopId32) // fermata-a
        assertEquals(3641354615.toInt(), t.lastStopId32) // fermata-g
    }

    @Test
    fun `una corsa cancellata arriva dichiarata, e senza punti`() {
        val t = set!!.byTripHash[corsaCancellata]!!
        assertTrue("doveva essere cancellata", t.canceled)
        assertEquals(1, t.status)
        assertEquals(0, t.points.size)
        assertNull("nessun ritardo complessivo dichiarato", t.tripDelaySec)
        assertEquals(0, t.firstStopId32)
    }

    @Test
    fun `una fermata saltata resta saltata, e non si propaga come ritardo`() {
        val t = set!!.byTripHash[corsaConSalto]!!
        assertEquals(3, t.points.size)
        assertEquals(listOf(1, 2, 3), t.points.map { it.stopSeq })
        assertTrue("la seconda fermata e' saltata", t.points[1].skipped)
        assertTrue("le altre due no", !t.points[0].skipped && !t.points[2].skipped)
        assertEquals(listOf(60, 45), listOf(t.points[0].delaySec, t.points[2].delaySec))
    }

    @Test
    fun `un punto senza dati si dichiara tale`() {
        val t = set!!.byTripHash[corsaSenzaDati]!!
        assertEquals(1, t.points.size)
        assertTrue("doveva essere senza dati", t.points[0].noData)
        assertEquals(4, t.points[0].stopSeq)
        assertNull(t.tripDelaySec)
    }

    @Test
    fun `le corse oltre le 24 sopravvivono al viaggio`() {
        // "25:10:00" e' un orario GTFS legittimo: 2.709 corse del feed vero
        // passano le 24, e l'ultima finisce alle 30:10. Se il campo fosse
        // troppo stretto o si perdesse il modulo, quella corsa sparirebbe o
        // si sposterebbe di un giorno.
        val t = set!!.byTripHash[corsaSenzaDati]!!
        assertEquals(25 * 3600 + 10 * 60, t.startTimeSec)
    }
}
