package dev.antigravity.fluidtransit.data.favorites

import dev.antigravity.fluidtransit.routing.BundleReader

/**
 * Le linee che ti riguardano, che non sono solo quelle stellate.
 *
 * "Sulle tue linee" voleva dire, alla lettera, le linee con la stella. Ma la
 * stella sulle linee quasi nessuno la mette: il gesto che si fa e' stellare
 * la FERMATA sotto casa, ed e' quello che l'app insegna dappertutto. Il
 * risultato, visto il 16/09 sul telefono: una fermata stellata servita dalle
 * linee 6, 12, 36, 37 e 39, un avviso in corso che nomina proprio la 12, la
 * 36 e la 37 — e la scheda Oggi che diceva "nessun avviso sulle tue linee".
 *
 * Le tue linee sono quelle stellate PIU' quelle che passano dalle tue
 * fermate. Sono una decina di fermate al massimo e una scansione dei loro
 * pattern: si ricalcola quando cambiano le stelle o il bundle, non a ogni
 * disegno.
 */
object MyRoutes {

    /**
     * @param starredRoutes gli hash delle linee stellate.
     * @param starredStops gli indici delle fermate stellate in questo bundle.
     */
    fun hashes(
        reader: BundleReader?,
        starredRoutes: Set<Long>,
        starredStops: List<Int>,
    ): Set<Long> {
        if (reader == null || starredStops.isEmpty()) return starredRoutes
        val out = HashSet<Long>(starredRoutes)
        for (stop in starredStops) {
            if (stop < 0) continue
            for (pattern in reader.patternsAtStop(stop)) {
                val route = reader.patternRoute(pattern)
                if (route >= 0) out.add(reader.routeIdHash(route))
            }
        }
        return out
    }
}
