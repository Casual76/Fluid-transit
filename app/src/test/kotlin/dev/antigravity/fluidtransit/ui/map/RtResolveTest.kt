package dev.antigravity.fluidtransit.ui.map

import dev.antigravity.fluidtransit.data.rt.RtDelay
import dev.antigravity.fluidtransit.data.rt.RtDelays
import dev.antigravity.fluidtransit.data.rt.RtVehicle
import dev.antigravity.fluidtransit.data.rt.RtVehicles
import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Ftb
import dev.antigravity.fluidtransit.routing.TestBundle
import java.io.File
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chi e' quel bus, e quale corsa sta facendo.
 *
 * E' il punto in cui il feed incontra gli orari, ed e' l'anello da cui
 * dipendono tutti e tre i sintomi descritti all'inizio: un mezzo che cambia
 * identita' e' un teletrasporto, una corsa non agganciata e' un numero senza
 * ritardo, e un'igiene sbagliata mette sulla mappa mezzi in deposito.
 *
 * Fino a stanotte non c'era una riga che lo provasse, e la ragione era di
 * costruzione: i test di `:app` non avevano un lettore di orari da usare.
 * Adesso ce l'hanno, perche' la rete di prova di `:core-routing` e'
 * diventata un testFixture invece di stare dentro i suoi soli test.
 */
class RtResolveTest {

    private val tmp = ArrayList<File>()

    @After
    fun pulisci() {
        tmp.forEach { it.delete() }
    }

    private fun bundle() = BundleReader(TestBundle.write(tmp))

    /** Un veicolo con i valori "non dichiarato" del feed, salvo quelli passati. */
    private fun veicolo(
        tripHash: Long = 0L,
        routeHash: Long = 0L,
        fixAgeSec: Int = 10,
        startTimeSec: Int = -1,
        direction: Int = -1,
        vehKey: Int = 0,
    ) = RtVehicle(
        tripHash = tripHash,
        routeHash = routeHash,
        lat = 43.0005,
        lon = 11.0,
        bearingDeg = -1,
        fixAgeSec = fixAgeSec,
        startTimeSec = startTimeSec,
        direction = direction,
        speedMs = -1.0,
        vehKey = vehKey,
    )

    /** Lo snapshot generato adesso: se fosse vecchio, l'eta' dei fix crescerebbe. */
    private fun snapshot(vararg v: RtVehicle) = RtVehicles(
        generatedAt = Instant.now().epochSecond,
        feedTimestamp = Instant.now().epochSecond,
        list = v.toList(),
    )

    private fun ritardi(vararg d: RtDelay) = RtDelays(
        generatedAt = Instant.now().epochSecond,
        feedTimestamp = Instant.now().epochSecond,
        byTripHash = d.associateBy { it.tripHash },
    )

    private fun ritardo(
        tripHash: Long,
        delaySec: Int = 0,
        canceled: Boolean = false,
        noData: Boolean = false,
    ) = RtDelay(
        tripHash = tripHash,
        routeHash = 0L,
        startTimeSec = -1,
        delaySec = delaySec,
        canceled = canceled,
        noData = noData,
        direction = -1,
        nextStopSeq = 0,
    )

    private val tripMattina = Ftb.hash64(TestBundle.tripIds[0])
    private val tripNotte = Ftb.hash64(TestBundle.tripIds[1])
    private val routeAlfa = Ftb.hash64(TestBundle.routeId)

    @Test
    fun `un mezzo con la sua corsa la trova negli orari`() {
        bundle().use { r ->
            val out = resolveRt(r, snapshot(veicolo(tripHash = tripMattina, vehKey = 7)), null)
            assertEquals(1, out.buses.size)
            val meta = out.busMetaByKey[7]!!
            assertTrue("la corsa deve risolversi", meta.tripIndex >= 0)
            assertTrue("e con lei la linea", meta.routeIndex >= 0)
            assertEquals(100, out.resolvedPercent)
            assertEquals(7, out.vehicleByTrip[meta.tripIndex])
        }
    }

    @Test
    fun `un trip_id che il bundle non conosce non aggancia niente`() {
        bundle().use { r ->
            val v = veicolo(tripHash = Ftb.hash64("mai-visto"), vehKey = 7)
            val out = resolveRt(r, snapshot(v), null)
            assertEquals("si disegna lo stesso: c'e' ed e' vivo", 1, out.buses.size)
            assertEquals(-1, out.busMetaByKey[7]!!.tripIndex)
            assertEquals("e la percentuale deve accorgersene", 0, out.resolvedPercent)
        }
    }

