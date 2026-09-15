package dev.antigravity.fluidtransit.routing

import java.io.File
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scrive un bundle sintetico minuscolo e lo rilegge. E' sia il test di
 * roundtrip del formato 2 sia la sua documentazione eseguibile: chi vuole
 * sapere come e' fatta una sezione la trova costruita qui, campo per campo.
 *
 * La rete: quattro fermate (A, B, C vicine in fila; D lontana), una linea,
 * un pattern A->B->C con due corse - una diurna alle 08:00 e una notturna
 * alle 25:30, che e' il caso che le app sbagliano.
 */
class BundleRoundtripTest {

    private val tmp = ArrayList<File>()

    // La rete di prova sta in TestBundle: la usano anche altri test.
    private val feedStart = TestBundle.feedStart
    private val dayCount = TestBundle.dayCount

    private fun writeBundle(): File = TestBundle.write(tmp)

    @AfterTest
    fun cleanup() {
        tmp.forEach { it.delete() }
    }


    @Test
    fun `la polilinea del pattern torna con l'aggancio delle fermate`() {
        BundleReader(writeBundle()).use { r ->
            assertTrue(r.hasPolylines)
            val poly = r.patternPolyline(0)!!
            assertEquals(5, poly.size)
            assertEquals(43.0005, poly.lat[1], 1e-9)
            assertEquals(11.0002, poly.lon[1], 1e-9)
            assertEquals(43.002, poly.lat[4], 1e-9)
            assertEquals(11.0, poly.lon[4], 1e-9)
            assertEquals(0, r.patternStopVertex(0, 0))
            assertEquals(2, r.patternStopVertex(0, 1))
            assertEquals(4, r.patternStopVertex(0, 2))
        }
    }

    // --- i test -------------------------------------------------------------

    @Test
    fun `header e sezioni tornano come scritti`() {
        BundleReader(writeBundle()).use { r ->
            assertEquals(feedStart, r.feedStart)
            assertEquals(dayCount, r.dayCount)
            assertEquals(TestBundle.maxTripEnd, r.maxTripEndSeconds)
            assertEquals(4, r.stopCount)
            assertEquals(1, r.routeCount)
            assertEquals(1, r.patternCount)
            assertEquals(2, r.tripCount)
            assertEquals(1, r.profileCount)
            assertTrue(r.verifyChecksums().isEmpty(), "CRC rotti: ${r.verifyChecksums()}")
        }
    }

    @Test
    fun `il buildId e' deterministico`() {
        val a = BundleReader(writeBundle()).use { it.buildId }
        val b = BundleReader(writeBundle()).use { it.buildId }
        assertEquals(a, b, "due build identici devono avere lo stesso buildId")
    }

    @Test
    fun `fermate, linee e stringhe`() {
        BundleReader(writeBundle()).use { r ->
            assertEquals("Piazza Alfa", r.stopName(0))
            assertEquals("Borgo Delta", r.stopName(3))
            assertEquals(43.001, r.stopLat(1), 1e-6)
            assertEquals("1", r.routeShortName(0))
            assertEquals("at - Test urbano", r.routeAgency(0))
            assertEquals(3, r.routeType(0))
            assertEquals(0x15AC96, r.routeColor(0))
            assertEquals(0x9B6DD6, r.routeDisplayColor(0))
        }
    }

    @Test
    fun `gli hash identificano fermate e linee attraverso i bundle`() {
        BundleReader(writeBundle()).use { r ->
            // Il contratto dei preferiti: quello che salvi oggi risolve domani.
            for (i in 0 until 4) {
                assertEquals(Ftb.hash64(TestBundle.stopIds[i]), r.stopIdHash(i))
                assertEquals(i, r.findStopByIdHash(Ftb.hash64(TestBundle.stopIds[i])))
            }
            assertEquals(-1, r.findStopByIdHash(Ftb.hash64("fermata-inventata")))
            assertEquals(0, r.findRouteByIdHash(Ftb.hash64(TestBundle.routeId)))
            assertEquals(-1, r.findRouteByIdHash(Ftb.hash64("R99")))
        }
    }

    @Test
    fun `trip_id e matcher secondario`() {
        BundleReader(writeBundle()).use { r ->
            assertEquals(0, r.findTripByTripId("R1-morning"))
            assertEquals(1, r.findTripByTripId("R1-night"))
            assertEquals(-1, r.findTripByTripId("R1-mai-esistita"))
            // Il matcher secondario: linea + direzione + orario di partenza.
            assertEquals(0, r.findTripByRouteAndDeparture(Ftb.hash64(TestBundle.routeId), 0, 8 * 3600))
            assertEquals(1, r.findTripByRouteAndDeparture(Ftb.hash64(TestBundle.routeId), 0, TestBundle.dep0s[1]))
            assertEquals(-1, r.findTripByRouteAndDeparture(Ftb.hash64(TestBundle.routeId), 1, 8 * 3600))
            assertEquals(-1, r.findTripByRouteAndDeparture(Ftb.hash64(TestBundle.routeId), 0, 9 * 3600))
        }
    }

