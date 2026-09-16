package dev.antigravity.fluidtransit.bundler

import dev.antigravity.fluidtransit.routing.Ftb

/**
 * L'assegnazione dei colori alle linee, condivisa fra il bundler (che la
 * scrive nel record ROUTES) e l'overlay (che la scrive nelle tile): stessa
 * funzione, stessi input, stessi colori ovunque.
 *
 * Il feed colora per categoria (~6 colori su 766 linee): inutilizzabile per
 * "ogni tratta col suo colore". La richiesta vera - decisa con l'utente -
 * non e' 766 colori distinti ma che 2-3 linee sovrapposte abbiano colori
 * nettamente diversi. E' una colorazione di grafo: due linee sono adiacenti
 * se condividono fermate, la palette ha 12 tinte tarate per la mappa, e
 * l'assegnazione e' deterministica - greedy per grado decrescente, pesata
 * sulle fermate condivise quando i vicini hanno gia' esaurito la palette.
 */
object RouteColoring {

    /**
     * Dodici tinte distinte, leggibili su basemap chiara e scura: sature ma
     * non fluorescenti, senza gialli pallidi (spariscono sul chiaro) e senza
     * blu notte (spariscono sullo scuro). Come interi 0xRRGGBB.
     */
    val PALETTE = intArrayOf(
        0xE5484D, // rosso
        0x2E90FA, // azzurro
        0x12A594, // verde acqua
        0xF76B15, // arancio
        0x8E4EC6, // viola
        0x5B9E31, // verde foglia
        0xE5006A, // magenta
        0x0B6BCB, // blu
        0xB8860B, // ocra
        0x00A2C7, // ciano
        0xD6409F, // rosa acceso
        0x7C66DC, // indaco
    )

    fun hex(paletteIndex: Int): String = "#%06X".format(PALETTE[paletteIndex])

