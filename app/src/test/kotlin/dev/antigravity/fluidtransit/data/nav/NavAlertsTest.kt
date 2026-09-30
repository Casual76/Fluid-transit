package dev.antigravity.fluidtransit.data.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Quando il telefono vibra, e quando deve stare zitto.
 *
 * Tutto quello che puo' andare storto in un avviso e' aritmetica: si
 * ripete a ogni giro, non arriva mai, oppure ne arrivano due attaccati che
 * si leggono come uno solo. Dentro un `Service` quei tre casi si provano
 * solo prendendo un autobus vero — e uno che vibra a vuoto ogni quindici
 * secondi mentre aspetti e' il motivo per cui si disinstalla un'app.
 *
 * Il difetto vero che questi test blindano: i segni di "gia' avvisato" si
 * azzeravano SOLO alla partenza. In un viaggio con un cambio, il secondo
 * "il tuo bus sta arrivando" non e' mai arrivato a nessuno.
 */
class NavAlertsTest {

    private val t0 = 1_700_000_000L

    /** JUnit non restituisce il valore: qui serve, e null e' gia' un difetto. */
    private fun atteso(a: NavAlerts.Avviso?): NavAlerts.Avviso {
        assertNotNull("atteso un avviso, non e' arrivato niente", a)
        return a!!
    }

    private fun attesa(
        stopsAway: Int,
        legIndex: Int = 0,
        canceled: Boolean = false,
    ) = NavState(
        kind = "journey",
        destName = "TORRE GALLI",
        phase = "wait",
        headline = "La 23 e' a $stopsAway fermate",
        detail = "parte tra 4 min",
        stopsRemaining = 6,
        totalStops = 6,
        etaEpoch = 0,
        legIndex = legIndex,
        lineName = "23",
        busStopsAway = stopsAway,
        canceled = canceled,
    )

    private fun bordo(
        remaining: Int,
        legIndex: Int = 1,
        total: Int = 6,
        meters: Int = -1,
    ) = NavState(
        kind = "journey",
        destName = "TORRE GALLI",
        phase = "ride",
        headline = "Scendi a TORRE GALLI",
        detail = "$remaining fermate",
        stopsRemaining = remaining,
        totalStops = total,
        etaEpoch = 0,
        metersToGo = meters,
        legIndex = legIndex,
        lineName = "23",
        alightName = "TORRE GALLI",
    )

    @Test
    fun `l'avviso del bus in arrivo non si ripete a ogni giro`() {
        val a = NavAlerts()
        assertNull("il primo giro conferma e basta", a.next(attesa(1), t0))
        val avviso = atteso(a.next(attesa(1), t0 + 15))
        assertEquals(NavAlerts.ARRIVING, avviso.id)
        // Il modo Preciso ripassa di qui ogni quindici secondi finche' il
        // bus non arriva: sono otto vibrazioni in due minuti.
        assertNull(a.next(attesa(1), t0 + 30))
        assertNull(a.next(attesa(0), t0 + 90))
    }

    @Test
    fun `un rimbalzo del feed da una fermata a tre e ritorno non fa scattare l'avviso`() {
        // Il conto delle fermate non e' monotono: una previsione che si
        // sposta lo fa andare 1 -> 3 -> 1 senza che il mezzo si muova.
        val a = NavAlerts()
        assertNull(a.next(attesa(3), t0))
        assertNull("prima conferma", a.next(attesa(1), t0 + 15))
        assertNull("il rimbalzo azzera", a.next(attesa(3), t0 + 30))
        assertNull("si ricomincia da capo", a.next(attesa(1), t0 + 45))
        assertNotNull("due giri di fila, adesso si", a.next(attesa(1), t0 + 60))
    }

    @Test
    fun `al cambio di tappa gli avvisi tornano armati`() {
        val a = NavAlerts()
        assertNull(a.next(attesa(1, legIndex = 0), t0))
        assertNotNull(a.next(attesa(1, legIndex = 0), t0 + 15))
        // Cambio: si e' scesi, si aspetta il secondo bus. Prima di questa
        // regola, qui non arrivava piu' niente per il resto del viaggio.
        assertNull(a.next(attesa(1, legIndex = 2), t0 + 600))
        val secondo = atteso(a.next(attesa(1, legIndex = 2), t0 + 615))
        assertEquals(NavAlerts.ARRIVING, secondo.id)
    }

