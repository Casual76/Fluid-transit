package dev.antigravity.fluidtransit.routing

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La ricerca sui luoghi con le abbreviazioni, i nomi-data, i civici e i
 * refusi: i quattro modi in cui una persona scrive cio' che il file dice in
 * un altro.
 */
class PlacesSearchTest {

    private val tmp = ArrayList<File>()

    @AfterTest
    fun cleanup() {
        tmp.forEach { it.delete() }
    }

    private fun street(name: String, ctx: String, lat: Double, vararg nums: String) = StreetEntry(
        name = name,
        context = ctx,
        lat = lat,
        lon = 11.25,
        numbers = nums.mapIndexed { i, n -> Triple(n, lat + i * 1e-4, 11.25) },
    )

    private fun file(): File {
        val f = File.createTempFile("luoghi", ".bin").also { tmp.add(it) }
        PlacesWriter.write(
            f,
            fast = listOf(
                PlaceEntry(Places.KIND_POI, "Chiesa di S. Marco", "Firenze", 43.778, 11.259),
                PlaceEntry(Places.KIND_POI, "Basilica di Santa Croce", "Firenze", 43.768, 11.262),
                PlaceEntry(Places.KIND_STREET, "P.ZA ARNOLFO", "Colle di Val d'Elsa", 43.422, 11.123),
                PlaceEntry(Places.KIND_STREET, "V.le Lavagnini", "Firenze", 43.781, 11.247),
                PlaceEntry(Places.KIND_STREET, "Via Roma", "Firenze", 43.770, 11.254),
                PlaceEntry(Places.KIND_STREET, "Via XX Settembre", "Firenze", 43.775, 11.250),
                PlaceEntry(Places.KIND_STREET, "Via XXV Aprile", "Prato", 43.880, 11.096),
                PlaceEntry(Places.KIND_STREET, "Via Bolognese", "Firenze", 43.800, 11.270),
                PlaceEntry(Places.KIND_STREET, "Via IV Novembre", "Empoli", 43.720, 11.250),
                PlaceEntry(Places.KIND_POI, "Esselunga", "Firenze", 43.790, 11.240, keywords = "supermercato"),
                PlaceEntry(
                    Places.KIND_POI, "Liceo Agnoletti", "Sesto Fiorentino", 43.818, 11.199,
                    keywords = "scuola liceo istituto superiore",
                ),
                PlaceEntry(Places.KIND_POI, "Farmacia Comunale", "Prato", 43.881, 11.097),
            ),
            streets = listOf(
                street("Via Roma", "Firenze", 43.770, "10", "12", "12/A", "120"),
                street("Via XX Settembre", "Firenze", 43.775, "5", "20", "20/B"),
                street("Via XXV Aprile", "Prato", 43.880, "4", "25", "40"),
                street("Via 4 Novembre", "Empoli", 43.720, "4", "4A", "12", "40", "44"),
                street("Via Bolognese", "Firenze", 43.800, "12", "14"),
                street("Via Dante", "Siena", 43.318, "121", "12/1", "1/21"),
            ),
        )
        return f
    }

    private fun <T> search(block: (PlacesSearch) -> T): T =
        PlacesReader(file()).use { block(PlacesSearch(it)) }

    // --- #7: punteggiatura e abbreviazioni ------------------------------

    @Test
    fun `S_ Marco trova San Marco`() = search { s ->
        val hits = s.fast("S. Marco")
        assertEquals("Chiesa di S. Marco", hits.firstOrNull()?.name, "hit: ${hits.map { it.name }}")
    }

    @Test
    fun `S_ Croce trova Santa Croce`() = search { s ->
        // "san" e' prefisso di "santa": una sigla sola copre santo, santa, sant.
        val hits = s.fast("S. Croce")
        assertEquals("Basilica di Santa Croce", hits.firstOrNull()?.name, "hit: ${hits.map { it.name }}")
    }

    @Test
    fun `piazza arnolfo trova P_ZA ARNOLFO`() = search { s ->
        val hits = s.fast("piazza arnolfo")
        assertEquals("P.ZA ARNOLFO", hits.firstOrNull()?.name, "hit: ${hits.map { it.name }}")
    }

    @Test
    fun `v_le lavagnini e viale lavagnini trovano la stessa via`() = search { s ->
        assertEquals("V.le Lavagnini", s.fast("v.le lavagnini").firstOrNull()?.name)
        assertEquals("V.le Lavagnini", s.fast("viale lavagnini").firstOrNull()?.name)
    }

    @Test
    fun `via roma con la virgola e il civico trova il civico`() = search { s ->
        for (q in listOf("via Roma, 12", "Via Roma 12, Firenze", "via roma 12")) {
            val hits = s.civici(q)
            assertEquals("Via Roma 12", hits.firstOrNull()?.name, "'$q': ${hits.map { it.name }}")
        }
    }

    // --- #8: il civico non e' il primo numero ---------------------------

    @Test
    fun `via 25 aprile non inventa un indirizzo`() = search { s ->
        assertTrue(s.civici("via 25 aprile").isEmpty(), "uscito: ${s.civici("via 25 aprile").map { it.name }}")
        // E nemmeno scritta in romano.
        assertTrue(s.civici("via xxv aprile").isEmpty())
    }

