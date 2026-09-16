package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * La regola unica del tempo.
 *
 * Prima ce n'erano quattro nello stesso prodotto, e sullo stesso schermo la
 * capsula del ritardo e il conteggio sotto non tornavano fra loro. Questi
 * test esistono per non tornarci.
 */
class TimesTest {

    @Test
    fun `si arrotonda al minuto piu' vicino, in tutti e due i versi`() {
        assertEquals(0, Times.toMinutes(29))
        assertEquals(1, Times.toMinutes(30))
        assertEquals(1, Times.toMinutes(89))
        assertEquals(2, Times.toMinutes(90))
        assertEquals(-1, Times.toMinutes(-30))
        assertEquals(-2, Times.toMinutes(-90))
    }

    @Test
    fun `i minuti che mancano non vanno sottozero`() {
        // Il widget mostrava "-5 min" per una corsa il cui orario era
        // passato: mancava un coerceAtLeast(0).
        assertEquals(0, Times.minutesUntil(1_000, 700))
        assertEquals(5, Times.minutesUntil(1_000, 1_300))
    }

    @Test
    fun `sotto la mezzo minuto si dice ora`() {
        assertEquals("ora", Times.minutesLabel(1_000, 1_020))
        assertEquals("1 min", Times.minutesLabel(1_000, 1_045))
        assertEquals("3 min", Times.minutesLabel(1_000, 1_180))
    }

    @Test
    fun `nessun dato e in orario sono due cose diverse`() {
        // Prima si guardava `delay != 0`, quindi una corsa monitorata e
        // puntuale era indistinguibile da una senza dati live.
        //
        // E le parole sono quelle del vocabolario comune: "da tabella", non
        // "previsto", che nel vecchio vocabolario voleva dire due cose opposte.
        assertEquals("orario da tabella", Times.delayLabel(null))
        assertEquals("in orario", Times.delayLabel(0))
        assertEquals("in orario", Times.delayLabel(20))
        assertEquals("+3 min di ritardo", Times.delayLabel(170))
        assertEquals("2 min in anticipo", Times.delayLabel(-100))
    }

    @Test
    fun `l'orologio ha sempre due cifre, e l'ora e' quella di Roma`() {
        val sette05 = java.time.ZonedDateTime
            .of(2026, 9, 3, 7, 5, 0, 0, Ftb.ROME)
            .toEpochSecond()
        assertEquals("07:05", Times.hhmm(sette05))
        val zeroSei = java.time.ZonedDateTime
            .of(2026, 1, 12, 6, 9, 0, 0, Ftb.ROME)
            .toEpochSecond()
        assertEquals("06:09", Times.hhmm(zeroSei))
    }

    @Test
    fun `le corse oltre la mezzanotte lo dicono`() {
        assertEquals("07:05", Times.serviceTime(7 * 3600 + 5 * 60))
        // La scheda linea stampava le 25:30 come "1:30", senza dire che quel
        // bus passa domani mattina.
        assertEquals("01:30 di notte", Times.serviceTime(25 * 3600 + 30 * 60))
        // L'ultima corsa del feed vero finisce alle 30:10: modulo 24 secco
        // diventerebbe "06:10", cioe' un'ora fa invece che fra sei.
        assertEquals("06:10 di notte", Times.serviceTime(30 * 3600 + 10 * 60))
        assertEquals("00:00 di notte", Times.serviceTime(24 * 3600))
        assertEquals("23:59", Times.serviceTime(23 * 3600 + 59 * 60))
    }
    @Test
    fun `una durata si dice come la direbbe una persona`() {
        // Sotto l'ora i minuti, che sono la grana giusta per un autobus.
        assertEquals("0 min", Times.durationLabel(0))
        assertEquals("1 min", Times.durationLabel(60))
        assertEquals("43 min", Times.durationLabel(43 * 60))
        assertEquals("59 min", Times.durationLabel(59 * 60))
    }

    @Test
    fun `la durata di un viaggio torna con i due orari scritti`() {
        // Visto sul telefono: "10:26 -> 10:51" con accanto "24 min", che e'
        // una sottrazione sbagliata per chiunque la faccia a mente. I secondi
        // davano ragione all'app — ventiquattro minuti e dieci — ma
        // l'orologio li tronca e la durata li arrotondava, e le due cose non
        // possono che litigare.
        val dieci26e50 =
            java.time.ZonedDateTime.of(2026, 9, 16, 10, 26, 50, 0, Ftb.ROME).toEpochSecond()
        val dieci51e00 =
            java.time.ZonedDateTime.of(2026, 9, 16, 10, 51, 0, 0, Ftb.ROME).toEpochSecond()
        assertEquals("25 min", Times.durationBetween(dieci26e50, dieci51e00))

        // E il conto torna proprio con quello che c'e' scritto: l'orologio
        // continua a troncare, che e' giusto — un bus delle 10:26:50 non
        // parte alle 10:27 — ed e' la durata ad allinearsi a lui.
        fun minuti(hhmm: String) = hhmm.take(2).toInt() * 60 + hhmm.drop(3).toInt()
        val scritti = minuti(Times.hhmm(dieci51e00)) - minuti(Times.hhmm(dieci26e50))
        assertEquals("$scritti min", Times.durationBetween(dieci26e50, dieci51e00))
    }

    @Test
    fun `i secondi non fanno comparire un minuto in piu'`() {
        val base = java.time.ZonedDateTime.of(2026, 9, 16, 9, 0, 0, 0, Ftb.ROME).toEpochSecond()
        assertEquals("10 min", Times.durationBetween(base, base + 10 * 60))
        assertEquals("10 min", Times.durationBetween(base + 59, base + 10 * 60 + 59))
        assertEquals("0 min", Times.durationBetween(base, base + 59))
    }

    @Test
    fun `sopra l'ora le ore vanno davanti`() {
        // "240 min" e' un numero da convertire in testa prima di capirlo, e un
        // viaggio notturno con tre ore di attesa in mezzo ci arriva senza
        // sforzo: era la durata che l'app mostrava per un viaggio da quattro
        // ore.
        assertEquals("1 h", Times.durationLabel(60 * 60))
        assertEquals("1 h 1 min", Times.durationLabel(61 * 60))
        assertEquals("3 h 21 min", Times.durationLabel((3 * 60 + 21) * 60))
        assertEquals("4 h", Times.durationLabel(240 * 60))
    }

    @Test
    fun `si arrotonda al minuto, come tutto il resto`() {
        // 89 secondi sono un minuto e mezzo: due posti dell'app che li
        // scrivono in modo diverso sono due posti che non si fidano l'uno
        // dell'altro.
        assertEquals("1 min", Times.durationLabel(89))
        assertEquals("2 min", Times.durationLabel(91))
        assertEquals("1 h", Times.durationLabel(59 * 60 + 40))
    }

    @Test
    fun `una durata negativa non esiste e non si stampa`() {
        // Non dovrebbe capitare, ma "-3 min di viaggio" sarebbe peggio di
        // "0 min": un orologio che va all'indietro e' esattamente il genere
        // di cosa che fa smettere di credere a tutto il resto.
        assertEquals("0 min", Times.durationLabel(-500))
    }

}
