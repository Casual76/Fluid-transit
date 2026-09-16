package dev.antigravity.fluidtransit.routing

import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Le parole del confronto con la fonte.
 *
 * Questa riga esiste per rispondere a una domanda precisa — "non so nemmeno
 * se i dati sono accurati" — e la risposta puo' essere solo una di quattro:
 * combaciano, non combaciano, non c'era abbastanza da confrontare, il
 * confronto non e' riuscito. Confonderne due sarebbe peggio che non dire
 * niente, e "zero differenze su zero orari" e' esattamente la confusione da
 * evitare: il banco stesso ci era gia' cascato.
 */
class FidelityTextTest {

    private val now = ZonedDateTime.of(2026, 9, 16, 12, 0, 0, 0, Ftb.ROME).toEpochSecond()
    private fun oreFa(n: Long) = now - n * 3600

    @Test
    fun `combaciano si dice con quanti orari e quando`() {
        val w = FidelityText.words(
            FidelityText.Verdict(oreFa(2), "uguale", punti = 842, diversi = 0),
            now,
        )
        assertEquals("Combaciano", w.title)
        assertTrue(w.detail.contains("842 orari"), w.detail)
        assertTrue(w.detail.contains("oggi alle 10:00"), w.detail)
        assertTrue(w.detail.contains("nessuna differenza"), w.detail)
    }

    @Test
    fun `non combaciano si dice a voce alta`() {
        val w = FidelityText.words(
            FidelityText.Verdict(oreFa(3), "diverso", punti = 500, diversi = 7),
            now,
        )
        assertEquals("NON combaciano", w.title)
        assertTrue(w.detail.contains("7 erano"), w.detail)
    }

    @Test
    fun `zero confronti non e' un via libera`() {
        // E' la confusione che conta: "zero differenze" su zero orari
        // confrontati suona come una promessa e non lo e'. Il banco stesso ci
        // era cascato, e dichiarava vittoria alle due di notte.
        val w = FidelityText.words(
            FidelityText.Verdict(oreFa(6), "poco", punti = 0, diversi = 0),
            now,
        )
        assertEquals("Non c'era abbastanza da confrontare", w.title)
        assertTrue(
            !w.detail.contains("nessuna differenza"),
            "non deve somigliare a un via libera: ${w.detail}",
        )
    }

    @Test
    fun `un confronto sfasato si distingue da un confronto riuscito`() {
        val w = FidelityText.words(
            FidelityText.Verdict(oreFa(1), "sfasato", punti = 0, diversi = 0),
            now,
        )
        assertEquals("Confronto non riuscito", w.title)
    }

    @Test
    fun `senza verdetto si dice che non e' ancora arrivato`() {
        val w = FidelityText.words(null, now)
        assertEquals("Non ancora", w.title)
    }

    @Test
    fun `senza rete non si dice che l'esito non e' ancora arrivato`() {
        // "Non ancora" e' un'affermazione sul mondo: dice che il banco non ha
        // ancora prodotto niente. Con la rete giu' e' falsa, e nasconde che
        // il problema e' qui.
        val w = FidelityText.words(null, now, reachable = false)
        assertEquals("Non siamo riusciti a chiederlo", w.title)
        assertTrue("Senza rete" in w.detail, w.detail)
    }

    @Test
    fun `un verdetto di tre giorni fa non descrive l'oggi`() {
        // Il banco gira due volte al giorno: se l'ultimo che si trova e' di
        // tre giorni fa, si e' fermato qualcosa, e mostrare quel numero come
        // se fosse di adesso sarebbe la bugia piu' facile da raccontare.
        val w = FidelityText.words(
            FidelityText.Verdict(now - 3 * 24 * 3600, "uguale", punti = 900, diversi = 0),
            now,
        )
        assertEquals("Vecchio di piu' di due giorni", w.title)
        assertTrue(!w.detail.contains("900"), "non deve vantare un numero vecchio: ${w.detail}")
    }

    @Test
    fun `un esito che non conosciamo non si finge di capirlo`() {
        // Il file lo scrive un workflow che puo' cambiare prima dell'app
        // installata: inventarsi un significato sarebbe peggio che ammetterlo.
        val w = FidelityText.words(
            FidelityText.Verdict(oreFa(2), "qualcosaltro", punti = 10, diversi = 0),
            now,
        )
        assertEquals("Esito sconosciuto", w.title)
    }

    @Test
    fun `un orario solo si dice al singolare`() {
        val w = FidelityText.words(
            FidelityText.Verdict(oreFa(2), "uguale", punti = 1, diversi = 0),
            now,
        )
        assertTrue(w.detail.contains("un orario"), w.detail)
    }
}
