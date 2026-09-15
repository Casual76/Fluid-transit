package dev.antigravity.fluidtransit.ui.map

import dev.antigravity.fluidtransit.routing.PathIndex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Il moto dei mezzi sulla mappa, senza la mappa.
 *
 * Qui vivevano due difetti che nessun test poteva vedere, perche' la classe
 * parlava MapLibre e non si poteva istanziare fuori da un telefono. Sono
 * tutti e due della famiglia che l'utente ha descritto come "vanno
 * all'indietro, si teletrasportano", e tutti e due stanno nel passaggio fra
 * il moto sulla strada e il ripiego:
 *
 *  1. quando la geometria sparisce (cambio corsa, decodifica non pronta) il
 *     ripiego ripartiva dai campi del glide, fermi a prima che il moto sulla
 *     strada prendesse il comando: il marker saltava indietro di minuti;
 *  2. un mezzo fuori dal riquadro non viene disegnato ma il dato vero gli
 *     arriva lo stesso; al rientro il tempo accumulato lo spingeva avanti
 *     SOPRA una posizione gia' aggiornata, e al dato dopo tornava indietro.
 */
/** JUnit vuole il messaggio per primo; qui si legge meglio al contrario. */
private fun assertTrue(condition: Boolean, message: String) =
    org.junit.Assert.assertTrue(message, condition)

class BusOverlayTest {

    // --- una rete di prova: due tratte dritte, lunghe e distinte ----------

    private val lat0 = 43.5
    private val lon0 = 11.0
    private val dLat = 1.0 / 110_540.0
    private val dLon = 1.0 / (111_320.0 * Math.cos(Math.toRadians(43.5)))

    /** Cinque chilometri verso nord, a partire da [fromLat]. */
    private fun dritta(fromLat: Double): PathIndex {
        val lat = ArrayList<Double>()
        val lon = ArrayList<Double>()
        var i = 0
        while (i <= 5_000) {
            lat.add(fromLat + dLat * i)
            lon.add(lon0)
            i += 25
        }
        return requireNotNull(
            PathIndex.of(lat.toDoubleArray(), lon.toDoubleArray(), intArrayOf(0, lat.size - 1)),
        )
    }

    /** Una sorgente di geometrie che il test accende e spegne a piacere. */
    private class Paths : PathSource {
        val byPattern = HashMap<Int, PathIndex>()
        override fun get(pattern: Int): PathIndex? = byPattern[pattern]
    }

    private fun render(
        lat: Double,
        lon: Double = 11.0,
        pattern: Int = 1,
        speed: Double = 10.0,
        age: Int = 0,
        key: Int = 7,
        bearing: Int = -1,
    ) = BusRender(
        vehKey = key,
        lat = lat,
        lon = lon,
        bearingDeg = bearing,
        colorRgb = 0x112233,
        cat = "u",
        routeHashHex = "aa",
        tripHashHex = "bb",
        patternIndex = pattern,
        speedMs = speed,
        fixAgeSec = age,
    )

    private fun metersBetween(a: BusPose, b: BusPose): Double =
        dev.antigravity.fluidtransit.routing.BundleReader.haversine(a.lat, a.lon, b.lat, b.lon)

    /** Quanto il mezzo si e' spostato verso nord: positivo = avanti. */
    private fun northProgress(from: BusPose, to: BusPose): Double =
        (to.lat - from.lat) / dLat

    // ------------------------------------------------------------ i difetti

    @Test
    fun `perdere la geometria non riporta il mezzo dove stava minuti fa`() {
        val paths = Paths().apply { byPattern[1] = dritta(lat0) }
        val overlay = BusOverlay().apply { this.paths = paths }

        var now = 1_000L
        overlay.setTargets(listOf(render(lat0, pattern = 1)), now)

        // Due minuti di corsa sulla strada: il mezzo fa parecchia strada.
        repeat(960) {
            now += 125
            overlay.poses(now)
        }
        val prima = overlay.poses(now).single()
        assertTrue(
            northProgress(BusPose(prima.render, lat0, lon0, -1), prima) > 200.0,
            "non si e' mosso: e' un altro problema",
        )

        // Il mezzo passa a una corsa la cui geometria non e' ancora
        // decodificata: e' quello che succede a ogni capolinea.
        now += 125
        overlay.setTargets(listOf(render(prima.lat, prima.lon, pattern = 2)), now)
        val dopo = overlay.poses(now).single()

        assertTrue(
            metersBetween(prima, dopo) < 30.0,
            "il marker e' saltato di ${metersBetween(prima, dopo).toInt()} m perdendo la geometria",
        )
    }

    @Test
    fun `un mezzo senza geometria dall'inizio non parte dal nulla`() {
        val overlay = BusOverlay().apply { paths = Paths() }
        var now = 1_000L
        overlay.setTargets(listOf(render(lat0, pattern = -1)), now)
        val prima = overlay.poses(now).single()

        now += 5_000
        val dopo = overlay.poses(now).single()

        assertEquals(prima.lat, dopo.lat, 1e-9)
        assertEquals(prima.lon, dopo.lon, 1e-9)
    }

