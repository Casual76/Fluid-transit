package dev.antigravity.fluidtransit.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidtransit.routing.Words

/**
 * Gli avvisi in corso, in cima a una scheda.
 *
 * Un avviso di servizio e' l'unica cosa che puo' rendere sbagliato tutto il
 * resto di un pannello — gli orari, le fermate, i minuti — quindi sta sopra,
 * non in fondo. Due righe al massimo: tre avvisi in cima a una scheda
 * spingono i numeri sotto il bordo, e i numeri sono il motivo per cui la
 * scheda si e' aperta. Il resto si conta e si apre.
 *
 * Sta qui e non nei due pannelli perche' erano gia' due copie: la scheda
 * della linea e quella della fermata mostrano la stessa cosa, e due copie
 * della stessa cosa e' come sono nate meta' delle incoerenze di quest'app.
 */
@Composable
fun AlertRows(
    alerts: List<String>,
    onOpenAlerts: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /**
     * Di cosa parlano gli avvisi che non ci stanno.
     *
     * Su una fermata sono di tutte le linee che ci passano; sulla scheda di
     * una linea o di una corsa sono di quella sola, e "su queste linee"
     * sarebbe una riga che promette piu' di quello che apre.
     */
    tail: String = "su queste linee",
) {
    if (alerts.isEmpty()) return
    for (a in alerts.take(MAX_ROWS)) {
        AlertRow(a, onOpenAlerts, modifier)
    }
    val resto = alerts.size - MAX_ROWS
    if (resto > 0) {
        AlertRow(
            "Altri " + Words.count(resto, "avviso", "avvisi") + " " + tail,
            onOpenAlerts,
            modifier,
        )
    }
}

private const val MAX_ROWS = 2

@Composable
private fun AlertRow(text: String, onOpenAlerts: (() -> Unit)?, modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { m -> if (onOpenAlerts != null) m.clickable { onOpenAlerts() } else m }
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
