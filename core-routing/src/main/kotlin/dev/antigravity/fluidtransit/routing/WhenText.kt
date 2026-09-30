package dev.antigravity.fluidtransit.routing

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Le parole del "quando", costruite sulle funzioni di [Times] e non al posto loro: l'orologio e'
 * [Times.hhmm], il giorno [Times.dateLabel], la durata [Times.durationBetween]. Qui c'e' solo la
 * colla fra le tre, per chi scrive un orario dentro una frase: gli strumenti dell'assistente, ma
 * anche un widget o una notifica.
 *
 * Serve perche' un orario detto senza il giorno e' ambiguo quanto un numero detto senza l'unita':
 * alle 23:00 "06:40" e' domattina, e nel testo per il modello non c'era scritto.
 */
object WhenText {

    private fun dateOf(epoch: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochSecond(epoch).atZone(zone).toLocalDate()

    /** Il giorno di [epoch] detto rispetto a oggi ("domani", "5 ottobre"); null se e' oggi. */
    fun dayWord(epoch: Long, nowEpoch: Long, zone: ZoneId): String? {
        val day = dateOf(epoch, zone)
        val today = dateOf(nowEpoch, zone)
        return if (day == today) null else Times.dateLabel(day, today)
    }

    /** "06:40", oppure "domani 06:40" quando il giorno non e' quello di adesso. */
    fun clock(epoch: Long, nowEpoch: Long, zone: ZoneId): String {
        val ora = Times.hhmm(epoch, zone)
        val giorno = dayWord(epoch, nowEpoch, zone) ?: return ora
        return "$giorno $ora"
    }

    /** "alle 06:40", oppure "domani alle 06:40": per le frasi che dicono quando si parte. */
    fun atClock(epoch: Long, nowEpoch: Long, zone: ZoneId): String {
        val ora = Times.hhmm(epoch, zone)
        val giorno = dayWord(epoch, nowEpoch, zone) ?: return "alle $ora"
        return "$giorno alle $ora"
    }

    /**
     * "alle 06:40 (fra 12 min)", "alle 06:40 (ora)", "domani alle 06:40 (fra 7 h 40 min)".
     *
     * Lo strumento "quando_uscire" scriveva `06:40 (461 min)` per la partenza di domattina: i
     * minuti che mancano, senza il giorno, e in un numero da dividere per sessanta (a voce,
     * "quattrocentosessantuno minuti"). Sotto l'ora restano i minuti, che sono la grana giusta
     * (e "ora" quando e' imminente, come i tabelloni); sopra si dicono le ore, come
     * [Times.durationLabel], contate fra i due orari scritti come [Times.durationBetween].
     */
    fun atClockWithWait(epoch: Long, nowEpoch: Long, zone: ZoneId): String {
        val attesa = when {
            epoch - nowEpoch < Times.NOW_SECONDS -> "ora"
            Times.minutesUntil(nowEpoch, epoch) < DepartureText.CLOCK_AFTER_MINUTES ->
                "fra ${Times.minutesLabel(nowEpoch, epoch)}"
            else -> "fra ${Times.durationBetween(nowEpoch, epoch)}"
        }
        return "${atClock(epoch, nowEpoch, zone)} ($attesa)"
    }

    /**
     * L'orario di una partenza come lo scrive la tabella del giorno [serviceDate]: dopo la
     * mezzanotte "01:13 di notte", non un "01:13" che sembra stamattina.
     *
     * Si parte dall'orologio a muro e non dai secondi del giorno di servizio: nei due giorni
     * dell'ora legale i secondi dal "mezzogiorno meno dodici ore" sfasano di un'ora rispetto
     * all'orologio, e la tabella deve dire quello che c'e' scritto sulla pensilina.
     */
    fun clockOnDay(epochSecond: Long, serviceDate: LocalDate, zone: ZoneId): String {
        val at = Instant.ofEpochSecond(epochSecond).atZone(zone)
        val days = ChronoUnit.DAYS.between(serviceDate, at.toLocalDate()).coerceAtLeast(0)
        return serviceClock((days * 86_400 + at.toLocalTime().toSecondOfDay()).toInt())
    }

    /**
     * Un orario in secondi dall'inizio del giorno di servizio. [Times.serviceTime] dice "di notte"
     * per le corse che il feed scrive fra le 24 e le 30, e va bene fin li'. Una fascia come "dalle
     * 20 alle 10" arriva alle 34: scritta "10:00 di notte" sarebbe una frase falsa, quindi oltre le
     * 30 si dice che e' il giorno dopo.
     */
    fun serviceClock(secondsFromServiceDay: Int): String {
        val h = secondsFromServiceDay / 3600
        return when {
            h < 31 -> Times.serviceTime(secondsFromServiceDay)
            h < 48 -> "${Times.clockOfDay(secondsFromServiceDay / 60)} del giorno dopo"
            else -> "${Times.clockOfDay(secondsFromServiceDay / 60)} fra ${h / 24} giorni"
        }
    }
}
