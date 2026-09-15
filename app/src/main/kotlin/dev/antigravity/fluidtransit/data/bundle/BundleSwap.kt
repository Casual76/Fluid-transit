package dev.antigravity.fluidtransit.data.bundle

import java.io.File
import java.io.IOException

/**
 * Lo scambio del bundle notturno, senza un istante in cui l'app resta senza
 * orari.
 *
 * Prima la promozione era due righe: cancella il vecchio, rinomina il nuovo.
 * Fra quelle due righe non ci sono orari, e se la seconda fallisce — o se il
 * processo muore proprio li' — al riavvio non ce ne sono piu': schermata di
 * benvenuto e cinquanta megabyte da riscaricare, con la rete che magari e'
 * proprio il motivo per cui e' fallita.
 *
 * Sta qui e non dentro [BundleManager] per un motivo solo: cosi' si puo'
 * provare. Il ramo che conta e' quello che fallisce, ed e' anche l'unico che
 * non si vede mai girare.
 */
internal object BundleSwap {

    /**
     * Mette [part] al posto di [active], tenendo il vecchio da parte.
     *
     * Al ritorno, [active] e' il nuovo e [previous] e' il vecchio: sta al
     * chiamante buttarlo quando ha finito di leggerlo. Se qualcosa va storto
     * il vecchio torna dov'era e la funzione lancia: non si e' perso niente.
     */
    fun promote(part: File, active: File, previous: File) {
        if (previous.isFile && !previous.delete()) {
            throw IOException("non riesco a liberare ${previous.name}")
        }
        val hadActive = active.isFile
        if (hadActive && !active.renameTo(previous)) {
            throw IOException("non riesco a mettere da parte il bundle attivo")
        }
        if (!part.renameTo(active)) {
            // Il passo che puo' fallire e' questo, ed e' il motivo di tutto:
            // qui il vecchio esiste ancora e si rimette dov'era.
            if (hadActive) previous.renameTo(active)
            throw IOException("non riesco a installare il bundle scaricato")
        }
    }

    /**
     * Uno scambio interrotto dalla morte del processo.
     *
     * Se [active] non c'e' ma [previous] si', quello e' l'unico orario
     * rimasto: torna al suo posto. Restituisce true se ha recuperato
     * qualcosa, che serve solo a raccontarlo.
     */
    fun recover(active: File, previous: File): Boolean {
        if (active.isFile || !previous.isFile) return false
        return previous.renameTo(active)
    }
}
