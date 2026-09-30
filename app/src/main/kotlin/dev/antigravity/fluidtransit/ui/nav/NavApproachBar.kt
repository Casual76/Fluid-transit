package dev.antigravity.fluidtransit.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidtransit.routing.NavApproach

/**
 * I pallini fra il mezzo e me.
 *
 * "Parte tra quattro minuti" e' una promessa; questa barra e' una cosa che
 * si puo' guardare. Il glifo a sinistra e' il bus, il cerchio a destra sei
 * tu, e in mezzo ci sono le fermate che gli mancano — che e' esattamente il
 * conto che fa chiunque aspetti un autobus guardando in fondo alla strada.
 *
 * Non si disegna quando non si sa dov'e' il mezzo: una barra con un numero
 * inventato, proprio nel momento in cui la si guarda, e' peggio di nessuna
 * barra.
 */
@Composable
fun NavApproachBar(
    /** Da dove e' il mezzo fino alla mia fermata. Vuota = non si sa: la barra non c'e'. */
    stops: List<NavApproach.Stop>,
    /** Quante fermate stanno nel buco in mezzo. */
    hidden: Int,
    colorRgb: Int,
    /**
     * Il feed sta parlando di questa corsa?
     *
     * Pieni o vuoti: e' l'unica differenza, e dice se il numero viene da un
     * mezzo che qualcuno sta guardando o da una tabella.
     */
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    if (stops.isEmpty()) return

    val tinta = if (live) {
        Color(0xFF000000 or colorRgb.toLong())
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    // I nomi e il "+N" si leggono: a 11 sp un grigio trasparente al 45% non
    // passava il contrasto minimo, proprio sul nome della fermata che si
    // sta aspettando. La trasparenza resta ai fili e ai pallini, che sono
    // disegno e non testo.
    val scritta = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.DirectionsBus,
                contentDescription = "Il tuo bus",
                tint = tinta,
                modifier = Modifier.size(18.dp),
            )
            // Le fermate che il mezzo deve ancora servire prima della mia.
            val intermedie = stops.dropLast(1)
            for ((i, _) in intermedie.withIndex()) {
                Filo(tinta)
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(tinta.copy(alpha = if (live) 0.75f else 0.35f), CircleShape),
                )
                // Le fermate nascoste stanno DOPO la prima: la prima e' quella
                // col nome scritto sotto. Il "+N" disegnato prima del primo
                // pallino diceva che mancavano fermate fra il bus e la sua
                // prossima, che per definizione non ne ha.
                if (i == 0 && hidden > 0) {
                    Filo(tinta)
                    Text(
                        text = "+$hidden",
                        style = MaterialTheme.typography.labelSmall,
                        color = scritta,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            Filo(tinta)
            // La mia fermata: un cerchio vuoto, come il traguardo di Maps.
            Box(
                modifier = Modifier
                    .size(13.dp)
                    .border(2.5.dp, tinta, CircleShape),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Solo i due estremi: a 360 dp sei nomi in fila non si leggono.
            //
            // A sinistra il NOME della prossima fermata, non il conteggio:
            // qui c'era "21 fermate" mentre due centimetri sopra il titolo
            // diceva "22 fermate" — lo stesso viaggio, due numeri diversi,
            // ed e' il tipo di contraddizione che fa smettere di credere a
            // tutto il resto.
            Text(
                text = if (stops.size > 1) stops.first().name else "",
                style = MaterialTheme.typography.labelSmall,
                color = scritta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(140.dp),
            )
            Text(
                text = stops.last().name,
                style = MaterialTheme.typography.labelSmall,
                color = scritta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(160.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

/** Il pezzo di linea fra due pallini. Prende quello che avanza. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.Filo(tinta: Color) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(2.dp)
            .padding(horizontal = 3.dp)
            .background(tinta.copy(alpha = 0.35f)),
    )
}
