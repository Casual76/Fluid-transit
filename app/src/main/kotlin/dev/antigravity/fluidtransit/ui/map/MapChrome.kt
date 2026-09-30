package dev.antigravity.fluidtransit.ui.map

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidGlassIconButton
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassEdge
import dev.antigravity.fluidengine.ui.fluid.glassSurface

// La barra di ricerca vive in SearchGlass.kt: e' la stessa superficie che si
// estende nel pannello, non un componente separato.

@Composable
private fun remember2() = androidx.compose.runtime.remember { MutableInteractionSource() }

private class ChipSpec(
    val filter: CategoryFilter,
    val label: String,
    val icon: ImageVector,
    /**
     * Quota di larghezza. Non uguale per tutti: "Extraurbani" e' tre volte
     * "Tutti" e con quote pari mandava a capo l'ultima lettera — visto sul
     * device. "Tutti" cede lo spazio che non gli serve.
     */
    val weight: Float,
)

private val Chips = listOf(
    ChipSpec(CategoryFilter.ALL, "Tutti", Icons.Rounded.DirectionsBus, 0.78f),
    ChipSpec(CategoryFilter.URBAN, "Urbani", Icons.Rounded.LocationCity, 1.0f),
    ChipSpec(CategoryFilter.EXTRA, "Extraurbani", Icons.Rounded.Landscape, 1.42f),
)

/** Come si dispone la fila dei filtri: a quote, o scorrevole. */
internal enum class ChipMode {
    /** Tre chip che si dividono la larghezza per quote: com'e' sempre stato. */
    WEIGHTED,

    /** Ogni chip larga quanto la sua etichetta, e la fila scorre. */
    SCROLLING,
}

internal class ChipLayout(
    val mode: ChipMode,
    val showIcon: Boolean,
    val horizontalPaddingDp: Int,
)

/** Lo schermo su cui sono misurate le soglie di [chipLayout]. */
private const val REFERENCE_WIDTH_DP = 360f

/**
 * La disposizione dei chip per una scala del carattere.
 *
 * Le quote di larghezza sono tarate a scala 1,0, dove "Tutti" ci sta di un
 * soffio (33 dp per 31 di testo, a 360 dp di schermo). Con il carattere
 * ingrandito — a 1,3 o a 2,0, cioe' proprio per chi fa fatica a leggere — le
 * tre etichette non stavano piu' nella loro quota, e siccome la riga e' a una
 * riga sola e senza puntini si leggeva "Tutt", "Urba", "Extraur": due filtri
 * che non si distinguevano piu' nemmeno dall'icona.
 *
 * Fino a 1,05 resta il disegno di sempre, con l'icona: oltre, "Tutti" non ci
 * sta piu'. Fra 1,05 e 1,5 si toglie l'icona (18 dp piu' 6 di spazio) e si
 * stringe il margine interno, e le tre etichette rientrano nelle loro quote
 * anche a 1,5; oltre non ci starebbero comunque, e si passa alla fila che
 * scorre, dove ogni etichetta ha tutto lo spazio che le serve.
 *
 * Le soglie valgono per uno schermo da 360 dp; su uno piu' stretto le quote
 * sono piu' piccole, quindi la scala si carica in proporzione: un telefono da
 * 320 dp a 1,3 sta stretto quanto uno da 360 a 1,46.
 */
internal fun chipLayout(fontScale: Float, screenWidthDp: Float = REFERENCE_WIDTH_DP): ChipLayout {
    val pressione = fontScale * REFERENCE_WIDTH_DP / screenWidthDp.coerceAtLeast(1f)
    return when {
        pressione <= 1.05f -> ChipLayout(ChipMode.WEIGHTED, showIcon = true, horizontalPaddingDp = 10)
        pressione <= 1.5f -> ChipLayout(ChipMode.WEIGHTED, showIcon = false, horizontalPaddingDp = 6)
        else -> ChipLayout(ChipMode.SCROLLING, showIcon = true, horizontalPaddingDp = 14)
    }
}

/**
 * I filtri per categoria sotto la barra.
 *
 * La regola, arrivata guardando la prima build: **la fila non e' mai piu'
 * corta dello schermo**. Ogni chip prende una quota della larghezza (peso,
 * non misura fissa) senza crescere in corpo del testo, cosi' su qualunque
 * schermo la fila arriva esattamente al margine.
 *
 * Vale finche' le etichette ci stanno: con il carattere ingrandito la fila
 * perde prima l'icona e poi, oltre 1,5, le quote — diventa una fila che
 * scorre con ogni chip larga quanto il suo testo (vedi [chipLayout]). Meglio
 * una fila piu' lunga dello schermo che etichette tagliate a meta' parola.
 * Quando i chip diventeranno troppi per starci (Tram, Treni) scorrera' anche
 * a scala 1,0, con la degradazione delle etichette come da spec.
 */
