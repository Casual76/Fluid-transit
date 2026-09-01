package dev.antigravity.fluidtransit.routing

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** luoghi.bin scritto e riletto, con la ricerca nei due stadi decisi. */
class PlacesRoundtripTest {

    private val tmp = ArrayList<File>()

    @AfterTest
    fun cleanup() {
        tmp.forEach { it.delete() }
    }

    private fun writeFile(): File {
        val file = File.createTempFile("luoghi", ".bin").also { tmp.add(it) }
        PlacesWriter.write(
            file,
            fast = listOf(
                PlaceEntry(Places.KIND_LOCALITY, "Sesto Fiorentino", "", 43.832, 11.199),
                PlaceEntry(Places.KIND_POI, "Liceo Agnoletti", "Sesto Fiorentino", 43.818, 11.199),
                PlaceEntry(Places.KIND_POI, "Caffè Piansa", "Firenze", 43.771, 11.254),
                PlaceEntry(Places.KIND_STREET, "Via Roma", "Firenze", 43.770, 11.254),
                PlaceEntry(Places.KIND_STREET, "Via Roma", "Prato", 43.880, 11.096),
            ),
            streets = listOf(
                StreetEntry(
                    name = "Via Roma",
                    context = "Firenze",
                    lat = 43.770,
                    lon = 11.254,
                    numbers = listOf(
                        Triple("10", 43.7701, 11.2541),
                        Triple("12", 43.7702, 11.2542),
                        Triple("12A", 43.7703, 11.2543),
                    ),
                ),
            ),
        )
        return file
    }

    @Test
    fun `il file torna come scritto`() {
        PlacesReader(writeFile()).use { r ->
            assertEquals(5, r.fastCount)
            assertEquals(1, r.streetCount)
            assertEquals(3, r.civiciCount)
            // Ordinati per (kind, nome): la localita' e' la prima.
            assertEquals(Places.KIND_LOCALITY, r.fastKind(0))
            assertEquals("Sesto Fiorentino", r.fastName(0))
            assertEquals("Via Roma", r.streetName(0))
            assertEquals("12A", r.civNumber(2))
            assertEquals(43.7703, r.civLat(2), 1e-9)
        }
    }

    @Test
    fun `la ricerca rapida trova la scuola col comune`() {
        PlacesReader(writeFile()).use { r ->
            val hits = PlacesSearch(r).fast("agnoletti sesto")
            assertTrue(hits.isNotEmpty(), "niente risultati")
            assertEquals("Liceo Agnoletti", hits[0].name)
            assertEquals("Sesto Fiorentino", hits[0].context)
        }
    }

    @Test
    fun `gli accenti non contano`() {
        PlacesReader(writeFile()).use { r ->
            val hits = PlacesSearch(r).fast("caffe piansa")
            assertEquals("Caffè Piansa", hits.firstOrNull()?.name)
        }
    }

    @Test
    fun `le due via roma restano distinte per comune`() {
        PlacesReader(writeFile()).use { r ->
            val hits = PlacesSearch(r).fast("via roma")
            assertEquals(2, hits.count { it.kind == Places.KIND_STREET })
        }
    }

    @Test
    fun `lo stadio civici capisce via roma 12 firenze`() {
        PlacesReader(writeFile()).use { r ->
            val hits = PlacesSearch(r).civici("via roma 12 firenze")
            assertTrue(hits.isNotEmpty(), "civico non trovato")
            assertEquals("Via Roma 12", hits[0].name)
            assertEquals(43.7702, hits[0].lat, 1e-9)
            // Il 12A e' il vicino plausibile, subito dopo.
            assertTrue(hits.any { it.name == "Via Roma 12A" })
        }
    }

    @Test
    fun `dopo close il file si cancella anche su Windows`() {
        val f = writeFile()
        val r = PlacesReader(f)
        r.fastName(0)
        r.close()
        assertTrue(f.delete(), "file ancora bloccato dopo close()")
    }
}
