package dev.antigravity.fluidtransit.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Il tocco sul mirino con i due interruttori della posizione.
 *
 * Il difetto: permesso concesso e Posizione di Android spenta, il tocco
 * cambiava l'icona e la mappa restava ferma. Non c'era un'eccezione da
 * cercare, solo un tasto che prometteva "seguimi" e non poteva.
 */
class MirinoActionTest {

    @Test
    fun `senza permesso si chiede il permesso, qualunque cosa faccia Android`() {
        assertEquals(
            MirinoAction.ChiediPermesso,
            mirinoAction(permesso = false, posizioneDiSistemaAccesa = true, follow = FollowMode.FREE),
        )
        assertEquals(
            MirinoAction.ChiediPermesso,
            mirinoAction(permesso = false, posizioneDiSistemaAccesa = false, follow = FollowMode.FREE),
        )
    }

    @Test
    fun `col permesso e la posizione di sistema spenta si porta all'interruttore`() {
        assertEquals(
            MirinoAction.ApriImpostazioni,
            mirinoAction(permesso = true, posizioneDiSistemaAccesa = false, follow = FollowMode.FREE),
        )
    }

    @Test
    fun `con la posizione di sistema spenta l'inquadratura non gira, nemmeno se era gia' segui`() {
        // Prima il tocco cambiava l'icona e basta: l'unico modo di non
        // prometterlo e' non toccare l'inquadratura, quindi il risultato non
        // porta nessun modo.
        for (modo in FollowMode.entries) {
            assertEquals(
                MirinoAction.ApriImpostazioni,
                mirinoAction(permesso = true, posizioneDiSistemaAccesa = false, follow = modo),
            )
        }
    }

    @Test
    fun `con tutto in ordine l'inquadratura gira come prima`() {
        fun dopo(modo: FollowMode) =
            mirinoAction(permesso = true, posizioneDiSistemaAccesa = true, follow = modo)
        assertEquals(MirinoAction.Segui(FollowMode.FOLLOW), dopo(FollowMode.FREE))
        assertEquals(MirinoAction.Segui(FollowMode.COMPASS), dopo(FollowMode.FOLLOW))
        assertEquals(MirinoAction.Segui(FollowMode.FOLLOW), dopo(FollowMode.COMPASS))
        assertEquals(MirinoAction.Segui(FollowMode.FOLLOW), dopo(FollowMode.NAV_CAMMINO))
    }
}