    @Test
    fun `senza trip_id si aggancia per linea, direzione e ora di partenza`() {
        // E' il matcher secondario: le due generazioni di dati non sono
        // sincronizzate e i trip_id orfani sono la normalita'.
        bundle().use { r ->
            val v = veicolo(
                routeHash = routeAlfa,
                startTimeSec = TestBundle.dep0s[0],
                direction = 0,
                vehKey = 7,
            )
            val out = resolveRt(r, snapshot(v), null)
            assertTrue("doveva agganciare", out.busMetaByKey[7]!!.tripIndex >= 0)
            assertNull(
                "ma la percentuale misura i trip_id, e qui non ce n'era nessuno",
                out.resolvedPercent,
            )
        }
    }

    @Test
    fun `l'aggancio secondario pretende l'ora esatta`() {
        // Scritto per dire com'e' adesso, non per dire che vada bene: un
        // minuto di scarto fra le due generazioni di dati lascia la corsa
        // orfana. Se un giorno si mette una tolleranza, questa riga diventa
        // rossa e va riscritta di proposito.
        bundle().use { r ->
            val v = veicolo(
                routeHash = routeAlfa,
                startTimeSec = TestBundle.dep0s[0] + 60,
                direction = 0,
                vehKey = 7,
            )
            val out = resolveRt(r, snapshot(v), null)
            assertEquals(-1, out.busMetaByKey[7]!!.tripIndex)
        }
    }

    @Test
    fun `i mezzi senza corsa e senza linea non sono bus vivi`() {
        // Misurato sul feed vero: ~300 su 1100 sono depositi e fuori
        // servizio. Metterli sulla mappa vuol dire una Toscana piena di bus
        // che non portano nessuno da nessuna parte.
        bundle().use { r ->
            val out = resolveRt(r, snapshot(veicolo(vehKey = 7)), null)
            assertTrue(out.buses.isEmpty())
        }
    }

    @Test
    fun `un fix piu' vecchio di dieci minuti non si disegna`() {
        bundle().use { r ->
            val vivo = veicolo(tripHash = tripMattina, fixAgeSec = 300, vehKey = 7)
            val morto = veicolo(tripHash = tripMattina, fixAgeSec = 900, vehKey = 8)
            val out = resolveRt(r, snapshot(vivo, morto), null)
            assertEquals(1, out.buses.size)
            assertEquals(7, out.buses.first().vehKey)
        }
    }

    @Test
    fun `l'eta' del fix arriva a adesso, non resta ferma allo snapshot`() {
        // Fra il rilevamento e il disegno ci sono il cron, la cache dell'edge
        // e il giro di poll: decine di secondi, che a 30 km/h sono centinaia
        // di metri. Non sommarli disegnava ogni mezzo sistematicamente
        // indietro, e dava al moto sulla strada un bersaglio fermo che poi lo
        // tirava all'indietro.
        bundle().use { r ->
            val vecchio = RtVehicles(
                generatedAt = Instant.now().epochSecond - 45,
                feedTimestamp = Instant.now().epochSecond - 45,
                list = listOf(veicolo(tripHash = tripMattina, fixAgeSec = 10, vehKey = 7)),
            )
            val eta = resolveRt(r, vecchio, null).buses.first().fixAgeSec
            assertTrue("l'eta' doveva crescere coi secondi passati: $eta", eta >= 50)
        }
    }

    @Test
    fun `un'eta' ignota resta ignota`() {
        bundle().use { r ->
            val v = veicolo(tripHash = tripMattina, fixAgeSec = -1, vehKey = 7)
            val out = resolveRt(r, snapshot(v), null)
            assertEquals(-1, out.buses.first().fixAgeSec)
        }
    }

    @Test
    fun `senza vehicle id due mezzi su due corse restano due mezzi`() {
        // Prima della Fase 8 finivano tutti sulla chiave 0: un marker solo,
        // che a ogni poll rimbalzava da un capo all'altro della Toscana.
        bundle().use { r ->
            val a = veicolo(tripHash = tripMattina, vehKey = 0)
            val b = veicolo(tripHash = tripNotte, vehKey = 0)
            val out = resolveRt(r, snapshot(a, b), null)
            assertEquals(2, out.buses.size)
            assertEquals(
                "due chiavi diverse, o e' un marker solo che salta",
                2,
                out.buses.map { it.vehKey }.toSet().size,
            )
            assertFalse("e nessuna chiave a zero", out.buses.any { it.vehKey == 0 })
        }
    }

