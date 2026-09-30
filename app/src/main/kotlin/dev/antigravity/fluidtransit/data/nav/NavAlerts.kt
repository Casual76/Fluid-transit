package dev.antigravity.fluidtransit.data.nav

/**
 * Quando far vibrare il telefono, e quando no.
 *
 * Sta fuori dal Service perche' tutto quello che puo' andare storto qui e'
 * aritmetica: un avviso che si ripete a ogni giro, uno che non arriva mai,
 * due che arrivano attaccati. Dentro un `Service` quei tre casi si provano
 * solo prendendo un autobus vero.
 *
 * Tre regole insieme, e servono tutte e tre:
 *
 * 1. **Un colpo per evento e per tappa.** Con un viaggio a due tratte i
 *    segni si riazzerano al cambio di tappa: prima stavano su due booleani
 *    riazzerati solo alla partenza, quindi il secondo "il tuo bus sta
 *    arrivando" non sarebbe arrivato mai.
 * 2. **Due giri consecutivi** prima di annunciare un bus in arrivo. Il
 *    conto delle fermate non e' monotono: un singhiozzo del feed lo fa
 *    andare 2 -> 4 -> 1, e col poll a quindici secondi del modo Preciso
 *    sono due vibrazioni in mezzo minuto su un mezzo che non si e' mosso.
 * 3. **Quarantacinque secondi fra un avviso e l'altro.** Su una tratta
 *    corta "preparati a scendere" e "scendi alla prossima" arrivano
 *    altrimenti uno addosso all'altro, e due vibrazioni di fila si leggono
 *    come una sola. Tranne per gli avvisi FINALI — arrivato, orari cambiati —
 *    dopo i quali il servizio smette di girare: se il silenzio li rimandava,
 *    non c'era un giro dopo in cui darli, e si perdevano per sempre.
 */
class NavAlerts(private val alightRadiusM: Int = 300) {

    class Avviso(
        val id: String,
        val titolo: String,
        val testo: String,
        /** Suona e vibra, oppure scivola in silenzio nel cassetto. */
        val forte: Boolean,
    )

    private var tappa = Int.MIN_VALUE
    private val fatti = HashSet<String>()
    private var atteso: String? = null
    private var conferme = 0
    private var ultimo = 0L

    /** L'avviso da mandare adesso, o null. Si chiama a ogni giro. */
    fun next(s: NavState, nowEpoch: Long): Avviso? {
        if (s.legIndex != tappa) {
            tappa = s.legIndex
            fatti.clear()
            atteso = null
            conferme = 0
        }
        val c = candidato(s)
        if (c == null || fatti.contains(c.avviso.id)) {
            atteso = null
            conferme = 0
            return null
        }
        if (c.avviso.id != atteso) {
            atteso = c.avviso.id
            conferme = 0
        }
        conferme++
        if (c.aspettaConferma && conferme < 2) return null
        if (!c.finale && ultimo != 0L && nowEpoch - ultimo < QUIET_SECONDS) return null
        fatti.add(c.avviso.id)
        ultimo = nowEpoch
        return c.avviso
    }

    private class Candidato(
        val avviso: Avviso,
        val aspettaConferma: Boolean,
        /** Dopo questo il servizio si ferma: non si rimanda. */
        val finale: Boolean = false,
    )

    /** Il piu' urgente fra quelli veri adesso. L'ordine e' la priorita'. */
    private fun candidato(s: NavState): Candidato? {
        // La navigazione ha smesso di guidare: gli orari si sono scambiati e
        // la corsa non c'e' piu'. Chi e' sul bus con lo schermo spento deve
        // saperlo, o aspetterebbe un "scendi" che non arrivera'.
        if (s.phase == "lost") {
            return Candidato(
                Avviso(LOST, s.headline, s.detail, forte = true),
                aspettaConferma = false,
                finale = true,
            )
        }
        if (s.phase == "arrived") {
            // Chi arriva a piedi, dopo l'ultima camminata, non deve scendere
            // da niente: "Scendi qui" in mezzo al marciapiede e' una frase
            // sbagliata detta con la vibrazione forte.
            val avviso = if (s.arrivedOnFoot) {
                Avviso(ARRIVED, "Sei arrivato", s.destName, forte = true)
            } else {
                Avviso(ARRIVED, "Scendi qui", "Sei a ${s.destName}", forte = true)
            }
            return Candidato(avviso, aspettaConferma = false, finale = true)
        }
        if (s.phase == "ride") {
            if (s.stopsRemaining <= 1) {
                return Candidato(
                    Avviso(ALIGHT, "Scendi alla prossima", s.headline, forte = true),
                    aspettaConferma = false,
                )
            }
            // Due fermate prima, e solo su una tratta abbastanza lunga da
            // rendere utile il preavviso: su tre fermate totali "preparati"
            // e "scendi" sarebbero la stessa cosa detta due volte.
            if (s.stopsRemaining <= 2 && s.totalStops >= 4) {
                return Candidato(
                    Avviso(
                        PREPARE,
                        "Preparati a scendere",
                        "Fra due fermate scendi a ${s.alightName}",
                        forte = false,
                    ),
                    aspettaConferma = false,
                )
            }
            // Fisicamente vicino alla discesa, con piu' fermate davanti di
            // quante dica l'orario: un bus in ritardo, e i soli orari
            // facevano scattare l'avviso dopo che eri gia' passato. Ma non si
            // dice "alla prossima", che qui sarebbe falso — e con un id suo:
            // se prendesse quello di "scendi alla prossima", quando davvero
            // manca una fermata quell'avviso risulterebbe gia' dato.
            if (s.metersToGo in 0..alightRadiusM) {
                return Candidato(
                    Avviso(
                        NEAR,
                        "Stai per arrivare a ${s.alightName}",
                        s.headline,
                        forte = true,
                    ),
                    aspettaConferma = false,
                )
            }
            return null
        }
        if (s.phase == "wait") {
            // Una corsa cancellata non arriva. Aspettarla in silenzio e' la
            // cosa peggiore che l'app possa fare, e fino a ieri era proprio
            // quello che faceva: la riconosceva e non avvisava nessuno.
            if (s.canceled) {
                return Candidato(
                    Avviso(
                        CANCELED,
                        dev.antigravity.fluidtransit.routing.DepartureText.canceledLine(s.lineName),
                        "Cerca un altro percorso",
                        forte = true,
                    ),
                    aspettaConferma = false,
                )
            }
            if (s.busStopsAway in 0..1) {
                return Candidato(
                    Avviso(
                        ARRIVING,
                        "Il tuo bus sta arrivando",
                        // Le stesse parole della card: erano riscritte qui.
                        "La ${s.lineName} ${NavigationService.dove(s.busStopsAway)}",
                        forte = true,
                    ),
                    aspettaConferma = true,
                )
            }
        }
        return null
    }

    companion object {
        const val ARRIVING = "bus-in-arrivo"
        const val CANCELED = "cancellata"
        const val PREPARE = "preparati"
        const val ALIGHT = "scendi-alla-prossima"
        const val ARRIVED = "scendi-qui"
        const val LOST = "orari-cambiati"
        const val NEAR = "vicino-alla-discesa"

        /** Sotto questo scarto due avvisi si leggono come uno solo. */
        const val QUIET_SECONDS = 45L
    }
}
