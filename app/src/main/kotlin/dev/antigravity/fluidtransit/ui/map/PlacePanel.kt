package dev.antigravity.fluidtransit.ui.map

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassEdge
import dev.antigravity.fluidengine.ui.fluid.glassSurface

/** Un luogo scelto: dalla ricerca, da un posto salvato o dal tieni-premuto. */
class PlaceRef(
    val name: String,
    val context: String,
    val lat: Double,
    val lon: Double,
    val savedId: Long? = null,
)

/** Un bottone di testo in vetro: vetro su vetro, come da regola dei pannelli. */
@Composable
fun GlassActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    backdrop: GlassBackdropState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    /**
     * Spento quando toccarlo non farebbe niente. Prima il tocco finiva nel
     * vuoto — "Salva" senza nome, "Crea la routine" senza giorni — e da
     * fuori sembrava un pulsante rotto.
     */
    enabled: Boolean = true,
    /**
     * Quante righe al massimo. Di norma quante ne servono; i tasti dei posti
     * nominati dall'assistente si fermano a due, perche' un nome di fermata
     * lungo occupava da solo mezza risposta.
     */
    maxLines: Int = Int.MAX_VALUE,
) {
    val alfa = if (enabled) 1f else 0.38f
    Row(
        modifier = modifier
            .glassSurface(
                state = backdrop,
                tint = GlassDefaults.floatingTint(),
                shape = FluidCapsuleShape,
                edge = GlassEdge.None,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (emphasized) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }.copy(alpha = alfa),
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = text,
            maxLines = maxLines,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
            color = if (emphasized) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            }.copy(alpha = alfa),
        )
    }
}

/**
 * Il pannello del luogo: nome, dove sta, e i due gesti decisi — "Portami
 * qui" che avvia gli itinerari e "Salva" che lo mette fra i tuoi posti.
 * Il salvataggio non apre dialoghi: il pannello si trasforma nel modulo,
 * come ogni altro passaggio di stato di questa superficie.
 */
