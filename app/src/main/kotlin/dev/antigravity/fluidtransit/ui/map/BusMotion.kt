package dev.antigravity.fluidtransit.ui.map

import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.PathIndex
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Le geometrie dei pattern che servono adesso, tenute in memoria.
 *
 * La decodifica e' pigra e asincrona: chi chiede [get] su un pattern nuovo
 * riceve null e intanto la costruzione parte in sottofondo, cosi' il mezzo
 * continua col ripiego in linea retta e passa alla strada appena pronta —
 * nessun singhiozzo al primo caricamento, e il main thread non vede mai i
 * varint (qualche migliaio per pattern).
 *
 * Il conto della memoria: dopo la semplificazione un pattern sta attorno al
 * centinaio di vertici, cioe' qualche kilobyte. Anche con tutti i mezzi
 * vivi della Toscana in scena si resta sotto i due megabyte, quindi non c'e'
 * niente da sfrattare.
 */
class PathCache(
    private val reader: BundleReader,
    private val scope: CoroutineScope,
) {
    private val ready = ConcurrentHashMap<Int, PathIndex>()

    /** I pattern per cui la geometria non esiste: non ci si riprova a ogni tick. */
    private val absent = ConcurrentHashMap.newKeySet<Int>()
    private val building = ConcurrentHashMap.newKeySet<Int>()

    val size: Int get() = ready.size

    fun get(pattern: Int): PathIndex? {
        if (pattern < 0 || !reader.hasPolylines) return null
        ready[pattern]?.let { return it }
        if (pattern in absent) return null
        if (!building.add(pattern)) return null
        scope.launch(Dispatchers.Default) {
            val built = runCatching { PathIndex.build(reader, pattern) }.getOrNull()
            if (built != null) ready[pattern] = built else absent.add(pattern)
            building.remove(pattern)
        }
        return null
    }
}
