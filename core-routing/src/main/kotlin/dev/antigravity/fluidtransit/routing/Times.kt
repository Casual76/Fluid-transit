package dev.antigravity.fluidtransit.routing

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Il tempo come lo legge un umano, con UNA regola sola.
 *
 * Prima della Fase 8 ce n'erano quattro nello stesso prodotto: la scheda
 * fermata troncava i minuti, la capsula del ritardo li arrotondava, gli
 * itinerari arrotondavano ma su un'altra base, la navigazione arrotondava
 * per eccesso. Il risultato era che la stessa corsa, sullo stesso schermo,
 * diceva "+3 min di ritardo" sopra e un conteggio che non tornava sotto —
 * ed e' esattamente il tipo di incoerenza che fa sembrare un'app
 * approssimativa anche quando i dati sono giusti.
 *
 * La regola: si arrotonda al minuto piu' vicino, sempre, in ogni direzione.
 */
object Times {

    /** Sotto questa soglia non si dice un numero, si dice "ora". */
    const val NOW_SECONDS = 30

    /** Arrotondamento al minuto piu' vicino, segno conservato. */
    fun toMinutes(seconds: Int): Int =
        if (seconds >= 0) (seconds + 30) / 60 else -((-seconds + 30) / 60)

    fun toMinutes(seconds: Long): Int = toMinutes(seconds.toInt())

    /**
     * I minuti che mancano, mai negativi: una corsa il cui orario e' passato
     * si mostra come imminente, non come "-2 min" (che il widget faceva).
     */
    fun minutesUntil(nowEpoch: Long, targetEpoch: Long): Int =
        toMinutes(targetEpoch - nowEpoch).coerceAtLeast(0)

    /** "ora", "1 min", "12 min" — l'etichetta breve delle liste. */
    fun minutesLabel(nowEpoch: Long, targetEpoch: Long): String {
        val seconds = targetEpoch - nowEpoch
        if (seconds < NOW_SECONDS) return "ora"
        return "${minutesUntil(nowEpoch, targetEpoch)} min"
    }

    /**
     * Il ritardo detto a parole. Null quando non c'e' un dato live: e'
     * diverso da "in orario", e prima le due cose erano indistinguibili
     * perche' si guardava `delay != 0`.
     */
    fun delayLabel(delaySeconds: Int?): String = when {
        delaySeconds == null -> "orario previsto"
        toMinutes(delaySeconds) > 0 -> "+${toMinutes(delaySeconds)} min di ritardo"
        toMinutes(delaySeconds) < 0 -> "${-toMinutes(delaySeconds)} min in anticipo"
        else -> "in orario"
    }

    /** L'orologio, sempre a due cifre: `07:05`, non `7:05`. */
    fun hhmm(epochSecond: Long, zone: ZoneId = Ftb.ROME): String {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), zone)
        return "%02d:%02d".format(t.hour, t.minute)
    }

    /**
     * L'orologio di un orario espresso in secondi dal giorno di servizio.
     *
     * Le corse oltre le 24:00 esistono e sono 2.709 nel feed: stamparle
     * modulo 24 senza dirlo — che e' quello che faceva la scheda linea,
     * dove le 25:30 diventavano "1:30" — nasconde all'utente che quel bus
     * passa domani mattina.
     */
    fun serviceTime(secondsFromServiceDay: Int): String {
        val h = secondsFromServiceDay / 3600
        val m = (secondsFromServiceDay % 3600) / 60
        return if (h >= 24) "%02d:%02d (domani)".format(h - 24, m) else "%02d:%02d".format(h, m)
    }
}
