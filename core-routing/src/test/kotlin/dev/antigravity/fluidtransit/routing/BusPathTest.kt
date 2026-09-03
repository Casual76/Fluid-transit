package dev.antigravity.fluidtransit.routing

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Il moto dei bus sulla strada, senza bundle sotto.
 *
 * Il difetto che questi test inchiodano e' quello che l'utente ha visto sul
 * telefono e ha descritto come "si teletrasportano ogni due minuti": il
 * marker scivolava fra due posizioni note e poi si CONGELAVA, perche' il
 * parametro dell'interpolazione era limitato a 1. Con l'origine che si
 * rigenera ogni ~120 secondi voleva dire venticinque secondi di moto e
 * novantacinque di immobilita'. Qui si verifica che un mezzo senza dati
 * nuovi continui ad avanzare, e che quando il dato arriva non salti.
 */
class BusPathTest {

    /** Un chilometro verso nord, poi un chilometro verso est: la svolta e' netta. */
    private fun elle(): PathIndex {
        val lat = ArrayList<Double>()
        val lon = ArrayList<Double>()
        val lat0 = 43.77
        val lon0 = 11.25
        val dLat = 1.0 / 110_540.0 // un metro
        val dLon = 1.0 / (111_320.0 * Math.cos(Math.toRadians(lat0)))
        var i = 0
        while (i <= 1000) {
            lat.add(lat0 + dLat * i)
            lon.add(lon0)
            i += 10
        }
        i = 10
        while (i <= 1000) {
            lat.add(lat0 + dLat * 1000)
            lon.add(lon0 + dLon * i)
            i += 10
        }
        // Tre fermate: partenza, la svolta, capolinea.
        val n = lat.size
        val stops = intArrayOf(0, 100, n - 1)
        return assertNotNull(PathIndex.of(lat.toDoubleArray(), lon.toDoubleArray(), stops))
    }

    @Test
    fun `la lunghezza e le ascisse delle fermate tornano`() {
        val p = elle()
        assertEquals(2000.0, p.length, 5.0)
        assertEquals(0.0, p.stopS[0], 1.0)
        assertEquals(1000.0, p.stopS[1], 5.0)
        assertEquals(p.length, p.stopS[2], 5.0)
    }

    @Test
    fun `la rotta viene dalla tangente, non dal movimento`() {
        val p = elle()
        val out = DoubleArray(3)
        p.sample(500.0, out)
        assertEquals(0.0, out[2], 2.0) // verso nord
        p.sample(1500.0, out)
        assertEquals(90.0, out[2], 2.0) // verso est
    }

    @Test
    fun `un punto fuori strada si proietta sulla strada`() {
        val p = elle()
        val out = DoubleArray(3)
        p.sample(500.0, out)
        // Trenta metri a est del tratto che corre verso nord.
        val offLon = out[1] + 30.0 / (111_320.0 * Math.cos(Math.toRadians(out[0])))
        val s = p.project(out[0], offLon)
        assertEquals(500.0, s, 15.0)
        assertEquals(30.0, p.distanceTo(out[0], offLon), 3.0)
    }

    @Test
    fun `la curva si vede nel tasso di svolta`() {
        val p = elle()
        assertTrue(p.turnRateAt(500.0) < 5.0, "sul rettilineo non si gira")
        assertTrue(p.turnRateAt(1000.0) > 60.0, "alla svolta si gira, e tanto")
    }

    @Test
    fun `senza dati nuovi il bus continua ad avanzare`() {
        val p = elle()
        // Dieci metri al secondo, partendo dall'inizio.
        val m = BusPathMotion(p, startS = 0.0, startSpeed = 10.0)
        m.onFix(latAt(p, 0.0), lonAt(p, 0.0), 10.0, 0, 0L)
        var now = 0L
        // Trenta secondi di fotogrammi a 8 Hz, nessun dato nuovo.
        repeat(240) {
            now += 125
            m.tick(125, now)
        }
        // Non e' congelato: ha fatto strada. Le soste alle fermate e il
        // rallentamento in curva rendono il totale minore dei 300 m teorici,
        // ma deve essersi mosso di molto piu' di zero.
        assertTrue(m.s > 120.0, "avanzato solo ${m.s} m in trenta secondi")
        assertTrue(m.s < 320.0, "avanzato ${m.s} m: piu' della velocita' dichiarata")
    }

