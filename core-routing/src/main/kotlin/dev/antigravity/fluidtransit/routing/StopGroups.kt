package dev.antigravity.fluidtransit.routing

/**
 * Le banchine della stessa fermata, riunite.
 *
 * Cercando "TORRE GALLI" sul telefono escono due righe identiche — stesso
 * nome, stesso sottotitolo "Fermata" — e non c'e' modo di sapere quale sia
 * quale. Sono i due sensi di marcia della stessa fermata, e l'unica strada per
 * scegliere e' provarne una e vedere se i bus vanno dove servono.
 *
 * ## Perche' si deduce invece di leggerlo
 *
 * GTFS ha `parent_station` apposta. Misurato sul feed del 15/09/2026:
 * **zero fermate su 33.930 lo dichiarano**. Non c'e' niente da leggere.
 *
 * Quello che c'e' sono i nomi: 8.012 nomi ripetuti su 18.152 fermate, cioe'
 * il 53% della rete. E la distanza li separa in due mondi con una nettezza
 * che rende la regola facile: **l'87% dei gruppi omonimi sta entro cento
 * metri** — sono le banchine — e il resto sta a chilometri: "SAN MARTINO"
 * quindici volte in centosettantacinque chilometri sono quindici paesi
 * diversi, e riunirli sarebbe un danno, non un servizio.
 *
 * Quindi: stesso nome **e** vicine. Il tetto sul diametro del gruppo evita che
 * una fila di fermate omonime lungo una statale si incolli tutta insieme
 * saltando di vicino in vicino.
 */
class StopGroups private constructor(
    private val groupOf: IntArray,
    private val members: Array<IntArray>,
) {
    val size: Int get() = members.size

    /** Il gruppo di una fermata. Sempre almeno lei stessa. */
    fun groupOf(stop: Int): Int = groupOf.getOrElse(stop) { -1 }

    /** Le fermate di un gruppo, in ordine di indice. */
    fun members(group: Int): IntArray =
        if (group in members.indices) members[group] else IntArray(0)

    /** Le compagne di banchina di una fermata, lei compresa. */
    fun siblings(stop: Int): IntArray = members(groupOf(stop))

    companion object {

        /**
         * Quanto possono distare due banchine della stessa fermata.
         *
         * Cento metri coprono l'87% dei gruppi omonimi; centocinquanta il 90%
         * e qualcosa, senza avvicinarsi ai casi da chilometri. Si sta larghi
         * perche' due banchine ai lati opposti di un incrocio, o le due
         * direzioni di un viale con l'aiuola in mezzo, arrivano a quella
         * distanza senza essere due fermate diverse.
         */
        const val NEAR_METERS = 150.0

        /**
         * Quanto puo' essere largo un gruppo intero.
         *
         * Senza questo tetto, una fila di fermate omonime lungo una statale si
         * incollerebbe tutta insieme saltando di vicino in vicino: A sta a
         * cento metri da B, B da C, e alla fine "Strada Statale 324" diventa
         * un'unica fermata lunga dieci chilometri.
         */
        const val MAX_SPREAD_METERS = 300.0

        fun build(reader: BundleReader): StopGroups = build(
            count = reader.stopCount,
            name = reader::stopName,
            lat = reader::stopLat,
            lon = reader::stopLon,
        )

        /** La porta d'ingresso dei test, che non hanno un bundle sotto. */
        fun build(
            count: Int,
            name: (Int) -> String,
            lat: (Int) -> Double,
            lon: (Int) -> Double,
        ): StopGroups {
            val byName = HashMap<String, MutableList<Int>>()
            for (i in 0 until count) {
                byName.getOrPut(Relevance.normalize(name(i))) { ArrayList() }.add(i)
            }

            val groupOf = IntArray(count) { -1 }
            val members = ArrayList<IntArray>(count)

            for (stops in byName.values) {
                if (stops.size == 1) {
                    groupOf[stops[0]] = members.size
                    members.add(intArrayOf(stops[0]))
                    continue
                }
                // Gruppi piccoli — mediana due, massimo venticinque — quindi
                // il confronto a coppie costa meno di qualunque indice.
                for (stop in stops) {
                    if (groupOf[stop] >= 0) continue
                    val group = members.size
                    val cluster = ArrayList<Int>(4)
                    cluster.add(stop)
                    groupOf[stop] = group
                    var grew = true
                    while (grew) {
                        grew = false
                        for (other in stops) {
                            if (groupOf[other] >= 0) continue
                            val nearOne = cluster.any { m ->
                                BundleReader.haversine(
                                    lat(m), lon(m), lat(other), lon(other),
                                ) <= NEAR_METERS
                            }
                            if (!nearOne) continue
                            // Il tetto sul diametro: si entra solo se il gruppo
                            // resta compatto anche col nuovo dentro.
                            val fits = cluster.all { m ->
                                BundleReader.haversine(
                                    lat(m), lon(m), lat(other), lon(other),
                                ) <= MAX_SPREAD_METERS
                            }
                            if (!fits) continue
                            cluster.add(other)
                            groupOf[other] = group
                            grew = true
                        }
                    }
                    members.add(cluster.toIntArray().also { it.sort() })
                }
            }
            return StopGroups(groupOf, members.toTypedArray())
        }
    }
}
