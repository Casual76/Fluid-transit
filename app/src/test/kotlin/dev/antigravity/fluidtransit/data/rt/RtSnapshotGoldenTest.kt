package dev.antigravity.fluidtransit.data.rt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il formato CONGELATO, inchiodato fra le due lingue.
 *
 * `RtCodecTest` verifica che il lettore rilegga quello che gli si da' in
 * pasto, ma i byte se li scrive da solo: se il Worker cambiasse idea su un
 * campo, quel test resterebbe verde. Qui i byte li ha prodotti il Worker
 * davvero, e li rilegge l'app.
 *
 * E' il test piu' importante della coppia, perche' questo formato non e'
 * nostro da cambiare: la 1.1.0 installata sui telefoni verifica
 * `recordSize == 40` e `== 32` e legge i campi a offset fissi. Se il Worker
 * scrivesse un byte diverso, quelle copie smetterebbero di funzionare e non
 * ci sarebbe modo di aggiustarle — dal manifest si cambia il comportamento,
 * non il codice.
 *
 * Il feed finto da cui vengono contiene i casi che si sbagliano davvero: un
 * mezzo senza corsa, un rilevamento oltre i 360 gradi, un'eta' del fix che
 * sfonda il campo, un orario oltre le 24, un ritardo assurdo da saturare,
 * una corsa cancellata. Si rigenerano con `node worker/tools/golden-snapshot.mjs`,
 * e vanno rigenerati solo se il formato cambia DI PROPOSITO — se cambiano da
 * soli, e' proprio quello che questo test esiste per fermare.
 */
class RtSnapshotGoldenTest {

    private val veicoliHex =
        "46545254010001002af1536500f15365030000002800000070346acb31fdf9b881274056511bec5ffde59b0297b9ab00" +
            "89004800047400000000530065a86a7900000000000000000000000000000000203e68fdb05654ffffffffffffffffff" +
            "ff00ffff000000009bf71852666b7392f7e90b8f3531643ec0559c02c0d8a7000500feffe86101000101feffd1c68827"

    private val ritardiHex =
        "46545254010002002af15365f6f05365050000002000000070346acb31fdf9b881274056511bec5f0474000078000000" +
            "03000000000000007c9e9a3785c45ea132b5058f35245d3e907e000000000101ffff00000000000030883b1c1921099d" +
            "f7e90b8f3531643ee8610100000003ff040000000000000014ff11a8c27323ac81274056511bec5f7f510100007d0000" +
            "feff0000000000008a2a9126623188e481274056511bec5f00000000008300000000000000000000"

    private fun bytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    private val veicoli = RtCodec.parseVehicles(bytes(veicoliHex))
    private val ritardi = RtCodec.parseDelays(bytes(ritardiHex))

    // Gli hash degli identificatori GTFS, calcolati dal Worker con `fnv64`.
    private val corsaNormale = -5117781111221898128L
    private val corsaNotturna = -7893847634642143333L
    private val corsaCancellata = -6818796709349777796L
    private val corsaSenzaDati = -7131132143232907216L
    private val corsaImpossibile = -6042858995120472300L
    private val corsaAnticipata = -1979277738605729142L
    private val linea6 = 6911929564260214657L
    private val linea37 = 4495772434125548023L

    @Test
    fun `la sezione dei veicoli si legge, con le sue due date`() {
        assertEquals(1700000042L, veicoli.generatedAt)
        assertEquals(1700000000L, veicoli.feedTimestamp)
        assertEquals(3, veicoli.list.size)
    }

    @Test
    fun `un mezzo dichiarato per intero arriva per intero`() {
        val v = veicoli.list[0]
        assertEquals(corsaNormale, v.tripHash)
        assertEquals(linea6, v.routeHash)
        assertEquals(43.771389, v.lat, 1e-9)
        assertEquals(11.254167, v.lon, 1e-9)
        assertEquals(137, v.bearingDeg)
        // Il fix era di trenta secondi prima della generazione.
        assertEquals(72, v.fixAgeSec)
        assertEquals(8 * 3600 + 15 * 60, v.startTimeSec)
        assertEquals(0, v.direction)
        assertEquals(8.3, v.speedMs, 1e-9)
        assertEquals(2037033061, v.vehKey)
    }

