package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Il taglio della tratta fra due ascisse.
 *
 * E' il mattone di tutto quello che la navigazione disegna: la tratta da
 * dove sali a dove scendi, la scia grigia di quello che il bus ha gia'
 * fatto, il tratto tratteggiato che gli manca per arrivare da te. Tre
 * disegni, una funzione sola — quindi tre modi di sbagliare nello stesso
 * posto.
 *
 * Il difetto che questi test rendono impossibile: appoggiare gli estremi al
 * vertice piu' vicino invece di interpolarli. Su una strada dritta i
 * vertici del bundle distano un centinaio di metri, e la coda grigia
 * sarebbe finita mezzo isolato dietro il marker del bus — che e' l'unica
 * cosa che l'occhio guarda davvero.
 */
class PathSliceTest {

    /** Un chilometro dritto verso nord, un vertice ogni cento metri. */
    private val lat0 = 43.0
    private val lon0 = 11.0
    private val perMetro = 1.0 / 110_540.0

    /** La latitudine a [metri] dalla partenza. */
    private fun a(metri: Double) = lat0 + perMetro * metri

    private fun dritto(): PathIndex {
        val lat = DoubleArray(11) { a(it * 100.0) }
        val lon = DoubleArray(11) { lon0 }
        // Fermate al principio, a meta' e in fondo.
        return assertNotNull(PathIndex.of(lat, lon, intArrayOf(0, 5, 10)))
    }

    private fun assertVicino(atteso: Double, trovato: Double, quanto: String) {
        assertTrue(
            kotlin.math.abs(atteso - trovato) < 1e-7,
            "$quanto: atteso $atteso, trovato $trovato",
        )
    }

    @Test
    fun `il taglio fra due ascisse porta gli estremi interpolati, non i vertici vicini`() {
        val p = dritto()
        val s = assertNotNull(p.slice(250.0, 650.0))
        // 250 e 650 non sono vertici: devono esserci lo stesso.
        assertVicino(a(250.0), s.lat[0], "il principio")
        assertVicino(a(650.0), s.lat[s.size - 1], "la fine")
        // In mezzo i vertici veri: 300, 400, 500, 600.
        assertEquals(6, s.size, "due estremi interpolati e quattro vertici")
        assertVicino(a(300.0), s.lat[1], "il primo vertice dentro")
        assertVicino(a(600.0), s.lat[4], "l'ultimo vertice dentro")
    }

    @Test
    fun `il tratto fatto e quello che resta si toccano senza lasciare un buco`() {
        // La scia grigia finisce dove comincia il colore: un buco di un
        // pixel fra i due si vede, ed e' il tipo di difetto che si nota solo
        // sul telefono vero.
        val p = dritto()
        val fatto = assertNotNull(p.slice(0.0, 430.0))
        val resta = assertNotNull(p.slice(430.0, 1000.0))
        assertVicino(fatto.lat[fatto.size - 1], resta.lat[0], "il giunto in latitudine")
        assertVicino(fatto.lon[fatto.size - 1], resta.lon[0], "il giunto in longitudine")
    }

    @Test
    fun `un taglio a lunghezza zero non produce una linea da disegnare`() {
        // Il bus fermo al capolinea di partenza e' il caso NORMALE, non un
        // errore: la scia di quello che ha fatto e' vuota, e una linea da
        // zero vertici spinta nella sorgente e' un artefatto.
        val p = dritto()
        assertNull(p.slice(300.0, 300.0), "stesso punto")
        assertNull(p.slice(300.0, 300.5), "mezzo metro non e' una linea")
        assertNull(p.slice(500.0, 400.0), "al contrario non e' un taglio")
    }

    @Test
    fun `un'ascissa oltre la fine si appoggia al capolinea invece di uscire dall'array`() {
        // Il mezzo che il feed mette un chilometro oltre il capolinea
        // esiste: un fix sporco basta. Qui non deve diventare un indice
        // fuori dall'array.
        val p = dritto()
        val coda = assertNotNull(p.slice(900.0, 5000.0))
        assertVicino(a(1000.0), coda.lat[coda.size - 1], "si ferma al capolinea")
        val testa = assertNotNull(p.slice(-100.0, 200.0))
        assertVicino(a(0.0), testa.lat[0], "e non parte prima della partenza")
    }

    @Test
    fun `la tratta fra due fermate si taglia sulle loro ascisse`() {
        // E' l'uso vero: "da dove sali a dove scendi" sono due indici di
        // fermata, non due numeri scelti a mano.
        val p = dritto()
        val tratta = assertNotNull(p.slice(p.stopS[0], p.stopS[1]))
        assertVicino(a(0.0), tratta.lat[0], "parte dalla fermata di salita")
        assertVicino(a(500.0), tratta.lat[tratta.size - 1], "e finisce a quella di discesa")
    }
}
