package dev.antigravity.fluidtransit.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidtransit.routing.DepartureText
import dev.antigravity.fluidtransit.routing.NextDeparture

/**
 * Perche' questo numero.
 *
 * La riga di un tabellone dice "dal bus" o "stimato" in due parole, che e'
 * quanto ci sta. Ma "stimato" e' una promessa su come lavora l'app, e una
 * promessa che non si puo' aprire e' una cosa da prendere sulla fiducia —
 * cioe' esattamente quello che qui manca. Da qualche parte deve esserci
 * scritto per esteso cosa ha detto il feed, per quale fermata, e cosa ci
 * abbiamo aggiunto noi.
 *
 * Si apre SUL numero che si e' toccato, non al centro dello schermo: un
 * pop-up che nasce altrove ha gia' perso il legame col tocco che l'ha
 * aperto, e niente glielo ridà.
 */
@Composable
fun WhyThisNumberPortal(
    row: NextDeparture?,
    nowEpoch: Long,
    origin: () -> Rect?,
    onDismiss: () -> Unit,
    onOpenDataStatus: (() -> Unit)? = null,
    /**
     * Quanto del tempo reale e' in strada adesso. Si chiede solo per una riga
     * senza dati dal vivo — e' quella la domanda a cui risponde — e fuori
     * dalla UI, perche' scorre tutte le corse del giorno.
     */
    coverage: (suspend () -> dev.antigravity.fluidtransit.routing.Coverage.Stato?)? = null,
    /** Com'e' il collegamento adesso: vedi [DepartureText.LiveLink]. */
    link: DepartureText.LiveLink = DepartureText.LiveLink.FULL,
) {
    val why = row?.let { DepartureText.why(it, nowEpoch, link) }
    val copertura by androidx.compose.runtime.produceState<String?>(null, row) {
        value = null
        val r = row ?: return@produceState
        // La copertura parla del feed: senza collegamento pieno direbbe "la
        // Regione non pubblica niente" di un buco che e' nostro.
        if (coverage == null || r.certainty != null || r.canceled || r.skipped) return@produceState
        if (link != DepartureText.LiveLink.FULL) return@produceState
        value = runCatching { coverage() }.getOrNull()?.sentence()
    }
    // Una riga nuova si apre dall'inizio, non da dove era arrivata la
    // lettura dell'ultima.
    val scorrimento = remember(row) { ScrollState(0) }
    FluidGlassModalPortal(
        visible = row != null,
        onDismissRequest = onDismiss,
        origin = origin,
        presentation = FluidGlassModalPresentation.Popover,
        paneTitle = why?.title,
    ) {
        if (why == null) return@FluidGlassModalPortal
        // Il testo scorre, il collegamento in fondo no.
        //
        // Il pop-up non scorre da se': viene misurato al 78% dell'altezza
        // della finestra meno i margini e ritagliato a quella misura. A
        // scala 1,0 su un 360x640 sono circa 470 dp e il testo ne occupa
        // 260-330, ma a caratteri grandi (circa 480 dp a 1,5 e oltre 700 a
        // 2,0) o con il telefono in orizzontale (circa 250 dp) le ultime
        // righe finivano tagliate a meta' e nessun gesto le rivelava. Erano
        // proprio quelle che dicono da dove viene il numero — "lo stimiamo
        // noi", quanta parte dei bus e' seguita — e "Vedi lo stato dei dati".
        //
        // `weight(fill = false)` fa prendere al blocco solo lo spazio che gli
        // serve, fino a quello che resta dopo aver misurato il collegamento:
        // il limite e' l'altezza stessa del pop-up, senza un numero di dp
        // scritto a mano. Il collegamento sta fuori dallo scorrimento perche'
        // e' l'unica azione, e non deve poter uscire dallo schermo.
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(scorrimento),
        ) {
            Row(
                modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.Start,
            ) {
                Text(
                    text = "${row.line} → ${row.destination}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = why.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 18.dp),
            )
            Spacer(Modifier.height(10.dp))
            for (line in why.lines) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 18.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            copertura?.let { frase ->
                Text(
                    text = frase,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 18.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        if (onOpenDataStatus != null) {
            Text(
                text = "Vedi lo stato dei dati",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 18.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = {
                            onDismiss()
                            onOpenDataStatus()
                        },
                    )
                    .padding(vertical = 4.dp),
            )
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.width(1.dp))
    }
}
