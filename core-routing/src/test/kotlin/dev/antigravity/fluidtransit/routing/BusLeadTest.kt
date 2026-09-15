package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Il tetto all'estrapolazione, cioe' la ragione per cui un mezzo non puo'
 * piu' essere riportato indietro.
 *
 * `BusPathTest` verifica che il marker non arretri nei casi noti. Questi test
 * verificano la proprieta' che li rende tutti impossibili invece che
 * improbabili: fra un dato vero e l'altro la simulazione non puo' correre piu'
 * di [BusPathMotion.MAX_LEAD_M], che sta sotto [BusPathMotion.SNAP_M].
 *
 * Il ragionamento, per chi legge fra un anno. `onFix` ha due regimi: sotto
 * SNAP_M corregge (e correggere non arretra mai, perche' l'anticipo si
 * smaltisce rallentando), sopra SNAP_M **riposiziona**, e riposizionare
 * all'indietro e' il teletrasporto che si vede sulla mappa. Un mezzo puo'
 * finire sopra SNAP_M solo se la simulazione lo ha spinto piu' avanti di
 * quanto sia andato davvero. Col tetto sotto la soglia quel caso non esiste.
 */
class BusLeadTest {

    /** Dieci chilometri dritti verso nord: serve spazio per correre. */
    private fun dritto(): PathIndex {
        val lat = ArrayList<Double>()
        val lon = ArrayList<Double>()
        val lat0 = 43.0
        val lon0 = 11.0
        val dLat = 1.0 / 110_540.0
        var i = 0
        while (i <= 10_000) {
            lat.add(lat0 + dLat * i)
            lon.add(lon0)
            i += 20
        }
        // Nessuna fermata in mezzo: qui si misura la corsa, non le soste.
        return assertNotNull(
            PathIndex.of(lat.toDoubleArray(), lon.toDoubleArray(), intArrayOf(0, lat.size - 1)),
        )
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
    fun `un mezzo senza dati nuovi si ferma invece di correre all'infinito`() {
        val p = dritto()
        // Venti metri al secondo: senza tetto, in cinque minuti farebbe sei
        // chilometri sulla fiducia.
        val m = BusPathMotion(p, startS = 0.0, startSpeed = 20.0)
        m.onFix(latAt(p, 0.0), lonAt(p, 0.0), 20.0, 0, 0L)

        var now = 0L
        repeat(2400) { // cinque minuti a 8 Hz
            now += 125
            m.tick(125, now)
        }

        assertTrue(
            m.s <= BusPathMotion.MAX_LEAD_M + 1.0,
            "corso ${m.s} m senza un solo dato vero: il tetto non tiene",
        )
        assertTrue(m.s > BusPathMotion.MAX_LEAD_M - 50.0, "si e' fermato molto prima del tetto: ${m.s}")
    }

    @Test
    fun `il tetto sta sotto la soglia del riposizionamento`() {
        // E' la disuguaglianza da cui dipende tutto il resto. Se qualcuno
        // alzasse il tetto sopra la soglia, l'arretramento tornerebbe
        // possibile senza che nessun altro test se ne accorga.
        assertTrue(
            BusPathMotion.MAX_LEAD_M < BusPathMotion.SNAP_M,
            "MAX_LEAD_M (${BusPathMotion.MAX_LEAD_M}) deve stare sotto SNAP_M (${BusPathMotion.SNAP_M})",
        )
    }

    @Test
    fun `un mezzo fermo che dichiara di correre non finisce oltre la soglia`() {
        val p = dritto()
        // Il caso vero: il feed fotografa il mezzo a venti metri al secondo
        // un attimo prima che si fermi a un semaforo. Per due minuti noi lo
        // crediamo in marcia; lui non si muove.
        val m = BusPathMotion(p, startS = 0.0, startSpeed = 20.0)
        m.onFix(latAt(p, 0.0), lonAt(p, 0.0), 20.0, 0, 0L)

        var now = 0L
        repeat(960) { // due minuti
            now += 125
            m.tick(125, now)
        }
        val simulato = m.s

        // Il dato vero: non si e' mosso di un metro.
        val prima = m.s
        m.onFix(latAt(p, 0.0), lonAt(p, 0.0), 0.0, 0, now)

        assertTrue(
            simulato < BusPathMotion.SNAP_M,
            "la simulazione e' finita a $simulato m, oltre la soglia: il dato vero la riposizionerebbe indietro",
        )
        assertTrue(m.s >= prima - 0.001, "il fix ha riportato indietro il marker da $prima a ${m.s}")
    }

    @Test
    fun `dopo un dato nuovo il mezzo riparte, il tetto non lo inchioda`() {
        val p = dritto()
        val m = BusPathMotion(p, startS = 0.0, startSpeed = 10.0)
        m.onFix(latAt(p, 0.0), lonAt(p, 0.0), 10.0, 0, 0L)

        var now = 0L
        repeat(2400) { now += 125; m.tick(125, now) }
        val fermo = m.s

        // Il mezzo si e' fatto davvero la sua strada: il tetto si sposta con lui.
        m.onFix(latAt(p, 1_500.0), lonAt(p, 1_500.0), 10.0, 0, now)
        repeat(400) { now += 125; m.tick(125, now) }

        assertTrue(m.s > fermo + 400.0, "non e' ripartito dopo il dato nuovo: ${m.s}")
    }

    @Test
    fun `recuperare terreno non e' un teletrasporto in avanti`() {
        val p = dritto()
        val m = BusPathMotion(p, startS = 0.0, startSpeed = 10.0)
        m.onFix(latAt(p, 0.0), lonAt(p, 0.0), 10.0, 0, 0L)

        // Il dato vero dice ottocento metri piu' avanti: dentro la soglia,
        // quindi si RECUPERA, non si riposiziona. Prima il recupero aveva solo
        // una durata (due secondi e mezzo) e nessun limite di ritmo: erano
        // trecento metri al secondo, cioe' un salto.
        m.onFix(latAt(p, 800.0), lonAt(p, 800.0), 10.0, 0, 1_000L)

        var now = 1_000L
        var previous = m.s
        var maxStep = 0.0
        repeat(240) { // trenta secondi
            now += 125
            m.tick(125, now)
            maxStep = maxOf(maxStep, m.s - previous)
            previous = m.s
        }

        // A 8 Hz, il doppio di dieci metri al secondo sono 2,5 m a fotogramma.
        assertTrue(maxStep < 4.0, "passo massimo di $maxStep m per fotogramma: e' un salto")
        assertTrue(m.s > 300.0, "in trenta secondi non ha recuperato niente: ${m.s}")
    }

    @Test
    fun `la velocita osservata conta piu' di quella dichiarata`() {
        val p = dritto()
        // Dichiara venti metri al secondo, ma fra un dato e l'altro ne ha
        // fatti due: e' fermo nel traffico e lo dice il movimento, non il
        // tachimetro. Estrapolare col tachimetro lo manderebbe lontanissimo.
        val m = BusPathMotion(p, startS = 0.0, startSpeed = 20.0)
        m.onFix(latAt(p, 0.0), lonAt(p, 0.0), 20.0, 0, 0L)
        m.onFix(latAt(p, 200.0), lonAt(p, 200.0), 20.0, 0, 100_000L)

        var now = 100_000L
        repeat(800) { now += 125; m.tick(125, now) }

        // Cento secondi di estrapolazione: col tachimetro sarebbero duemila
        // metri, con l'osservazione ne bastano poche centinaia.
        assertTrue(m.s < 200.0 + 800.0, "estrapolato fino a ${m.s}: sta ancora credendo al tachimetro")
    }
}
