package dev.antigravity.fluidtransit.data.bundle

import dev.antigravity.fluidtransit.data.bundle.BundleRefreshPolicy.Network
import dev.antigravity.fluidtransit.data.bundle.BundleRefreshPolicy.Step
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il controllo in sottofondo degli orari e la sua unica regola sui dati
 * mobili: su rete a consumo non si scarica senza chiedere.
 *
 * Il ramo che conta e' quello che NON scarica. Un giro che scarica lo vede
 * chiunque abbia un Wi-Fi; uno che scarica di troppo lo vede solo chi ha un
 * piano dati, quando ha gia' speso i megabyte.
 */
class BundleRefreshPolicyTest {

    @Test
    fun `su rete a consumo con orari scaduti si chiede, non si scarica`() {
        assertEquals(
            Network.AskFirst,
            BundleRefreshPolicy.network(metered = true, expiring = true, approved = false),
        )
        // E con un bundle nuovo la mossa e' offrirlo, mai installarlo.
        assertEquals(
            Step.Offer,
            BundleRefreshPolicy.step(Network.AskFirst, sameBuild = false, overlayChanged = false),
        )
    }

    @Test
    fun `su rete a consumo con orari ancora buoni non si fa niente`() {
        val rete = BundleRefreshPolicy.network(metered = true, expiring = false, approved = false)
        assertEquals(Network.Skip, rete)
        // Nemmeno se c'e' un bundle nuovo: quelli di ieri bastano.
        assertEquals(
            Step.Nothing,
            BundleRefreshPolicy.step(rete, sameBuild = false, overlayChanged = true),
        )
    }

    @Test
    fun `su rete non a consumo si scarica il bundle nuovo, scaduti o no`() {
        for (expiring in listOf(true, false)) {
            val rete = BundleRefreshPolicy.network(metered = false, expiring = expiring, approved = false)
            assertEquals(Network.Free, rete)
            assertEquals(
                Step.Install,
                BundleRefreshPolicy.step(rete, sameBuild = false, overlayChanged = false),
            )
        }
    }

    @Test
    fun `senza sapere se la rete e' a consumo il giro si salta, anche con orari scaduti`() {
        // All'avvio "non lo so ancora" e' uno stato vero; scaricare in quel
        // momento vuol dire scommettere sul piano dati di qualcuno.
        for (expiring in listOf(true, false)) {
            assertEquals(
                Network.Skip,
                BundleRefreshPolicy.network(metered = null, expiring = expiring, approved = false),
            )
        }
    }

    @Test
    fun `il si' dell'utente vale su qualunque rete`() {
        for (metered in listOf(true, false, null)) {
            for (expiring in listOf(true, false)) {
                assertEquals(
                    Network.Free,
                    BundleRefreshPolicy.network(metered, expiring, approved = true),
                )
            }
        }
    }

    @Test
    fun `senza il si' dell'utente si installa solo su una rete che sappiamo non a consumo`() {
        // L'invariante di tutta la tabella: nessuna combinazione senza un si'
        // e senza un Wi-Fi arriva a Install.
        for (metered in listOf(true, false, null)) {
            for (expiring in listOf(true, false)) {
                val rete = BundleRefreshPolicy.network(metered, expiring, approved = false)
                val mossa = BundleRefreshPolicy.step(rete, sameBuild = false, overlayChanged = false)
                if (metered != false) {
                    assertNotEquals("metered=$metered expiring=$expiring", Step.Install, mossa)
                } else {
                    assertEquals(Step.Install, mossa)
                }
            }
        }
    }

    @Test
    fun `stessi orari con un overlay nuovo si aggiorna l'overlay anche quando gli orari andrebbero solo offerti`() {
        // L'indice e' gia' in mano e l'overlay non costa un download.
        for (rete in listOf(Network.Free, Network.AskFirst)) {
            assertEquals(
                Step.OverlayOnly,
                BundleRefreshPolicy.step(rete, sameBuild = true, overlayChanged = true),
            )
            assertEquals(
                Step.Nothing,
                BundleRefreshPolicy.step(rete, sameBuild = true, overlayChanged = false),
            )
        }
    }

    @Test
    fun `dove non si tocca la rete non si tocca nemmeno l'overlay`() {
        assertEquals(
            Step.Nothing,
            BundleRefreshPolicy.step(Network.Skip, sameBuild = true, overlayChanged = true),
        )
    }

    @Test
    fun `gli orari scadono domani, non solo oggi`() {
        val oggi = LocalDate.of(2026, 10, 4)
        // Ultimo giorno di validita' domani: il giro di domani mattina e' gia' tardi.
        assertTrue(BundleRefreshPolicy.expiring(oggi, feedEnd = LocalDate.of(2026, 10, 5)))
        // Oggi e' l'ultimo giorno.
        assertTrue(BundleRefreshPolicy.expiring(oggi, feedEnd = oggi))
        // Gia' scaduti.
        assertTrue(BundleRefreshPolicy.expiring(oggi, feedEnd = LocalDate.of(2026, 10, 1)))
        // Dopodomani ancora no.
        assertFalse(BundleRefreshPolicy.expiring(oggi, feedEnd = LocalDate.of(2026, 10, 6)))
    }

    @Test
    fun `i megabyte sono arrotondati e mai zero`() {
        val mb = 1024L * 1024L
        assertEquals(6, BundleRefreshPolicy.megabytes(6 * mb))
        assertEquals(6, BundleRefreshPolicy.megabytes(6 * mb + mb / 4))
        assertEquals(7, BundleRefreshPolicy.megabytes(6 * mb + mb / 2))
        // Con la divisione intera era "circa 0 MB".
        assertEquals(1, BundleRefreshPolicy.megabytes(900 * 1024L))
        assertEquals(1, BundleRefreshPolicy.megabytes(1))
        assertEquals(1, BundleRefreshPolicy.megabytes(0))
    }
}
