package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Il criterio di pertinenza, sui casi che sul telefono fallivano davvero:
 * la propria via che non usciva mai, il supermercato che non si trovava,
 * la scuola cercata col nome sbagliato del comune.
 */
class RelevanceTest {

    private fun s(
        name: String,
        extra: String = "",
        query: String,
        kind: Int = 4,
        distance: Double = -1.0,
    ): Int = Relevance.score(
        Relevance.normalize(name),
        Relevance.normalize(extra),
        Relevance.tokens(query),
        kind,
        distance,
    )

    @Test
    fun `fra due vie omonime vince quella vicina`() {
        // E' il caso che rendeva la ricerca inservibile: duecento "Via Roma"
        // toscane con punteggio identico, restituite in ordine di file.
        val mia = s("Via Roma", "Sesto Fiorentino", query = "via roma", distance = 900.0)
        val altrove = s("Via Roma", "Grosseto", query = "via roma", distance = 140_000.0)
        assertTrue(mia > altrove, "vicina $mia, lontana $altrove")
    }

    @Test
    fun `il nome esatto batte il nome che lo contiene`() {
        val esatto = s("Esselunga", "Firenze", query = "esselunga", kind = 4, distance = 3_000.0)
        val dentro = s("Parcheggio Esselunga", "Firenze", query = "esselunga", kind = 4, distance = 3_000.0)
        assertTrue(esatto > dentro, "esatto $esatto, dentro $dentro")
    }

    @Test
    fun `una via non viene schiacciata da una localita_ omonima`() {
        // Prima il tipo valeva fino a quindici punti: cercare la propria via
        // voleva dire perdere contro qualunque paese con lo stesso nome.
        val via = s("Via Agnoletti", "Sesto Fiorentino", query = "agnoletti", kind = 4, distance = 1_000.0)
        val localita = s("Agnoletti", "Arezzo", query = "agnoletti", kind = 6, distance = 100_000.0)
        assertTrue(via > localita, "via $via, localita $localita")
    }

    @Test
    fun `il contorno aiuta a trovare ma pesa meno del nome`() {
        val nelNome = s("Via Roma", "Empoli", query = "roma")
        val nelContorno = s("Via Verdi", "Roma", query = "roma")
        assertTrue(nelNome > nelContorno, "nome $nelNome, contorno $nelContorno")
    }

    @Test
    fun `le parole categoria fanno trovare la scuola`() {
        // "scuola" non compare nel nome del liceo: sta fra le parole-chiave.
        val trovato = s("Liceo Agnoletti", "Sesto Fiorentino scuola liceo", query = "scuola agnoletti sesto")
        assertTrue(trovato != Relevance.NO_MATCH)
    }

    @Test
    fun `su tre parole se ne puo_ sbagliare una`() {
        // L'etichetta geografica dice Campi Bisenzio, l'utente scrive Sesto:
        // il confine amministrativo non sta in testa a nessuno.
        val trovato = s("Liceo Agnoletti", "Campi Bisenzio scuola", query = "scuola agnoletti sesto")
        assertTrue(trovato != Relevance.NO_MATCH)
        val pieno = s("Liceo Agnoletti", "Sesto Fiorentino scuola", query = "scuola agnoletti sesto")
        assertTrue(pieno > trovato, "chi manca deve pagare: pieno $pieno, parziale $trovato")
    }

    @Test
    fun `su due parole devono esserci tutte e due`() {
        assertEquals(Relevance.NO_MATCH, s("Via Roma", "Grosseto", query = "via bologna"))
    }

    @Test
    fun `la parola intera batte il prefisso di un_altra parola`() {
        // Cercando "via roma 12" si finiva in via Romagnosi: per il
        // punteggio le due erano identiche, e Romagnosi viene prima in
        // ordine alfabetico.
        val intera = s("Roma Termini", query = "roma")
        val prefisso = s("Romagnosi Alberto", query = "roma")
        assertTrue(intera > prefisso, "intera $intera, prefisso $prefisso")
    }

    @Test
    fun `senza posizione nessuno viene penalizzato`() {
        val a = s("Via Roma", "Firenze", query = "via roma")
        val b = s("Via Roma", "Livorno", query = "via roma")
        assertEquals(a, b)
    }

    @Test
    fun `la vicinanza non ribalta una pertinenza molto migliore`() {
        // Un nome che combacia in pieno a quaranta chilometri deve battere
        // un aggancio debole sotto casa: la vicinanza aiuta a scegliere fra
        // simili, non a far vincere qualcosa che non c'entra.
        val lontanoEsatto = s("Esselunga", "Prato", query = "esselunga", distance = 35_000.0)
        val vicinoDebole = s(
            "Bar Da Gigi",
            "Firenze esselunga",
            query = "esselunga",
            distance = 300.0,
        )
        assertTrue(lontanoEsatto > vicinoDebole, "esatto $lontanoEsatto, debole $vicinoDebole")
    }
}
