package com.voicerewriter

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.voicerewriter.textproc.VocabEntry
import com.voicerewriter.ui.FlowBrush
import com.voicerewriter.ui.FlowCyan
import com.voicerewriter.ui.FlowWhite
import com.voicerewriter.ui.VoiceFlowTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The personal dictionary: names and terms the recognizer gets wrong. Each word can carry
 * aliases (how the recognizer mishears it) and short recordings of the user saying it — a
 * recording is transcribed on the spot and what was heard becomes an alias, so the word comes
 * out right in later dictations. [com.voicerewriter.textproc.VocabCorrector] does the snapping.
 *
 * This is the one place to correct the recognizer: dictation inserts directly, with no review
 * step, and the dictionary is improved here over time.
 */
class VocabActivity : ComponentActivity() {

    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = VocabRepository(applicationContext)
        setContent {
            VoiceFlowTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VocabScreen(repo)
                }
            }
        }
    }

    override fun onDestroy() {
        player?.release(); player = null
        super.onDestroy()
    }

    private fun play(name: String) {
        player?.release()
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(VoiceSamples.file(this@VocabActivity, name).absolutePath)
                setOnCompletionListener { it.release(); if (player === it) player = null }
                prepare()
                start()
            }
        }.getOrNull()
    }

    @Composable
    private fun VocabScreen(repo: VocabRepository) {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val entries = remember { mutableStateListOf<VocabEntry>() }
        var canonical by remember { mutableStateOf("") }
        var aliases by remember { mutableStateOf("") }
        var expansion by remember { mutableStateOf("") }
        var recordingFor by remember { mutableStateOf<String?>(null) } // canonical being recorded
        var pendingRecord by remember { mutableStateOf<String?>(null) }

        suspend fun reload() { entries.clear(); entries.addAll(repo.get()) }
        LaunchedEffect(Unit) { reload() }
        fun persist() = scope.launch { repo.save(entries.toList()) }

        // Reload after the contacts-import screen adds entries.
        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { scope.launch { reload() } }

        val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) recordingFor = pendingRecord
            else Toast.makeText(context, tr("Se necesita el micrófono para grabar.", "The microphone is needed to record."), Toast.LENGTH_SHORT).show()
            pendingRecord = null
        }

        fun startRecording(word: String) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) recordingFor = word
            else { pendingRecord = word; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        }

        /** Add the word in the form (if it's new) and return its canonical spelling. */
        fun addFromForm(): String? {
            val word = canonical.trim()
            if (word.isEmpty()) return null
            val a = aliases.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val idx = entries.indexOfFirst { it.canonical.equals(word, ignoreCase = true) && !it.isSnippet }
            if (idx >= 0) {
                val e = entries[idx]
                entries[idx] = e.copy(aliases = (e.aliases + a).distinctBy { it.lowercase() })
            } else {
                entries.add(0, VocabEntry(word, a, expansion.trim().ifEmpty { null }))
            }
            canonical = ""; aliases = ""; expansion = ""
            persist()
            return if (idx >= 0) entries[idx].canonical else word
        }

        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr("Diccionario personal", "Personal dictionary"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                tr(
                    "Nombres y palabras que el reconocimiento escribe mal. Agrega la palabra y graba cómo la " +
                        "dices: escucho la grabación y, si la entiendo distinto, lo guardo como alias para " +
                        "escribirla bien la próxima vez. Todo se queda en tu teléfono.",
                    "Names and terms the recognizer gets wrong. Add the word and record how you say it: I " +
                        "listen to the recording and, if I hear it differently, save that as an alias so it's " +
                        "written right next time. Everything stays on your phone.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = canonical, onValueChange = { canonical = it },
                        label = { Text(tr("Palabra o frase", "Word or phrase")) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = aliases, onValueChange = { aliases = it },
                        label = { Text(tr("Cómo la escribe mal (opcional, separado por comas)", "How it gets misheard (optional, comma-separated)")) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = expansion, onValueChange = { expansion = it },
                        label = { Text(tr("Se expande a (opcional, p. ej. un correo)", "Expands to (optional, e.g. an email)")) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            enabled = canonical.isNotBlank() && expansion.isBlank(),
                            onClick = { addFromForm()?.let { startRecording(it) } },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Mic, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(tr("Agregar y grabar", "Add & record"))
                        }
                        Button(
                            enabled = canonical.isNotBlank(),
                            onClick = { addFromForm() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(tr("Agregar", "Add"))
                        }
                    }
                }
            }

            TextButton(onClick = {
                importLauncher.launch(android.content.Intent(context, ContactsImportActivity::class.java))
            }) { Text(tr("Importar de contactos", "Import from contacts")) }

            if (entries.isEmpty()) {
                Text(tr("Todavía no hay palabras.", "No words yet."), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries, key = { it.canonical + "|" + (it.expansion ?: "") }) { e ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth().animateContentSize(),
                        ) {
                            Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        if (e.isSnippet) "${e.canonical}  ⇒  ${e.expansion}" else e.canonical,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    if (e.aliases.isNotEmpty()) Text(
                                        "↳ ${e.aliases.joinToString(", ")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (e.voiceSamples.isNotEmpty()) Text(
                                        tr("🎙 ${e.voiceSamples.size} grabación(es)", "🎙 ${e.voiceSamples.size} recording(s)"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (e.voiceSamples.isNotEmpty()) {
                                    IconButton(onClick = { play(e.voiceSamples.last()) }) {
                                        Icon(Icons.Default.PlayArrow, tr("Escuchar", "Play"))
                                    }
                                }
                                if (!e.isSnippet) {
                                    IconButton(onClick = { startRecording(e.canonical) }) {
                                        Icon(Icons.Default.Mic, tr("Grabar pronunciación", "Record pronunciation"),
                                            tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                IconButton(onClick = {
                                    VoiceSamples.delete(context, e.voiceSamples)
                                    entries.remove(e); persist()
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = tr("Eliminar", "Delete"),
                                        modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        recordingFor?.let { word ->
            RecordDialog(
                word = word,
                onSaved = { sample, alias ->
                    val idx = entries.indexOfFirst { it.canonical == word && !it.isSnippet }
                    if (idx >= 0) {
                        val e = entries[idx]
                        val newAliases = if (alias != null && e.aliases.none { it.equals(alias, ignoreCase = true) })
                            e.aliases + alias else e.aliases
                        entries[idx] = e.copy(aliases = newAliases, voiceSamples = e.voiceSamples + sample)
                        persist()
                    }
                },
                onDismiss = { recordingFor = null },
            )
        }
    }

    private enum class RecState { LISTENING, PROCESSING, DONE, FAILED }

    /**
     * Record one sample of [word]: listen (auto-stops on a pause, or after a few seconds, or on
     * tap), transcribe, and report what was heard. [onSaved] gets the sample's file name and the
     * alias to add (null when the word was already recognized correctly).
     */
    @Composable
    private fun RecordDialog(word: String, onSaved: (String, String?) -> Unit, onDismiss: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val recorder = remember { AudioRecorder(context.applicationContext) }
        var state by remember { mutableStateOf(RecState.LISTENING) }
        var heard by remember { mutableStateOf("") }
        var learned by remember { mutableStateOf<String?>(null) }
        var message by remember { mutableStateOf("") }
        var level by remember { mutableStateOf(0f) }
        var job by remember { mutableStateOf<Job?>(null) }
        var take by remember { mutableStateOf(0) }

        fun complete(samples: ShortArray?) {
            if (state != RecState.LISTENING) return
            if (samples == null) {
                message = tr("No se captó audio. Intenta de nuevo, un poco más fuerte.", "Didn't catch any audio. Try again, a little louder.")
                state = RecState.FAILED
                return
            }
            state = RecState.PROCESSING
            job = scope.launch {
                val name = VoiceSamples.newName(word)
                try {
                    val text = VoiceSamples.transcribe(context, samples, name)
                    heard = text.trim()
                    if (heard.isEmpty()) {
                        VoiceSamples.delete(context, listOf(name))
                        message = tr("No te entendí. Intenta de nuevo.", "I couldn't make that out. Try again.")
                        state = RecState.FAILED
                    } else {
                        learned = VoiceSamples.aliasFrom(heard, word)
                        onSaved(name, learned)
                        state = RecState.DONE
                    }
                } catch (e: Exception) {
                    VoiceSamples.delete(context, listOf(name))
                    message = e.message ?: tr("No se pudo transcribir.", "Couldn't transcribe.")
                    state = RecState.FAILED
                }
            }
        }

        // One take per value of [take]: start listening, auto-stop on a pause or at the cap.
        LaunchedEffect(take) {
            state = RecState.LISTENING; heard = ""; learned = null; message = ""
            val s = SettingsRepository(context.applicationContext).get()
            if (s.sttProvider == "local" && !OnDeviceStt.isReady(context, s.sttModel)) {
                message = tr("Primero descarga el modelo de voz en Ajustes.", "Download the speech model in Settings first.")
                state = RecState.FAILED
                return@LaunchedEffect
            }
            try {
                recorder.start(vadAutoStop = true, onAutoStop = { complete(recorder.stop()) })
            } catch (e: Exception) {
                message = e.message ?: tr("No se pudo iniciar el micrófono.", "Couldn't start the mic.")
                state = RecState.FAILED
                return@LaunchedEffect
            }
            val started = System.currentTimeMillis()
            while (isActive && recorder.isRecording) {
                level = (recorder.amplitude() / 14000f).coerceIn(0f, 1f)
                if (System.currentTimeMillis() - started > VoiceSamples.MAX_MS) { complete(recorder.stop()); break }
                delay(50)
            }
        }
        DisposableEffect(Unit) { onDispose { job?.cancel(); recorder.cancel() } }

        Dialog(onDismissRequest = { recorder.cancel(); onDismiss() }) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
                Column(
                    Modifier.padding(22.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(tr("Di: «$word»", "Say: “$word”"), style = MaterialTheme.typography.titleMedium)
                    AnimatedContent(
                        targetState = state,
                        transitionSpec = { fadeIn(tween(220)).togetherWith(fadeOut(tween(160))) },
                        label = "rec-state",
                    ) { st ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            when (st) {
                                RecState.LISTENING -> {
                                    MicOrb(level, Modifier.size(120.dp).clip(CircleShape).clickable { complete(recorder.stop()) })
                                    Text(tr("Escuchando… toca para terminar", "Listening… tap to finish"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                RecState.PROCESSING -> {
                                    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                                    Text(tr("Escuchando la grabación…", "Listening back…"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                RecState.DONE -> {
                                    Text(tr("Escuché: «$heard»", "I heard: “$heard”"), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        if (learned != null)
                                            tr("Listo. Cuando vuelva a escuchar «$learned» escribiré «$word».",
                                                "Done. Next time I hear “$learned” I'll write “$word”.")
                                        else tr("¡Te entiendo perfecto! Guardé la grabación.",
                                            "I understood you perfectly! The recording is saved."),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                RecState.FAILED -> Text(message, color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (state == RecState.DONE || state == RecState.FAILED) {
                            TextButton(onClick = { take++ }) { Text(tr("Grabar otra", "Record another")) }
                        }
                        TextButton(onClick = { recorder.cancel(); onDismiss() }) {
                            Text(if (state == RecState.DONE) tr("Listo", "Done") else tr("Cancelar", "Cancel"))
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }

    /** Gradient microphone with rings that ripple out and swell with the voice level. */
    @Composable
    private fun MicOrb(level: Float, modifier: Modifier = Modifier) {
        val t = rememberInfiniteTransition(label = "orb")
        val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "phase")
        Box(modifier, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val half = size.minDimension / 2f
                val r = half * 0.62f
                for (k in 0..1) {
                    val p = (phase + k * 0.5f) % 1f
                    drawCircle(FlowCyan.copy(alpha = (1f - p) * 0.5f), r + (half - r) * p, c, style = Stroke(3.dp.toPx()))
                }
                drawCircle(FlowBrush, r * (1f + level * 0.12f), c)
            }
            Icon(painterResource(R.drawable.ic_voiceflow), null, tint = FlowWhite, modifier = Modifier.size(44.dp))
        }
    }
}
