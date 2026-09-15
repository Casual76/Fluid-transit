package dev.antigravity.fluidtransit.routing

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Il modello dei ritardi si legge mentre lo si riempie, sempre.
 *
 * Non e' un caso limite: le osservazioni entrano da un collettore
 * dell'Application — novecento corse in fila a ogni giro di trip-updates, piu'
 * una potatura e uno svuotamento completo allo scambio notturno — e le letture
 * arrivano dai tabelloni, che si calcolano sul pool di sfondo, e dagli
 * strumenti dell'assistente. Sono thread diversi, e lo sono sempre.
 *
 * Una `HashMap` letta mentre la si riempie non solleva: risponde male. Durante
 * l'ingrandimento della tabella una chiave che c'e' puo' risultare assente, e
 * una corsa che il feed sta seguendo perde il suo ritardo per quel giro — cioe'
 * una riga che dice "orario da tabella" mentre quella accanto dice "dal bus".
 *
 * Questa prova non puo' dimostrare l'assenza di una corsa fra thread, che e'
 * probabilistica per natura: quello che inchioda e' il contratto. Il modello
 * regge quattro thread che lo martellano senza sollevare e senza dimenticarsi
 * quello che ha appena ricevuto.
 */
class DelayModelConcurrencyTest {

    private val CORSE = 400
    private val GIRI = 3_000

    /**
     * L'istante, uno solo per tutti.
     *
     * Le osservazioni e le letture usano lo stesso, cosi' la regola
     * "un'osservazione vecchia non e' il ritardo di adesso" non entra mai in
     * gioco: qui si sta provando la concorrenza, e un test che dipende da
     * quale thread e' arrivato prima si rompe da solo un giro su cinque —
     * che in un cancello e' peggio del difetto che sorveglia.
     */
    private val ADESSO = 1_000L

    @Test
    fun `scrivere e leggere insieme non rompe niente e non perde niente`() {
        val model = DelayModel()
        val errore = AtomicReference<Throwable?>(null)
        val via = CountDownLatch(1)

        // Tutte le corse ci sono gia' prima di partire: da qui in avanti una
        // lettura che torna null e' un difetto, non una corsa.
        for (corsa in 0 until CORSE) model.observe(corsa, 60, 1, ADESSO)

        val scrittore = Thread {
            try {
                via.await()
                for (giro in 0 until GIRI) {
                    for (corsa in 0 until CORSE) {
                        model.observe(corsa, giro % 600, (giro % 20) + 1, ADESSO)
                    }
                    // Non toglie niente — serve a far correre l'iteratore
                    // sulla mappa mentre gli altri la leggono, che e' il
                    // momento in cui una mappa nuda sbaglia.
                    if (giro % 200 == 199) model.forgetBefore(ADESSO - 100)
                }
            } catch (t: Throwable) {
                errore.compareAndSet(null, t)
            }
        }

        val lettori = (0 until 3).map { n ->
            Thread {
                try {
                    via.await()
                    for (giro in 0 until GIRI) {
                        val corsa = (giro * 7 + n) % CORSE
                        assertTrue(
                            model.at(corsa, 3, 20, ADESSO) != null,
                            "la corsa $corsa e' sparita mentre qualcuno scriveva",
                        )
                        assertTrue(model.current(corsa) != null, "ritardo sparito")
                        model.nextStop(corsa)
                        if (giro % 100 == 0) assertTrue(model.size >= CORSE)
                    }
                } catch (t: Throwable) {
                    errore.compareAndSet(null, t)
                }
            }
        }

        (lettori + scrittore).forEach { it.start() }
        via.countDown()
        (lettori + scrittore).forEach { it.join(60_000) }

        assertNull(errore.get(), "un thread e' morto: ${errore.get()}")
    }

    @Test
    fun `svuotare mentre qualcuno legge non lascia mezze risposte`() {
        // Lo svuotamento c'e' davvero, e capita mentre l'app e' in uso: gli
        // indici del bundle non sopravvivono allo scambio notturno, quindi al
        // cambio di build il modello si azzera sotto i piedi dei tabelloni.
        val model = DelayModel()
        val errore = AtomicReference<Throwable?>(null)
        val via = CountDownLatch(1)

        val svuotatore = Thread {
            try {
                via.await()
                for (giro in 0 until GIRI) {
                    for (corsa in 0 until 50) model.observe(corsa, 120, 2, 1_000L)
                    model.clear()
                }
            } catch (t: Throwable) {
                errore.compareAndSet(null, t)
            }
        }
        val lettore = Thread {
            try {
                via.await()
                for (giro in 0 until GIRI * 10) {
                    // O c'e' un ritardo intero o non c'e' niente: mai un
                    // oggetto a meta'.
                    val live = model.at(giro % 50, 3, 20, 1_000L)
                    if (live != null) assertTrue(live.delaySeconds in -32000..32000)
                }
            } catch (t: Throwable) {
                errore.compareAndSet(null, t)
            }
        }

        listOf(svuotatore, lettore).forEach { it.start() }
        via.countDown()
        listOf(svuotatore, lettore).forEach { it.join(60_000) }

        assertNull(errore.get(), "un thread e' morto: ${errore.get()}")
    }
}
