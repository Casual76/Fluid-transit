package dev.antigravity.fluidtransit.ui.settings

import dev.antigravity.fluidengine.foundation.AppUpdateInstallState
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.ui.fluid.FluidUpdateStage
import dev.antigravity.fluidengine.ui.fluid.FluidUpdateUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Da quello che sa l'`UpdateHolder` allo stato che la sezione degli
 * aggiornamenti disegna.
 *
 * E' la parte di `UpdateSettings.kt` con le decisioni: l'ordine in cui le
 * cose si dicono (un'installazione fallita prima di tutto, perche' ha il suo
 * "riprova"), che una versione nota non sparisca mentre si ricontrolla, e che
 * l'errore non arrivi mai nudo. Il disegno e' dell'engine e ha i suoi test.
 */
class UpdateStateTest {

    private val nuova = AvailableAppUpdate(
        version = "1.4.0",
        changelog = "Il nuovo motore grafico.\n\nAltro.",
        releaseTag = "fluid-transit-1.4.0",
        apkAsset = "fluid-transit-1.4.0.apk",
        downloadUrl = "https://example.org/a.apk",
        sizeBytes = 52_000_000L,
    )

    private fun stato(
        available: AvailableAppUpdate? = null,
        install: AppUpdateInstallState? = null,
        checking: Boolean = false,
        error: String? = null,
        neverChecked: Boolean = false,
    ) = transitUpdateState(available, install, checking, error, neverChecked)

    @Test
    fun `prima del primo controllo non si promette niente`() {
        assertEquals(FluidUpdateUiState.Idle, stato(neverChecked = true))
    }

    @Test
    fun `dopo un controllo senza novita' dice che e' aggiornata`() {
        assertEquals(FluidUpdateUiState.UpToDate, stato())
    }

    @Test
    fun `mentre controlla lo dice`() {
        assertEquals(FluidUpdateUiState.Checking, stato(checking = true))
    }

    @Test
    fun `una versione nuova resta anche mentre si ricontrolla`() {
        val s = stato(available = nuova, checking = true)
        assertTrue(s is FluidUpdateUiState.Available)
        assertEquals("1.4.0", (s as FluidUpdateUiState.Available).release.version)
        assertEquals(52_000_000L, s.release.sizeBytes)
    }

    @Test
    fun `un controllo fallito dice la frase in italiano e sotto il testo tecnico`() {
        val s = stato(error = "Unable to resolve host \"raw.githubusercontent.com\"")
        assertTrue(s is FluidUpdateUiState.CheckFailed)
        val message = (s as FluidUpdateUiState.CheckFailed).message.orEmpty()
        // La frase prima, il dettaglio per chi indaga dopo, mai l'eccezione sola.
        assertTrue(message, message.contains("\n"))
        assertTrue(message, message.lines().last().contains("raw.githubusercontent.com"))
    }

    @Test
    fun `un controllo fallito senza dettagli dice che non sa perche'`() {
        val s = stato(error = "") as FluidUpdateUiState.CheckFailed
        assertTrue(s.message.orEmpty().contains("non sappiamo dire perche'"))
    }

    @Test
    fun `lo scaricamento porta la sua percentuale`() {
        val s = stato(available = nuova, install = AppUpdateInstallState.Downloading(0.5f, 26_000_000L, 52_000_000L))
        assertTrue(s is FluidUpdateUiState.Downloading)
        assertEquals(0.5f, (s as FluidUpdateUiState.Downloading).progress)
    }

    @Test
    fun `senza il peso del file la barra non finge una percentuale`() {
        val s = stato(available = nuova, install = AppUpdateInstallState.Downloading(0f, 1_000L, 0L))
        assertEquals(null, (s as FluidUpdateUiState.Downloading).progress)
    }

    @Test
    fun `la conferma di Android e' un'attesa, non una barra che gira`() {
        val s = stato(available = nuova, install = AppUpdateInstallState.AwaitingUserAction("Conferma nella finestra"))
        assertEquals(FluidUpdateStage.AwaitingUser, (s as FluidUpdateUiState.Working).stage)
    }

    @Test
    fun `un'installazione fallita ha la sua riga col riprova, anche senza controllo recente`() {
        val s = stato(
            available = nuova,
            install = AppUpdateInstallState.Error("Abilita l'installazione da questa app"),
            // Un controllo in corso non nasconde il fallimento.
            checking = true,
        )
        assertTrue(s is FluidUpdateUiState.Failed)
        // Il messaggio del motore nasce gia' in italiano e dice cosa fare: non lo si copre.
        assertTrue((s as FluidUpdateUiState.Failed).message.contains("Abilita l'installazione"))
    }

    @Test
    fun `un'installazione fallita senza una versione nota non cade`() {
        val s = stato(install = AppUpdateInstallState.Error(""))
        assertTrue(s is FluidUpdateUiState.Failed)
        assertTrue((s as FluidUpdateUiState.Failed).message.contains("non e' riuscita"))
    }

    @Test
    fun `installata, e' installata`() {
        val s = stato(available = nuova, install = AppUpdateInstallState.Installed("/data/a.apk"))
        assertTrue(s is FluidUpdateUiState.Installed)
    }
}
