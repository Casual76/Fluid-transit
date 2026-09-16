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
     *
     * Il caso senza dati dice "orario da tabella", non "orario previsto".
     * "Previsto" voleva dire due cose opposte nel vocabolario di prima —
     * l'orario pubblicato e l'assenza di dati dal vivo — e questa era
     * l'ultima riga rimasta a usarlo nel secondo senso: la si leggeva
     * nella testata della scheda corsa, dove diceva "Orario previsto"
     * proprio dove la scheda fermata scrive "orario da tabella".
     */
    fun delayLabel(delaySeconds: Int?): String = when {
        delaySeconds == null -> "orario da tabella"
        toMinutes(delaySeconds) > 0 -> "+${toMinutes(delaySeconds)} min di ritardo"
        toMinutes(delaySeconds) < 0 -> "${-toMinutes(delaySeconds)} min in anticipo"
        else -> "in orario"
    }

    /**
     * Una durata detta come la direbbe una persona.
     *
     * "240 min" e' un numero che si deve convertire in testa prima di capirlo,
     * e un viaggio notturno con tre ore di attesa in mezzo lo raggiunge senza
     * sforzo. Sotto l'ora restano i minuti, che sono la grana giusta per un
     * autobus; sopra, le ore davanti, e i minuti solo se ce ne sono.
     *
     * Si arrotonda al minuto piu' vicino come tutto il resto dell'app: un
     * viaggio da 89 secondi e' "1 min", non "1 min" per troncamento e "2 min"
     * da un'altra parte.
     */
    fun durationLabel(seconds: Int): String {
        val minutes = toMinutes(seconds).coerceAtLeast(0)
        if (minutes < 60) return "$minutes min"
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0) "$h h" else "$h h $m min"
    }

    /** L'orologio, sempre a due cifre: `07:05`, non `7:05`. */
    fun hhmm(epochSecond: Long, zone: ZoneId = Ftb.ROME): String {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), zone)
        return "%02d:%02d".format(t.hour, t.minute)
    }

    /**
     * L'orologio di un orario espresso in secondi dal giorno di servizio.
     *
     * Le corse oltre le 24:00 esistono e sono 2.709 nel feed, e l'ultima
     * finisce alle 30:10. Stamparle modulo 24 senza dirlo fa credere che
     * l'ultima corsa sia passata stamattina invece che stanotte: "30:10"
     * diventa "06:10", che e' un'ora fa invece che fra sei.
     *
     * Questa funzione esisteva gia', con i suoi test, e non la chiamava
     * nessuno: la scheda linea si era riscritta la stessa cosa in casa e
     * l'assistente se l'era riscritta male, senza il "di notte". Le parole
     * sono quelle della scheda linea, che erano le migliori delle tre.
     */
    fun serviceTime(secondsFromServiceDay: Int): String {
        val h = secondsFromServiceDay / 3600
        val m = (secondsFromServiceDay % 3600) / 60
        val orologio = "%02d:%02d".format(h % 24, m)
        return if (h >= 24) "$orologio di notte" else orologio
    }
}
