package dev.antigravity.fluidtransit.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import dev.antigravity.fluidtransit.data.nav.NavFocus
import dev.antigravity.fluidtransit.routing.PathIndex
import dev.antigravity.fluidtransit.ui.map.MapLine
import dev.antigravity.fluidtransit.ui.map.NavMapFocus
import dev.antigravity.fluidtransit.ui.map.NavTrail
import dev.antigravity.fluidtransit.ui.map.TransitMapController
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Il collante fra il viaggio in corso e la mappa. Non disegna niente di
 * suo: accende la modalita' navigazione, tiene aggiornato il taglio della
 * tratta e la spegne quando il viaggio finisce.
 *
 * Sta in un file suo e non dentro `MapScreen` per la ragione scritta in
 * APERTO: quella schermata e' gia' a duemilacinquecento righe, e si e'
 * deciso di non farla crescere.
 */
@Composable
fun NavMapBinding(
    controller: TransitMapController,
    focus: NavFocus?,
    phase: String?,
    myVehKey: Int?,
    /** La tappa a fuoco e' gia' fatta: l'ultima camminata, o l'arrivo. */
    finished: Boolean = false,
) {
    // A bordo le altre linee non servono piu': si e' gia' scelto.
    val showUseful = mostraUtili(phase, finished)

    LaunchedEffect(focus, showUseful) {
        val f = focus
        if (f == null) {
            controller.exitNavMode()
            return@LaunchedEffect
        }
        controller.enterNavMode(navMapFocus(f, phase, finished))
    }

    // Il taglio della scia. Un giro al secondo: l'occhio segue il marker del
    // bus, non l'estremo della coda, e rifarlo agli otto fotogrammi del
    // glide sarebbe lavoro che nessuno vede.
    val ultima = remember(focus) { doubleArrayOf(-1.0) }
    LaunchedEffect(focus, myVehKey) {
        val f = focus
        if (f == null) {
            controller.setNavTrail(null)
            return@LaunchedEffect
        }
        // Il taglio riparte da capo a ogni mezzo nuovo. Il ricordo stava
        // legato al solo fuoco: se il feed perdeva il mezzo e poi lo
        // ritrovava, il primo giro trovava lo spostamento sotto i quindici
        // metri e la scia restava intera fino al primo tratto di strada.
        ultima[0] = -1.0
        val path = f.path
        if (path == null) {
            // Bundle senza polilinee: le fermate in fila, senza taglio. E'
            // brutto e onesto — il contrario di una scia inventata.
            controller.setNavTrail(
                NavTrail(
                    remaining = MapLine(f.fallbackLat, f.fallbackLon, f.colorRgb),
                    done = null,
                    approach = null,
                ),
            )
            return@LaunchedEffect
        }
        // Il primo disegno non aspetta il secondo: la tratta intera esce
        // subito, il taglio arriva quando si sa dov'e' il mezzo.
        controller.setNavTrail(
            NavTrail(path.slice(f.sBoard, f.sAlight)?.linea(f.colorRgb), null, null),
        )
        if (myVehKey == null) return@LaunchedEffect

        while (true) {
            val pos = controller.busPosition(myVehKey)
            if (pos != null) {
                val s = path.project(pos[0], pos[1], hint = ultima[0])
                if (ultima[0] < 0 || abs(s - ultima[0]) >= SPOSTAMENTO_M) {
                    ultima[0] = s
                    controller.setNavTrail(taglia(path, f, s))
                }
            }
            delay(1000)
        }
    }
}

/**
 * Le altre linee si guardano mentre si aspetta, non mentre si e' a bordo.
 *
 * Una volta saliti la scelta e' fatta: tenere in scena la linea che sarebbe
 * andata bene lo stesso toglie spazio all'unica cosa che conta, cioe' dove
 * scendere.
 */
internal fun mostraUtili(phase: String?, finished: Boolean = false): Boolean =
    // Nell'ultima camminata, e all'arrivo, non si aspetta piu' nessun bus:
    // le linee che "andrebbero bene lo stesso" tornavano accese come se si
    // fosse ancora alla fermata.
    phase != "ride" && phase != "arrived" && !finished

/**
 * Da fuoco della navigazione a "cosa accendere sulla mappa".
 *
 * Sta fuori dal composable per poterlo interrogare: e' una traduzione, e le
 * traduzioni si sbagliano in silenzio.
 */
internal fun navMapFocus(f: NavFocus, phase: String?, finished: Boolean = false) = NavMapFocus(
    routeHashHex = f.routeHashHex,
    stopHashes = f.stopHashes,
    usefulRouteHashes = f.usefulRouteHashes,
    showUseful = mostraUtili(phase, finished),
    colorRgb = f.colorRgb,
)

/**
 * Sotto questo spostamento non si ridisegna: quindici metri sono meno di un
 * tratto di dash, e la scia non cambia forma.
 */
private const val SPOSTAMENTO_M = 15.0

/**
 * I tre pezzi, dall'ascissa del mezzo: quello che ha fatto, quello che gli
 * resta, e — se deve ancora arrivare da me — quanto gli manca.
 */
internal fun taglia(path: PathIndex, f: NavFocus, sBus: Double): NavTrail {
    val fatto = if (sBus > f.sBoard) {
        path.slice(f.sBoard, minOf(sBus, f.sAlight))?.linea(GRIGIO)
    } else {
        null
    }
    val resta = path.slice(maxOf(sBus, f.sBoard), f.sAlight)?.linea(f.colorRgb)
    // Il mezzo non e' ancora alla mia fermata: il tratto fra lui e me e'
    // strada che fara' senza di me, e si disegna tratteggiata.
    val avvicinamento = if (sBus < f.sBoard) {
        path.slice(sBus, f.sBoard)?.linea(f.colorRgb)
    } else {
        null
    }
    return NavTrail(remaining = resta, done = fatto, approach = avvicinamento)
}

/** Il grigio di quello che e' gia' passato. Il colore lo mette il layer. */
private const val GRIGIO = 0x9A9AA4

private fun PathIndex.Slice.linea(colorRgb: Int) = MapLine(lat, lon, colorRgb)