    @Test
    fun `un mezzo senza corsa si disegna lo stesso, solo senza linea`() {
        // Il feed vero lo fa: un mezzo in servizio che non dichiara quale
        // corsa sta facendo. Va mostrato, senza inventargli una linea, e i
        // campi assenti devono valere meno uno e non zero — zero e' una
        // direzione, un rilevamento e una velocita' legittimi.
        val v = veicoli.list[1]
        assertEquals(0L, v.tripHash)
        assertEquals(0L, v.routeHash)
        assertEquals(-43.5, v.lat, 1e-9)
        assertEquals(-11.25, v.lon, 1e-9)
        assertEquals(-1, v.bearingDeg)
        assertEquals(-1, v.fixAgeSec)
        assertEquals(-1, v.startTimeSec)
        assertEquals(-1, v.direction)
        assertEquals(-1.0, v.speedMs, 1e-9)
        assertEquals(0, v.vehKey)
    }

    @Test
    fun `i valori fuori scala vengono ridotti prima di partire, non dopo`() {
        val v = veicoli.list[2]
        assertEquals(corsaNotturna, v.tripHash)
        assertEquals(linea37, v.routeHash)
        // 725 gradi non esistono: il Worker li riporta nel giro.
        assertEquals(5, v.bearingDeg)
        // L'eta' vera era di quasi venti ore: il campo e' a 16 bit e si ferma
        // un gradino sotto il valore che vuol dire "ignota".
        assertEquals(65534, v.fixAgeSec)
        // 9000 m/s non esistono nemmeno: stessa storia, e il decimetro al
        // secondo e' l'unita' del campo.
        assertEquals(6553.4, v.speedMs, 1e-9)
        assertEquals(1, v.direction)
        assertEquals(663275217, v.vehKey)
    }

    @Test
    fun `le corse oltre le 24 sopravvivono anche qui`() {
        // 2.709 corse del feed vero passano le 24, e l'ultima finisce alle
        // 30:10. Se il campo fosse troppo stretto o si perdesse il modulo,
        // quel mezzo si sposterebbe di un giorno o sparirebbe.
        assertEquals(25 * 3600 + 10 * 60, veicoli.list[2].startTimeSec)
        assertEquals(25 * 3600 + 10 * 60, ritardi.byTripHash[corsaSenzaDati]!!.startTimeSec)
    }

    @Test
    fun `la sezione dei ritardi si legge, con le sue corse`() {
        assertEquals(1700000042L, ritardi.generatedAt)
        // Il timestamp e' quello di trip-updates, non quello dei veicoli: i
        // due feed hanno generazioni diverse e confonderli vorrebbe dire
        // dichiarare fresco un dato vecchio.
        assertEquals(1699999990L, ritardi.feedTimestamp)
        assertEquals(5, ritardi.byTripHash.size)
    }

    @Test
    fun `un ritardo normale arriva com'era`() {
        val d = ritardi.byTripHash[corsaNormale]!!
        assertEquals(linea6, d.routeHash)
        assertEquals(120, d.delaySec)
        assertEquals(8 * 3600 + 15 * 60, d.startTimeSec)
        assertEquals(0, d.direction)
        assertEquals(3, d.nextStopSeq)
        assertFalse(d.canceled)
        assertFalse(d.noData)
    }

    @Test
    fun `cancellata e senza dati restano due cose diverse anche attraverso il filo`() {
        val cancellata = ritardi.byTripHash[corsaCancellata]!!
        assertTrue(cancellata.canceled)
        assertFalse(cancellata.noData)
        assertEquals(-1, cancellata.nextStopSeq)

        val senzaDati = ritardi.byTripHash[corsaSenzaDati]!!
        assertFalse(senzaDati.canceled)
        assertTrue(senzaDati.noData)
        assertEquals(-1, senzaDati.direction)
        assertEquals(4, senzaDati.nextStopSeq)
    }

    @Test
    fun `un ritardo assurdo si satura, non gira di segno`() {
        // Il campo e' a 16 bit: 99999 secondi scritti senza controllo
        // tornerebbero indietro come 34463, cioe' un anticipo. Un mezzo che
        // il feed dichiara in ritardo di un giorno deve restare in ritardo.
        assertEquals(32000, ritardi.byTripHash[corsaImpossibile]!!.delaySec)
        assertEquals(-32000, ritardi.byTripHash[corsaAnticipata]!!.delaySec)
    }

    @Test
    fun `la fermata successiva non diventa mai per sbaglio ignota`() {
        // 65535 nel campo vuol dire "non lo so". Una sequenza vera cosi'
        // grande non esiste (misurate: 1..76), ma se arrivasse va fermata un
        // gradino prima, altrimenti una fermata dichiarata diventerebbe una
        // fermata sconosciuta.
        assertEquals(65534, ritardi.byTripHash[corsaImpossibile]!!.nextStopSeq)
        // E lo zero e' una sequenza, non un'assenza.
        assertEquals(0, ritardi.byTripHash[corsaAnticipata]!!.nextStopSeq)
    }
}