    @Test
    fun `lo stesso mezzo due volte resta un mezzo solo`() {
        // Capita al cambio di corsa di un blocco: il feed pubblica il
        // veicolo due volte, una per la corsa che finisce e una per quella
        // che comincia, con due posizioni diverse. La lista arriva ai marker,
        // che sono indicizzati per chiave: due record con la stessa chiave
        // davano al marker due rilevamenti in conflitto nello stesso istante,
        // e a ogni giro rimbalzava fra i due punti.
        bundle().use { r ->
            val vecchio = veicolo(tripHash = tripMattina, fixAgeSec = 120, vehKey = 7)
            val fresco = veicolo(tripHash = tripNotte, fixAgeSec = 5, vehKey = 7)
            val out = resolveRt(r, snapshot(vecchio, fresco), null)
            assertEquals(1, out.buses.size)
            assertEquals("deve vincere il rilevamento piu' fresco", 5, out.buses.first().fixAgeSec)
        }
    }

    @Test
    fun `fra due doppioni l'ordine nel feed non decide`() {
        bundle().use { r ->
            val fresco = veicolo(tripHash = tripNotte, fixAgeSec = 5, vehKey = 7)
            val vecchio = veicolo(tripHash = tripMattina, fixAgeSec = 120, vehKey = 7)
            val out = resolveRt(r, snapshot(fresco, vecchio), null)
            assertEquals(1, out.buses.size)
            assertEquals(5, out.buses.first().fixAgeSec)
            // E anche i dettagli del tap devono essere quelli del vincitore.
            assertEquals(r.findTripByIdHash(tripNotte), out.busMetaByKey[7]!!.tripIndex)
        }
    }

    @Test
    fun `un doppione senza eta' perde contro uno con l'eta'`() {
        // L'eta' ignota non e' "appena arrivato": e' "non lo so", e fra le
        // due si sceglie quella che si puo' giudicare.
        bundle().use { r ->
            val ignoto = veicolo(tripHash = tripMattina, fixAgeSec = -1, vehKey = 7)
            val noto = veicolo(tripHash = tripNotte, fixAgeSec = 200, vehKey = 7)
            val out = resolveRt(r, snapshot(ignoto, noto), null)
            assertEquals(1, out.buses.size)
            assertTrue(out.buses.first().fixAgeSec >= 200)
        }
    }

    @Test
    fun `un ritardo dichiarato arriva alla corsa giusta`() {
        bundle().use { r ->
            val trip = r.findTripByIdHash(tripMattina)
            val out = resolveRt(r, snapshot(), ritardi(ritardo(tripMattina, delaySec = 180)))
            assertEquals(180, out.delayByTrip[trip])
            assertTrue(out.canceledTrips.isEmpty())
        }
    }

    @Test
    fun `una corsa cancellata non porta anche un ritardo`() {
        // Un ritardo su una corsa che non parte e' un numero che promette un
        // bus: le due liste devono restare separate.
        bundle().use { r ->
            val trip = r.findTripByIdHash(tripMattina)
            val d = ritardo(tripMattina, delaySec = 180, canceled = true)
            val out = resolveRt(r, snapshot(), ritardi(d))
            assertTrue(out.canceledTrips.contains(trip))
            assertNull(out.delayByTrip[trip])
        }
    }

    @Test
    fun `no_data vuol dire che non sappiamo, non che e' in orario`() {
        bundle().use { r ->
            val trip = r.findTripByIdHash(tripMattina)
            val out = resolveRt(r, snapshot(), ritardi(ritardo(tripMattina, noData = true)))
            assertNull("zero e' un'informazione, l'assenza no", out.delayByTrip[trip])
        }
    }

    @Test
    fun `un ritardo di una corsa sconosciuta non finisce su un'altra`() {
        // Gli indici del bundle non sopravvivono alla notte: un ritardo che
        // non trova la sua corsa deve sparire, non atterrare sul primo
        // indice libero.
        bundle().use { r ->
            val out = resolveRt(r, snapshot(), ritardi(ritardo(Ftb.hash64("mai-visto"), 600)))
            assertTrue(out.delayByTrip.isEmpty())
        }
    }
}
