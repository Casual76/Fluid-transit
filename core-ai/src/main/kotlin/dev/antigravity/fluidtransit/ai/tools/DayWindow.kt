package dev.antigravity.fluidtransit.ai.tools

/**
 * La fascia oraria di `orari_fermata_giorno`, in minuti dall'inizio del giorno di servizio
 * (oltre 24 ore per le corse dopo la mezzanotte, come nel feed).
 */
internal class DayWindow(val fromMinutes: Int, val toMinutes: Int) {

    val horizonSeconds: Int get() = (toMinutes - fromMinutes) * 60

    companion object {
        private val CLOCK = Regex("(\\d{1,2})[:.]?(\\d{2})?")

        /**
         * "07:00", "7.30", "23", "26:00" (le due di notte) in minuti; null se non e' un'ora.
         * Si accettano le ore fino alle 29 perche' "fino alle 25" e' una cosa che si dice.
         */
        fun minutesOf(raw: String): Int? {
            val m = CLOCK.find(raw.trim()) ?: return null
            val h = m.groupValues[1].toIntOrNull()?.takeIf { it in 0..29 } ?: return null
            val min = m.groupValues[2].toIntOrNull() ?: 0
            if (min !in 0..59) return null
            return h * 60 + min
        }

        /**
         * Dove finisce il giorno di servizio di questo bundle, in minuti: il feed vero arriva
         * alle 30:10, e mai meno di 24 ore anche se un bundle non ha corse notturne.
         */
        fun serviceDayEndMinutes(maxTripEndSeconds: Int): Int =
            ((maxTripEndSeconds + 59) / 60).coerceIn(24 * 60, 36 * 60)

        /**
         * Le due ore chieste dal modello, o quelle di default; null quando una c'e' e non si
         * capisce (meglio dirlo che rispondere su una fascia diversa da quella chiesta).
         *
         * Prima la fascia si calcolava come `(alle - dalle) * 60` con un minimo di un minuto: "fra
         * le 23 e le 2" diventava un minuto dopo le 23, e lo strumento rispondeva "non passa
         * niente" su una linea che di notte passa eccome. Se "alle" non viene dopo "dalle" la fascia
         * attraversa la mezzanotte, ed e' quello che il modello voleva dire. E senza fascia si
         * guarda tutto il giorno di servizio: con le 6-22 di prima il primo bus delle 05:20 e gli
         * ultimi della notte non comparivano mai, e "il primo bus di domani" era il secondo.
         */
        fun parse(from: String?, to: String?, serviceDayEndMinutes: Int): DayWindow? {
            val start = if (from == null) 0 else minutesOf(from) ?: return null
            val end = if (to == null) {
                maxOf(serviceDayEndMinutes, start + 60)
            } else {
                var e = minutesOf(to) ?: return null
                while (e <= start) e += 24 * 60
                e
            }
            return DayWindow(start, end)
        }
    }
}
