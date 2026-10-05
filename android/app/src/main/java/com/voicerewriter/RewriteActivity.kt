package com.voicerewriter

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.voicerewriter.textproc.AppContext
import com.voicerewriter.textproc.TextProcessor
import com.voicerewriter.textproc.TextProcessingConfig
import com.voicerewriter.textproc.VocabCorrector
import com.voicerewriter.ui.FlowCyan
import com.voicerewriter.ui.FlowBlue
import com.voicerewriter.ui.FlowSky
import com.voicerewriter.ui.FlowWhite
import com.voicerewriter.ui.VoiceFlowTheme
import com.voicerewriter.ui.FlowBrush
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import androidx.lifecycle.withResumed
import com.voicerewriter.ui.FlowMint
import com.voicerewriter.ui.FlowLight
import com.voicerewriter.ui.FlowNavy
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility

class RewriteActivity : ComponentActivity() {

    companion object {
        /** How long a dictation waits on an in-flight model download before giving up. */
        private const val MODEL_WAIT_TIMEOUT_MS = 25_000L

        const val EXTRA_AUTO_RECORD = "com.voicerewriter.AUTO_RECORD"
        /** Hold-to-talk: the bubble is holding the gesture and its release ends the take. */
        const val EXTRA_PUSH_TO_TALK = "com.voicerewriter.PUSH_TO_TALK"

        /** Re-transcribe a saved recording instead of opening the mic (see [PendingAudio]). */
        const val EXTRA_RETRY_ID = "com.voicerewriter.RETRY_ID"

        /** Launch straight into a retry of the saved recording [id]. */
        fun retryIntent(context: Context, id: String): Intent =
            Intent(context, RewriteActivity::class.java)
                .putExtra(EXTRA_RETRY_ID, id)
    }

    private lateinit var repo: SettingsRepository
    private val sourceState = mutableStateOf("") // selection (PROCESS_TEXT) or clipboard text
    private var readOnly: Boolean = false
    private var processTextMode: Boolean = false // launched from the selection toolbar
    private var voiceMode: Boolean = false        // launched from the bubble
    private var clipboardResolved: Boolean = false
    private var autoRecord: Boolean = false        // start recording on open (dictation)
    private var pushToTalk: Boolean = false        // hold-to-talk: bubble release ends the take
    private var retryId: String? = null            // re-transcribe this saved recording instead

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SettingsRepository(applicationContext)

        val processText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        if (processText != null) {
            processTextMode = true
            sourceState.value = processText.toString()
            readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        } else {
            voiceMode = true
            autoRecord = intent.getBooleanExtra(EXTRA_AUTO_RECORD, false)
            pushToTalk = intent.getBooleanExtra(EXTRA_PUSH_TO_TALK, false)
            retryId = intent.getStringExtra(EXTRA_RETRY_ID)
        }

        setContent {
            VoiceFlowTheme {
                when {
                    processTextMode ->
                        ChipRewriteSheet(
                            title = tr("Reescribir", "Rewrite"),
                            acceptLabel = if (readOnly) tr("Copiar", "Copy") else tr("Aceptar", "Accept"),
                            onAccept = ::accept,
                        )
                    else -> VoiceSheet()
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || clipboardResolved || !voiceMode) return
        sourceState.value = readClipboardText()
        clipboardResolved = true
    }

    private fun readClipboardText(): String {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cb.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
    }

    /** PROCESS_TEXT path: replace the selection in place (or copy if read-only). */
    private fun accept(result: String) {
        if (!readOnly) {
            setResult(Activity.RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result))
        } else {
            setClipboard(result)
            setResult(Activity.RESULT_CANCELED)
        }
        finish()
    }

