package dev.antigravity.fluidtransit.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Gli indirizzi interni dell'app.
 *
 * Due proprieta' contano piu' delle altre, e sono opposte: quello che
 * scriviamo noi si deve rileggere identico (altrimenti un widget punta a una
 * fermata che non esiste), e quello che non abbiamo scritto noi non deve
 * aprire niente. Aprire "qualcosa di simile" e' peggio che non aprire.
 */
class DeeplinkTest {

    private fun stop(l: Deeplink?) = l as Deeplink.Stop

    @Test
    fun `una fermata si riscrive e si rilegge identica`() {
        val link = Deeplink.parse(Deeplink.stop("1a2b3c4d", "Piazza Dalmazia"))
        assertEquals("1a2b3c4d", stop(link).idHashHex)
        assertEquals("Piazza Dalmazia", stop(link).name)
    }

    @Test
    fun `i nomi veri passano interi`() {
        // Accenti, apostrofi, barre, e caratteri che nella query vogliono
        // dire altro: sono tutti nomi di fermate della rete vera.
        for (name in listOf(
            "VIA DELL'ARCOVEGGIO",
            "Citta' della Scienza",
            "SAN PIERO A SIEVE / STAZIONE",
            "Q = via Q? & altro",
            "Ponte all'Asse — capolinea",
        )) {
            val link = Deeplink.parse(Deeplink.stop("ff", name))
            assertEquals(name, stop(link).name)
        }
    }

    @Test
    fun `una fermata senza nome resta apribile`() {
        // Il nome e' solo il titolo che si vede mentre il bundle cerca
        // l'hash: senza, il pannello si apre lo stesso.
        val link = Deeplink.parse("fluidtransit://stop/abc123")
        assertEquals("abc123", stop(link).idHashHex)
        assertEquals("", stop(link).name)
    }

    @Test
    fun `linea e routine`() {
        assertEquals(
            "deadbeef",
            (Deeplink.parse(Deeplink.route("deadbeef")) as Deeplink.Route).idHashHex,
        )
        assertEquals(
            1726000000123L,
            (Deeplink.parse(Deeplink.journey(1726000000123L)) as Deeplink.Journey).routineId,
        )
    }

    @Test
    fun `le destinazioni senza argomenti`() {
        assertSame(Deeplink.Nav, Deeplink.parse(Deeplink.nav()))
        assertSame(Deeplink.Today, Deeplink.parse(Deeplink.today()))
        assertSame(Deeplink.DataStatus, Deeplink.parse(Deeplink.dataStatus()))
    }

    @Test
    fun `lo schema e il nome non guardano le maiuscole`() {
        // Il sistema puo' consegnare lo schema normalizzato in modi diversi;
        // l'intent-filter del manifest e' gia' case-insensitive sullo schema.
        assertSame(Deeplink.Nav, Deeplink.parse("FluidTransit://NAV"))
        assertEquals("ab", stop(Deeplink.parse("fluidtransit://STOP/AB")).idHashHex)
    }

    @Test
    fun `la barra finale non cambia niente`() {
        assertSame(Deeplink.DataStatus, Deeplink.parse("fluidtransit://data-status/"))
        assertEquals("ab", stop(Deeplink.parse("fluidtransit://stop/ab/")).idHashHex)
    }

    @Test
    fun `quello che non e' nostro non apre niente`() {
        for (bad in listOf(
            null,
            "",
            "   ",
            "https://fluidtransit.example/stop/ab",
            "fluidtransit://",
            "fluidtransit://qualcosa",
            // "trip" non e' (ancora) del vocabolario: nessuno lo emette.
            "fluidtransit://trip/deadbeef",
            "stop/ab",
            "://stop/ab",
            // Uno schema che comincia come il nostro non e' il nostro.
            "fluid://nav",
            "fluidtransito://nav",
        )) {
            assertNull("ha aperto qualcosa: $bad", Deeplink.parse(bad))
        }
    }

    @Test
    fun `un hash che non e' un hash non apre un pannello vuoto`() {
        // findStopByIdHash di un hash inventato torna -1: il pannello si
        // aprirebbe senza nome e senza orari, e sembrerebbe un guasto.
        for (bad in listOf(
            "fluidtransit://stop",
            "fluidtransit://stop/",
            "fluidtransit://stop/zzzz",
            "fluidtransit://stop/12 34",
            "fluidtransit://stop/00112233445566778", // 17 cifre: non ci sta in 64 bit
            "fluidtransit://route/../../etc",
            "fluidtransit://journey/domani",
            "fluidtransit://journey/",
        )) {
            assertNull("ha aperto qualcosa: $bad", Deeplink.parse(bad))
        }
    }

    @Test
    fun `una percentuale monca non fa saltare la lettura`() {
        // Non lo scriviamo noi, ma ce lo possono consegnare.
        assertEquals("100%", stop(Deeplink.parse("fluidtransit://stop/ab?name=100%")).name)
        assertEquals("%ZZ", stop(Deeplink.parse("fluidtransit://stop/ab?name=%ZZ")).name)
    }

    @Test
    fun `il nome si legge anche se non e' il primo parametro`() {
        val link = Deeplink.parse("fluidtransit://stop/ab?from=widget&name=Rifredi&x=1")
        assertEquals("Rifredi", stop(link).name)
    }

    @Test
    fun `il frammento non finisce nel nome`() {
        assertSame(Deeplink.Today, Deeplink.parse("fluidtransit://today#tutto"))
    }
}
