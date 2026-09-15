package dev.antigravity.fluidtransit.routing

import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Quando vale un avviso di servizio, detto come lo direbbe una persona.
 *
 * Gli avvisi arrivavano con due timestamp e non si mostravano: sei righe in
 * fondo alla scheda Oggi, con la testata e i primi 220 caratteri, senza
 * periodo e senza le linee toccate. Ma un avviso senza periodo e' quasi
 * inutile — "deviazione in via Nazionale" e' un'altra cosa se dura fino a
 * stasera o fino a marzo — e l'informazione c'era gia' dentro il feed.
 */
object AlertText {

    /**
     * Il periodo, o null quando non c'e' niente da dire.
     *
     * Un avviso gia' cominciato e senza fine dichiarata non ha un periodo da
     * raccontare: e' semplicemente in corso, e scriverlo occuperebbe una riga
     * per non dire niente.
     */
    fun period(startEpoch: Long, endEpoch: Long, nowEpoch: Long): String? {
        val futuro = startEpoch > nowEpoch
        return when {
            futuro && endEpoch > 0 ->
                "Dal ${moment(startEpoch, nowEpoch)} al ${moment(endEpoch, nowEpoch)}"
            futuro -> "Dal ${moment(startEpoch, nowEpoch)}"
            endEpoch > nowEpoch -> "Fino a ${moment(endEpoch, nowEpoch)}"
            // Gia' finito: puo' capitare fra un giro di feed e l'altro.
            endEpoch > 0 -> "Terminato"
            else -> null
        }
    }

    /** Vero se l'avviso riguarda adesso: e' il filtro che decide cosa mostrare. */
    fun active(startEpoch: Long, endEpoch: Long, nowEpoch: Long): Boolean =
        (startEpoch == 0L || startEpoch <= nowEpoch) &&
            (endEpoch == 0L || endEpoch >= nowEpoch)

    /**
     * Un istante, con la precisione che serve e non di piu'.
     *
     * Oggi e domani si dicono per nome, la settimana col giorno, oltre con la
     * data. L'ora si aggiunge solo quando il giorno da solo non basta a
     * decidere se uscire adesso.
     */
    private fun moment(epoch: Long, nowEpoch: Long): String {
        val zone = Ftb.ROME
        val t = ZonedDateTime.ofInstant(Instant.ofEpochSecond(epoch), zone)
        val now = ZonedDateTime.ofInstant(Instant.ofEpochSecond(nowEpoch), zone)
        val giorni = ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())
        val ora = "%02d:%02d".format(t.hour, t.minute)
        return when {
            giorni == 0L -> "oggi alle $ora"
            giorni == 1L -> "domani alle $ora"
            giorni in 2..6 -> "${nomeGiorno(t)} alle $ora"
            else -> "${t.dayOfMonth} ${nomeMese(t)}"
        }
    }

    private fun nomeGiorno(t: ZonedDateTime): String =
        t.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.ITALIAN)

    private fun nomeMese(t: ZonedDateTime): String =
        t.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ITALIAN)
}