    private fun setClipboard(text: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("rewrite", text))
    }

    private fun streamFor(s: Settings, prompt: String, text: String): kotlinx.coroutines.flow.Flow<String> =
        if (s.provider == "local") LocalLlmEngine.streamWithPrompt(applicationContext, s, prompt, text)
        else RewriteEngine.streamWithPrompt(s, prompt, text)

    private fun friendlyError(e: Throwable): String = Dictation.friendlyError(e)

    private fun llmReady(s: Settings): Boolean =
        if (s.provider == "local") LlmModelManager.isReady(this, s.model) else s.isConfigured

    // ---------------- dictation launcher ----------------

    /**
     * Voice mode is only a launcher now: the dictation itself runs in [Dictation], inside the
     * bubble's service. This invisible activity exists for the instant it takes to get the
     * microphone (Android only hands a foreground-service microphone to an app that is on
     * screen) and then closes, so the user can keep scrolling and switching apps while they
     * talk; only a tap on the bubble ends the take. It stays longer only to ask for the mic
     * permission, to wait for a speech model that is still downloading, or to show an error.
     */
    @Composable
    private fun VoiceSheet() {
        val scope = rememberCoroutineScope()
        var error by remember { mutableStateOf<String?>(null) }
        var settings by remember { mutableStateOf<Settings?>(null) }

        fun begin(s: Settings) {
            scope.launch {
                // The bubble's service owns the dictation; bring it up if it isn't running yet
                // (e.g. dictation started from Home or onboarding).
                if (BubbleService.instance == null && SetupUtils.canDrawOverlays(this@RewriteActivity)) {
                    runCatching { SetupUtils.startBubble(this@RewriteActivity) }
                    val deadline = System.currentTimeMillis() + 2_000
                    while (BubbleService.instance == null && System.currentTimeMillis() < deadline) delay(50)
                }
                // Only while we're resumed on screen may the service take the microphone type.
                val err = withResumed { Dictation.start(this@RewriteActivity, s, pushToTalk) }
                if (err == null) finish() else error = err
            }
        }

        val micPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val s = settings
            if (granted && s != null) begin(s)
            else error = tr("Se negó el permiso del micrófono.", "Microphone permission denied.")
        }

        LaunchedEffect(Unit) {
            val s = repo.get(); settings = s
            val retry = retryId
            if (retry != null) {
                // Home's "Retry" on a recording an earlier attempt never finished.
                Dictation.retry(this@RewriteActivity, retry, s)
                finish(); return@LaunchedEffect
            }
            if (!autoRecord || Dictation.isBusy) { finish(); return@LaunchedEffect }
            if (s.sttProvider == "local") {
                if (!OnDeviceStt.isReady(this@RewriteActivity, s.sttModel)) {
                    // Onboarding starts the model download without waiting for it, so a first
                    // dictation can land mid-download: wait for it instead of failing.
                    if (OnDeviceStt.isParakeet(s.sttModel)) ParakeetModelManager.ensureDownloading(this@RewriteActivity)
                    BubbleService.instance?.showProcessing()
                    android.widget.Toast.makeText(
                        this@RewriteActivity,
                        tr("Terminando de descargar el modelo de voz…", "Finishing the speech model download…"),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    val deadline = System.currentTimeMillis() + MODEL_WAIT_TIMEOUT_MS
                    while (System.currentTimeMillis() < deadline && !OnDeviceStt.isReady(this@RewriteActivity, s.sttModel)) delay(500)
                    BubbleService.instance?.showIdle()
                    if (!OnDeviceStt.isReady(this@RewriteActivity, s.sttModel)) {
                        error = tr("El modelo de voz no está descargado. Abre Ajustes → Voz → Descargar modelo.",
                            "On-device model not downloaded. Open Settings → Voice → Download model.")
                        return@LaunchedEffect
                    }
                }
            } else if (!s.isSttConfigured) {
                error = tr("No hay clave de voz a texto. Abre los ajustes de VoiceFlow.",
                    "No speech-to-text key set. Open VoiceFlow settings.")
                return@LaunchedEffect
            }
            val granted = ContextCompat.checkSelfPermission(
                this@RewriteActivity, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) begin(s) else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        Box(Modifier.fillMaxSize()) {
            Spacer(Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { if (error != null) finish() })
            Box(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 20.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                AnimatedVisibility(
                    visible = error != null,
                    enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 3 },
                    exit = fadeOut(tween(160)),
                ) {
                    ErrorCard(message = error.orEmpty(), onClose = { finish() })
                }
            }
        }
    }

    @Composable
    private fun ErrorCard(message: String, onClose: () -> Unit) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(FlowBrush))
                    Text("VOICEFLOW", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text(tr("Cerrar", "Close")) }
                }
            }
        }
    }

    // ---------------- chip rewrite sheet (text selected via PROCESS_TEXT) ----------------

    @Composable
    private fun ChipRewriteSheet(title: String, acceptLabel: String, onAccept: (String) -> Unit) {
        val scope = rememberCoroutineScope()
        val haptics = LocalHapticFeedback.current
        var selectedAction by remember { mutableStateOf<String?>(null) }
        var output by remember { mutableStateOf("") }
        var streaming by remember { mutableStateOf(false) }
        var done by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var streamJob by remember { mutableStateOf<Job?>(null) }

        fun run(actionId: String) {
            runCatching { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
            streamJob?.cancel()
            selectedAction = actionId; output = ""; error = null; done = false; streaming = true
            streamJob = scope.launch {
                // Anti-AI guardrails humanize prose; they fight structured output, so
                // turn them off for Prompt Engineer (which needs its bold template).
                val settings = repo.get().let {
                    if (actionId == "prompt_engineer") it.copy(antiAI = false) else it
                }
                if (!llmReady(settings)) {
                    error = if (settings.provider == "local")
                        tr("El modelo en el dispositivo no está descargado. Ábrelo en VoiceFlow → Ajustes.", "On-device model not downloaded. Open VoiceFlow → Settings.")
                    else tr("No hay clave de API. Abre VoiceFlow para configurarla.", "No API key set. Open VoiceFlow to configure it.")
                    streaming = false
                    return@launch
                }
                if (sourceState.value.isBlank()) {
                    error = tr("No hay texto que transformar. Copia algún texto primero.", "Nothing to transform. Copy some text first.")
                    streaming = false
                    return@launch
                }
                streamFor(settings, Defaults.DEFAULT_PROMPTS.getValue(actionId), sourceState.value)
                    .catch { e -> error = friendlyError(e); streaming = false }
                    .onCompletion { cause ->
                        if (cause == null && error == null) { output = RewriteEngine.cleanOutput(output); done = true }
                        streaming = false
                    }
                    .collect { chunk -> output += chunk }
            }
        }

        BottomSheet(onScrimTap = {
            streamJob?.cancel(); setResult(Activity.RESULT_CANCELED); finish()
        }) {
            SheetHeader(title)
            ChipRow(enabled = !streaming, selected = selectedAction, onPick = ::run)

            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                selectedAction == null -> Text(
                    sourceState.value.ifEmpty { tr("Leyendo el portapapeles…", "Reading clipboard…") },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                )
                streaming && output.isBlank() -> TranscribingRing(tr("Trabajando", "Working"))
                else -> OutputText(RewriteEngine.cleanOutput(output).ifEmpty { "…" })
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (streaming) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                if (selectedAction != null && !streaming && error == null) {
                    TextButton(onClick = { run(selectedAction!!) }) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Text("  " + tr("Rehacer", "Redo"))
                    }
                }
                TextButton(onClick = { streamJob?.cancel(); setResult(Activity.RESULT_CANCELED); finish() }) {
                    Icon(Icons.Default.Close, null, Modifier.size(18.dp)); Text("  " + tr("Descartar", "Discard"))
                }
                TextButton(enabled = done && output.isNotBlank(), onClick = { onAccept(output) }) {
                    Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Text("  $acceptLabel")
                }
            }
        }
    }

    // ---------------- shared UI pieces ----------------

    @Composable
    private fun BottomSheet(onScrimTap: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            // Tap above the sheet to dismiss/stop.
            Spacer(Modifier.fillMaxSize().clickable(onClick = onScrimTap))
            Surface(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                tonalElevation = 8.dp,
                shadowElevation = 16.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    content = content,
                )
            }
        }
    }

    @Composable
    private fun SheetHeader(title: String) {
        // Mono uppercase "eyebrow" caption (handoff label spec).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(FlowBrush))
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun OutputText(text: String) {
        Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ChipRow(enabled: Boolean, selected: String?, onPick: (String) -> Unit) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (action in Defaults.ACTIONS) {
                AssistChip(
                    onClick = { if (enabled) onPick(action.id) },
                    enabled = enabled || selected == action.id,
                    label = { Text(action.title) },
                )
            }
        }
    }

    /** Working: a rotating cyan ring around the VoiceFlow microphone. */
    @Composable
    private fun TranscribingRing(label: String) {
        val t = rememberInfiniteTransition(label = "ring")
        val angle by t.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
            label = "spin",
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    rotate(angle) {
                        val w = 4.dp.toPx()
                        drawArc(
                            brush = Brush.sweepGradient(
                                listOf(FlowCyan.copy(alpha = 0f), FlowCyan, FlowBlue, FlowSky),
                            ),
                            startAngle = 0f,
                            sweepAngle = 300f,
                            useCenter = false,
                            topLeft = Offset(w / 2f, w / 2f),
                            size = Size(size.width - w, size.height - w),
                            style = Stroke(width = w, cap = StrokeCap.Round),
                        )
                    }
                }
                Icon(
                    painter = painterResource(R.drawable.ic_voiceflow),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
