package dev.antigravity.fluidtransit.data.favorites

import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Ftb
import dev.antigravity.fluidtransit.routing.TestBundle
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quali linee sono "le tue".
 *
 * La scheda Oggi diceva "nessun avviso sulle tue linee" con tre avvisi in
 * corso proprio sulle linee della fermata sotto casa: "tue" voleva dire, alla
 * lettera, le linee con la stella — e la stella sulle linee quasi nessuno la
 * mette, perche' il gesto che l'app insegna dappertutto e' stellare la
 * fermata.
 */
class MyRoutesTest {

    private val tmp = ArrayList<File>()

    @After
    fun pulisci() {
        tmp.forEach { it.delete() }
    }

    private fun bundle() = BundleReader(TestBundle.write(tmp))

    private val linea = Ftb.hash64(TestBundle.routeId)

    @Test
    fun `le linee che passano dalle tue fermate sono tue`() {
        bundle().use { r ->
            val stop = r.findStopByIdHash(Ftb.hash64(TestBundle.stopIds[0]))
            val mie = MyRoutes.hashes(r, starredRoutes = emptySet(), starredStops = listOf(stop))
            assertTrue("la linea della fermata stellata doveva esserci", linea in mie)
        }
    }

    @Test
    fun `le linee stellate restano tue anche senza fermate`() {
        bundle().use { r ->
            val mie = MyRoutes.hashes(r, starredRoutes = setOf(linea), starredStops = emptyList())
            assertEquals(setOf(linea), mie)
        }
    }

    @Test
    fun `le due liste si sommano senza ripetersi`() {
        bundle().use { r ->
            val stop = r.findStopByIdHash(Ftb.hash64(TestBundle.stopIds[1]))
            val mie = MyRoutes.hashes(r, starredRoutes = setOf(linea), starredStops = listOf(stop))
            assertEquals(1, mie.size)
        }
    }

    @Test
    fun `senza orari valgono le stelle e basta`() {
        // Succede all'avvio, prima che il bundle sia aperto: meglio le sole
        // linee stellate che un errore o una lista vuota.
        val mie = MyRoutes.hashes(null, starredRoutes = setOf(linea), starredStops = listOf(3))
        assertEquals(setOf(linea), mie)
    }

    @Test
    fun `una fermata che non esiste non aggiunge niente ne' fa danni`() {
        // Le stelle sopravvivono allo scambio notturno degli orari, le
        // fermate no: l'indice -1 e' il modo in cui il resto dell'app dice
        // "questa fermata oggi non c'e'".
        bundle().use { r ->
            val mie = MyRoutes.hashes(r, starredRoutes = emptySet(), starredStops = listOf(-1))
            assertTrue(mie.isEmpty())
        }
    }

    @Test
    fun `una fermata che nessuna linea serve non aggiunge niente`() {
        bundle().use { r ->
            val lontana = r.findStopByIdHash(Ftb.hash64(TestBundle.stopIds[3]))
            val mie = MyRoutes.hashes(r, starredRoutes = emptySet(), starredStops = listOf(lontana))
            assertTrue("Borgo Delta non e' servita dalla linea di prova", mie.isEmpty())
        }
    }
}
