package dev.antigravity.fluidtransit.ui.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidTabBarDefaults
import dev.antigravity.fluidtransit.data.nav.NavFocus
import dev.antigravity.fluidtransit.data.nav.NavPlan
import dev.antigravity.fluidtransit.data.nav.NavState
import dev.antigravity.fluidtransit.routing.DepartureText
import dev.antigravity.fluidtransit.ui.map.LiveDot
import dev.antigravity.fluidtransit.ui.map.RoutePill
import dev.antigravity.fluidtransit.ui.map.liveGreen
import dev.antigravity.fluidtransit.ui.map.panelListMax

/**
 * La card del viaggio in corso: una riga quando si vuole guardare la mappa,
 * il viaggio intero quando si vuole capire.
 *
 * Prima qui c'era una riga sola, e basta: "Scendi a TORRE GALLI · 4
 * fermate". Tutto il resto — quante ne mancano, dov'e' il bus, cosa viene
 * dopo il cambio — era calcolato dal servizio e non lo leggeva nessuno.
 */
@Composable
fun NavCard(
    state: NavState,
    plan: NavPlan?,
    focus: NavFocus?,
    esteso: Boolean,
    colorOf: (route: Int) -> Int,
    onToggle: () -> Unit,
    onStop: () -> Unit,
) {
    if (!esteso) {
        NavMini(state, onToggle, onStop)
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, bottom = 14.dp),
    ) {
        Testata(state, onToggle, onStop)

        // La barra di avvicinamento: mentre si aspetta dice dov'e' il bus,
        // a bordo dice quanto manca alla discesa. E' la stessa barra girata,
        // perche' e' la stessa domanda dall'altro lato.
        if (state.approach.isNotEmpty()) {
            NavApproachBar(
                stops = state.approach,
                hidden = state.approachHidden,
                colorRgb = state.lineColorRgb,
                // La stessa risposta della riga qui sotto: pallini pieni solo
                // quando le parole dicono "dal bus". Prima SERVED riempiva i
                // pallini mentre la didascalia diceva "stimato".
                live = DepartureText.fromFeed(state.busCertainty),
                modifier = Modifier.padding(top = 12.dp, end = 12.dp),
            )
            // Da dove viene il numero, con le parole del tabellone: se non
            // e' il feed a parlare, la barra non deve sembrare live.
            DepartureText.source(state.busCertainty)?.let { fonte ->
                Text(
                    text = fonte,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // A bordo, quanto del viaggio e' andato. Non e' un dato in piu': e'
        // la sola cosa che dice "manca poco" senza far contare le fermate.
        // Sul viaggio intero, non sulla tappa: con un cambio la barra non
        // riparte da zero a meta' strada.
        if (state.phase == "ride" && state.journeyStops > 0) {
            FluidProgressBar(
                progress = { state.journeyDone.toFloat() / state.journeyStops },
                modifier = Modifier.padding(top = 12.dp, end = 12.dp),
            )
        }

        Alternative(state, focus)

        if (plan != null && plan.legs.size > 1) {
            Spacer(modifier = Modifier.height(10.dp))
            NavSteps(
                plan = plan,
                state = state,
                colorOf = colorOf,
                modifier = Modifier
                    .heightIn(max = panelListMax(260.dp, reserve = AROUND_THE_CARD))
                    .padding(end = 12.dp),
            )
        }
    }
}

/**
 * Quanto si mette da parte per cio' che non e' l'elenco delle tappe:
 * testata, barra di avvicinamento, avanzamento, alternative e i margini del
 * vetro. Misurata sulla fase di attesa, che e' la piu' carica.
 */
private val AROUND_THE_CARD = 320.dp

/**
 * La riga sotto il titolo, al minuto giusto.
 *
 * Il servizio la scrive a ogni giro — ogni 15-60 secondi a seconda del modo
 * viaggio — e "parte tra 4 min" restava li' fino al giro dopo. Qui si
 * riscrive col battito comune dell'app: la stessa regola, un orologio solo.
 */
@Composable
private fun dettaglioAdesso(state: NavState): String {
    val formula = state.detailAt ?: return state.detail
    val battito = remember { dev.antigravity.fluidtransit.data.time.UiClock.ticks() }
    val adesso by battito.collectAsStateWithLifecycle(
        initialValue = System.currentTimeMillis() / 1000,
    )
    return formula(adesso)
}

@Composable
private fun Testata(state: NavState, onToggle: () -> Unit, onStop: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.lineName.isNotEmpty()) {
            RoutePill(state.lineName, state.lineColorRgb)
        } else if (state.phase == "ride") {
            LiveDot(liveGreen())
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "Riduci la navigazione",
                    onClick = onToggle,
                ),
        ) {
            Text(
                text = state.headline,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Il colore dice la puntualita', non la fase: il verde "perche' si e'
            // a bordo" si leggeva come "in orario" anche su un bus in ritardo.
            Text(
                text = dettaglioAdesso(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Chiudi(onStop)
    }
}

/**
 * Le linee che vanno bene lo stesso.
 *
 * Si dicono mentre si aspetta e non a bordo: una volta saliti sono rumore.
 * E quando la corsa e' cancellata non sono un di piu' — sono l'unica cosa
 * che resta da dire.
 */
@Composable
private fun Alternative(state: NavState, focus: NavFocus?) {
    if (state.phase != "wait") return
    val utili = focus?.useful?.take(3).orEmpty()
    if (utili.isEmpty()) return
    val cancellata = state.canceled
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (cancellata) "Passa anche" else "Va bene anche",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (u in utili) RoutePill(u.lineName, u.colorRgb)
    }
}

/** La riga sola: prende il posto della tab bar e lascia guardare la mappa. */
@Composable
private fun NavMini(state: NavState, onToggle: () -> Unit, onStop: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(FluidTabBarDefaults.Height)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.lineName.isNotEmpty()) {
            RoutePill(state.lineName, state.lineColorRgb)
        } else if (state.phase == "ride") {
            LiveDot(liveGreen())
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "Apri la navigazione",
                    onClick = onToggle,
                ),
        ) {
            Text(
                text = state.headline,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = dettaglioAdesso(state),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                // Senza i puntini il nome si taglia e basta: "fino a PISANA"
                // per PISANA MONTICELLI si legge come un'altra fermata, non
                // come un nome accorciato.
                overflow = TextOverflow.Ellipsis,
            )
        }
        Chiudi(onStop)
    }
}

/**
 * Termina il viaggio.
 *
 * E' l'UNICO modo di finire una navigazione: il trascinamento verso il
 * basso riduce e basta. Una trascinata distratta che ferma il viaggio
 * mentre si e' sul bus e' il difetto peggiore che questa schermata possa
 * avere.
 */
@Composable
private fun Chiudi(onStop: () -> Unit) {
    Icon(
        imageVector = Icons.Rounded.Close,
        contentDescription = "Termina la navigazione",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .size(40.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onStop,
            )
            .padding(8.dp),
    )
}
