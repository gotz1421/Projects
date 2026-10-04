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
    private lateinit var audioRecorder: AudioRecorder
    private val sourceState = mutableStateOf("") // selection (PROCESS_TEXT) or clipboard text
    private var readOnly: Boolean = false
    private var processTextMode: Boolean = false // launched from the selection toolbar
    private var voiceMode: Boolean = false        // launched from the bubble
    private var clipboardResolved: Boolean = false
    private var autoRecord: Boolean = false        // start recording on open (dictation)
    private var pushToTalk: Boolean = false        // hold-to-talk: bubble release ends the take
    private var retryId: String? = null            // re-transcribe this saved recording instead

    /**
     * The durable recording this sheet is working on, if any. Compose state so the error
     * screen can offer a retry the moment one exists; an activity field (not `remember`) so
     * [onDestroy] can hand it back for retry rather than stranding it as "in flight".
     */
    private var pendingId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SettingsRepository(applicationContext)
        audioRecorder = AudioRecorder(applicationContext)

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

    override fun onDestroy() {
        super.onDestroy()
        audioRecorder.cancel()
        // Hand any unsettled recording back: with nothing live working on it, it reads as
        // unfinished and Home offers a retry. Nothing is written — absence of a result is
        // already the truth on disk.
        pendingId?.let { PendingAudio.release(it) }
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

    /** Voice path: hand the result to the accessibility service (auto-insert) and close. */
    private fun acceptVoice(result: String) {
        // No toast on the clipboard fallback: users who skipped Accessibility chose the
        // clipboard deliberately, so being told about it after every single dictation (and
        // nudged to grant the permission again) is nagging, not information.
        val enqueued = OpenWisprAccessibilityService.enqueueInsert(result)
        if (!enqueued) setClipboard(result)
        LastDictation.set(this, result)
        // Delivery is confirmed — inserted, or on the clipboard where the user can reach it —
        // and the transcript is already in history. Only now is the recording settled, which
        // is what makes it eligible for retention pruning.
        pendingId?.let { id ->
            val ctx = applicationContext
            pendingId = null
            Thread { runCatching { PendingAudio.settle(ctx, id, result) } }.start()
        }
        postFixNotification()
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    /**
     * Host app captured the moment recording starts. Read this (not the live
     * [OpenWisprAccessibilityService.lastHostPackage]) downstream — by the time a
     * dictation finishes, transient system windows may have moved focus, which used
     * to mislabel entries as "System UI".
     */
    private var dictationHostPkg: String? = null

    /** Packages the user assigned to a tone in "Tone by app" (package → category key). */
    private var appToneAssignments: Map<String, String> = emptyMap()

    /** App context of the in-flight dictation, captured in [process] for corpus tagging. */
    private var dictationCategory: AppContext.Category = AppContext.Category.GENERIC

    /**
     * Save an accepted dictation to the personalization corpus (off-thread). [cleaned] is
     * what the pipeline produced; [final] is what the user kept — equal when they didn't edit.
     */
    private fun recordCorpus(cleaned: String, final: String, edited: Boolean) {
        val ctx = applicationContext
        val cat = dictationCategory.key
        Thread {
            runCatching {
                CorrectionCorpus.record(ctx, CorrectionSample(
                    ts = System.currentTimeMillis(), category = cat,
                    cleaned = cleaned, final = final, edited = edited,
                ))
            }
        }.start()
    }

    /**
     * Render past corrections as a compact few-shot block for the polish prompt. Each
     * example is truncated so a couple of them can't blow the tiny model's context budget.
     */
    private fun fewShotBlock(samples: List<CorrectionSample>): String {
        val usable = samples.filter { it.cleaned.isNotBlank() && it.final.isNotBlank() }
        if (usable.isEmpty()) return ""
        fun clip(s: String) = s.trim().let { if (it.length > 240) it.take(240) + "…" else it }
        return buildString {
            append("Examples of how I like my dictation cleaned (raw, then the version I keep):")
            for (s in usable) {
                append("\nRaw: ").append(clip(s.cleaned))
                append("\nKept: ").append(clip(s.final))
            }
        }
    }

    /** Log a finished dictation to the Home feed (off-thread; no-op when history is disabled). */
    private fun recordHistory(before: String, after: String, durationSec: Int, edited: Boolean, onDevice: Boolean) {
        if (after.isBlank()) return
        val pkg = (dictationHostPkg ?: OpenWisprAccessibilityService.lastHostPackage).orEmpty()
        val label = appLabel(pkg)
        val words = after.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        val entry = DictationEntry(
            id = "${System.currentTimeMillis()}-${after.hashCode() and 0xffff}",
            timestamp = System.currentTimeMillis(),
            appPackage = pkg,
            appLabel = label,
            durationSec = durationSec,
            words = words,
            accepted = !edited,
            onDevice = onDevice,
            before = before,
            after = after,
        )
        val ctx = applicationContext
        Thread { runCatching { DictationHistory.record(ctx, entry) } }.start()
    }

    /**
     * In chat/messaging apps, strip the single trailing full stop Whisper tends to add to a
     * short one-liner ("On my way." -> "On my way"). Only touches a *single-sentence* message
     * ending in a lone "." — never "!"/"?", never an ellipsis, and never multi-sentence text
     * (where the period is doing real work). Question/exclamation marks are left untouched.
     */
    private fun dropChatTerminalPeriod(text: String, category: AppContext.Category): String {
        if (category != AppContext.Category.CHAT && category != AppContext.Category.SOCIAL) return text
        val t = text.trimEnd()
        if (!t.endsWith(".") || t.endsWith("..")) return text // keep ellipses
        val body = t.dropLast(1).trimEnd()
        if (body.isEmpty()) return text
        // Only for a single sentence — bail if there's another terminator or a line break inside.
        if (body.any { it == '.' || it == '!' || it == '?' || it == '\n' }) return text
        return body
    }

    private fun appLabel(pkg: String): String {
        if (pkg.isBlank()) return tr("Dictado", "Dictation")
        return runCatching {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() })
    }

    /** Quiet, replace-in-place notification: tap to fix a mis-heard word. */
    private fun postFixNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        // On Android 13+ this silently no-ops without POST_NOTIFICATIONS — skip cleanly.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val channelId = "dictation_fix"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ch = android.app.NotificationChannel(
                channelId, tr("Correcciones de dictado", "Dictation corrections"), android.app.NotificationManager.IMPORTANCE_LOW,
            ).apply { description = tr("Toca para corregir una palabra mal escuchada después de dictar.", "Tap to fix a mis-heard name after dictation.") }
            nm.createNotificationChannel(ch)
        }
        val pi = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, FixDictationActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_voiceflow)
            .setContentTitle(tr("Dictado ✓", "Dictated ✓"))
            .setContentText(tr("¿Alguna palabra salió mal? Toca para enseñársela al diccionario.", "Got a word wrong? Tap to teach it to your dictionary."))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(42, notif) }
    }

    private fun setClipboard(text: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("rewrite", text))
    }

    private fun streamFor(s: Settings, prompt: String, text: String): kotlinx.coroutines.flow.Flow<String> =
        if (s.provider == "local") LocalLlmEngine.streamWithPrompt(applicationContext, s, prompt, text)
        else RewriteEngine.streamWithPrompt(s, prompt, text)

    /** Map a raw exception to plain, actionable copy for the sheet. */
    private fun friendlyError(e: Throwable): String {
        val msg = e.message ?: e.toString()
        val low = msg.lowercase()
        return when {
            e is java.net.UnknownHostException || e is java.net.ConnectException ||
                "unable to resolve host" in low || "failed to connect" in low ->
                tr("Sin conexión. Revisa tu red o cambia a \"en el dispositivo\" en Ajustes.", "No connection. Check your network, or switch to on-device in Settings.")
            e is java.net.SocketTimeoutException || "timeout" in low || "timed out" in low ->
                tr("Tardó demasiado. Inténtalo de nuevo.", "That took too long. Try again.")
            "401" in low || "403" in low || "unauthor" in low || "api key" in low || "invalid key" in low ->
                tr("Revisa tu clave de API en Ajustes.", "Check your API key in Settings.")
            else -> msg
        }
    }

    private fun llmReady(s: Settings): Boolean =
        if (s.provider == "local") LlmModelManager.isReady(this, s.model) else s.isConfigured

    /**
     * A downloaded on-device engine that *isn't* the one that just failed, as `id to label`.
     * Parakeet and Whisper fail on different things, so re-running the saved audio on the
     * other one genuinely rescues transcripts — and on-device it costs nothing but a few
     * seconds. Null when the user only has one engine, or is on a cloud provider.
     */
    private fun altOnDeviceEngine(s: Settings): Pair<String, String>? {
        if (s.sttProvider != "local") return null
        val current = OnDeviceStt.resolveModel(s.sttModel)
        if (!OnDeviceStt.isParakeet(current)) {
            return if (ParakeetModelManager.isReady(this)) ParakeetModelManager.MODEL_ID to "Parakeet" else null
        }
        return WhisperModelManager.MODELS
            .firstOrNull { WhisperModelManager.isReady(this, it.id) }
            ?.let { it.id to "Whisper" }
    }

    // ---------------- voice dictation (no review sheet) ----------------

    /**
     * Dictation runs without a review step: once the text is transcribed and cleaned up it goes
     * straight into the field. The only UI is a small status pill (listening / transcribing),
     * plus an error card when something fails, so nothing covers the app being typed into.
     * Corrections happen afterwards, by teaching the personal dictionary — never by editing
     * the transcript in between.
     */
    private enum class Stage { IDLE, WAITING_MODEL, RECORDING, TRANSCRIBING, CORRECTING, ERROR }

    @Composable
    private fun VoiceSheet() {
        val scope = rememberCoroutineScope()
        val haptics = LocalHapticFeedback.current
        val parakeetDlPct by ParakeetModelManager.downloadProgress.collectAsState()

        var settings by remember { mutableStateOf<Settings?>(null) }
        var stage by remember { mutableStateOf(Stage.IDLE) }
        var transcript by remember { mutableStateOf("") }   // raw STT
        var output by remember { mutableStateOf("") }       // LLM streaming buffer
        var error by remember { mutableStateOf<String?>(null) }
        var streamJob by remember { mutableStateOf<Job?>(null) }
        var ampJob by remember { mutableStateOf<Job?>(null) }
        var pendingStart by remember { mutableStateOf(false) }
        var recStartMs by remember { mutableStateOf(0L) }
        var durationSec by remember { mutableStateOf(0) }
        val amps = remember { mutableStateListOf<Float>() }

        val micPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) pendingStart = true
            else { error = tr("Se negó el permiso del micrófono.", "Microphone permission denied."); stage = Stage.ERROR }
        }

        /** Final step: the text goes straight into the field (or the clipboard as a fallback). */
        fun deliver(text: String) {
            // A blank result (e.g. cleanup ate everything) must not insert nothing silently.
            if (text.isBlank()) {
                error = tr("No se detectó texto para insertar. Inténtalo de nuevo.", "Nothing to insert. Try again.")
                stage = Stage.ERROR
                return
            }
            runCatching { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
            recordHistory(transcript, text, durationSec, edited = false,
                onDevice = settings?.sttProvider == "local")
            recordCorpus(text, text, edited = false)
            acceptVoice(text)
        }

        fun process(s: Settings, spoken: String) {
            transcript = spoken; output = ""; error = null
            val host = dictationHostPkg ?: OpenWisprAccessibilityService.lastHostPackage
            // App-context up front: drives code handling, the chat-period rule, and polish tone.
            // Apps the user assigned to a tone in "Tone by app" win over the built-in guess.
            val category = AppContext.categoryFor(host, spoken, appToneAssignments)
            dictationCategory = category
            val isCode = category == AppContext.Category.CODE
            val cleaned0 = if (s.deterministicCleanup)
                TextProcessor.process(spoken, TextProcessingConfig(), isCodeContext = isCode) else spoken
            // Chat/messaging: drop the trailing full stop Whisper adds to short one-liners — a
            // period on a single casual message reads as terse/formal, which people don't want.
            val cleaned = dropChatTerminalPeriod(cleaned0, category)
            // Guards: skip the LLM where it tends to
            // harm rather than help — polish off, very short input, or code/terminal context
            // (the deterministic stage already handles those; code only goes to the LLM at FULL).
            val wordCount = cleaned.trim().split(Regex("\\s+")).count { it.isNotBlank() }
            // Skip the LLM when the deterministic stage already produced line structure: once
            // ListFormatter has turned a dictated enumeration into a multi-line numbered list
            // (or "new line"/"new paragraph" into real breaks), handing it to the tiny on-device
            // model reflows it back onto one line. Keep the structured text verbatim.
            val deterministicStructure = cleaned.contains('\n')
            if (!s.llmPolishEnabled || wordCount < 4 || (isCode && s.polishLevel != PolishLevel.FULL) ||
                deterministicStructure) {
                deliver(cleaned); return
            }
            stage = Stage.CORRECTING
            val relaxed = RewriteEngine.hasSelfCorrection(spoken)
            streamJob = scope.launch {
                val tone = AppToneRepository(this@RewriteActivity).toneFor(category)
                // L3: at Medium/Full, show the model how THIS user likes their dictation cleaned,
                // using their closest past corrections as few-shot (skipped at Light to keep it cheap).
                val examples = if (s.polishLevel == PolishLevel.MEDIUM || s.polishLevel == PolishLevel.FULL)
                    withContext(Dispatchers.IO) {
                        fewShotBlock(CorrectionCorpus.similar(applicationContext, cleaned, category.key, k = 2))
                    } else ""
                // The cleanup level (light/medium/full) tunes how much the model may edit.
                val prompt = buildString {
                    append(Defaults.DICTATION_PROMPT)
                    if (s.polishLevel.instruction.isNotEmpty()) append("\n\n").append(s.polishLevel.instruction)
                    if (tone.isNotBlank()) append("\n\nTone for this app: ").append(tone)
                    if (examples.isNotEmpty()) append("\n\n").append(examples)
                }
                collectInto(
                    streamFor(s, prompt, cleaned),
                    { output += it },
                    // The polish is optional — never throw away a good transcript on its failure.
                    { _ -> deliver(cleaned) },
                    {
                        // Content-preservation guard: if the model dropped too much or ballooned
                        // with invented content, fall back to the deterministic text.
                        val polished = RewriteEngine.cleanOutput(output)
                        if (polished.isBlank() || !RewriteEngine.preservesContent(cleaned, polished, relaxed)) {
                            deliver(cleaned)
                        } else {
                            deliver(dropChatTerminalPeriod(polished, category))
                        }
                    },
                )
            }
        }

        /**
         * Transcribe a take and run it through the cleanup pipeline. Shared by the first
         * attempt and every retry — a retry must not be a second, subtly different code path.
         * [recId] names the durable copy; the cloud backend uploads that exact file.
         */
        fun runTranscription(s: Settings, samples: ShortArray, recId: String?) {
            error = null; output = ""
            recId?.let { PendingAudio.claim(it) }
            pendingId = recId
            stage = Stage.TRANSCRIBING
            BubbleService.instance?.showProcessing()
            streamJob = scope.launch {
                try {
                    val vocab = VocabRepository(this@RewriteActivity).get()
                    // Personal vocab biases decoding (B3) on both backends, then snaps
                    // remaining near-misses afterward (B2).
                    val bias = if (vocab.isEmpty()) null else VocabCorrector.biasPrompt(vocab)
                    val raw = if (s.sttProvider == "local") {
                        OnDeviceStt.transcribe(this@RewriteActivity, s, WavIo.toFloats(samples), bias)
                    } else {
                        SttEngine.transcribe(s, PendingAudio.wavFile(this@RewriteActivity, recId!!), bias)
                    }
                    val text = if (vocab.isEmpty()) raw else VocabCorrector.correct(raw, vocab)
                    if (text.isBlank()) {
                        error = tr("No se escuchó nada. Inténtalo de nuevo.", "Empty transcript. Try again.")
                        stage = Stage.ERROR
                    } else process(s, text)
                } catch (e: Exception) {
                    // Deliberately does not touch the saved recording. This is exactly the
                    // failure the write-ahead copy exists for; deleting it here is what used
                    // to turn a transient error into permanently lost words.
                    error = friendlyError(e); stage = Stage.ERROR
                }
            }
        }

        /** Re-run the saved audio, optionally on a different engine. */
        fun retryTranscription(s: Settings) {
            val id = pendingId ?: return
            val samples = PendingAudio.samples(this@RewriteActivity, id)
            if (samples == null) {
                error = tr("Esa grabación ya no está en este dispositivo.", "That recording is no longer on this device.")
                stage = Stage.ERROR
                return
            }
            runTranscription(s, samples, id)
        }

        fun stopRecording(s: Settings) {
            if (stage != Stage.RECORDING) return
            durationSec = ((System.currentTimeMillis() - recStartMs) / 1000L).toInt().coerceAtLeast(1)
            BubbleService.recordingStopper = null
            ampJob?.cancel()
            // Switch the bubble to its working animation the instant the take ends, so there's
            // no frozen "still listening" frame while the audio is saved.
            BubbleService.instance?.showProcessing()
            val samples = audioRecorder.stop()
            if (samples == null) {
                error = tr("No se captó audio. Toca y habla un poco más.", "Didn't catch any audio. Tap and speak a little longer.")
                stage = Stage.ERROR
                return
            }
            // Write-ahead: the take is on durable storage before the first transcription
            // attempt, on the on-device path too — that path used to hold the only copy in
            // RAM, so any failure or process death took the recording with it.
            val host = (dictationHostPkg ?: OpenWisprAccessibilityService.lastHostPackage).orEmpty()
            val rec = PendingAudio.begin(
                this@RewriteActivity, samples, durationSec,
                appPackage = host, appLabel = appLabel(host),
                sttProvider = s.sttProvider, sttModel = s.sttModel,
            )
            if (rec == null && s.sttProvider != "local") {
                // The cloud upload needs a file and we just failed to write one — say so
                // rather than proceeding as if a recoverable copy existed.
                error = tr("No se pudo guardar la grabación. Libera espacio e inténtalo de nuevo.",
                    "Couldn't save the recording. Free up some storage and try again.")
                stage = Stage.ERROR
                return
            }
            runTranscription(s, samples, rec?.id)
        }

        fun startRecording(s: Settings) {
            error = null; transcript = ""; output = ""; amps.clear()
            // "Keep history" off promises nothing is saved to disk. Clear anything a previous
            // session left behind (e.g. a crash mid-dictation) before this one writes its own.
            if (!DictationHistory.keepHistory(this@RewriteActivity)) {
                val ctx = applicationContext
                Thread { runCatching { PendingAudio.purgeAll(ctx) } }.start()
            }
            // A previous take (they hit "Record again") is no longer live. It keeps its audio
            // and stays retryable from Home; this take gets its own row.
            pendingId?.let { PendingAudio.release(it) }
            pendingId = null
            // Snapshot the target app now, while it still has focus, before our window or any
            // system window can steal it (otherwise the entry gets mislabelled, e.g. "System UI").
            dictationHostPkg = OpenWisprAccessibilityService.lastHostPackage
            recStartMs = System.currentTimeMillis()
            // Hold-to-talk never uses VAD: the finger lifting is the end of the take, and an
            // auto-stop firing on a mid-sentence pause would cut the user off while they are
            // still visibly holding the button down.
            val useVad = s.vadAutoStop && !pushToTalk
            try { audioRecorder.start(vadAutoStop = useVad, onAutoStop = { stopRecording(s) }) }
            catch (e: Exception) {
                error = e.message ?: tr("No se pudo iniciar el micrófono.", "Couldn't start the mic.")
                stage = Stage.ERROR
                return
            }
            stage = Stage.RECORDING
            BubbleService.instance?.showRecording()
            BubbleService.recordingStopper = { stopRecording(s) }
            // The release can beat us here: launching this activity takes long enough that a
            // quick press-and-let-go finishes before the recorder exists. BubbleService clears
            // the flag on release, so an already-lifted finger means stop now, not never.
            if (pushToTalk && !BubbleService.holdingToTalk) { stopRecording(s); return }
            ampJob?.cancel()
            ampJob = scope.launch {
                while (isActive && audioRecorder.isRecording) {
                    val raw = audioRecorder.amplitude()
                    BubbleService.instance?.showAmplitude(raw)
                    amps.add((raw / 14000f).coerceIn(0f, 1f))
                    if (amps.size > 56) amps.removeAt(0)
                    delay(55)
                }
            }
        }

        fun requestMicThenRecord(s: Settings) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) startRecording(s) else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        fun ensurePermissionThenRecord(s: Settings) {
            if (s.sttProvider == "local") {
                if (!OnDeviceStt.isReady(this, s.sttModel)) {
                    // Onboarding starts the model download and deliberately doesn't wait for
                    // it, so a first dictation legitimately lands mid-download. Sit on a
                    // spinner and start the moment it lands; only a genuinely stalled or
                    // failed download should reach the error below. Nudge the download too,
                    // in case it hasn't started yet (e.g. this is a resumed process).
                    if (OnDeviceStt.isParakeet(s.sttModel)) ParakeetModelManager.ensureDownloading(this)
                    error = null
                    stage = Stage.WAITING_MODEL
                    // No status bar any more, so say it once and let the bubble show it's working.
                    BubbleService.instance?.showProcessing()
                    android.widget.Toast.makeText(
                        this@RewriteActivity,
                        tr("Terminando de descargar el modelo de voz…", "Finishing the speech model download…"),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    scope.launch {
                        val deadline = System.currentTimeMillis() + MODEL_WAIT_TIMEOUT_MS
                        while (System.currentTimeMillis() < deadline) {
                            delay(500)
                            if (OnDeviceStt.isReady(this@RewriteActivity, s.sttModel)) {
                                requestMicThenRecord(s)
                                return@launch
                            }
                        }
                        error = tr("El modelo de voz no está descargado. Abre Ajustes → Voz → Descargar modelo.",
                            "On-device model not downloaded. Open Settings → Voice → Download model.")
                        stage = Stage.ERROR
                    }
                    return
                }
            } else if (!s.isSttConfigured) {
                error = tr("No hay clave de voz a texto. Abre los ajustes de VoiceFlow.",
                    "No speech-to-text key set. Open VoiceFlow settings.")
                stage = Stage.ERROR
                return
            }
            requestMicThenRecord(s)
        }

        fun onMicTap() {
            val s = settings ?: return
            when (stage) {
                Stage.RECORDING -> stopRecording(s)
                Stage.IDLE, Stage.ERROR -> ensurePermissionThenRecord(s)
                else -> {}
            }
        }

        /**
         * Leave without inserting. The recording, if any, stays on disk — retryable from Home —
         * unless [discardAudio] says the user threw this dictation away.
         */
        fun cancelAndFinish(discardAudio: Boolean = false) {
            streamJob?.cancel(); ampJob?.cancel()
            BubbleService.recordingStopper = null
            BubbleService.instance?.showIdle()
            audioRecorder.cancel()
            pendingId?.let { id ->
                val ctx = applicationContext
                pendingId = null
                if (discardAudio) Thread { runCatching { PendingAudio.discard(ctx, id) } }.start()
                else PendingAudio.release(id)
            }
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        LaunchedEffect(Unit) {
            val s = repo.get(); settings = s
            appToneAssignments = withContext(Dispatchers.IO) {
                AppToneRepository(this@RewriteActivity).appAssignments()
            }
            val retry = retryId
            if (retry != null) {
                // Opened from Home to re-run a recording an earlier attempt never finished.
                val rec = withContext(Dispatchers.IO) { PendingAudio.get(this@RewriteActivity, retry) }
                val saved = withContext(Dispatchers.IO) { PendingAudio.samples(this@RewriteActivity, retry) }
                if (rec == null || saved == null) {
                    error = tr("Esa grabación ya no está en este dispositivo.", "That recording is no longer on this device.")
                    stage = Stage.ERROR
                } else {
                    durationSec = rec.durationSec
                    dictationHostPkg = rec.appPackage.ifBlank { null }
                    runTranscription(s, saved, retry)
                }
            } else if (autoRecord) ensurePermissionThenRecord(s)
        }
        LaunchedEffect(pendingStart) {
            if (pendingStart) { pendingStart = false; settings?.let { startRecording(it) } }
        }
        // The bubble mirrors the stage: back to its resting look whenever nothing is in flight.
        LaunchedEffect(stage) {
            if (stage == Stage.ERROR || stage == Stage.IDLE) BubbleService.instance?.showIdle()
            if (stage == Stage.ERROR) runCatching { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
        }
        DisposableEffect(Unit) {
            onDispose {
                BubbleService.recordingStopper = null
                ampJob?.cancel()
                BubbleService.instance?.showIdle()
            }
        }

        Box(Modifier.fillMaxSize()) {
            // Tapping outside: stops a recording, closes an error. While the text is being
            // transcribed it does nothing — this window is invisible there, and a tap meant for
            // the app underneath must not throw the dictation away.
            Spacer(Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                when (stage) {
                    Stage.RECORDING -> onMicTap()
                    Stage.TRANSCRIBING, Stage.CORRECTING -> {}
                    else -> cancelAndFinish()
                }
            })
            Box(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 20.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                // No status bar while listening or transcribing: the bubble's animation is the
                // only feedback. Something appears here only when a dictation fails.
                AnimatedVisibility(
                    visible = stage == Stage.ERROR,
                    enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 3 },
                    exit = fadeOut(tween(160)),
                ) {
                    // The recording outlived the failure, so offer to re-run it before asking
                    // the user to say the whole thing again. Re-running the *same* on-device
                    // engine on the *same* samples fails identically, so a retry prefers the
                    // other downloaded engine when there is one.
                    val saved = pendingId
                    val alt = if (saved != null) settings?.let { altOnDeviceEngine(it) } else null
                    ErrorCard(
                        message = error ?: tr("Algo salió mal.", "Something went wrong."),
                        detail = when {
                            saved == null -> null
                            alt != null -> tr("Tu grabación está guardada en este dispositivo; no se perdió nada. Reintentar la procesa con ${alt.second}.",
                                "Your recording is saved on this device. Nothing was lost. Retry runs it again on ${alt.second}.")
                            else -> tr("Tu grabación está guardada en este dispositivo; no se perdió nada.",
                                "Your recording is saved on this device. Nothing was lost.")
                        },
                        onRetry = if (saved != null) {
                            { settings?.let { s -> retryTranscription(if (alt != null) s.copy(sttModel = alt.first) else s) } }
                        } else null,
                        recordLabel = if (saved != null) tr("Grabar de nuevo", "Record again") else tr("Intentar de nuevo", "Try again"),
                        onRecord = { onMicTap() },
                        onClose = { cancelAndFinish() },
                    )
                }
            }
        }
    }

    @Composable
    private fun ErrorCard(
        message: String,
        detail: String?,
        onRetry: (() -> Unit)?,
        recordLabel: String,
        onRecord: () -> Unit,
        onClose: () -> Unit,
    ) {
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
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text(tr("Cerrar", "Close")) }
                    if (onRetry != null) {
                        TextButton(onClick = onRetry) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Text("  " + tr("Reintentar", "Retry"))
                        }
                    }
                    TextButton(onClick = onRecord) {
                        Icon(Icons.Default.Mic, null, Modifier.size(18.dp)); Text("  $recordLabel")
                    }
                }
            }
        }
    }

    /** Collect a streaming Flow<String>, forwarding chunks/errors/completion. */
    private suspend fun collectInto(
        flow: kotlinx.coroutines.flow.Flow<String>,
        onChunk: (String) -> Unit,
        onError: (Throwable) -> Unit,
        onDone: () -> Unit,
    ) {
        var failed = false
        flow.catch { e -> failed = true; onError(e) }
            .onCompletion { cause -> if (cause == null && !failed) onDone() }
            .collect { chunk -> onChunk(chunk) }
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