    @Test
    fun `preparati a scendere e scendi alla prossima non arrivano attaccati`() {
        val a = NavAlerts()
        val preparati = atteso(a.next(bordo(remaining = 2), t0))
        assertEquals(NavAlerts.PREPARE, preparati.id)
        assertEquals("non deve suonare", false, preparati.forte)
        // Fermate corte: dieci secondi dopo ne manca una sola. Due
        // vibrazioni di fila si leggono come una sola, e quella che conta e'
        // la seconda.
        assertNull(a.next(bordo(remaining = 1), t0 + 10))
        val scendi = atteso(a.next(bordo(remaining = 1), t0 + 50))
        assertEquals(NavAlerts.ALIGHT, scendi.id)
        assertEquals(true, scendi.forte)
    }

    @Test
    fun `su una tratta di tre fermate non si dice due volte la stessa cosa`() {
        val a = NavAlerts()
        // "Preparati fra due fermate" e "scendi alla prossima" su una tratta
        // cosi' corta sono lo stesso avviso detto a dieci secondi di
        // distanza.
        assertNull(a.next(bordo(remaining = 2, total = 3), t0))
        assertEquals(
            NavAlerts.ALIGHT,
            atteso(a.next(bordo(remaining = 1, total = 3), t0 + 30)).id,
        )
    }

    @Test
    fun `una corsa cancellata mentre aspetto avvisa una volta sola`() {
        // La cancellazione era gia' riconosciuta e finiva dentro una frase
        // sullo schermo: chi non guardava aspettava un autobus che non
        // sarebbe mai arrivato.
        val a = NavAlerts()
        val avviso = atteso(a.next(attesa(5, canceled = true), t0))
        assertEquals(NavAlerts.CANCELED, avviso.id)
        assertEquals(true, avviso.forte)
        assertNull(a.next(attesa(5, canceled = true), t0 + 60))
    }

    /** Si cammina verso la fermata della 23, che non si prende piu'. */
    private fun cammino(missed: Boolean = false, canceled: Boolean = false) = NavState(
        kind = "journey",
        destName = "TORRE GALLI",
        phase = "walk",
        headline = if (canceled) "La 23 e' stata cancellata" else "La 23 e' gia' passata",
        detail = "la prossima alle 14:15",
        stopsRemaining = 6,
        totalStops = 6,
        etaEpoch = 0,
        legIndex = 0,
        lineName = "23",
        alightName = "TORRE GALLI",
        canceled = canceled,
        missed = missed,
    )

    @Test
    fun `il bus gia' passato si dice mentre si cammina, e una volta sola`() {
        // Visto il 30/09: alle 13:58 il mezzo della 20 era un chilometro
        // oltre la fermata, e la card diceva ancora "Cammina verso". Il
        // telefono deve dirlo a chi cammina con lo schermo in tasca.
        val a = NavAlerts()
        assertNull("il primo giro conferma e basta", a.next(cammino(missed = true), t0))
        val avviso = atteso(a.next(cammino(missed = true), t0 + 15))
        assertEquals(NavAlerts.MISSED, avviso.id)
        assertEquals("La prossima alle 14:15", avviso.testo)
        assertEquals(true, avviso.forte)
        assertNull(a.next(cammino(missed = true), t0 + 30))

        // Arrivati alla fermata la tappa cambia, e la notizia e' la stessa:
        // riarmata col cambio di tappa vibrava una seconda volta.
        val arrivato = NavState(
            kind = "journey",
            destName = "TORRE GALLI",
            phase = "wait",
            headline = "La 23 e' gia' passata",
            detail = "la prossima alle 14:15",
            stopsRemaining = 6,
            totalStops = 6,
            etaEpoch = 0,
            legIndex = 1,
            lineName = "23",
            alightName = "TORRE GALLI",
            missed = true,
        )
        assertNull(a.next(arrivato, t0 + 120))
        assertNull(a.next(arrivato, t0 + 135))
    }

