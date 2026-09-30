package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * La forma con cui si cerca: punteggiatura, abbreviazioni e numeri romani
 * delle date, sui nomi veri del feed e sulle cose che la gente scrive.
 *
 * I nomi sono quelli che il feed ha davvero (COLLE P.ZA ARNOLFO, XXVII
 * APRILE SANTA REPARATA): un test con nomi inventati non avrebbe trovato
 * il difetto.
 */
class SearchFormTest {

    private fun f(s: String) = Places.searchForm(s)

    @Test
    fun `le abbreviazioni col punto si espandono`() {
        assertEquals("piazza arnolfo", f("P.ZA ARNOLFO"))
        assertEquals("colle piazza arnolfo", f("COLLE P.ZA ARNOLFO"))
        assertEquals("viale lavagnini", f("v.le Lavagnini"))
        assertEquals("corso italia", f("C.so Italia"))
        assertEquals("porta romana", f("p.ta Romana"))
    }

    @Test
    fun `san e santa si scrivono come si leggono`() {
        assertEquals("san marco", f("S. Marco"))
        assertEquals("san marco", f("S.Marco"))
        assertEquals("san croce", f("s croce"))
        assertEquals("santa maria novella", f("Sta. Maria Novella"))
    }

    @Test
    fun `sta e ple in fondo alla query restano quello che si sta scrivendo`() {
        // A meta' digitazione "sta" e' l'inizio di stazione e stadio, e "ple"
        // di plebiscito: espanderle in santa/piazzale faceva sparire i risultati.
        assertEquals("sta", f("sta"))
        assertEquals("via ple", f("via ple"))
        // Col punto, o con una parola dopo, sono abbreviazioni vere.
        assertEquals("santa", f("sta."))
        assertEquals("piazzale michelangelo", f("p.le Michelangelo"))
        assertEquals("piazzale michelangelo", f("ple Michelangelo"))
        assertEquals("santa maria novella", f("sta Maria Novella"))
    }

    @Test
    fun `una s o una v sole restano quello che sono`() {
        // Una sigla di linea di un carattere non si riscrive: "s" non e' "san".
        assertEquals("s", f("S"))
        assertEquals("s", f("S."))
        assertEquals("v", f("v"))
        // Senza il punto una "v" non e' "via": e' il cinque romano.
        assertEquals("via v roma", f("via v roma"))
        assertEquals("via 5 novembre", f("via V novembre"))
        assertEquals("via roma", f("v. Roma"))
    }

    @Test
    fun `la punteggiatura non resta attaccata alla parola`() {
        assertEquals("via roma 12", f("via Roma, 12"))
        assertEquals("via roma 12 firenze", f("Via Roma 12, Firenze"))
        assertEquals("san piero via san francesco", f("San Piero, Via San Francesco"))
        assertEquals("monsummano t", f("Monsummano T."))
        assertEquals("firenze calenzano", f("FIRENZE - CALENZANO"))
    }

    @Test
    fun `la barra dei civici resta`() {
        assertEquals("via roma 12/a", f("via roma 12/A"))
    }

    @Test
    fun `gli apostrofi e gli accenti fanno come prima`() {
        assertEquals("piazza dell orto", f("Piazza dell'Orto"))
        assertEquals("localita caffe", f("Località Caffè"))
    }

    @Test
    fun `una data in romano e in cifre e la stessa parola`() {
        assertEquals("via 27 aprile", f("via XXVII Aprile"))
        assertEquals("via 27 aprile", f("via 27 aprile"))
        assertEquals("via 20 settembre", f("Via XX Settembre"))
        assertEquals("via 20 settembre", f("via 20 settembre"))
        assertEquals("piazza 4 novembre", f("PIAZZA IV NOVEMBRE"))
        assertEquals("via 25 aprile", f("Via XXV Aprile"))
        // E' uguale anche dentro un nome piu' lungo, come nel feed.
        assertEquals("27 aprile santa reparata", f("XXVII APRILE SANTA REPARATA"))
    }