    @Test
    fun `rientrando nel riquadro il mezzo non si trova piu' avanti del vero`() {
        val paths = Paths().apply { byPattern[1] = dritta(lat0) }
        val fuori = doubleArrayOf(40.0, 9.0, 41.0, 10.0) // un riquadro altrove
        val dentro = doubleArrayOf(40.0, 9.0, 45.0, 12.0)

        // Due mezzi identici: uno sempre in scena, l'altro fuori per due
        // minuti. Ricevono esattamente gli stessi dati.
        val visibile = BusOverlay().apply { this.paths = paths }
        val nascosto = BusOverlay().apply { this.paths = paths }

        var now = 1_000L
        visibile.setTargets(listOf(render(lat0)), now)
        nascosto.setTargets(listOf(render(lat0)), now)

        repeat(960) {
            now += 125
            visibile.poses(now, dentro)
            nascosto.poses(now, fuori) // tagliato: non si disegna e non si simula
        }

        // Il dato vero, identico per tutti e due.
        val fix = lat0 + dLat * 900.0
        now += 125
        visibile.setTargets(listOf(render(fix)), now)
        nascosto.setTargets(listOf(render(fix)), now)

        val a = visibile.poses(now, dentro).single()
        val b = nascosto.poses(now, dentro).single() // rientra in scena adesso

        assertTrue(
            metersBetween(a, b) < 60.0,
            "il mezzo rientrato sta ${metersBetween(a, b).toInt()} m " +
                "piu' avanti di quello rimasto in scena: ha contato il tempo due volte",
        )
    }

    // ------------------------------------------------- la proprieta' generale

    @Test
    fun `in nessun caso il mezzo torna indietro`() {
        // La formulazione che copre anche i difetti non ancora trovati.
        // La corsa cambia, la geometria va e viene, i dati sono a volte
        // fotocopie e a volte no: qualunque cosa succeda, verso nord si va
        // sempre avanti.
        val paths = Paths()
        val overlay = BusOverlay().apply { this.paths = paths }

        var now = 1_000L
        var truth = 0.0
        var pattern = 1
        overlay.setTargets(listOf(render(lat0)), now)
        var previous = overlay.poses(now).single()

        repeat(40) { giro ->
            // La geometria appare, sparisce e cambia, come nella realta'.
            when (giro % 4) {
                0 -> paths.byPattern[pattern] = dritta(lat0)
                2 -> paths.byPattern.remove(pattern)
            }
            if (giro % 7 == 6) pattern++

            repeat(240) {
                now += 125
                val pose = overlay.poses(now).single()
                assertTrue(
                    northProgress(previous, pose) > -1.0,
                    "arretrato di ${-northProgress(previous, pose)} m al giro $giro",
                )
                previous = pose
            }
            // Un dato vero ogni trenta secondi: a volte avanza, a volte no.
            if (giro % 3 != 0) truth += 120.0
            overlay.setTargets(
                listOf(render(lat0 + dLat * truth, pattern = pattern)),
                now,
            )
            val dopoFix = overlay.poses(now).single()
            assertTrue(
                northProgress(previous, dopoFix) > -1.0,
                "il dato vero ha arretrato il mezzo al giro $giro",
            )
            previous = dopoFix
        }
    }

    // ------------------------------------------------------ igiene di base

    @Test
    fun `un mezzo che sparisce si smette di disegnarlo, poi si dimentica`() {
        val overlay = BusOverlay().apply { paths = Paths() }
        var now = 1_000L
        overlay.setTargets(listOf(render(lat0, pattern = -1)), now)
        assertEquals(1, overlay.poses(now).size)

        now += BusOverlay.HIDE_MS + 1_000
        assertEquals("oltre HIDE_MS non si disegna piu'", 0, overlay.poses(now).size)
        assertTrue(!overlay.isEmpty, "ma non e' ancora dimenticato")

        now += BusOverlay.FORGET_MS
        overlay.setTargets(emptyList(), now)
        assertTrue(overlay.isEmpty, "oltre FORGET_MS si butta")
    }

    @Test
    fun `il taglio per riquadro guarda l'ultimo dato vero`() {
        val overlay = BusOverlay().apply { paths = Paths() }
        val now = 1_000L
        overlay.setTargets(listOf(render(lat0, pattern = -1)), now)

        assertEquals(0, overlay.poses(now, doubleArrayOf(40.0, 9.0, 41.0, 10.0)).size)
        assertEquals(1, overlay.poses(now, doubleArrayOf(40.0, 9.0, 45.0, 12.0)).size)
        assertEquals(1, overlay.poses(now, null).size)
    }

    @Test
    fun `senza rotta dal feed e senza geometria si disegna il pallino`() {
        val overlay = BusOverlay().apply { paths = Paths() }
        val now = 1_000L
        overlay.setTargets(listOf(render(lat0, pattern = -1, bearing = -1)), now)

        assertEquals(-1, overlay.poses(now).single().bearingDeg)
    }
}
