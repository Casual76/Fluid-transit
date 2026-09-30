package dev.antigravity.fluidtransit.data.bundle

import java.time.LocalDate

/**
 * Le decisioni del controllo in sottofondo degli orari, senza rete e senza
 * Android: cosi' si possono provare.
 *
 * Il controllo aveva una sola regola sui dati mobili — "su rete a consumo non
 * si scarica" — e una sola eccezione, nata perche' chi usa solo i dati non
 * avrebbe altrimenti nessun modo di aggiornare degli orari scaduti. Ma
 * l'eccezione era scritta come "scarica lo stesso", cioe' sei megabyte sul
 * piano dati di qualcuno senza dirglielo, contro la decisione presa per il
 * primo scarico: su rete a consumo si CHIEDE prima. Adesso l'eccezione e' una
 * domanda ([Network.AskFirst]), e la domanda sta nella schermata dei dati.
 *
 * Sta qui e non dentro [BundleManager] per lo stesso motivo di [BundleSwap]:
 * il manager vuole un Context, e il ramo che conta — quello che non deve
 * scaricare senza chiedere — e' proprio quello che non si vede mai girare.
 */
internal object BundleRefreshPolicy {

    /** Che cosa e' lecito fare con la rete di adesso. */
    enum class Network {
        /** Rete non a consumo, o l'utente ha gia' detto di si': si scarica. */
        Free,

        /** Rete a consumo con gli orari in scadenza: si scarica solo dopo un si'. */
        AskFirst,

        /** Non si tocca la rete: a consumo con orari ancora buoni, o rete non ancora aperta. */
        Skip,
    }

    /** Che cosa fare, una volta letto l'indice. */
    enum class Step {
        Nothing,

        /** Stessi orari, ma la pipeline delle tile ha pubblicato un overlay nuovo. */
        OverlayOnly,
        Install,

        /** Ci sono orari nuovi ma la rete e' a consumo: si offre, non si scarica. */
        Offer,
    }

    /**
     * @param metered `true` a consumo, `false` no, `null` se una rete per noi
     *   ancora non c'e' (vedi `Metered`).
     * @param expiring gli orari in tasca sono scaduti o scadono domani.
     * @param approved l'utente ha detto "scarica ora": la rete non conta piu'.
     */
    fun network(metered: Boolean?, expiring: Boolean, approved: Boolean): Network = when {
        approved -> Network.Free
        metered == false -> Network.Free
        // "Non lo so ancora" all'avvio: il giro si salta, il prossimo e' fra
        // un'ora. Chi vuole comunque aggiornare lo dice con il tasto.
        metered == null -> Network.Skip
        // A consumo: con orari ancora buoni il bundle di ieri basta e la
        // domanda non vale la pena; con orari finiti l'app non serve a
        // niente, ed e' il caso in cui si chiede.
        expiring -> Network.AskFirst
        else -> Network.Skip
    }

    /**
     * @param sameBuild l'indice annuncia il bundle che si ha gia'.
     * @param overlayChanged l'indice annuncia un overlay diverso da quello in uso.
     */
    fun step(network: Network, sameBuild: Boolean, overlayChanged: Boolean): Step = when {
        network == Network.Skip -> Step.Nothing
        // Un overlay nuovo non costa un download: l'indice e' gia' in mano,
        // quindi vale anche quando gli orari nuovi andrebbero solo offerti.
        sameBuild -> if (overlayChanged) Step.OverlayOnly else Step.Nothing
        network == Network.AskFirst -> Step.Offer
        else -> Step.Install
    }

    /**
     * Gli orari in tasca sono finiti, o finiscono domani.
     *
     * Domani e non solo oggi: un aggiornamento che arriva la sera dell'ultimo
     * giorno di validita' e' gia' in ritardo per chi guarda il tabellone
     * l'indomani mattina.
     */
    fun expiring(today: LocalDate, feedEnd: LocalDate): Boolean =
        !today.plusDays(1).isBefore(feedEnd)

    /**
     * I megabyte per una frase come "circa 6 MB".
     *
     * Arrotondati e mai zero: con la divisione intera un indice da 900 kB
     * diventava "circa 0 MB", che a chi decide se spendere i propri dati non
     * dice niente.
     */
    fun megabytes(bytes: Long): Int {
        val mb = 1024L * 1024L
        return ((bytes + mb / 2) / mb).toInt().coerceAtLeast(1)
    }
}
