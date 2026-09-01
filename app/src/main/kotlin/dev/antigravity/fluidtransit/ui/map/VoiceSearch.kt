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

/** Cosa ha capito il mic evoluto. */
class VoiceResult(val azione: String, val testo: String)

/**
 * La chiave Groq DELL'UTENTE: la genera lui su console.groq.com e la
 * incolla in Impostazioni — deciso cosi'. Vive solo sul suo telefono e
 * viaggia solo verso Groq; senza chiave il mic resta quello di sistema,
 * che trascrive e basta.
 */
object GroqKey {
    private const val PREFS = "groq-key"

    fun get(context: Context): String? = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("key", null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    fun set(context: Context, value: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (value.isNullOrBlank()) remove("key") else putString("key", value.trim())
            }
            .apply()
    }
}

/**
 * Il canale con Groq, diretto dall'app con la chiave dell'utente: prima la
 * trascrizione Whisper, poi l'LLM piccolo che distingue "portami a X" da
 * una ricerca o dalla dettatura. Ritorna null quando conviene ricadere sul
 * riconoscimento di sistema (rete giu', chiave sbagliata, errori).
 */
object VoiceApi {

    private const val GROQ = "https://api.groq.com/openai/v1"

    private const val SYSTEM_PROMPT = """Sei l'interprete vocale di un'app di trasporto pubblico toscana.
Ricevi la trascrizione di cio' che l'utente ha detto. Rispondi SOLO con un oggetto JSON:
{"azione": "naviga" | "cerca" | "detta", "testo": "..."}

- "naviga": l'utente vuole ANDARE in un posto ("portami a X", "come arrivo a X",
  "andiamo in piazza Y"). In "testo" metti SOLO la destinazione, pulita.
- "cerca": l'utente nomina una fermata, una linea o un luogo da guardare
  ("fermata unita'", "linea 6"). In "testo" il termine da cercare.
- "detta": tutto il resto. In "testo" la trascrizione cosi' com'e'.

Niente altro testo fuori dal JSON."""

    fun interpret(audio: File, apiKey: String): VoiceResult? = runCatching {
        val transcript = transcribe(audio, apiKey) ?: return null
        // L'LLM e' un di piu': se inciampa, la trascrizione vale comunque.
        val understood = runCatching { understand(transcript, apiKey) }.getOrNull()
        VoiceResult(
            azione = understood?.first ?: "detta",
            testo = (understood?.second ?: transcript).trim(),
        ).takeIf { it.testo.isNotEmpty() }
    }.getOrNull()

    private fun transcribe(audio: File, apiKey: String): String? {
        val boundary = "----fluidtransit${System.nanoTime()}"
        val conn = URL("$GROQ/audio/transcriptions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8000
        conn.readTimeout = 25000
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.outputStream.use { out ->
            fun field(name: String, value: String) {
                out.write(
                    ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n" +
                        "$value\r\n").toByteArray(),
                )
            }
            field("model", "whisper-large-v3-turbo")
            field("language", "it")
            field("temperature", "0")
            out.write(
                ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; " +
                    "filename=\"comando.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n").toByteArray(),
            )
            audio.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        if (conn.responseCode != 200) return null
        return JSONObject(conn.inputStream.bufferedReader().readText())
            .optString("text").trim().takeIf { it.isNotEmpty() }
    }

    private fun understand(transcript: String, apiKey: String): Pair<String, String>? {
        val conn = URL("$GROQ/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8000
        conn.readTimeout = 15000
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject()
            .put("model", "llama-3.1-8b-instant")
            .put("temperature", 0)
            .put("max_tokens", 120)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages",
                org.json.JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", transcript)),
            )
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode != 200) return null
        val content = JSONObject(conn.inputStream.bufferedReader().readText())
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
        val parsed = JSONObject(content)
        val azione = parsed.optString("azione")
        val testo = parsed.optString("testo").trim()
        if (azione !in listOf("naviga", "cerca", "detta") || testo.isEmpty()) return null
        return azione to testo
    }
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
    apiKey: String,
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
        val result = withContext(Dispatchers.IO) { VoiceApi.interpret(file, apiKey) }
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
