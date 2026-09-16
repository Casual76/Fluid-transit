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
        val da = momento(startEpoch, nowEpoch)
        val a = momento(endEpoch, nowEpoch)
        return when {
            futuro && endEpoch > 0 -> "${da.dal()} ${da.testo} ${a.al()} ${a.testo}"
            futuro -> "${da.dal()} ${da.testo}"
            endEpoch > nowEpoch -> "Fino ${a.al()} ${a.testo}"
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
     *
     * Pubblica perche' serve anche altrove: qualunque cosa l'app dica con un
     * "quando" dovrebbe dirlo con le stesse parole, e due formati diversi per
     * la stessa idea sono due cose da imparare invece di una.
     */
    fun moment(epoch: Long, nowEpoch: Long): String = momento(epoch, nowEpoch).testo

    /**
     * Un istante e la preposizione che vuole davanti.
     *
     * In italiano l'articolo dipende da come si nomina il giorno: si dice
     * "fino a domani" ma "fino AL 31 dicembre", "da oggi alle 14" ma "DAL 5
     * ottobre". Senza questa distinzione uscivano due righe sbagliate sulla
     * stessa schermata: "Fino a 31 dicembre", che si legge sul telefono in
     * questo momento, e "Dal oggi alle 14:00" per un avviso che comincia
     * piu' tardi oggi. E "Dal lunedi'" non e' nemmeno un refuso: vuol dire
     * tutti i lunedi'.
     */
    private class Momento(val testo: String, val data: Boolean) {
        fun dal() = if (data) "Dal" else "Da"
        fun al() = if (data) "al" else "a"
    }

    private fun momento(epoch: Long, nowEpoch: Long): Momento {
        val zone = Ftb.ROME
        val t = ZonedDateTime.ofInstant(Instant.ofEpochSecond(epoch), zone)
        val now = ZonedDateTime.ofInstant(Instant.ofEpochSecond(nowEpoch), zone)
        val giorni = ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())
        val ora = "%02d:%02d".format(t.hour, t.minute)
        return when {
            giorni == 0L -> Momento("oggi alle $ora", data = false)
            giorni == 1L -> Momento("domani alle $ora", data = false)
            giorni in 2..6 -> Momento("${nomeGiorno(t)} alle $ora", data = false)
            // L'anno solo quando non e' questo.
            //
            // "Fino a 28 febbraio" letto a settembre puo' voler dire il
            // febbraio appena passato o quello che viene, e i due sensi sono
            // opposti: uno vuol dire "e' finita", l'altro "dura ancora cinque
            // mesi". Gli avvisi di deviazione per lavori scavalcano l'anno
            // regolarmente.
            t.year != now.year ->
                Momento("${t.dayOfMonth} ${nomeMese(t)} ${t.year}", data = true)

            else -> Momento("${t.dayOfMonth} ${nomeMese(t)}", data = true)
        }
    }

    private fun nomeGiorno(t: ZonedDateTime): String =
        t.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.ITALIAN)

    private fun nomeMese(t: ZonedDateTime): String =
        t.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ITALIAN)
    /**
     * Il testo di un avviso, senza le code del posto da cui viene.
     *
     * Gli avvisi di Autolinee Toscane nascono come messaggi social e ne
     * portano i segni: cominciano con una riga di hashtag — "#at_Firenze" —
     * e hanno righe vuote a raffica in mezzo. Messi in un sottotitolo
     * tagliato a centoventi caratteri, il risultato era che le prime undici
     * lettere che si leggevano di un avviso erano "#at_Firenze", e il
     * contenuto cominciava dopo.
     *
     * Non si tocca altro: le emoji restano (il cantiere e il bus dicono
     * qualcosa a colpo d'occhio) e le parole nemmeno si sfiorano.
     */
    fun body(raw: String): String {
        var righe = raw.replace("\r", "").split("\n")
        // Via le righe iniziali fatte solo di hashtag o vuote.
        var da = 0
        while (da < righe.size) {
            val r = righe[da].trim()
            if (r.isEmpty() || (r.startsWith("#") && !r.contains(" "))) da++ else break
        }
        righe = righe.drop(da)
        // Due righe vuote di fila diventano una: il resto e' impaginazione.
        val out = StringBuilder()
        var vuote = 0
        for (r in righe) {
            if (r.isBlank()) {
                vuote++
                if (vuote > 1) continue
            } else {
                vuote = 0
            }
            out.append(r.trimEnd()).append('\n')
        }
        return out.toString().trim()
    }
}