    @Test
    fun `via 4 novembre 12 offre il 12 e non il 4`() = search { s ->
        val hits = s.civici("via 4 novembre 12")
        assertEquals("Via 4 Novembre 12", hits.firstOrNull()?.name, "hit: ${hits.map { it.name }}")
        assertTrue(hits.none { it.name == "Via 4 Novembre 4" || it.name == "Via 4 Novembre 40" })
    }

    @Test
    fun `via XX settembre 20 offre il 20 anche dopo la conversione dei romani`() = search { s ->
        val hits = s.civici("via xx settembre 20")
        assertEquals("Via XX Settembre 20", hits.firstOrNull()?.name, "hit: ${hits.map { it.name }}")
        // Il 20/B e' il vicino plausibile.
        assertTrue(hits.any { it.name == "Via XX Settembre 20/B" })
    }

    @Test
    fun `il 12 trova 12A e 12 barra A ma non il 120`() = search { s ->
        val nomi = s.civici("via roma 12").map { it.name }
        assertTrue("Via Roma 12" in nomi)
        assertTrue("Via Roma 12/A" in nomi, "manca il 12/A: $nomi")
        assertTrue("Via Roma 120" !in nomi, "il 120 non e' il 12: $nomi")
    }

    @Test
    fun `il civico con la lettera si trova in tutte le forme`() = search { s ->
        for (q in listOf("via roma 12/a", "via roma 12a", "via roma 12 a")) {
            val hits = s.civici(q)
            // "12 a" lascia "a" come parola di nome: basta che il 12/A esca.
            assertTrue(hits.any { it.name == "Via Roma 12/A" }, "'$q': ${hits.map { it.name }}")
        }
        assertEquals("Via Roma 12/A", s.civici("via roma 12/a").first().name)
    }

    @Test
    fun `un indirizzo col CAP trova il civico e non prende il CAP per civico`() = search { s ->
        // "Via Roma 12, 50123 Firenze" e' come si copia un indirizzo da Maps:
        // l'ultimo numero e' il CAP, il civico e' il 12.
        val nomi = s.civici("via roma 12 50123 firenze").map { it.name }
        assertTrue("Via Roma 12" in nomi, "manca il 12: $nomi")
    }

    @Test
    fun `121 e 12 barra 1 sono civici diversi`() = search { s ->
        val cerca121 = s.civici("via dante 121").map { it.name }
        assertTrue("Via Dante 121" in cerca121)
        assertTrue("Via Dante 12/1" !in cerca121, "il 12/1 non e' il 121: $cerca121")
        val internoUno = s.civici("via dante 12/1").map { it.name }
        assertTrue("Via Dante 12/1" in internoUno, "$internoUno")
        assertTrue("Via Dante 121" !in internoUno, "il 121 non e' il 12/1: $internoUno")
    }

    // --- #38: romani e cifre --------------------------------------------

    @Test
    fun `27 aprile e XXVII aprile sono la stessa via`() = search { s ->
        val cifre = s.fast("via 25 aprile").firstOrNull()
        val romano = s.fast("via xxv aprile").firstOrNull()
        assertEquals("Via XXV Aprile", cifre?.name)
        assertEquals("Via XXV Aprile", romano?.name)
    }

    @Test
    fun `una via scritta in romano si trova in cifre e viceversa`() = search { s ->
        assertEquals("Via XX Settembre", s.fast("via 20 settembre").firstOrNull()?.name)
        // E il contrario: nel file e' in romano, si scrive in cifre.
        assertEquals("Via IV Novembre", s.fast("via 4 novembre").firstOrNull()?.name)
        assertEquals("Via IV Novembre", s.fast("via iv novembre").firstOrNull()?.name)
    }

    // --- #10: i refusi nei luoghi ---------------------------------------

    @Test
    fun `un refuso su un luogo lo trova`() = search { s ->
        assertEquals("Esselunga", s.fast("essellunga").firstOrNull()?.name)
        assertEquals("Farmacia Comunale", s.fast("farmcia comunale").firstOrNull()?.name)
        assertEquals("Liceo Agnoletti", s.fast("liceo agnoleti").firstOrNull()?.name)
    }

    @Test
    fun `un refuso su un indirizzo lo trova`() = search { s ->
        val hits = s.civici("via bolognesse 12")
        assertEquals("Via Bolognese 12", hits.firstOrNull()?.name, "hit: ${hits.map { it.name }}")
    }

    @Test
    fun `chi combacia davvero batte chi somiglia`() = search { s ->
        // "via roma" esatta non passa dal ripiego, e una via che somiglia non
        // la supera.
        val hits = s.fast("via roma")
        assertEquals("Via Roma", hits.firstOrNull()?.name)
    }

    @Test
    fun `il refuso non inventa risultati dal nulla`() = search { s ->
        assertTrue(s.fast("zzzzqqqq").isEmpty())
        assertTrue(s.civici("via zzzzqqqq 12").isEmpty())
    }
}