    @Test
    fun `una corsa cancellata si dice gia' mentre si cammina, e non si ripete alla fermata`() {
        val a = NavAlerts()
        val avviso = atteso(a.next(cammino(canceled = true), t0))
        assertEquals(NavAlerts.CANCELED, avviso.id)
        val allaFermata = NavState(
            kind = "journey",
            destName = "TORRE GALLI",
            phase = "wait",
            headline = "La 23 e' stata cancellata",
            detail = "la prossima alle 14:15",
            stopsRemaining = 6,
            totalStops = 6,
            etaEpoch = 0,
            legIndex = 1,
            lineName = "23",
            alightName = "TORRE GALLI",
            canceled = true,
        )
        assertNull(a.next(allaFermata, t0 + 120))
    }

    @Test
    fun `senza sapere dov'e' il bus non si avvisa niente`() {
        val a = NavAlerts()
        assertNull(a.next(attesa(-1), t0))
        assertNull(a.next(attesa(-1), t0 + 15))
        assertNull(a.next(attesa(-1), t0 + 30))
    }

    @Test
    fun `l'arrivo non aspetta il silenzio fra due avvisi`() {
        // Dopo l'arrivo il servizio smette di girare: non c'e' un giro dopo.
        // Se il silenzio di quarantacinque secondi lo rimandava — "scendi
        // alla prossima" dieci secondi prima, su una fermata corta — il
        // "Scendi qui" non arrivava mai.
        val a = NavAlerts()
        assertNotNull(a.next(bordo(remaining = 1), t0))
        val arrivo = NavState(
            kind = "journey",
            destName = "TORRE GALLI",
            phase = "arrived",
            headline = "Arrivato",
            detail = "TORRE GALLI",
            stopsRemaining = 0,
            totalStops = 6,
            etaEpoch = t0,
            legIndex = 1,
        )
        assertEquals(NavAlerts.ARRIVED, atteso(a.next(arrivo, t0 + 10)).id)
        assertNull("una volta sola", a.next(arrivo, t0 + 60))
    }

    @Test
    fun `l'arrivo come lo manda davvero il servizio, senza tappa`() {
        // Lo stato "arrivato" del servizio non ha una tappa: legIndex e' -1.
        // Il cambio di tappa riarma gli avvisi, e un "scendi qui" gia' dato
        // non deve per questo ripetersi ne' sparire.
        val a = NavAlerts()
        assertNotNull(a.next(bordo(remaining = 1, legIndex = 1), t0))
        val arrivo = NavState(
            kind = "journey",
            destName = "TORRE GALLI",
            phase = "arrived",
            headline = "Arrivato",
            detail = "TORRE GALLI",
            stopsRemaining = 0,
            totalStops = 0,
            etaEpoch = t0,
        )
        assertEquals(-1, arrivo.legIndex)
        assertEquals(NavAlerts.ARRIVED, atteso(a.next(arrivo, t0 + 60)).id)
        assertNull("una volta sola", a.next(arrivo, t0 + 200))
    }

    @Test
    fun `vicino alla discesa si avvisa, ma senza dire alla prossima`() {
        // Un bus in ritardo: la tabella dice quattro fermate, ma sei a
        // duecentocinquanta metri. Con i soli orari l'avviso arrivava dopo
        // che eri gia' passato; con "scendi alla prossima" si mentiva.
        val a = NavAlerts()
        val avviso = atteso(a.next(bordo(remaining = 4, meters = 250), t0))
        assertEquals(NavAlerts.NEAR, avviso.id)
        assertEquals("Stai per arrivare a TORRE GALLI", avviso.titolo)
        // E quando davvero manca una fermata, quell'avviso arriva lo stesso:
        // il primo aveva un id suo e non l'ha consumato.
        assertEquals(
            NavAlerts.ALIGHT,
            atteso(a.next(bordo(remaining = 1, meters = 120), t0 + 60)).id,
        )
    }