    /**
     * Assegna un indice di palette a ogni linea.
     *
     * @param routeIds gli id GTFS, nell'ordine degli indici usati in [routesAtStop]
     * @param routesAtStop per ogni fermata, gli indici delle linee che la servono
     * @param ieri i colori dell'ultimo bundle pubblicato. Vedi [Ieri].
     * @return per ogni linea, l'indice nella [PALETTE]
     */
    fun assign(
        routeIds: List<String>,
        routesAtStop: Iterable<Collection<Int>>,
        ieri: Ieri = Ieri.NESSUNO,
    ): IntArray {
        val preferred = ieri.colori
        // Il grafo di sovrapposizione, pesato: quante fermate condividono.
        val adjacency = HashMap<Long, Int>()
        for (s in routesAtStop) {
            val list = s.toIntArray().also { it.sort() }
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val key = (list[i].toLong() shl 32) or list[j].toLong()
                    adjacency[key] = (adjacency[key] ?: 0) + 1
                }
            }
        }
        val neighbors = Array(routeIds.size) { HashMap<Int, Int>() }
        for ((key, weight) in adjacency) {
            val a = (key ushr 32).toInt()
            val b = (key and 0xffffffff).toInt()
            neighbors[a][b] = weight
            neighbors[b][a] = weight
        }

        val colors = IntArray(routeIds.size) { -1 }
        val order = routeIds.indices.sortedWith(
            compareByDescending<Int> { neighbors[it].size }.thenBy { routeIds[it] },
        )

        // Primo giro: chi puo' tenersi il colore di ieri se lo tiene.
        //
        // Prima questa era solo una preferenza dentro il giro unico, e non
        // bastava: una linea con tanti incroci si prendeva per prima la
        // tinta che un'altra aveva ieri, e quella doveva spostarsi anche se
        // nessuno glielo chiedeva.
        //
        // Misurato su una rete di prova da 120 linee e 900 fermate — molto
        // piu' fitta di quella vera — perturbando una fermata su cento come
        // fa un feed da una notte all'altra: senza preferenza cambiavano
        // colore 42 linee su 120, con la preferenza dentro il giro unico 24,
        // con questo primo giro dedicato 6. Quel che resta sono gli incroci
        // NUOVI, dove un colore deve per forza spostarsi.
        if (preferred.isNotEmpty()) {
            for (r in order) {
                val want = preferred[Ftb.hash64(routeIds[r])] ?: continue
                if (want !in PALETTE.indices) continue
                // Si cede il colore solo davanti a un incrocio NUOVO.
                //
                // Dodici tinte non bastano per una regione intera: sul feed
                // vero restano 560 coppie che si incrociano e condividono la
                // tinta, su 12.703. Quelle erano gia' cosi' ieri, e
                // spostarle non ne toglie nemmeno una — misurato: 560
                // conflitti prima, 559 dopo, e 98 linee di un altro colore
                // senza che nella rete fosse cambiato niente. Quindi una
                // coppia che ieri si incrociava gia' con la stessa tinta non
                // e' un motivo per cambiarla; una che si incrocia per la
                // prima volta si'.
                //
                // La differenza si legge dal bundle di ieri, non si indovina
                // dai colori: due linee lontane possono avere la stessa
                // tinta senza essersi mai incrociate, e trattare quel caso
                // come "gia' cosi'" vorrebbe dire creare conflitti nuovi.
                val mio = Ftb.hash64(routeIds[r])
                val scontro = neighbors[r].keys.any { n ->
                    colors[n] == want && !ieri.tollerata(mio, Ftb.hash64(routeIds[n]))
                }
                if (scontro) continue
                colors[r] = want
            }
        }

        for (r in order) {
            if (colors[r] >= 0) continue
            val usedWeight = IntArray(PALETTE.size)
            for ((n, w) in neighbors[r]) {
                val c = colors[n]
                if (c >= 0) usedWeight[c] += w
            }
            // Fra i colori liberi, il primo a partire da una posizione
            // ruotata: quella di IERI se si sa, altrimenti sull'hash della
            // linea, cosi' linee lontane e mai adiacenti non escono tutte
            // della prima tinta della palette.
            //
            // Il colore di ieri e' quello che rende la linea riconoscibile.
            // L'hash da solo non basta a tenerlo fermo: l'ordine con cui si
            // assegna dipende dal grado nel grafo delle sovrapposizioni, e
            // basta che una linea guadagni due fermate perche' scavalchi
            // un'altra e le porti via la tinta. Misurato sul telefono il
            // 16/09, dopo il primo bundle nuovo in giorni: alla stessa
            // fermata, quattro pastiglie su sette avevano cambiato colore.
            val hash = Ftb.hash64(routeIds[r])
            val start = preferred[hash]?.takeIf { it in PALETTE.indices }
                ?: ((hash % PALETTE.size + PALETTE.size) % PALETTE.size).toInt()
            var best = -1
            for (k in PALETTE.indices) {
                val c = (start + k) % PALETTE.size
                if (usedWeight[c] == 0) {
                    best = c
                    break
                }
            }
            if (best < 0) {
                best = 0
                for (c in PALETTE.indices) if (usedWeight[c] < usedWeight[best]) best = c
            }
            colors[r] = best
        }
        return colors
    }

    /**
     * Com'era colorata la rete l'ultima volta che si e' pubblicato.
     *
     * Due cose, non una: il colore di ogni linea, e le coppie che gia'
     * ieri si incrociavano condividendo la tinta. La seconda serve a non
     * confondere "questo conflitto c'era gia'" con "questo lo sto creando
     * adesso": due linee lontane possono avere la stessa tinta senza
     * essersi mai incrociate.
     */
    class Ieri(
        /** Per hash del `route_id`, l'indice nella [PALETTE]. */
        val colori: Map<Long, Int> = emptyMap(),
        /** Coppie di hash che ieri si incrociavano con la stessa tinta. */
        private val insieme: Set<Long> = emptySet(),
    ) {
        fun tollerata(a: Long, b: Long): Boolean = chiave(a, b) in insieme

        companion object {
            val NESSUNO = Ieri()

            /** La chiave simmetrica di una coppia, indipendente dall'ordine. */
            fun chiave(a: Long, b: Long): Long {
                val lo = if (a <= b) a else b
                val hi = if (a <= b) b else a
                // Mescolata, non sommata: due coppie diverse che finiscono
                // sulla stessa chiave si tradurrebbero in un conflitto nuovo
                // lasciato passare.
                return (lo * -0x61c8864680b583ebL) xor (hi + 0x9e3779b97f4a7c15uL.toLong())
            }
        }
    }

    /**
     * Legge com'era colorata la rete da un bundle gia' pubblicato.
     *
     * Il bundle porta il colore come 0xRRGGBB, non l'indice: si torna
     * all'indice cercandolo nella palette, e una tinta che non c'e' piu'
     * (palette cambiata) semplicemente non produce preferenza.
     *
     * Non e' un requisito: senza il file precedente si colora come si e'
     * sempre fatto. Il job notturno lo scarica se c'e', e se non c'e' — primo
     * build, release vuota, rete che non risponde — non succede niente.
     */
    fun readPrevious(file: java.io.File?): Ieri {
        if (file == null || !file.isFile) return Ieri.NESSUNO
        val byColor = HashMap<Int, Int>(PALETTE.size * 2)
        for (i in PALETTE.indices) byColor[PALETTE[i]] = i
        return runCatching {
            dev.antigravity.fluidtransit.routing.BundleReader(file).use { r ->
                val colori = HashMap<Long, Int>(r.routeCount * 2)
                for (i in 0 until r.routeCount) {
                    val idx = byColor[r.routeDisplayColor(i)] ?: continue
                    colori[r.routeIdHash(i)] = idx
                }
                // Le coppie che si incrociavano con la stessa tinta: si
                // ricavano dalle fermate del bundle, che sono li' accanto.
                val insieme = HashSet<Long>(4096)
                val viste = HashSet<Long>(16384)
                for (s in 0 until r.stopCount) {
                    val rotte = r.patternsAtStop(s)
                        .map { r.patternRoute(it) }
                        .filter { it >= 0 }
                        .distinct()
                    for (i in rotte.indices) {
                        for (j in i + 1 until rotte.size) {
                            val ha = r.routeIdHash(rotte[i])
                            val hb = r.routeIdHash(rotte[j])
                            val k = Ieri.chiave(ha, hb)
                            if (!viste.add(k)) continue
                            if (r.routeDisplayColor(rotte[i]) == r.routeDisplayColor(rotte[j])) {
                                insieme.add(k)
                            }
                        }
                    }
                }
                Ieri(colori, insieme)
            }
        }.getOrElse { Ieri.NESSUNO }
    }

    /**
     * Il bundle di ieri, se il job ce l'ha messo a disposizione.
     *
     * Una variabile d'ambiente e non un argomento: la colorazione la fanno
     * due comandi diversi — il bundle e l'overlay delle tile — e devono
     * vedere ESATTAMENTE gli stessi input, altrimenti le pastiglie e le
     * tratte sulla mappa si contraddicono. Una variabile la leggono
     * entrambi senza cambiare la firma di nessuno dei due.
     */
    fun previousFromEnv(): Ieri =
        readPrevious(System.getenv("FT_BUNDLE_PRECEDENTE")?.let { java.io.File(it) })

    /** Coppie adiacenti con lo stesso colore: la misura della promessa mantenuta. */
    fun conflicts(colors: IntArray, routesAtStop: Iterable<Collection<Int>>): Pair<Int, Int> {
        val seen = HashSet<Long>()
        var conflicts = 0
        for (s in routesAtStop) {
            val list = s.toIntArray().also { it.sort() }
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val key = (list[i].toLong() shl 32) or list[j].toLong()
                    if (seen.add(key) && colors[list[i]] == colors[list[j]]) conflicts++
                }
            }
        }
        return conflicts to seen.size
    }
}
