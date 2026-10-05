package com.voicerewriter

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.voicerewriter.textproc.AppContext
import com.voicerewriter.textproc.TextProcessingConfig
import com.voicerewriter.textproc.TextProcessor
import com.voicerewriter.textproc.VocabCorrector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The dictation itself — recording, transcription, cleanup, insertion — running in the app
 * process alongside [BubbleService], not inside an activity.
 *
 * It used to live in RewriteActivity, a see-through window covering the whole screen while
 * recording: any touch landed on it and ended the take, so the user couldn't scroll or switch
 * apps mid-dictation. Now RewriteActivity only exists for the instant it takes to start the
 * microphone (Android lets a *visible* app promote its service to a microphone foreground
 * service), then closes. From there the recording keeps running while the user does whatever
 * they like, and only a deliberate tap on the bubble ends it.
 *
 * Main-thread only.
 */
object Dictation {

    private const val TAG = "Dictation"

    enum class Phase { IDLE, RECORDING, PROCESSING }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase

    /** Bumps every time a dictation lands (inserted or on the clipboard). Onboarding watches it. */
    private val _delivered = MutableStateFlow(0)
    val delivered: StateFlow<Int> = _delivered

    val isBusy: Boolean get() = _phase.value != Phase.IDLE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var app: Context
    private var recorder: AudioRecorder? = null
    private var settings: Settings? = null
    private var ampJob: Job? = null
    private var work: Job? = null
    private var recStartMs = 0L
    private var durationSec = 0
    private var pendingId: String? = null
    private var hostPkg: String? = null
    private var category: AppContext.Category = AppContext.Category.GENERIC

    // ---------------- start / stop ----------------

