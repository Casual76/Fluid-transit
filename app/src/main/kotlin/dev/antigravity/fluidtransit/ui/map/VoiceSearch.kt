package dev.antigravity.fluidtransit.ui.map

import android.content.Context
import android.media.MediaRecorder
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassEdge
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Cosa ha capito il proxy vocale. */
class VoiceResult(val azione: String, val testo: String)

/**
 * Il canale col proxy vocale. Ritorna null quando conviene ricadere sul
 * riconoscimento di sistema: chiave mancante (501), rete giu', errori.
 */
object VoiceApi {

    private const val ENDPOINT =
        "https://fluid-transit-rt.fluid-transit.workers.dev/voice/v1/interpret"

    fun interpret(audio: File): VoiceResult? = runCatching {
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        conn.setRequestProperty("Content-Type", "audio/mp4")
        conn.setRequestProperty("User-Agent", "FluidTransit/1.0")
        conn.outputStream.use { o -> audio.inputStream().use { it.copyTo(o) } }
        if (conn.responseCode != 200) return null
        val body = JSONObject(conn.inputStream.bufferedReader().readText())
        if (!body.optBoolean("ok")) return null
        VoiceResult(
            azione = body.optString("azione", "detta"),
            testo = body.optString("testo").trim(),
        ).takeIf { it.testo.isNotEmpty() }
    }.getOrNull()
}

/**
 * L'overlay del mic evoluto: vetro sopra la mappa, "ti ascolto", tocco per
 * finire (o 15 secondi e finisce da solo). L'audio va al NOSTRO Worker; se
 * il proxy non puo' (chiave assente, rete), si ricade sul mic di sistema
 * senza far pesare niente all'utente.
 */
@Composable
fun VoiceOverlay(
    backdrop: GlassBackdropState,
    onResult: (VoiceResult) -> Unit,
    onFallback: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var phase by remember { mutableStateOf("listening") } // listening | sending
    var stopRequested by remember { mutableStateOf(false) }
    val recorder = remember { VoiceRecorder(context) }

    DisposableEffect(Unit) {
        val ok = recorder.start()
        if (!ok) onFallback()
        onDispose { recorder.release() }
    }

    // Tocco per finire, o il tetto dei 15 secondi: la stessa strada.
    LaunchedEffect(stopRequested) {
        if (!stopRequested) {
            kotlinx.coroutines.delay(15_000)
            stopRequested = true
            return@LaunchedEffect
        }
        phase = "sending"
        val file = withContext(Dispatchers.IO) { recorder.stop() }
        if (file == null || file.length() < 400) {
            onCancel()
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) { VoiceApi.interpret(file) }
        file.delete()
        if (result != null) onResult(result) else onFallback()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { if (phase == "listening") stopRequested = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .glassSurface(
                    state = backdrop,
                    tint = GlassDefaults.floatingTint(),
                    shape = ContinuousCornerShape(FluidRadius.Sheet),
                    edge = GlassEdge.None,
                    role = GlassRole.Modal,
                )
                .padding(horizontal = 36.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val pulse = rememberInfiniteTransition(label = "voicePulse")
            val scale by pulse.animateFloat(
                initialValue = 1f,
                targetValue = 1.25f,
                animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
                label = "voiceScale",
            )
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer {
                        if (phase == "listening") {
                            scaleX = scale
                            scaleY = scale
                        }
                    },
            )
            Text(
                text = if (phase == "listening") "Ti ascolto…" else "Un attimo…",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (phase == "listening") {
                    "\"Portami a…\", una fermata, una linea.\nTocca per finire."
                } else {
                    "Sto capendo cosa hai detto."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = { if (phase == "listening") stopRequested = true },
                ),
            )
        }
    }
}

/** MediaRecorder incartato: AAC mono a 16 kHz, quanto basta per Whisper. */
private class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var output: File? = null

    fun start(): Boolean = runCatching {
        val file = File(context.cacheDir, "voice-command.m4a")
        @Suppress("DEPRECATION")
        val r = MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(16_000)
        r.setAudioEncodingBitRate(24_000)
        r.setAudioChannels(1)
        r.setMaxDuration(16_000)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        output = file
        true
    }.getOrDefault(false)

    fun stop(): File? = runCatching {
        recorder?.stop()
        release()
        output
    }.getOrNull()

    fun release() {
        runCatching { recorder?.release() }
        recorder = null
    }
}
