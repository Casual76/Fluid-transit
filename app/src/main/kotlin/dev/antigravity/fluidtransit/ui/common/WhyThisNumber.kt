package dev.antigravity.fluidtransit.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
) {
    val why = row?.let { DepartureText.why(it, nowEpoch) }
    FluidGlassModalPortal(
        visible = row != null,
        onDismissRequest = onDismiss,
        origin = origin,
        presentation = FluidGlassModalPresentation.Popover,
        paneTitle = why?.title,
    ) {
        if (why == null) return@FluidGlassModalPortal
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
