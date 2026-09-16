package dev.antigravity.fluidtransit.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidtransit.routing.DepartureText
import dev.antigravity.fluidtransit.routing.NextDeparture
import dev.antigravity.fluidtransit.ui.map.LiveDot
import dev.antigravity.fluidtransit.ui.map.RoutePill
import dev.antigravity.fluidtransit.ui.map.liveGreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * La riga di una partenza. Una sola, per tutta l'app.
 *
 * Il verde e il pallino che pulsa erano una convenzione che valeva soltanto
 * dentro i pannelli della mappa: la scheda Oggi scriveva "dal bus" a parole
 * senza colore, i Preferiti concatenavano tutto su una riga, il widget
 * mostrava i minuti nudi. Stessa fermata, quattro grammatiche.
 *
 * Il tono lo decide [DepartureText], che sta in `:core-routing` e non puo'
 * vedere Compose; qui si traduce in colore, in un posto solo.
 */
@Composable
fun DepartureRowUi(
    row: NextDeparture,
    nowEpoch: Long,
    modifier: Modifier = Modifier,
    /** Il tocco sulla pillola della linea, dove ha senso andarci. */
    onLineTap: (() -> Unit)? = null,
    /**
     * Da che fermata parte: serve quando la lista ne mescola piu' di una.
     *
     * Null quando la fermata e' gia' il titolo della scheda. Chi lo passa
     * puo' aggiungerci la distanza — "XXVII APRILE SAN ZANOBI - a 250 m" —
     * che e' l'informazione che manca per decidere se conviene camminare
     * fin li' o aspettare il prossimo qui.
     */
    stopLabel: String? = null,
    /** Un'azione a destra del testo, per esempio "vola sul bus". */
    trailing: @Composable (() -> Unit)? = null,
    /**
     * Il tocco sulla riga della provenienza: "perche' questo numero".
     *
     * Riceve il rettangolo di quelle parole, cosi' la spiegazione nasce da
     * li' e non dal centro dello schermo. Quando c'e', il testo si sottolinea:
     * un tocco che non si annuncia non lo trova nessuno, e una sottolineatura
     * e' il modo in cui da sempre si dice "questo si puo' aprire".
     */
    onSupportTap: ((androidx.compose.ui.geometry.Rect?) -> Unit)? = null,
) {
    val phrase = DepartureText.phrase(row, nowEpoch)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val pill = Modifier.let {
            if (onLineTap == null) {
                it
            } else {
                it.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "Mostra la linea ${row.line}",
                    onClick = onLineTap,
                )
            }
        }
        RoutePill(text = row.line, colorRgb = row.colorRgb, modifier = pill)

        // La provenienza sta SOTTO la destinazione, non sotto il numero.
        //
        // Messa a destra costringeva la colonna dei minuti a essere larga
        // quanto "dal bus - da tabella alle 20:03", e la destinazione finiva
        // troncata a "LA G..." proprio sulle righe che avevano qualcosa in
        // piu' da dire. Il numero a destra resta corto, la spiegazione ha
        // tutta la riga.
        Column(modifier = Modifier.weight(1f)) {
            if (!stopLabel.isNullOrEmpty()) {
                Text(
                    text = stopLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = row.destination,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            var supportBounds by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
            }
            Text(
                text = phrase.support,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = if (onSupportTap != null) {
                    androidx.compose.ui.text.style.TextDecoration.Underline
                } else {
                    null
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = if (onSupportTap == null) {
                    Modifier
                } else {
                    Modifier
                        .onGloballyPositioned { supportBounds = it.boundsInRoot() }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClickLabel = "Perche' questo numero",
                            onClick = { onSupportTap(supportBounds) },
                        )
                },
            )
        }

        trailing?.invoke()

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // Il pallino pulsa solo dove il feed sta guardando adesso.
            if (phrase.pulse) LiveDot(toneColor(phrase.tone))
            Text(
                text = phrase.headline,
                style = MaterialTheme.typography.titleSmall,
                color = toneColor(phrase.tone),
            )
        }
    }
}

/**
 * Il tono in colore. E' l'unico posto in Compose dove si fa questa scelta.
 *
 * `ESTIMATED` non e' verde: il verde vuol dire "lo dice il mezzo", e una
 * stima nostra non lo dice. Prima erano dello stesso colore e la differenza
 * stava solo nel pallino, che meta' delle schermate non disegnavano.
 */
@Composable
fun toneColor(tone: DepartureText.Tone): Color = when (tone) {
    DepartureText.Tone.LIVE -> liveGreen()
    DepartureText.Tone.ESTIMATED -> MaterialTheme.colorScheme.onSurfaceVariant
    DepartureText.Tone.SCHEDULED -> MaterialTheme.colorScheme.onSurface
    DepartureText.Tone.CANCELED -> MaterialTheme.colorScheme.error
}