@Composable
fun PlacePanelContent(
    ref: PlaceRef,
    backdrop: GlassBackdropState,
    onDismiss: () -> Unit,
    onGo: () -> Unit,
    onSave: (label: String) -> Unit,
    onRemoveSaved: (() -> Unit)? = null,
    /** "Parti da qui": mette questo punto come ORIGINE del pianificatore. */
    onStartHere: (() -> Unit)? = null,
    /**
     * Si sta scrivendo il nome: chi ospita il pannello lo solleva sopra la
     * tastiera (vedi [keyboardLift]). Sta fuori perche' il sollevamento deve
     * spostare tutto il vetro senza toccarne le misure: uno spazio dentro il
     * pannello, com'era, si accorciava proprio quando serviva — coi caratteri
     * grandi o su una finestra bassa il campo restava sotto la tastiera.
     */
    onEditing: (Boolean) -> Unit = {},
) {
    var saving by remember { mutableStateOf(false) }
    var customLabel by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(saving) { onEditing(saving) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onEditing(false) } }
    // Salvato, il modulo si chiude. La scheda che torna ha lo stesso punto, e
    // quindi la stessa chiave: il suo stato restava in piedi, e dopo "Salva"
    // o "Fatto" il modulo era ancora aperto col nome scritto dentro, senza
    // nessun segno che il posto fosse stato salvato.
    fun salva(nome: String) {
        onSave(nome)
        saving = false
        customLabel = ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = ref.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (ref.context.isNotEmpty()) {
                Text(
                    text = ref.context,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "Chiudi",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(40.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onDismiss,
                )
                .padding(8.dp),
        )
    }

    if (!saving) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassActionButton(
                text = "Portami qui",
                icon = Icons.Rounded.Directions,
                backdrop = backdrop,
                onClick = onGo,
                emphasized = true,
                modifier = Modifier.weight(1f),
            )
            if (onRemoveSaved != null) {
                GlassActionButton(
                    text = "Rimuovi",
                    icon = Icons.Rounded.Star,
                    backdrop = backdrop,
                    onClick = onRemoveSaved,
                )
            } else {
                GlassActionButton(
                    text = "Salva",
                    icon = Icons.Rounded.Star,
                    backdrop = backdrop,
                    onClick = { saving = true },
                )
            }
        }
        // Su una riga sua: e' l'azione meno frequente delle due, ma e'
        // l'unico modo per far partire un viaggio da qui invece che da dove
        // sei — cosa che fino alla Fase 8 non si poteva fare affatto.
        if (onStartHere != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 14.dp),
            ) {
                GlassActionButton(
                    text = "Parti da qui",
                    icon = Icons.Rounded.MyLocation,
                    backdrop = backdrop,
                    onClick = onStartHere,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else {
        // --- il modulo del salvataggio: etichetta pronta o nome libero ----
        Text(
            text = "Salva come",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (label in listOf("Casa", "Lavoro", "Scuola")) {
                GlassActionButton(
                    text = label,
                    icon = null,
                    backdrop = backdrop,
                    onClick = { salva(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .glassSurface(
                        state = backdrop,
                        tint = GlassDefaults.floatingTint(),
                        shape = FluidCapsuleShape,
                        edge = GlassEdge.None,
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = customLabel,
                    onValueChange = { customLabel = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    // "Fatto" sulla tastiera chiudeva la tastiera e basta: il
                    // nome restava li', scritto, e per salvarlo si doveva
                    // cercare "Salva" — che con la tastiera aperta era
                    // coperto. Ora fa quello che fa il tasto accanto, con la
                    // sua stessa regola: senza un nome non c'e' niente da
                    // salvare, e Invio chiude solo la tastiera come prima.
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            if (customLabel.isNotBlank()) salva(customLabel)
                        },
                    ),
                    decorationBox = { inner ->
                        if (customLabel.isEmpty()) {
                            Text(
                                text = "Oppure un nome tuo…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    },
                )
            }
            GlassActionButton(
                text = "Salva",
                icon = null,
                backdrop = backdrop,
                emphasized = true,
                // Senza un nome non c'e' niente da salvare: il campo accanto
                // dice gia' cosa manca.
                enabled = customLabel.isNotBlank(),
                onClick = { salva(customLabel) },
            )
        }
        // Il modulo sale sopra la tastiera.
        //
        // Toccando il campo del nome la tastiera si prendeva gli ultimi
        // 260-300 dp dello schermo, e il pannello — che siede 90 dp sopra la
        // barra di sistema, appena sopra la tab bar, e non sa niente della
        // tastiera, perche' l'app e' a tutto schermo e la tastiera arriva
        // solo come margine — restava coperto tranne la testata: sparivano la
        // riga Casa/Lavoro/Scuola, il campo e "Salva", e si scriveva alla
        // cieca. In orizzontale il pannello era coperto per intero.
        //
        // Si alza di quanto la tastiera SPORGE oltre il punto in cui il
        // pannello gia' sta (`keyboardLift`), non di tutta la sua altezza:
        // sommarla al margine lasciava piu' di 90 dp di vetro vuoto fra il
        // modulo e la tastiera. Sta qui e non nell'host della mappa perche'
        // vale solo per questa scheda — una tastiera aperta da un'altra parte
        // non deve sollevare gli altri pannelli — e vale solo mentre si
        // compila. Il vetro si allunga verso l'alto e il fondo resta dov'e',
        // sotto la tastiera; in orizzontale la testata esce dallo schermo e
        // restano visibili il campo e "Salva", che sono in fondo.
        // Il sollevamento lo fa chi ospita il pannello: vedi [onEditing].
    }
    Spacer(Modifier.height(6.dp))
}

/**
 * A che distanza dal fondo, sopra la barra di sistema, siede questo pannello.
 *
 * E' il numero di MapScreen (`ContentInset + 10.dp`, lo stesso di ogni
 * pannello che non e' in modalita' linea): se la' cambia, cambia anche qui,
 * altrimenti il modulo si alza troppo poco o troppo.
 */
internal val PLACE_PANEL_RESTING_MARGIN = FluidTabBarDefaults.ContentInset + 10.dp

/**
 * Di quanto alzare il modulo perche' la tastiera non lo copra.
 *
 * Il fondo del pannello sta a [restingMargin] sopra la barra di sistema; la
 * tastiera comincia a [ime] dal fondo dello schermo, barra compresa, quindi
 * quella barra si toglie una volta sola. Quando la tastiera e' piu' bassa del
 * pannello non c'e' niente da alzare, e il risultato non e' mai negativo.
 * La regola e' la stessa di `listMax`: la tastiera si sottrae UNA volta.
 */
internal fun keyboardLift(ime: Dp, navBar: Dp, restingMargin: Dp): Dp =
    maxOf(ime - navBar - restingMargin, 0.dp)
