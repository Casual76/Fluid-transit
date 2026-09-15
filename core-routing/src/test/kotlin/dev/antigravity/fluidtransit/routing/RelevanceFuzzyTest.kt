package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La ricerca che perdona un refuso.
 *
 * Due proprieta' opposte, e la seconda conta quanto la prima: chi scrive
 * "soderni" deve trovare Soderini, e chi scrive "fonte" NON deve trovare
 * Ponte al posto di quello che ha chiesto. Per questo la tolleranza si accende
 * solo quando la ricerca esatta non ha trovato niente, e un risultato trovato
 * per somiglianza vale sempre meno di uno trovato per davvero.
 */
class RelevanceFuzzyTest {

    private val rete = listOf(
        "soderini torrino santa rosa",
        "ospedale torre galli",
        "piazza beccaria",
        "careggi ingresso",
        "fonte dei seppi",
        "ponte a greve",
    )

    /** Il punteggio migliore, e chi lo prende. */
    private fun cerca(query: String, fuzzy: Boolean): Pair<String, Int>? {
        val tokens = Relevance.tokens(query)
        if (tokens.isEmpty()) return null
        val s = Relevance.Session(tokens, fuzzy)
        for ((i, nome) in rete.withIndex()) s.observe(i, nome, nome.length)
        var best: Pair<String, Int>? = null
        for (k in 0 until s.candidateCount) {
            val punteggio = s.score(k, 0, -1.0)
            if (best == null || punteggio > best!!.second) {
                best = rete[s.candidateId(k)] to punteggio
            }
        }
        return best
    }

    @Test
    fun `senza tolleranza un refuso non trova niente`() {
        assertEquals(null, cerca("soderni", fuzzy = false))
    }

    @Test
    fun `con la tolleranza il refuso trova la fermata giusta`() {
        assertEquals("soderini torrino santa rosa", cerca("soderni", fuzzy = true)?.first)
    }

    @Test
    fun `i refusi veri del telefono`() {
        // Lettera in meno, lettera in piu', lettera sbagliata, e due lettere
        // scambiate — che e' il piu' comune di tutti.
        val casi = mapOf(
            "soderni" to "soderini torrino santa rosa", // manca una lettera
            "soderiini" to "soderini torrino santa rosa", // una di troppo
            "soderona" to null, // troppo diverso: due modifiche
            "careggu" to "careggi ingresso", // lettera sbagliata
            "cargegi" to "careggi ingresso", // due lettere scambiate
            "beccarai" to "piazza beccaria", // scambio in coda
        )
        for ((query, atteso) in casi) {
            assertEquals(atteso, cerca(query, fuzzy = true)?.first, "cercando \"$query\"")
        }
    }

    @Test
    fun `una parola corta non si tollera`() {
        // A tre lettere una modifica copre mezzo vocabolario: "via" sarebbe
        // "vie", "vai", "vi", "ia". Meglio nessun risultato che uno a caso.
        //
        // "dea" sta a una sostituzione da "dei", che nella rete di prova
        // c'e': senza il limite sulla lunghezza uscirebbe "fonte dei seppi".
        assertEquals(null, cerca("dea", fuzzy = true))
    }

    @Test
    fun `chi combacia davvero batte sempre chi somiglia`() {
        // "fonte" esiste: la tolleranza non deve poter far vincere "ponte".
        // E' la ragione per cui la somiglianza vale meno dell'incontro piu'
        // debole, non un caso fortunato dei punteggi.
        val esatto = cerca("fonte", fuzzy = false)
        assertEquals("fonte dei seppi", esatto?.first)

        val conTolleranza = cerca("fonte", fuzzy = true)
        assertEquals(
            "fonte dei seppi",
            conTolleranza?.first,
            "la somiglianza ha superato l'incontro vero",
        )
    }

    @Test
    fun `la tolleranza non inventa risultati dal nulla`() {
        // Una parola che non assomiglia a niente resta senza risposta: un
        // risultato sbagliato e' peggio di nessun risultato.
        assertEquals(null, cerca("zzzzzzzz", fuzzy = true))
        assertEquals(null, cerca("mercato centrale", fuzzy = true))
    }

    @Test
    fun `le parole che combaciano davvero continuano a contare di piu'`() {
        // Con la tolleranza accesa, una query esatta deve dare lo stesso
        // risultato di prima: la seconda passata non cambia le regole, le
        // allarga solo dove non c'era niente.
        val senza = cerca("torre galli", fuzzy = false)
        val con = cerca("torre galli", fuzzy = true)
        assertEquals(senza?.first, con?.first)
        assertTrue(
            con!!.second >= senza!!.second,
            "la tolleranza ha abbassato un punteggio esatto",
        )
    }
}
