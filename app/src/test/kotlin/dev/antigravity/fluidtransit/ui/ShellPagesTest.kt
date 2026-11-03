package dev.antigravity.fluidtransit.ui

import dev.antigravity.fluidengine.ui.page.FluidStackChangeKind
import dev.antigravity.fluidengine.ui.page.FluidPageMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La pila delle pagine della shell.
 *
 * Il difetto che questi test tengono lontano e' quello trovato in un'altra
 * migrazione: cambiando scheda con una pila sola, `popUpTo(start)` accumula le
 * schede una sull'altra e il tasto Indietro le ripercorre tutte. Qui la pila e'
 * calcolata da `(scheda, pagina sopra)` e non si accumula: cambiare scheda la
 * sostituisce, e un passo fra due schede e' un passo laterale — senza gesto e
 * senza pagina sotto — mai un push.
 */
class ShellPagesTest {

    private val schede = listOf(RouteMap, RouteToday, RouteFavorites, RouteSettings)

    @Test
    fun `una scheda sola e' una pila di una pagina`() {
        for (s in schede) assertEquals(listOf(s), shellStack(s, above = null))
    }

    @Test
    fun `lo stato dei dati e gli avvisi stanno sopra la scheda che c'e'`() {
        assertEquals(listOf(RouteToday, RouteAlerts), shellStack(RouteToday, RouteAlerts))
        assertEquals(listOf(RouteSettings, RouteDataStatus), shellStack(RouteSettings, RouteDataStatus))
    }

    @Test
    fun `fra due schede il passo e' laterale, e il verso e' quello della barra`() {
        assertEquals(1, tabStep(RouteMap, RouteToday).coerceIn(-1, 1))
        assertEquals(3, tabStep(RouteMap, RouteSettings))
        assertEquals(-2, tabStep(RouteSettings, RouteToday))
        assertEquals(0, tabStep(RouteToday, RouteToday))
    }

    @Test
    fun `una pagina sopra le schede non e' pari a nessuna scheda`() {
        for (s in schede) {
            assertEquals(0, tabStep(s, RouteAlerts))
            assertEquals(0, tabStep(RouteDataStatus, s))
        }
    }

    @Test
    fun `l'host legge il cambio di scheda come un passo laterale e non come un push`() {
        for (da in schede) for (a in schede) {
            if (da == a) continue
            val change = FluidPageMotion.classifyStackChange(
                old = shellStack(da, null),
                new = shellStack(a, null),
                peer = ::tabStep,
            )
            assertEquals("$da -> $a", FluidStackChangeKind.Peer, change.kind)
        }
    }

    @Test
    fun `aprire e chiudere una pagina sopra la scheda e' un push e poi un pop`() {
        val aperta = FluidPageMotion.classifyStackChange(
            old = shellStack(RouteToday, null),
            new = shellStack(RouteToday, RouteAlerts),
            peer = ::tabStep,
        )
        assertEquals(FluidStackChangeKind.Push, aperta.kind)
        val chiusa = FluidPageMotion.classifyStackChange(
            old = shellStack(RouteToday, RouteAlerts),
            new = shellStack(RouteToday, null),
            peer = ::tabStep,
        )
        assertEquals(FluidStackChangeKind.Pop, chiusa.kind)
        assertEquals(RouteToday, chiusa.to)
    }

    @Test
    fun `dalla mappa si va alle schede e il ritorno a una scheda non accumula niente`() {
        // Mappa -> Oggi -> Preferiti -> Mappa: la pila e' sempre di una pagina.
        var route = RouteMap
        for (next in listOf(RouteToday, RouteFavorites, RouteMap)) {
            route = next
            assertEquals(1, shellStack(route, null).size)
        }
        assertTrue(shellStack(route, null) == listOf(RouteMap))
    }
}
