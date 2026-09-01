package dev.antigravity.fluidtransit.bundler

/**
 * Geometria condivisa fra overlay e bundle (POLYLINES): stessa
 * semplificazione, stessa metrica. Piano locale proiettato: a scala urbana
 * l'errore e' millimetrico e la semplicita' vale piu' della geodesia.
 */

internal fun metersApart(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val kx = 111_320.0 * Math.cos(Math.toRadians(lat1))
    val ky = 110_574.0
    val dx = (lon2 - lon1) * kx
    val dy = (lat2 - lat1) * ky
    return Math.sqrt(dx * dx + dy * dy)
}

/** Douglas-Peucker iterativo sugli indici: ritorna gli indici da tenere, in ordine. */
internal fun simplify(lat: DoubleArray, lon: DoubleArray, toleranceMeters: Double): IntArray {
    val n = lat.size
    if (n <= 2) return IntArray(n) { it }
    val keep = BooleanArray(n)
    keep[0] = true
    keep[n - 1] = true
    val stack = ArrayDeque<IntArray>()
    stack.addLast(intArrayOf(0, n - 1))
    while (stack.isNotEmpty()) {
        val (from, to) = stack.removeLast()
        if (to - from < 2) continue
        var worst = -1
        var worstDist = 0.0
        val kx = 111_320.0 * Math.cos(Math.toRadians(lat[from]))
        val ky = 110_574.0
        val ax = lon[from] * kx
        val ay = lat[from] * ky
        val bx = lon[to] * kx
        val by = lat[to] * ky
        val abx = bx - ax
        val aby = by - ay
        val ab2 = abx * abx + aby * aby
        for (i in from + 1 until to) {
            val px = lon[i] * kx
            val py = lat[i] * ky
            val t = if (ab2 == 0.0) 0.0 else ((px - ax) * abx + (py - ay) * aby) / ab2
            val tc = t.coerceIn(0.0, 1.0)
            val dx = px - (ax + abx * tc)
            val dy = py - (ay + aby * tc)
            val d = Math.sqrt(dx * dx + dy * dy)
            if (d > worstDist) {
                worstDist = d
                worst = i
            }
        }
        if (worstDist > toleranceMeters && worst > 0) {
            keep[worst] = true
            stack.addLast(intArrayOf(from, worst))
            stack.addLast(intArrayOf(worst, to))
        }
    }
    val out = ArrayList<Int>(n / 4)
    for (i in 0 until n) if (keep[i]) out.add(i)
    return out.toIntArray()
}