    /**
     * Start recording. Call while an activity of ours is visible (that is what allows the
     * microphone to keep working once the user leaves it), with the mic permission granted and
     * the speech model ready. Returns an error message, or null when recording has started.
     */
    fun start(ctx: Context, s: Settings, pushToTalk: Boolean): String? {
        if (isBusy) return null
        app = ctx.applicationContext
        val bubble = BubbleService.instance
            ?: return tr("La burbuja no está activa. Abre VoiceFlow y permite «Mostrar sobre otras apps».",
                "The bubble isn't running. Open VoiceFlow and allow “Display over other apps”.")
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return tr("Se necesita el permiso del micrófono.", "Microphone permission needed.")
        }
        // Must happen now, while our activity is on screen: from here on the microphone keeps
        // working even when the user moves to other apps.
        try {
            bubble.enterMicForeground()
        } catch (e: Exception) {
            Log.e(TAG, "mic foreground failed", e)
            return tr("Android no permitió usar el micrófono en segundo plano.", "Android didn't allow the microphone in the background.")
        }
        settings = s
        // "Keep history" off promises nothing is saved to disk. Clear anything a previous
        // session left behind (e.g. a crash mid-dictation) before this one writes its own.
        if (!DictationHistory.keepHistory(app)) {
            val c = app
            Thread { runCatching { PendingAudio.purgeAll(c) } }.start()
        }
        val rec = AudioRecorder(app)
        // Auto-stop on a pause only if the user turned it on (off by default in VoiceFlow: the
        // tap on the bubble ends the dictation). Never with hold-to-talk, where the release does.
        val useVad = s.vadAutoStop && !pushToTalk
        try {
            rec.start(vadAutoStop = useVad, onAutoStop = { stop() })
        } catch (e: Exception) {
            bubble.exitMicForeground()
            return e.message ?: tr("No se pudo iniciar el micrófono.", "Couldn't start the mic.")
        }
        recorder = rec
        recStartMs = System.currentTimeMillis()
        _phase.value = Phase.RECORDING
        bubble.showRecording()
        BubbleService.recordingStopper = { stop() }
        ampJob = scope.launch {
            while (isActive && rec.isRecording) {
                BubbleService.instance?.showAmplitude(rec.amplitude())
                delay(40)
            }
        }
        // A quick press-and-release can finish before the recorder exists: the bubble clears the
        // flag on release, so an already-lifted finger means stop now, not never.
        if (pushToTalk && !BubbleService.holdingToTalk) stop()
        return null
    }

    /** End the take (the bubble's tap, a hold-to-talk release, or auto-stop) and process it. */
    fun stop() {
        if (_phase.value != Phase.RECORDING) return
        val s = settings ?: return
        BubbleService.recordingStopper = null
        ampJob?.cancel()
        _phase.value = Phase.PROCESSING
        BubbleService.instance?.showProcessing()
        durationSec = ((System.currentTimeMillis() - recStartMs) / 1000L).toInt().coerceAtLeast(1)
        val samples = recorder?.stop()
        recorder = null
        // Dictation is for wherever the user is when they finish — they may have moved apps.
        hostPkg = OpenWisprAccessibilityService.lastHostPackage
        if (samples == null) {
            fail(tr("No se captó audio. Toca la burbuja y habla un poco más.", "Didn't catch any audio. Tap the bubble and speak a little longer."))
            return
        }
        // Write-ahead: the take is on durable storage before the first transcription attempt.
        val host = hostPkg.orEmpty()
        val saved = PendingAudio.begin(
            app, samples, durationSec,
            appPackage = host, appLabel = appLabel(host),
            sttProvider = s.sttProvider, sttModel = s.sttModel,
        )
        if (saved == null && s.sttProvider != "local") {
            fail(tr("No se pudo guardar la grabación. Libera espacio e inténtalo de nuevo.",
                "Couldn't save the recording. Free up some storage and try again."))
            return
        }
        transcribe(s, samples, saved?.id)
    }

    /** Throw the take away (e.g. the service is going down). */
    fun cancel() {
        ampJob?.cancel(); work?.cancel()
        BubbleService.recordingStopper = null
        recorder?.cancel(); recorder = null
        pendingId?.let { PendingAudio.release(it) }
        pendingId = null
        _phase.value = Phase.IDLE
    }

    /** Re-run a saved recording (from Home's "Retry"). */
    fun retry(ctx: Context, id: String, s: Settings) {
        if (isBusy) return
        app = ctx.applicationContext
        settings = s
        val rec = PendingAudio.get(app, id)
        val samples = PendingAudio.samples(app, id)
        if (rec == null || samples == null) {
            toast(tr("Esa grabación ya no está en este dispositivo.", "That recording is no longer on this device."))
            return
        }
        durationSec = rec.durationSec
        hostPkg = rec.appPackage.ifBlank { null }
        _phase.value = Phase.PROCESSING
        BubbleService.instance?.showProcessing()
        transcribe(s, samples, id)
    }

    // ---------------- pipeline ----------------

    private fun transcribe(s: Settings, samples: ShortArray, recId: String?) {
        recId?.let { PendingAudio.claim(it) }
        pendingId = recId
        work = scope.launch {
            try {
                val assignments = withContext(Dispatchers.IO) { AppToneRepository(app).appAssignments() }
                val vocab = VocabRepository(app).get()
                // Personal vocab biases decoding on both backends, then snaps remaining
                // near-misses afterwards.
                val bias = if (vocab.isEmpty()) null else VocabCorrector.biasPrompt(vocab)
                val raw = if (s.sttProvider == "local") {
                    OnDeviceStt.transcribe(app, s, WavIo.toFloats(samples), bias)
                } else {
                    SttEngine.transcribe(s, PendingAudio.wavFile(app, recId!!), bias)
                }
                val text = if (vocab.isEmpty()) raw else VocabCorrector.correct(raw, vocab)
                if (text.isBlank()) fail(tr("No se escuchó nada. Inténtalo de nuevo.", "Empty transcript. Try again."))
                else process(s, text, assignments)
            } catch (e: Exception) {
                // The saved recording is left alone: this is exactly the failure it exists for.
                fail(friendlyError(e))
            }
        }
    }

    private suspend fun process(s: Settings, spoken: String, assignments: Map<String, String>) {
        // App context drives code handling, the chat-period rule and the polish tone. Apps the
        // user pinned to a tone in "Tone by app" win over the built-in guess.
        category = AppContext.categoryFor(hostPkg, spoken, assignments)
        val isCode = category == AppContext.Category.CODE
        val cleaned0 = if (s.deterministicCleanup)
            TextProcessor.process(spoken, TextProcessingConfig(), isCodeContext = isCode) else spoken
        val cleaned = dropChatTerminalPeriod(cleaned0, category)
        val wordCount = cleaned.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        // Skip the model where it tends to harm rather than help (polish off, very short input,
        // code, or text the deterministic stage already structured into lines).
        if (!s.llmPolishEnabled || wordCount < 4 || (isCode && s.polishLevel != PolishLevel.FULL) ||
            cleaned.contains('\n')) {
            deliver(spoken, cleaned); return
        }
        val relaxed = RewriteEngine.hasSelfCorrection(spoken)
        val tone = AppToneRepository(app).toneFor(category)
        val examples = if (s.polishLevel == PolishLevel.MEDIUM || s.polishLevel == PolishLevel.FULL)
            withContext(Dispatchers.IO) {
                fewShotBlock(CorrectionCorpus.similar(app, cleaned, category.key, k = 2))
            } else ""
        val prompt = buildString {
            append(Defaults.DICTATION_PROMPT)
            if (s.polishLevel.instruction.isNotEmpty()) append("\n\n").append(s.polishLevel.instruction)
            if (tone.isNotBlank()) append("\n\nTone for this app: ").append(tone)
            if (examples.isNotEmpty()) append("\n\n").append(examples)
        }
        var output = ""
        var failed = false
        val stream = if (s.provider == "local") LocalLlmEngine.streamWithPrompt(app, s, prompt, cleaned)
        else RewriteEngine.streamWithPrompt(s, prompt, cleaned)
        stream.catch { failed = true }.collect { output += it }
        // The polish is optional — never throw away a good transcript on its failure, and fall
        // back to the deterministic text if the model dropped or invented content.
        val polished = RewriteEngine.cleanOutput(output)
        if (failed || polished.isBlank() || !RewriteEngine.preservesContent(cleaned, polished, relaxed)) {
            deliver(spoken, cleaned)
        } else {
            deliver(spoken, dropChatTerminalPeriod(polished, category))
        }
    }

    /** Final step: straight into the field, or onto the clipboard as the fallback. */
    private fun deliver(spoken: String, text: String) {
        if (text.isBlank()) {
            fail(tr("No se detectó texto para insertar. Inténtalo de nuevo.", "Nothing to insert. Try again."))
            return
        }
        val enqueued = OpenWisprAccessibilityService.enqueueInsert(text)
        if (!enqueued) setClipboard(text)
        LastDictation.set(app, text)
        recordHistory(spoken, text)
        recordCorpus(text)
        pendingId?.let { id ->
            val c = app
            pendingId = null
            Thread { runCatching { PendingAudio.settle(c, id, text) } }.start()
        }
        postFixNotification()
        finishUp()
        _delivered.value = _delivered.value + 1
    }

    private fun fail(message: String) {
        val saved = pendingId != null
        pendingId?.let { PendingAudio.release(it) }
        pendingId = null
        toast(
            if (saved) message + " " + tr("La grabación quedó guardada en Inicio para reintentar.",
                "The recording is saved on Home so you can retry.")
            else message,
        )
        finishUp()
    }

    private fun finishUp() {
        BubbleService.recordingStopper = null
        _phase.value = Phase.IDLE
        BubbleService.instance?.showIdle()
        BubbleService.instance?.exitMicForeground()
    }

    // ---------------- helpers ----------------

    private fun toast(msg: String) = main.post { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }

    private fun setClipboard(text: String) {
        val cb = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("dictation", text))
    }

    private fun appLabel(pkg: String): String {
        if (pkg.isBlank()) return tr("Dictado", "Dictation")
        return runCatching {
            val pm = app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() })
    }

    /**
     * In chat/messaging apps, strip the single trailing full stop recognizers add to a short
     * one-liner ("On my way." -> "On my way"). Never touches "!"/"?", ellipses, or text with
     * more than one sentence.
     */
    fun dropChatTerminalPeriod(text: String, category: AppContext.Category): String {
        if (category != AppContext.Category.CHAT && category != AppContext.Category.SOCIAL) return text
        val t = text.trimEnd()
        if (!t.endsWith(".") || t.endsWith("..")) return text
        val body = t.dropLast(1).trimEnd()
        if (body.isEmpty()) return text
        if (body.any { it == '.' || it == '!' || it == '?' || it == '\n' }) return text
        return body
    }

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

    private fun recordHistory(before: String, after: String) {
        val pkg = hostPkg.orEmpty()
        val entry = DictationEntry(
            id = "${System.currentTimeMillis()}-${after.hashCode() and 0xffff}",
            timestamp = System.currentTimeMillis(),
            appPackage = pkg,
            appLabel = appLabel(pkg),
            durationSec = durationSec,
            words = after.trim().split(Regex("\\s+")).count { it.isNotBlank() },
            accepted = true,
            onDevice = settings?.sttProvider == "local",
            before = before,
            after = after,
        )
        val c = app
        Thread { runCatching { DictationHistory.record(c, entry) } }.start()
    }

    private fun recordCorpus(text: String) {
        val c = app
        val cat = category.key
        Thread {
            runCatching {
                CorrectionCorpus.record(c, CorrectionSample(
                    ts = System.currentTimeMillis(), category = cat,
                    cleaned = text, final = text, edited = false,
                ))
            }
        }.start()
    }

    /** Quiet notification after a dictation: tap to teach the dictionary a mis-heard word. */
    private fun postFixNotification() {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val channelId = "dictation_fix"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ch = android.app.NotificationChannel(
                channelId, tr("Correcciones de dictado", "Dictation corrections"), android.app.NotificationManager.IMPORTANCE_LOW,
            ).apply { description = tr("Toca para corregir una palabra mal escuchada después de dictar.", "Tap to fix a mis-heard word after dictation.") }
            nm.createNotificationChannel(ch)
        }
        val pi = android.app.PendingIntent.getActivity(
            app, 0, Intent(app, FixDictationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = androidx.core.app.NotificationCompat.Builder(app, channelId)
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

    /** Map a raw exception to plain, actionable copy. */
    fun friendlyError(e: Throwable): String {
        val msg = e.message ?: e.toString()
        val low = msg.lowercase()
        return when {
            e is java.net.UnknownHostException || e is java.net.ConnectException ||
                "unable to resolve host" in low || "failed to connect" in low ->
                tr("Sin conexión. Revisa tu red o cambia a \"en el teléfono\" en Ajustes.", "No connection. Check your network, or switch to on-device in Settings.")
            e is java.net.SocketTimeoutException || "timeout" in low || "timed out" in low ->
                tr("Tardó demasiado. Inténtalo de nuevo.", "That took too long. Try again.")
            "401" in low || "403" in low || "unauthor" in low || "api key" in low || "invalid key" in low ->
                tr("Revisa tu clave de API en Ajustes.", "Check your API key in Settings.")
            else -> msg
        }
    }
}