@Composable
fun CategoryChipsRow(
    backdrop: GlassBackdropState,
    selected: CategoryFilter,
    onSelect: (CategoryFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = chipLayout(
        fontScale = LocalDensity.current.fontScale,
        screenWidthDp = LocalConfiguration.current.screenWidthDp.toFloat(),
    )
    val scrolling = layout.mode == ChipMode.SCROLLING
    val scroll = rememberScrollState()
    Row(
        // Un gruppo di scelte esclusive, detto come tale: con un lettore di
        // schermo i tre chip erano tre pulsanti uguali, e quale fosse acceso
        // lo diceva solo il colore.
        modifier = modifier
            .fillMaxWidth()
            .then(if (scrolling) Modifier.horizontalScroll(scroll) else Modifier)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (chip in Chips) {
            val isSelected = chip.filter == selected
            Row(
                modifier = Modifier
                    // Nella fila che scorre le quote non hanno senso (la
                    // larghezza a disposizione e' infinita): ognuna prende
                    // quanto le serve, con un minimo per restare toccabile.
                    .then(
                        if (scrolling) {
                            Modifier.widthIn(min = 48.dp)
                        } else {
                            Modifier.weight(chip.weight)
                        },
                    )
                    // Minimo, non fisso: a carattere grande la riga di testo
                    // da sola e' alta quanto i 40 dp, e un'altezza fissa la
                    // tagliava in alto e in basso.
                    .heightIn(min = 40.dp)
                    .glassSurface(
                        state = backdrop,
                        tint = GlassDefaults.floatingTint(),
                        shape = FluidCapsuleShape,
                        edge = GlassEdge.None,
                    )
                    .selectable(
                        selected = isSelected,
                        interactionSource = remember2(),
                        indication = null,
                        role = Role.RadioButton,
                        onClick = { onSelect(chip.filter) },
                    )
                    .padding(horizontal = layout.horizontalPaddingDp.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                if (layout.showIcon) {
                    Icon(
                        imageVector = chip.icon,
                        contentDescription = null,
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = chip.label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                    // Solo una rete di sicurezza: a tagliare per bene ci
                    // pensa la disposizione, qui si evita che il taglio
                    // sia muto se un giorno un'etichetta diventa piu' lunga.
                    overflow = TextOverflow.Ellipsis,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

/** Un tasto tondo in vetro agli angoli della mappa. */
@Composable
fun MapCornerButton(
    icon: ImageVector,
    contentDescription: String,
    backdrop: GlassBackdropState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    /**
     * Rotazione dell'icona in gradi. In modalita' bussola il tasto
     * posizione ruota col nord — e' l'unica bussola dell'app, quella di
     * MapLibre in alto e' spenta.
     */
    iconRotation: () -> Float = { 0f },
) {
    FluidGlassIconButton(
        onClick = onClick,
        backdrop = backdrop,
        selected = selected,
        modifier = modifier,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { rotationZ = iconRotation() },
        )
    }
}


/**
 * Quando il vivo non c'e', e perche'.
 *
 * Diceva sempre la stessa frase — "Bus live non disponibili" — qualunque
 * fosse successo, e finiva con "Dettagli in Impostazioni → Stato dei dati":
 * istruzioni, dove un tocco avrebbe fatto la stessa cosa. Sono tre guasti
 * diversi, e uno solo dei tre e' nostro:
 *
 *   il feed della Regione e' fermo   noi funzioniamo, non arriva niente
 *   il nostro proxy non risponde     le posizioni si', i ritardi no
 *   non risponde nemmeno la Regione  restano gli orari di tabella
 *
 * Sapere quale dei tre cambia cosa aspettarsi dai numeri, ed e' la
 * differenza fra "l'app e' rotta" e "oggi il dato non c'e'".
 */
@Composable
fun LiveDownCapsule(
    backdrop: GlassBackdropState,
    status: dev.antigravity.fluidtransit.data.rt.RealtimeClient.Status,
    onOpenDataStatus: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Il telefono e' scollegato.
     *
     * E' il quarto caso, ed e' l'unico che non e' un guasto di nessuno. Senza
     * distinguerlo l'app diceva "non risponde ne' il nostro proxy ne' la
     * Regione" anche a chi era in metropolitana: leggeva che i nostri server
     * sono giu' e concludeva che l'app e' rotta, invece di guardare la barra
     * in alto. Dare la colpa a se' stessi quando la colpa non c'e' e' un modo
     * lento di far perdere fiducia.
     */
    offline: Boolean = false,
) {
    var expanded by remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    // "Da 340 min" si leggeva quando la Regione si fermava per ore: l'eta'
    // si dice con le parole di tutte le eta' dell'app.
    val minuti = dev.antigravity.fluidtransit.routing.Times.durationLabel(
        (status.feedAgeSeconds ?: 0L).coerceIn(60L, Int.MAX_VALUE.toLong()).toInt(),
    )
    val titolo = when {
        offline -> "Il telefono non e' in rete"
        status.source == dev.antigravity.fluidtransit.data.rt.RealtimeClient.Source.SCHEDULE_ONLY -> "Nessun dato dal vivo"
        status.source == dev.antigravity.fluidtransit.data.rt.RealtimeClient.Source.DIRECT -> "Ritardi non disponibili"
        else -> "Il feed della Regione e' fermo"
    }
    val spiegazione = when {
        offline ->
            "Senza connessione non arrivano ne' le posizioni dei bus ne' i " +
                "ritardi. Gli orari di tabella ci sono lo stesso, perche' " +
                "sono sul telefono, e le schede lo dicono riga per riga."
        status.source == dev.antigravity.fluidtransit.data.rt.RealtimeClient.Source.SCHEDULE_ONLY ->
            "Non risponde ne' il nostro proxy ne' la Regione. Valgono gli " +
                "orari di tabella, e le schede lo dicono riga per riga."
        status.source == dev.antigravity.fluidtransit.data.rt.RealtimeClient.Source.DIRECT ->
            "Il nostro proxy non risponde: le posizioni dei bus arrivano " +
                "direttamente dalla Regione, i ritardi no. I minuti che vedi " +
                "sono quelli di tabella."
        else ->
            "Da $minuti la Regione non pubblica posizioni nuove. Noi le " +
                "stiamo chiedendo: i bus sulla mappa sono dove erano allora."
    }
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .glassSurface(
                state = backdrop,
                tint = GlassDefaults.floatingTint(),
                shape = dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape(
                    dev.antigravity.fluidengine.ui.fluid.FluidRadius.Card,
                ),
                edge = GlassEdge.None,
            )
            .clickable(
                interactionSource = remember2(),
                indication = null,
                role = Role.Button,
                onClickLabel = if (expanded) "Chiudi" else "Perche'",
                onClick = { expanded = !expanded },
            )
            .animateContentSize()
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = titolo,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (expanded) {
            Text(
                text = spiegazione,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Vedi lo stato dei dati",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember2(),
                        indication = null,
                        role = Role.Button,
                        onClick = onOpenDataStatus,
                    )
                    .padding(vertical = 4.dp),
            )
        }
    }
}

/**
 * C'e' una versione nuova.
 *
 * Stessa forma dell'avviso del live, e per lo stesso motivo: e' un'informazione
 * che riguarda l'app e non la mappa, quindi si appoggia sopra senza rubare
 * spazio. Toccandola si apre; dentro, il tasto che installa e quello che la
 * manda via — via per questa sessione soltanto, perche' un'app che si aggiorna
 * da sola non deve poter perdere un aggiornamento per una distrazione.
 */
@Composable
fun UpdateCapsule(
    backdrop: GlassBackdropState,
    version: String,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .glassSurface(
                state = backdrop,
                tint = GlassDefaults.floatingTint(),
                shape = dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape(
                    dev.antigravity.fluidengine.ui.fluid.FluidRadius.Card,
                ),
                edge = GlassEdge.None,
            )
            .clickable(
                interactionSource = remember2(),
                indication = null,
                role = Role.Button,
                onClick = { expanded = !expanded },
            )
            .animateContentSize()
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Aggiornamento disponibile: $version",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (expanded) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                GlassActionButton(
                    text = "Installa",
                    icon = null,
                    backdrop = backdrop,
                    onClick = onInstall,
                    emphasized = true,
                )
                GlassActionButton(
                    text = "Non ora",
                    icon = null,
                    backdrop = backdrop,
                    onClick = onDismiss,
                )
            }
        }
    }
}
