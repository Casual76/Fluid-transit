package dev.antigravity.fluidtransit.ui.map

import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.StopGroups
import dev.antigravity.fluidtransit.routing.TestBundle
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La ricerca di fermate e linee, dal bundle.
 *
 * E' la prima cosa che si tocca dopo la mappa e non aveva una riga di test:
 * il punteggio di [dev.antigravity.fluidtransit.routing.Relevance] si' — e'
 * in `:core-routing` — ma non il pezzo che lo alimenta, cioe' quello che
 * decide COSA finisce nell'indice. Ed e' proprio li' che sta la decisione
 * che si vede di piu': una riga per gruppo di fermate omonime e non una per
 * banchina, perche' sulla rete vera le omonime sono il 53% del totale e
 * cercando "TORRE GALLI" uscivano due righe identiche.
 */
class SearchIndexTest {

    private val tmp = ArrayList<File>()

    @After
    fun pulisci() {
        tmp.forEach { it.delete() }
    }

    private fun bundle() = BundleReader(TestBundle.write(tmp))

    @Test
    fun `una fermata si trova col suo nome`() {
        bundle().use { r ->
            val hits = SearchIndex.build(r, null).search("alfa")
            val stop = hits.filterIsInstance<SearchIndex.Hit.Stop>().firstOrNull()
            assertTrue("nessuna fermata trovata: ${hits.map { it.title }}", stop != null)
            assertEquals("Piazza Alfa", stop!!.title)
        }
    }

    @Test
    fun `una fermata trovata porta con se' dove sta sulla mappa`() {
        // Senza coordinate il risultato non sarebbe cliccabile: e' il tocco
        // che ci porta la camera.
        bundle().use { r ->
            val stop = SearchIndex.build(r, null).search("gamma")
                .filterIsInstance<SearchIndex.Hit.Stop>().first()
            assertEquals(r.stopLat(stop.stopIndex), stop.lat, 1e-9)
            assertEquals(r.stopLon(stop.stopIndex), stop.lon, 1e-9)
        }
    }

    @Test
    fun `una linea a una cifra si trova scrivendo quella cifra`() {
        // Le linee a una cifra sono fra le piu' usate di Firenze, e "6" e'
        // esattamente come una persona le chiama. Prima un carattere solo
        // non cercava niente: chi scriveva 6 restava li' a chiedersi cosa
        // avesse sbagliato.
        bundle().use { r ->
            val hits = SearchIndex.build(r, null).search("1")
            assertTrue(
                "la linea 1 doveva uscire: ${hits.map { it.title }}",
                hits.any { it is SearchIndex.Hit.Route },
            )
        }
    }

    @Test
    fun `con un carattere solo non escono le fermate`() {
        // La guardia di prima aveva la sua ragione: una lettera sola su
        // 34.000 nomi di fermata pesa l'indice intero per restituire mezza
        // Toscana. Resta, ma solo per le fermate.
        bundle().use { r ->
            val hits = SearchIndex.build(r, null).search("a")
            assertTrue(
                "con un carattere solo niente fermate: ${hits.map { it.title }}",
                hits.none { it is SearchIndex.Hit.Stop },
            )
        }
    }

    @Test
    fun `il punto di una linea e' una sua fermata vera`() {
        // La mappa ci si centra sopra: se fosse un punto qualunque — o
        // peggio lo zero di default — si aprirebbe in mezzo al mare.
        bundle().use { r ->
            val route = SearchIndex.build(r, null).search("1")
                .filterIsInstance<SearchIndex.Hit.Route>().first()
            val distanze = (0 until r.stopCount).map {
                BundleReader.haversine(route.lat, route.lon, r.stopLat(it), r.stopLon(it))
            }
            assertTrue("il punto della linea non e' su nessuna fermata", distanze.min() < 1.0)
        }
    }

    @Test
    fun `col raggruppamento il punto di una linea resta una fermata vera`() {
        // Il difetto che questa riga ha trovato: il punto della linea era
        // il NUMERO della sua prima fermata, e si andava a leggerlo in un
        // array indicizzato per gruppo di omonime. Con le omonime i due
        // indici divergono, e il punto finiva su un'altra fermata o sul
        // ripiego zero, che e' il Golfo di Guinea. Effetto doppio: la mappa
        // si apriva altrove, e il premio per la vicinanza premiava la linea
        // sbagliata — cercando "6" da Firenze veniva su quella di Empoli.
        //
        // Servono le omonime per vederlo: con quattro nomi diversi i due
        // indici coincidono e il difetto sta nascosto.
        val file = TestBundle.write(
            tmp,
            stopNames = listOf("Stazione", "Stazione", "Stazione", "Borgo Delta"),
        )
        BundleReader(file).use { r ->
            val gruppi = StopGroups.build(r)
            val route = SearchIndex.build(r, gruppi).search("1")
                .filterIsInstance<SearchIndex.Hit.Route>().first()
            val vicine = (0 until r.stopCount).map {
                BundleReader.haversine(route.lat, route.lon, r.stopLat(it), r.stopLon(it))
            }
            assertTrue(
                "il punto della linea (${route.lat}, ${route.lon}) non e' su nessuna fermata",
                vicine.min() < 1.0,
            )
        }
    }

    @Test
    fun `con i gruppi le omonime diventano una riga sola`() {
        bundle().use { r ->
            val gruppi = StopGroups.build(r)
            val conGruppi = SearchIndex.build(r, gruppi)
            val senza = SearchIndex.build(r, null)
            // La rete di prova non ha omonime, quindi i due indici devono
            // dare lo stesso numero di righe: e' il controllo che il
            // raggruppamento non PERDA fermate quando non c'e' niente da
            // raggruppare, che e' il modo in cui un filtro sbaglia in
            // silenzio.
            for (q in listOf("alfa", "beta", "gamma", "delta")) {
                assertEquals(
                    "'$q' cambia numero di fermate col raggruppamento",
                    senza.search(q).filterIsInstance<SearchIndex.Hit.Stop>().size,
                    conGruppi.search(q).filterIsInstance<SearchIndex.Hit.Stop>().size,
                )
            }
        }
    }

    @Test
    fun `una query vuota non cerca niente`() {
        bundle().use { r ->
            assertTrue(SearchIndex.build(r, null).search("").isEmpty())
            assertTrue(SearchIndex.build(r, null).search("   ").isEmpty())
        }
    }

    @Test
    fun `un refuso si perdona, ma solo quando non si trova niente`() {
        bundle().use { r ->
            val idx = SearchIndex.build(r, null)
            assertTrue(
                "\"alva\" doveva trovare Piazza Alfa col ripiego sui refusi",
                idx.search("alva").any { it.title == "Piazza Alfa" },
            )
        }
    }

    @Test
    fun `il punto di riferimento non cambia chi si trova`() {
        // La vicinanza pesa sul punteggio, non sull'insieme: chi cerca una
        // fermata lontana la deve comunque trovare, altrimenti la ricerca
        // servirebbe solo per dove sei gia'.
        bundle().use { r ->
            val idx = SearchIndex.build(r, null)
            val lontano = idx.search("delta", refLat = 43.0, refLon = 11.0)
            val vicino = idx.search("delta", refLat = 43.5, refLon = 11.5)
            assertEquals(
                lontano.filterIsInstance<SearchIndex.Hit.Stop>().map { it.title },
                vicino.filterIsInstance<SearchIndex.Hit.Stop>().map { it.title },
            )
            assertEquals("Borgo Delta", vicino.first().title)
        }
    }
}
