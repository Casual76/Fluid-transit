package dev.antigravity.fluidtransit.ui.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidtransit.data.nav.NavLeg
import dev.antigravity.fluidtransit.data.nav.NavPlan
import dev.antigravity.fluidtransit.data.nav.NavState
import dev.antigravity.fluidtransit.routing.Times
import dev.antigravity.fluidtransit.routing.Words
import dev.antigravity.fluidtransit.ui.map.RoutePill

/**
 * Il viaggio intero, passo per passo, con la tappa in corso in evidenza e
 * quelle passate spente.
 *
 * E' la stessa spina colorata del dettaglio dell'itinerario: chi ha scelto
 * il viaggio su quella carta deve ritrovare la stessa forma qui dentro,
 * altrimenti "Parti" sembra portare da un'altra parte.
 */
@Composable
fun NavSteps(
    plan: NavPlan,
    state: NavState,
    colorOf: (route: Int) -> Int,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(plan.legs.size + 1) { i ->
            if (i == plan.legs.size) {
                Traguardo(plan.destName, state.phase == "arrived")
                return@items
            }
            val leg = plan.legs[i]
            val neutro = MaterialTheme.colorScheme.onSurfaceVariant
            // Una tappa percorsa che negli orari nuovi non si ritrova non ha
            // piu' una linea nel bundle: il suo indice e' -1, e chiedergli il
            // colore voleva dire leggere fuori dalla tabella.
            val coloreLinea = if (leg is NavLeg.Ride && !leg.orphaned) colorOf(leg.route) else neutro.toArgb()
            val tinta = when (leg) {
                is NavLeg.Ride -> Color(0xFF000000 or (coloreLinea and 0xFFFFFF).toLong())
                else -> neutro
            }
            // Le tappe gia' fatte restano: servono a capire dove si e'
            // arrivati. Ma non competono con quella in corso.
            // A viaggio finito sono passate tutte: lo stato "arrivato" non ha
            // una tappa, e senza questo l'elenco le riproponeva da fare.
            val passata = state.phase == "arrived" ||
                (state.legIndex >= 0 && i < state.legIndex)
            val corrente = i == state.legIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Spina(tinta.copy(alpha = if (passata) 0.2f else 0.45f), prima = i == 0)
                when (leg) {
                    is NavLeg.Walk -> Riga(
                        // Le parole della camminata sono quelle della card e
                        // degli itinerari: "4 min a piedi", "meno di un minuto".
                        titolo = Times.durationOrUnderMinute(leg.seconds)
                            .replaceFirstChar { it.uppercase() } + " a piedi fino a ${leg.toName}",
                        sotto = null,
                        passata = passata,
                        corrente = corrente,
                        icona = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.DirectionsWalk,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                    )

                    is NavLeg.Ride -> Riga(
                        titolo = "Sali a ${leg.stopNames.firstOrNull() ?: ""}",
                        sotto = "Scendi a ${leg.alightName} · " +
                            Words.count(
                                leg.alightPosition - leg.boardPosition,
                                "fermata",
                                "fermate",
                            ),
                        passata = passata,
                        corrente = corrente,
                        icona = {
                            RoutePill(leg.lineName, coloreLinea)
                        },
                    )
                }
            }
        }
    }
}

/** La barra che corre a sinistra e tiene insieme le tappe. */
@Composable
private fun Spina(tinta: Color, prima: Boolean) {
    Box(
        modifier = Modifier
            .width(10.dp)
            .fillMaxHeight()
            .drawBehind {
                val x = size.width / 2f
                val spessore = 6.dp.toPx()
                drawLine(
                    color = tinta,
                    start = Offset(x, if (prima) spessore / 2f else 0f),
                    end = Offset(x, size.height),
                    strokeWidth = spessore,
                    cap = StrokeCap.Round,
                )
            },
    )
}

@Composable
private fun Riga(
    titolo: String,
    sotto: String?,
    passata: Boolean,
    corrente: Boolean,
    icona: @Composable () -> Unit,
) {
    val alpha = if (passata) 0.45f else 1f
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icona()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = titolo,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (corrente) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (sotto != null) {
                Text(
                    text = sotto,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    // Due righe: "Scendi a OSPEDALE TORRE GALLI · 18 fe..."
                    // taglia via proprio il numero di fermate, che e' la
                    // meta' dell'informazione.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Dove si va. In fondo all'elenco perche' e' la fine del viaggio. */
@Composable
private fun Traguardo(destName: String, arrivato: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Flag,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = destName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (arrivato) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
