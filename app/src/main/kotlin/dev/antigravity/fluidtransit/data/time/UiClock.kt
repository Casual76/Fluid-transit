package dev.antigravity.fluidtransit.data.time

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Il battito dell'app: uno solo, e allineato al muro.
 *
 * Prima ce n'erano quattro, ognuno figlio della schermata che lo aveva
 * scritto: quindici secondi nella scheda fermata, quindici nel pannello
 * corsa, venti nella scheda Oggi, trenta nei Preferiti. Ognuno partiva
 * quando la sua schermata si apriva, quindi due schermate aperte a sette
 * secondi di distanza giravano il minuto a sette secondi di distanza. La
 * stessa fermata mostrava "3 min" in un posto e "4 min" nell'altro, e tutti e
 * due avevano ragione.
 *
 * L'allineamento al muro e' il pezzo che rende letterale lo "stesso istante":
 * il battito cade a secondi multipli dell'intervallo, non a intervalli dal
 * momento in cui qualcuno si e' iscritto. Due schermate aperte in momenti
 * diversi battono insieme lo stesso.
 *
 * Dieci secondi e non sessanta: il numero mostrato e' arrotondato al minuto
 * piu' vicino, quindi cambia a meta' minuto, e un battito al minuto lo
 * lascerebbe indietro fino a trenta secondi.
 */
object UiClock {

    const val TICK_SECONDS = 10L

    /**
     * L'istante corrente in secondi, a ogni battito.
     *
     * Emette subito, cosi' chi si iscrive non aspetta il primo intervallo.
     */
    fun ticks(nowMillis: () -> Long = System::currentTimeMillis): Flow<Long> = flow {
        while (true) {
            val ms = nowMillis()
            emit(ms / 1000)
            val periodMs = TICK_SECONDS * 1000
            delay(periodMs - ms % periodMs)
        }
    }
}