    @Test
    fun `lontano dalla discesa e con fermate davanti non si avvisa niente`() {
        val a = NavAlerts()
        assertNull(a.next(bordo(remaining = 4, meters = 400), t0))
        assertNull("senza posizione, idem", a.next(bordo(remaining = 4), t0 + 60))
    }

    @Test
    fun `la navigazione interrotta dagli orari nuovi si fa sentire`() {
        // Chi e' sul bus con lo schermo spento aspetterebbe un "scendi" che
        // non arrivera' piu'.
        val a = NavAlerts()
        val perso = NavState(
            kind = "journey",
            destName = "TORRE GALLI",
            phase = "lost",
            headline = "Gli orari sono cambiati",
            detail = "la corsa che seguivi non c'e' piu'",
            stopsRemaining = 0,
            totalStops = 0,
            etaEpoch = 0,
            legIndex = 1,
        )
        // Un avviso dieci secondi prima non deve tenerlo in silenzio: dopo
        // questo il servizio si ferma, e non ci sarebbe un'altra occasione.
        assertNotNull(a.next(bordo(remaining = 1), t0 - 10))
        val avviso = atteso(a.next(perso, t0))
        assertEquals(NavAlerts.LOST, avviso.id)
        assertEquals(true, avviso.forte)
        assertNull(a.next(perso, t0 + 60))
    }

    @Test
    fun `chi arriva a piedi non si sente dire scendi qui`() {
        val a = NavAlerts()
        val aPiedi = NavState(
            kind = "journey",
            destName = "Fiesole Piazza Mino",
            phase = "arrived",
            headline = "Arrivato",
            detail = "Fiesole Piazza Mino",
            stopsRemaining = 0,
            totalStops = 0,
            etaEpoch = t0,
            arrivedOnFoot = true,
        )
        val avviso = atteso(a.next(aPiedi, t0))
        assertEquals(NavAlerts.ARRIVED, avviso.id)
        assertEquals("Sei arrivato", avviso.titolo)
    }

    @Test
    fun `una fermata di salita saltata non fa dire che il bus sta arrivando`() {
        // Il feed dichiara SALTATA la fermata dove si sale: il mezzo e'
        // alla tua fermata per il conto delle fermate, ma non si ferma.
        val saltata = NavState(
            kind = "journey",
            destName = "TORRE GALLI",
            phase = "wait",
            headline = "La 23 non ferma a PIAZZA DALMAZIA",
            detail = "la prossima alle 14:15",
            stopsRemaining = 6,
            totalStops = 6,
            etaEpoch = 0,
            legIndex = 0,
            lineName = "23",
            alightName = "TORRE GALLI",
            busStopsAway = 0,
            skipped = true,
        )
        val a = NavAlerts()
        assertNull("il primo giro conferma e basta", a.next(saltata, t0))
        val avviso = atteso(a.next(saltata, t0 + 15))
        assertEquals(NavAlerts.SKIPPED, avviso.id)
        assertEquals("La prossima alle 14:15", avviso.testo)
        assertNull(a.next(saltata, t0 + 30))
    }

    @Test
    fun `a bordo la discesa saltata dice dove scendere e zittisce scendi alla prossima`() {
        val saltata = NavState(
            kind = "journey",
            destName = "TORRE GALLI",
            phase = "ride",
            headline = "La 23 non ferma a TORRE GALLI",
            detail = "scendi prima, a PONTE",
            stopsRemaining = 1,
            totalStops = 6,
            etaEpoch = 0,
            legIndex = 1,
            lineName = "23",
            alightName = "TORRE GALLI",
            metersToGo = 200,
            skipped = true,
        )
        val a = NavAlerts()
        // Senza conferma: a bordo un giro d'attesa puo' essere la fermata
        // che serviva. E non "Scendi alla prossima" ne' "Stai per arrivare".
        val avviso = atteso(a.next(saltata, t0))
        assertEquals(NavAlerts.SKIPPED_ALIGHT, avviso.id)
        assertEquals("Scendi prima, a PONTE", avviso.testo)
        assertNull(a.next(saltata, t0 + 15))
    }
}
