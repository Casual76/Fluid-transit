package dev.antigravity.fluidtransit.bundler

import dev.antigravity.fluidtransit.routing.Ftb
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Il colore di una linea non cambia da una notte all'altra.
 *
 * Il colore e' il modo in cui una linea si riconosce da lontano: la
 * pastiglia nelle liste, la tratta sulla mappa, la carta grande della scheda
 * Oggi. Il 16/09, dopo il primo bundle nuovo dopo giorni, alla stessa
 * fermata quattro pastiglie su sette avevano cambiato tinta — il 37 da verde
 * a blu, la C3 da viola a verde — perche' l'assegnazione riparte da zero ogni
 * notte e l'ordine dipende dal grado nel grafo delle sovrapposizioni: basta
 * che una linea guadagni due fermate perche' scavalchi un'altra e le porti
 * via il colore.
 *
 * Qui si misura proprio quello: si colora una rete, la si perturba come fa
 * un feed vero da un giorno all'altro, e si conta quante linee cambiano
 * tinta con e senza il colore di ieri come preferenza.
 */
class RouteColoringStabilityTest {

    /** Una rete finta ma della forma giusta: linee che condividono fermate. */
    private fun rete(
        rnd: Random,
        routes: Int,
        stops: Int,
        perFermata: Int = 4,
    ): Pair<List<String>, List<List<Int>>> {
        val ids = (0 until routes).map { "R$it" }
        val atStop = (0 until stops).map {
            val quante = 1 + rnd.nextInt(perFermata)
            (0 until quante).map { rnd.nextInt(routes) }.distinct()
        }
        return ids to atStop
    }

    private fun mappa(ids: List<String>, colors: IntArray): Map<Long, Int> =
        ids.indices.associate { Ftb.hash64(ids[it]) to colors[it] }

    private fun cambiate(ids: List<String>, prima: IntArray, dopo: IntArray): Int =
        ids.indices.count { prima[it] != dopo[it] }

    @Test
    fun `col colore di ieri quasi nessuna linea cambia tinta`() {
        val rnd = Random(20260916)
        val (ids, ieri) = rete(rnd, routes = 120, stops = 900)
        val colIeri = RouteColoring.assign(ids, ieri)

        // La notte: il feed cambia un po'. Qualche fermata serve linee
        // diverse — e' esattamente quello che fa l'inizio della scuola, in
        // piccolo.
        val oggi = ieri.mapIndexed { i, s ->
            if (i % 100 == 0) (s + listOf(rnd.nextInt(120))).distinct() else s
        }

        val senza = RouteColoring.assign(ids, oggi)
        val con = RouteColoring.assign(ids, oggi, mappa(ids, colIeri))

        val cambiateSenza = cambiate(ids, colIeri, senza)
        val cambiateCon = cambiate(ids, colIeri, con)
        // Il numero esatto non conta e cambierebbe col seme: conta che il
        // colore di ieri tenga ferme le linee che possono restare ferme.
        // Misurato con questo seme: 42 cambi senza preferenza, 6 con. Le
        // soglie sono larghe perche' il numero esatto dipende dal seme e
        // dalla perturbazione; il rapporto fra i due no.
        assertTrue(
            cambiateCon * 5 < cambiateSenza,
            "col colore di ieri $cambiateCon cambi, senza $cambiateSenza",
        )
        assertTrue(cambiateCon < ids.size / 10, "troppe linee cambiate, $cambiateCon")
    }

    @Test
    fun `due linee che si incrociano hanno comunque colori diversi`() {
        val rnd = Random(7)
        // Una rete rada, dove dodici tinte bastano davvero: e' li' che la
        // promessa si puo' chiedere per intero.
        val (ids, rete) = rete(rnd, routes = 200, stops = 600, perFermata = 2)
        val ieri = RouteColoring.assign(ids, rete)
        // Preferenze volutamente ostili: tutte la stessa tinta.
        val tutteUguali = ids.indices.associate { Ftb.hash64(ids[it]) to 3 }
        val con = RouteColoring.assign(ids, rete, tutteUguali)

        val (conflittiIeri, coppie) = RouteColoring.conflicts(ieri, rete.map { it })
        val (conflittiCon, _) = RouteColoring.conflicts(con, rete.map { it })
        assertTrue(coppie > 0, "la rete di prova non ha sovrapposizioni")
        // Una preferenza non puo' MAI far dare lo stesso colore a due linee
        // che si incrociano: si prova per prima, ma se e' occupata si passa
        // oltre come sempre. Su una rete che dodici tinte bastano a colorare,
        // sono zero conflitti con o senza.
        assertEquals(0, conflittiIeri)
        assertEquals(0, conflittiCon)
    }

    @Test
    fun `senza preferenze si colora esattamente come prima`() {
        val rnd = Random(99)
        val (ids, rete) = rete(rnd, routes = 40, stops = 300)
        assertTrue(
            RouteColoring.assign(ids, rete).contentEquals(
                RouteColoring.assign(ids, rete, emptyMap()),
            ),
        )
    }
}