    @Test
    fun `un romano che non e' una data non si tocca`() {
        // Senza un mese accanto "XX" e' un'altra cosa (un papa, un secolo).
        assertEquals("papa giovanni xxiii", f("Papa Giovanni XXIII"))
        assertEquals("via xx", f("via XX"))
        // E un romano che non e' un giorno non diventa un numero.
        assertEquals("via l settembre", f("via L settembre"))
        assertEquals("via xxxii settembre", f("via XXXII settembre"))
        assertEquals("via iiii novembre", f("via IIII novembre"))
    }

    @Test
    fun `le chiavi del bundle non cambiano`() {
        // normalize e' la chiave con cui il bundler ordina e raggruppa: la
        // forma di ricerca non deve averla toccata.
        assertEquals("p.za arnolfo", Places.normalize("P.ZA Arnolfo"))
        assertEquals("s. marco", Places.normalize("S. Marco"))
    }

    @Test
    fun `idempotente sulla propria uscita`() {
        for (s in listOf("S. Marco", "p.za arnolfo", "via XX Settembre, 12/a", "via v. Roma")) {
            assertEquals(f(s), f(f(s)), "non idempotente su '$s'")
        }
    }

    @Test
    fun `i token della query sono quelli dell'indice`() {
        // Il punto di tutto: stessa forma dai due lati.
        assertEquals(Relevance.tokens("p.za arnolfo"), Relevance.tokens("piazza arnolfo"))
        assertEquals(Relevance.tokens("27 aprile"), Relevance.tokens("XXVII aprile"))
    }

    @Test
    fun `civicKey toglie i segni`() {
        assertEquals("12a", Places.civicKey("12/A"))
        assertEquals("12a", Places.civicKey("12 a"))
        assertEquals("12a", Places.civicKey("12a"))
        assertNotEquals(Places.civicKey("12"), Places.civicKey("120"))
        // Fra due cifre la barra e' un interno, non un segno da togliere.
        assertNotEquals(Places.civicKey("12/1"), Places.civicKey("121"))
        assertNotEquals(Places.civicKey("3/4"), Places.civicKey("34"))
        assertEquals("10/12", Places.civicKey("10-12"))
    }

    // --- i suggerimenti di tipo ---------------------------------------------

    @Test
    fun `linea 23 diventa 23 e chiede solo le linee`() {
        val h = Relevance.kindHints(Relevance.tokens("linea 23"))
        assertEquals(listOf("23"), h.rest)
        assertTrue(h.routesOnly)
        assertTrue(!h.stopsOnly)
    }

    @Test
    fun `fermata careggi chiede solo le fermate`() {
        val h = Relevance.kindHints(Relevance.tokens("Fermata Careggi"))
        assertEquals(listOf("careggi"), h.rest)
        assertTrue(h.stopsOnly)
        assertEquals("careggi", h.text)
    }

    @Test
    fun `una parola-tipo da sola si cerca com'e'`() {
        for (q in listOf("fermata", "bus", "linea")) {
            val h = Relevance.kindHints(Relevance.tokens(q))
            assertEquals(listOf(q), h.rest)
            assertTrue(!h.routesOnly && !h.stopsOnly)
        }
    }

    @Test
    fun `senza parole-tipo la query resta com'e'`() {
        val h = Relevance.kindHints(Relevance.tokens("via roma 12"))
        assertEquals(listOf("via", "roma", "12"), h.rest)
        assertTrue(!h.routesOnly && !h.stopsOnly)
    }

    @Test
    fun `linea e fermata insieme non restringono`() {
        val h = Relevance.kindHints(Relevance.tokens("linea fermata duomo"))
        assertEquals(listOf("duomo"), h.rest)
        assertTrue(!h.routesOnly && !h.stopsOnly)
    }

    @Test
    fun `la parola-tipo con un resto che e' un nome non e' una sigla di linea`() {
        assertTrue(Relevance.kindHints(Relevance.tokens("linea 23")).restIsRouteCode)
        assertTrue(Relevance.kindHints(Relevance.tokens("bus 23a")).restIsRouteCode)
        assertTrue(Relevance.kindHints(Relevance.tokens("linea t1")).restIsRouteCode)
        assertFalse(Relevance.kindHints(Relevance.tokens("linea gotica")).restIsRouteCode)
        assertFalse(Relevance.kindHints(Relevance.tokens("bus firenze")).restIsRouteCode)
    }
}
