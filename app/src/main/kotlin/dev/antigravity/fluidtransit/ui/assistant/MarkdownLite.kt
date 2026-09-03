package dev.antigravity.fluidtransit.ui.assistant

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Il markdown che serve davvero: **grassetto** ed elenchi con "-".
 *
 * Non una libreria: il prompt chiede al modello di limitarsi a questi due, e
 * tutto il resto sarebbe superficie da mantenere per niente. Se un giorno
 * arrivasse un titolo, si vedrebbe il cancelletto — e sarebbe un difetto del
 * prompt da correggere, non da nascondere qui.
 */
@Composable
fun MarkdownLite(text: String) {
    for (raw in text.lines()) {
        val line = raw.trimEnd()
        if (line.isBlank()) continue
        val bullet = line.trimStart().startsWith("- ")
        val body = if (bullet) line.trimStart().removePrefix("- ") else line
        Text(
            text = bold(body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = if (bullet) Modifier.padding(start = 12.dp) else Modifier,
        )
    }
}

/** Da `**cosi'**` al grassetto, senza toccare il resto. */
private fun bold(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val open = text.indexOf("**", i)
        if (open < 0) {
            append(text.substring(i))
            break
        }
        val close = text.indexOf("**", open + 2)
        if (close < 0) {
            append(text.substring(i))
            break
        }
        append(text.substring(i, open))
        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        append(text.substring(open + 2, close))
        pop()
        i = close + 2
    }
}