    @Test
    fun `la ricerca spaziale trova le vicine e non la lontana`() {
        BundleReader(writeBundle()).use { r ->
            val near = r.stopsNear(43.001, 11.0, 500.0).sorted()
            assertContentEquals(listOf(0, 1, 2), near)
            assertTrue(r.stopsNear(42.0, 10.0, 500.0).isEmpty())
        }
    }

    @Test
    fun `i transfer tornano nei due versi`() {
        BundleReader(writeBundle()).use { r ->
            val fromA = r.transfersFrom(0)
            assertEquals(1, fromA.size)
            assertEquals(1, fromA[0].targetStop)
            assertEquals(100, fromA[0].seconds)
            assertEquals(0, r.transfersFrom(1)[0].targetStop)
            assertTrue(r.transfersFrom(2).isEmpty())
            assertTrue(r.transfersFrom(3).isEmpty())
        }
    }

    @Test
    fun `prossimi passaggi alle 07_50 trovano la corsa delle 08_00`() {
        BundleReader(writeBundle()).use { r ->
            val date = feedStart.plusDays(3)
            val now = Ftb.serviceDayStart(date).plusSeconds(7 * 3600 + 50 * 60)
            val deps = r.nextDepartures(stop = 1, now = now)
            assertEquals(1, deps.size)
            assertEquals(0, deps[0].tripIndex)
            // Alla fermata B (posizione 1) la corsa passa 120 s dopo la partenza.
            assertEquals(
                Ftb.serviceDayStart(date).plusSeconds((8 * 3600 + 120).toLong()),
                deps[0].instant,
            )
            assertEquals("Corso Gamma", r.patternDestination(deps[0].patternIndex))
        }
    }

    @Test
    fun `alle 01_15 la corsa notturna di ieri esiste ancora`() {
        // La corsa parte alle 25:30 del giorno di servizio precedente, cioe'
        // all'01:30 di orologio: chi interroga solo "oggi" la perde, ed e'
        // l'errore per cui certe app dicono che l'ultimo bus non esiste.
        BundleReader(writeBundle()).use { r ->
            val serviceDate = feedStart.plusDays(5)
            val now = Ftb.serviceDayStart(serviceDate).plusSeconds(25 * 3600 + 15 * 60)
            assertEquals(1, now.atZone(Ftb.ROME).hour, "premessa: e' l'una di notte")
            val deps = r.nextDepartures(stop = 0, now = now)
            assertEquals(1, deps.size)
            assertEquals(1, deps[0].tripIndex)
            assertEquals(serviceDate, deps[0].serviceDate, "il giorno di servizio e' ieri")
        }
    }

    @Test
    fun `dopo close il file si puo' cancellare anche su Windows`() {
        val file = writeBundle()
        val r = BundleReader(file)
        r.stopName(0) // tocca davvero la mappa
        r.close()
        assertTrue(file.delete(), "il file e' rimasto bloccato dopo close()")
    }

    @Test
    fun `un CRC rotto viene scoperto alla prima lettura della sezione`() {
        val file = writeBundle()
        // Corrompe un byte dentro la sezione STOPS senza toccare l'header.
        java.io.RandomAccessFile(file, "rw").use { raf ->
            // La prima sezione per id e' STRINGS(1)... troviamo STOPS dalla tabella.
            raf.seek(Ftb.OFF_SECTION_TABLE.toLong())
            var stopsOffset = -1L
            val count = 14 // basta scorrere le voci presenti
            for (i in 0 until count) {
                val base = Ftb.OFF_SECTION_TABLE + i * Ftb.SECTION_ENTRY_SIZE
                raf.seek(base.toLong())
                val id = Integer.reverseBytes(raf.readInt()) // il file e' little-endian
                if (id == Ftb.S_STOPS) {
                    raf.seek((base + 8).toLong())
                    stopsOffset = java.lang.Long.reverseBytes(raf.readLong())
                    break
                }
            }
            assertTrue(stopsOffset > 0, "sezione STOPS non trovata nella tabella")
            raf.seek(stopsOffset + 16)
            raf.writeByte(0x7f)
        }
        val r = BundleReader(file)
        assertEquals(4, runCatching { r.stopCount }.let { result ->
            // La prima lettura della sezione deve fallire con il CRC.
            assertTrue(result.isFailure, "la corruzione non e' stata rilevata")
            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue("STOPS" in message, "messaggio senza nome sezione: $message")
            4
        })
        r.close()
    }
}