    @Test
    fun `un dato nuovo non fa saltare il marker`() {
        val p = elle()
        val m = BusPathMotion(p, startS = 200.0, startSpeed = 8.0)
        m.onFix(latAt(p, 200.0), lonAt(p, 200.0), 8.0, 0, 0L)
        val before = m.s
        // Il dato vero dice cento metri piu' avanti di dove siamo.
        m.onFix(latAt(p, 300.0), lonAt(p, 300.0), 8.0, 0, 1_000L)
        assertEquals(before, m.s, 1.0, "la posizione non deve cambiare nell'istante del dato")
        // Mezzo secondo dopo si e' avvicinato, ma non ci e' arrivato di colpo.
        m.tick(500, 1_500L)
        assertTrue(m.s > before + 10.0, "non sta riassorbendo l'errore")
        assertTrue(m.s < 300.0, "ha saltato alla posizione nuova invece di avvicinarsi")
    }

    @Test
    fun `uno scarto enorme e' un altro posto, non una correzione`() {
        val p = elle()
        val m = BusPathMotion(p, startS = 0.0, startSpeed = 8.0)
        m.onFix(latAt(p, 0.0), lonAt(p, 0.0), 8.0, 0, 0L)
        m.onFix(latAt(p, 1_900.0), lonAt(p, 1_900.0), 8.0, 0, 1_000L)
        assertEquals(1_900.0, m.s, 20.0, "un mezzo riassegnato deve ricominciare da dove e'")
    }

    @Test
    fun `il fix vecchio si porta avanti di quanto e' vecchio`() {
        val p = elle()
        val m = BusPathMotion(p, startS = 0.0, startSpeed = 10.0)
        // Rilevamento di sessanta secondi prima, a dieci metri al secondo:
        // adesso il bus e' seicento metri piu' avanti di dove lo si e' visto.
        m.onFix(latAt(p, 100.0), lonAt(p, 100.0), 10.0, 60, 0L)
        assertEquals(700.0, m.s, 30.0)
    }

    @Test
    fun `il capolinea non si oltrepassa`() {
        val p = elle()
        val m = BusPathMotion(p, startS = p.length - 30.0, startSpeed = 12.0)
        var now = 0L
        repeat(400) {
            now += 125
            m.tick(125, now)
        }
        assertTrue(m.s <= p.length + 0.001, "uscito dalla tratta: ${m.s} su ${p.length}")
        assertTrue(m.arrived)
    }

    @Test
    fun `il salto lungo fra fotogrammi non simula un minuto di soste`() {
        val p = elle()
        val m = BusPathMotion(p, startS = 0.0, startSpeed = 10.0)
        m.onFix(latAt(p, 0.0), lonAt(p, 0.0), 10.0, 0, 0L)
        // La mappa era zoomata fuori per un minuto: si recupera in blocco.
        m.tick(60_000, 60_000L)
        assertEquals(600.0, m.s, 1.0)
    }

    @Test
    fun `la sosta alla fermata ferma il mezzo`() {
        val p = elle()
        // Si parte poco prima della fermata intermedia, a passo d'uomo.
        val m = BusPathMotion(p, startS = 990.0, startSpeed = 3.0)
        var now = 0L
        var stopped = 0
        repeat(160) {
            now += 125
            m.tick(125, now)
            if (m.speed == 0.0) stopped++
        }
        assertTrue(stopped > 20, "non ha sostato alla fermata: $stopped fotogrammi da fermo")
    }

    private fun latAt(p: PathIndex, s: Double): Double {
        val out = DoubleArray(3)
        p.sample(s, out)
        return out[0]
    }

    private fun lonAt(p: PathIndex, s: Double): Double {
        val out = DoubleArray(3)
        p.sample(s, out)
        return out[1]
    }

    @Test
    fun `una geometria degenere non costruisce un indice`() {
        assertTrue(PathIndex.of(doubleArrayOf(43.0), doubleArrayOf(11.0), intArrayOf(0)) == null)
        val same = PathIndex.of(
            doubleArrayOf(43.0, 43.0),
            doubleArrayOf(11.0, 11.0),
            intArrayOf(0, 1),
        )
        assertTrue(same == null, "due punti identici non sono una tratta")
        assertTrue(abs(0.0) == 0.0)
    }
}
