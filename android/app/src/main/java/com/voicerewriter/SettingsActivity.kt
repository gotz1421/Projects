package com.voicerewriter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.voicerewriter.ui.FlowWhite
import com.voicerewriter.ui.MonoEyebrow
import com.voicerewriter.ui.VoiceFlowTheme
import com.voicerewriter.ui.FlowBrush
import com.voicerewriter.ui.Wordmark
import kotlinx.coroutines.launch

/**
 * Settings, redesigned to the OpenWispr Settings spec: a sectioned, on-device-first
 * surface — setup status, Voice (engine segment + per-model download manager, Parakeet
 * recommended on top), Cleanup & Polish (smart cleanup + AI-polish level + advanced
 * polish model), Bubble, Privacy, Personalization, Reliability, General. Changes persist
 * immediately. All prior capabilities (cloud STT/LLM providers, voice profile, etc.) are
 * preserved under the relevant sections / Advanced.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repo = SettingsRepository(applicationContext)
        setContent {
            VoiceFlowTheme {
                SettingsScreen(repo) { lifecycleScope.launch { it() } }
            }
        }
    }
}

@Composable
private fun SettingsScreen(repo: SettingsRepository, launch: (suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    var loaded by remember { mutableStateOf(false) }

    // permissions / setup
    var bubbleOn by remember { mutableStateOf(BubbleService.isRunning || BubblePrefs.enabled(context)) }
    var a11yEnabled by remember { mutableStateOf(false) }
    var showA11yConsent by remember { mutableStateOf(false) }
    var notifOn by remember { mutableStateOf(true) }
    var micGranted by remember { mutableStateOf(false) }

    // LLM (rewrite / polish model)
    var provider by remember { mutableStateOf(Defaults.DEFAULT_PROVIDER) }
    var model by remember { mutableStateOf(Defaults.DEFAULT_MODEL) }
    var customEndpoint by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var voice by remember { mutableStateOf("") }
    var antiAI by remember { mutableStateOf(true) }
    var temperature by remember { mutableFloatStateOf(Defaults.DEFAULT_TEMPERATURE.toFloat()) }

    // STT
    var sttProvider by remember { mutableStateOf(Defaults.DEFAULT_STT_PROVIDER) }
    var sttEndpoint by remember { mutableStateOf("") }
    var sttKey by remember { mutableStateOf("") }
    var sttModel by remember { mutableStateOf("") }
    var defaultMode by remember { mutableStateOf(Defaults.MODE_DICTATE) }
    var deterministicCleanup by remember { mutableStateOf(true) }
    var polishLevel by remember { mutableStateOf(PolishLevel.OFF) }
    var vadAutoStop by remember { mutableStateOf(true) }
    var parakeetHotwordsExperimental by remember { mutableStateOf(false) }
    var bubbleOnlyOnFields by remember { mutableStateOf(true) }
    var bubbleOpacity by remember { mutableStateOf(BubblePrefs.opacity(context)) }
    var snoozeMinutes by remember { mutableStateOf(BubblePrefs.snoozeMinutes(context)) }
    var keepHistory by remember { mutableStateOf(DictationHistory.keepHistory(context)) }
    var audioKeepDays by remember { mutableStateOf(PendingAudio.keepDays(context)) }

    var advancedOpen by remember { mutableStateOf(false) }
    var cloudLlmOpen by remember { mutableStateOf(false) }

    // download tracking (a single in-flight download at a time)
    var dlId by remember { mutableStateOf<String?>(null) }
    var dlProgress by remember { mutableFloatStateOf(0f) }
    var modelsRev by remember { mutableStateOf(0) } // bump to recompute readiness after a download

    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (android.provider.Settings.canDrawOverlays(context)) { SetupUtils.startBubble(context); bubbleOn = true }
        else Toast.makeText(context, tr("Se necesita el permiso para mostrar la burbuja", "Permission needed to show the bubble"), Toast.LENGTH_SHORT).show()
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> notifOn = granted }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> micGranted = granted }
    val a11yLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        a11yEnabled = SetupUtils.accessibilityEnabled(context)
    }
    fun openA11ySettings() = a11yLauncher.launch(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun enableBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifOn) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (!android.provider.Settings.canDrawOverlays(context)) {
            overlayLauncher.launch(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        } else { SetupUtils.startBubble(context); bubbleOn = true }
    }

    LaunchedEffect(Unit) {
        val s = repo.get()
        provider = s.provider; model = s.model; customEndpoint = s.customEndpoint; apiKey = s.apiKey
        voice = s.voice; antiAI = s.antiAI; temperature = s.temperature.toFloat()
        sttProvider = s.sttProvider; sttEndpoint = s.sttEndpoint; sttKey = s.sttKey; sttModel = s.sttModel
        defaultMode = s.defaultMode
        deterministicCleanup = s.deterministicCleanup; polishLevel = s.polishLevel
        vadAutoStop = s.vadAutoStop; bubbleOnlyOnFields = s.bubbleOnlyOnFields
        parakeetHotwordsExperimental = s.parakeetHotwordsExperimental
        a11yEnabled = SetupUtils.accessibilityEnabled(context)
        notifOn = SetupUtils.notificationsGranted(context)
        micGranted = SetupUtils.micGranted(context)
        loaded = true
    }

    // Re-check the permission rows every time the screen resumes. None of these grants deliver a
    // result callback (see SetupUtils' header), and accessibility in particular gets revoked out
    // from under the app by OEM battery managers and Play Protect's restricted settings — so a
    // row read once at first composition goes stale and tells the user auto-insert is on when it
    // isn't. This is also where the bubble self-heals if its service was killed.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                a11yEnabled = SetupUtils.accessibilityEnabled(context)
                notifOn = SetupUtils.notificationsGranted(context)
                micGranted = SetupUtils.micGranted(context)
                // The bubble is how VoiceFlow is used, so it's always on once overlays are allowed.
                val wanted = SetupUtils.canDrawOverlays(context)
                if (!BubbleService.isRunning && wanted) SetupUtils.startBubble(context)
                // `isRunning` only flips in the service's onCreate, which hasn't happened yet on
                // the line after startForegroundService — so trust the intent we just acted on
                // rather than reading back a flag that is still false.
                bubbleOn = BubbleService.isRunning || wanted
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    if (!loaded) return

    // Device-fit inputs for the on-device model list below. Computed once per screen: reading
    // ActivityManager and free space on every recomposition would be wasteful, and neither
    // answer changes while Settings is open.
    val fit = remember { DeviceFit.plan(context) }
    val recommendedId = fit.sttModel
    val deviceHint = remember { DeviceFit.recommendationLabel(context) }

    // Issue #53: a downloaded model you no longer want. Holds what's pending while the
    // confirmation is up; null when nothing is.
    var pendingDelete by remember { mutableStateOf<DeletableModel?>(null) }

    fun snapshot() = Settings(
        provider = provider, model = model.trim(), customEndpoint = customEndpoint.trim(),
        apiKey = apiKey.trim(), voice = voice, antiAI = antiAI, temperature = temperature.toDouble(),
        sttProvider = sttProvider, sttEndpoint = sttEndpoint.trim(), sttKey = sttKey.trim(),
        sttModel = sttModel.trim(), defaultMode = defaultMode,
        deterministicCleanup = deterministicCleanup, polishLevel = polishLevel,
        vadAutoStop = vadAutoStop, bubbleOnlyOnFields = bubbleOnlyOnFields,
        hasCompletedOnboarding = true,
        parakeetHotwordsExperimental = parakeetHotwordsExperimental,
    )

    // Persist silently on every change (the design saves immediately).
    fun persist() = launch {
        repo.save(snapshot())
        BubbleService.instance?.refreshGating()
    }

    fun sttModelState(id: String): String = when {
        sttModel == id -> "active"
        dlId == id -> "downloading"
        OnDeviceStt.isReady(context, id) -> "downloaded"
        else -> "idle"
    }
    fun llmModelState(id: String): String = when {
        provider == "local" && model == id -> "active"
        dlId == id -> "downloading"
        LlmModelManager.isReady(context, id) -> "downloaded"
        else -> "idle"
    }

    fun downloadStt(id: String) {
        dlId = id; dlProgress = 0f
        launch {
            try {
                if (OnDeviceStt.isParakeet(id)) ParakeetModelManager.download(context) { p -> dlProgress = p }
                else WhisperModelManager.download(context, id) { p -> dlProgress = p }
                sttModel = id; dlId = null; modelsRev++; persist()
            } catch (e: Exception) {
                dlId = null; Toast.makeText(context, e.message ?: tr("Falló la descarga", "Download failed"), Toast.LENGTH_LONG).show()
            }
        }
    }
    /**
     * Delete a downloaded model and refresh the list. Never called for the active model — the
     * row only offers it on a downloaded, inactive one — so nothing the app is about to load
     * can disappear underneath it.
     */
    fun deleteModel(target: DeletableModel) {
        launch {
            val freed = when (target.kind) {
                ModelKind.STT ->
                    if (OnDeviceStt.isParakeet(target.id)) ParakeetModelManager.delete(context)
                    else WhisperModelManager.delete(context, target.id)
                ModelKind.LLM -> LlmModelManager.delete(context, target.id)
            }
            modelsRev++
            val mb = freed / (1024 * 1024)
            Toast.makeText(
                context,
                if (mb > 0) tr("Se eliminó ${target.label}: se liberaron ${mb}MB", "Deleted ${target.label} — ${mb}MB freed") else tr("Se eliminó ${target.label}", "Deleted ${target.label}"),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun downloadLlm(id: String) {
        dlId = id; dlProgress = 0f
        launch {
            try {
                LlmModelManager.download(context, id) { p -> dlProgress = p }
                provider = "local"; model = id; dlId = null; modelsRev++; persist()
            } catch (e: Exception) {
                dlId = null; Toast.makeText(context, e.message ?: tr("Falló la descarga", "Download failed"), Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
        HeroHeader()

        Column(
            Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            // ---------------- SETUP STATUS ----------------
            val setupComplete = bubbleOn && a11yEnabled && micGranted
            SetupStatusCard(setupComplete) {
                if (!setupComplete) {
                    StatusRow(tr("Micrófono", "Microphone"), if (micGranted) tr("Activado", "On") else tr("Necesario para escucharte", "Needed to hear you"), micGranted) {
                        if (!micGranted) PillButton(tr("Activar", "Enable")) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                    }
                    StatusRow(tr("Burbuja flotante", "Floating bubble"), if (bubbleOn) tr("Activada", "On") else tr("Toca para hablar sobre cualquier app", "Tap-to-talk over any app"), bubbleOn) {
                        if (!bubbleOn) PillButton(tr("Activar", "Enable")) { enableBubble() }
                    }
                    StatusRow(tr("Inserción automática", "Auto-insert"), if (a11yEnabled) tr("Activada", "On") else tr("Escribe el texto en el campo donde estás", "Types text into the field you're in"), a11yEnabled) {
                        PillButton(if (a11yEnabled) tr("Administrar", "Manage") else tr("Activar", "Enable")) {
                            // Enabling is a first-time grant → show the required disclosure first.
                            // "Manage" (already enabled) goes straight to system settings.
                            if (a11yEnabled) openA11ySettings() else showA11yConsent = true
                        }
                    }
                }
            }

            pendingDelete?.let { target ->
                DeleteModelDialog(
                    target = target,
                    onConfirm = { deleteModel(target); pendingDelete = null },
                    onDismiss = { pendingDelete = null },
                )
            }

            if (showA11yConsent) {
                AccessibilityConsentDialog(
                    onConfirm = {
                        showA11yConsent = false
                        AccessibilityConsent.record(context)
                        // The guide walks through Android's "restricted settings" for APK installs.
                        a11yLauncher.launch(AccessibilityGuideActivity.intent(context))
                    },
                    onDismiss = { showA11yConsent = false },
                )
            }

            // ---------------- LANGUAGE ----------------
            // Spanish by default; the whole app (screens, bubble notification, tones) follows this.
            Section(tr("Idioma", "Language")) {
                Card {
                    Padded {
                        Label(tr("Idioma de la app", "App language"))
                        Spacer(Modifier.height(12.dp))
                        Segment(
                            options = listOf(Lang.ES to "Español", Lang.EN to "English"),
                            selected = Lang.code,
                            onSelect = { code ->
                                Lang.set(context, code)
                                // The bubble's notification text is built once; rebuild it in the new language.
                                BubbleService.instance?.refreshNotification()
                            },
                        )
                    }
                }
            }

            // ---------------- VOICE · TRANSCRIPTION ----------------
            Section(tr("Voz · transcripción", "Voice · transcription")) {
                Card {
                    Padded {
                        Label(tr("Motor", "Engine"))
                        Spacer(Modifier.height(12.dp))
                        Segment(
                            options = listOf("local" to tr("En el teléfono", "On-device"), "groq" to "Groq", "openai" to "OpenAI", "custom" to tr("Otro", "Custom")),
                            selected = sttProvider,
                            onSelect = {
                                sttProvider = it
                                if (it != "custom" && it != "local") {
                                    val d = Defaults.STT_PROVIDERS[it]?.defaultModel.orEmpty()
                                    if (d.isNotEmpty()) sttModel = d
                                }
                                persist()
                            },
                        )
                    }
                    if (sttProvider == "local") {
                        Divider()
                        Padded {
                            Text(tr("Los modelos se descargan una vez y luego funcionan sin internet.", "Models download once, then run fully offline."),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            // Which model suits *this* phone, not which is biggest. The list
                            // below shows every option regardless — this is the hint that keeps
                            // someone on a 3GB device from picking the 631MB one and hitting an
                            // out-of-memory failure they have no way to diagnose.
                            Text(deviceHint,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            modelsRev // read so this recomposes after a download finishes
                            sttModelOptions(recommendedId).forEach { m ->
                                ModelRow(
                                    name = m.name, meta = m.meta, recommended = m.recommended,
                                    state = sttModelState(m.id), progress = dlProgress,
                                    onGet = { downloadStt(m.id) }, onUse = { sttModel = m.id; persist() },
                                    onDelete = {
                                        pendingDelete = DeletableModel(ModelKind.STT, m.id, m.name, m.meta)
                                    },
                                )
                                Spacer(Modifier.height(10.dp))
                            }
                        }
                    } else {
                        Divider()
                        Padded {
                            Label(tr("Clave de API", "API key"))
                            Spacer(Modifier.height(8.dp))
                            KeyField(sttKey, Defaults.STT_PROVIDERS[sttProvider]?.let { keyPlaceholder(sttProvider) } ?: "key") { sttKey = it; persist() }
                            if (sttProvider == "custom") {
                                Spacer(Modifier.height(10.dp))
                                KeyField(sttEndpoint, tr("URL del endpoint de transcripción", "Transcription endpoint URL")) { sttEndpoint = it; persist() }
                                Spacer(Modifier.height(10.dp))
                                KeyField(sttModel, tr("ID del modelo", "Model id")) { sttModel = it; persist() }
                            }
                            Spacer(Modifier.height(10.dp))
                            InfoNote(buildString {
                                append(tr("El audio se envía a ", "Audio is sent to "))
                                append(if (sttProvider == "groq") "Groq" else if (sttProvider == "openai") "OpenAI" else tr("tu proveedor", "your provider"))
                                append(tr(" para transcribirlo.", " for transcription."))
                            }) { sttProvider = "local"; persist() }
                        }
                    }
                    Divider()
                    ToggleRow(tr("Detener al hacer pausa", "Auto-stop on pause"), tr("Termina la grabación cuando dejas de hablar", "End recording when you stop talking"), vadAutoStop) { vadAutoStop = it; persist() }
                    if (sttProvider == "local" && OnDeviceStt.isParakeet(OnDeviceStt.resolveModel(sttModel))) {
                        Divider()
                        ToggleRow(
                            tr("Decodificación con tu diccionario (experimental)", "Vocab-biased decoding (experimental)"),
                            tr("Intenta acertar las palabras de tu diccionario durante la transcripción, no solo después. " +
                                "Usa un modo con un error conocido que a veces devuelve texto vacío o incorrecto. " +
                                "Déjalo apagado salvo que lo estés probando.",
                                "Tries harder to hit your personal dictionary during transcription, not just after. " +
                                "Uses a decode mode with a known upstream bug that occasionally returns blank or " +
                                "wrong text. Leave off unless you're testing it."),
                            parakeetHotwordsExperimental,
                        ) { parakeetHotwordsExperimental = it; persist() }
                    }
                }
            }

            // ---------------- CLEANUP & POLISH ----------------
            Section(tr("Limpieza y pulido", "Cleanup & polish")) {
                Card {
                    ToggleRow(tr("Limpieza inteligente", "Smart cleanup"), tr("Muletillas, puntuación, números, correcciones · en el teléfono, al instante", "Fillers, punctuation, numbers, backtracking · on-device, instant"), deterministicCleanup) { deterministicCleanup = it; persist() }
                    Divider()
                    Padded {
                        Text(tr("Pulir con IA", "Polish with AI"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(tr("Un modelo opcional mejora el texto ya limpio.", "An optional model refines the cleaned text."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Segment(
                            options = PolishLevel.entries.map { it.name.lowercase() to it.label },
                            selected = polishLevel.name.lowercase(),
                            onSelect = { sel -> PolishLevel.entries.firstOrNull { it.name.lowercase() == sel }?.let { polishLevel = it; persist() } },
                        )
                        Spacer(Modifier.height(11.dp))
                        Text(polishLevel.blurb, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text(tr("El pulido siempre respeta tus palabras y su sentido. Si se desvía, se usa el texto limpio.", "Polish always keeps your words and meaning. It falls back to the clean text if it strays."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)

                        if (polishLevel != PolishLevel.OFF) {
                            Spacer(Modifier.height(14.dp))
                            DisclosureHeader(tr("Modelo de pulido avanzado", "Advanced polish model"), advancedOpen) { advancedOpen = !advancedOpen }
                            AnimatedVisibility(visible = advancedOpen) {
                                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Label(tr("Modelo de pulido", "Polish model"))
                                    modelsRev
                                    LlmModelManager.MODELS.forEach { m ->
                                        ModelRow(
                                            name = m.label, meta = m.sizeLabel, recommended = m.recommended,
                                            state = llmModelState(m.id), progress = dlProgress,
                                            onGet = { downloadLlm(m.id) }, onUse = { provider = "local"; model = m.id; persist() },
                                            onDelete = {
                                                pendingDelete = DeletableModel(ModelKind.LLM, m.id, m.label, m.sizeLabel)
                                            },
                                        )
                                    }
                                    Column {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(tr("Creatividad", "Creativity"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                            Text("%.1f".format(temperature), style = MonoEyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Slider(value = temperature, onValueChange = { temperature = it }, onValueChangeFinished = { persist() }, valueRange = 0f..1f)
                                    }
                                    ToggleRowBare(tr("Evitar frases de IA", "Anti-AI phrasing"), antiAI) { antiAI = it; persist() }
                                    DisclosureHeader(tr("Usar un modelo en la nube", "Use a cloud model instead"), cloudLlmOpen) { cloudLlmOpen = !cloudLlmOpen }
                                    AnimatedVisibility(visible = cloudLlmOpen) {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Segment(
                                                options = Defaults.PROVIDERS.values.filter { it.id != "local" }.map { it.id to it.label },
                                                selected = if (provider == "local") "" else provider,
                                                onSelect = { p -> provider = p; Defaults.PROVIDERS[p]?.defaultModel?.takeIf { it.isNotEmpty() }?.let { model = it }; persist() },
                                            )
                                            if (provider != "local") {
                                                if (provider == "custom") KeyField(customEndpoint, tr("URL del endpoint", "Endpoint URL")) { customEndpoint = it; persist() }
                                                KeyField(model, tr("ID del modelo", "Model id")) { model = it; persist() }
                                                KeyField(apiKey, tr("Clave de API", "API key")) { apiKey = it; persist() }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---------------- BUBBLE ----------------
            Section(tr("Burbuja", "Bubble")) {
                Card {
                    // Field gating is driven by the accessibility service (BubbleService.gateActive
                    // needs it to know what's focused). With the grant revoked the toggle keeps
                    // reading "on" while the bubble is in fact always visible, which looks like the
                    // setting broke. Say so instead.
                    ToggleRow(
                        tr("Solo al escribir", "Only when typing"),
                        if (bubbleOnlyOnFields && !a11yEnabled) tr("Requiere la inserción automática. La burbuja se queda visible hasta que la actives.", "Needs auto-insert. The bubble stays visible until you turn it back on.")
                        else if (bubbleOnlyOnFields) tr("Aparece cada vez que se abre el teclado", "Appears whenever the keyboard opens")
                        else tr("Siempre visible en pantalla", "Always on screen"),
                        bubbleOnlyOnFields,
                    ) { bubbleOnlyOnFields = it; persist() }
                    Divider()
                    // Idle transparency. Live-applied so the user sees the bubble change as they
                    // drag; it still goes fully opaque while touched or recording.
                    Padded {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(tr("Opacidad de la burbuja", "Bubble opacity"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text("${(bubbleOpacity * 100).toInt()}%", style = MonoEyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Slider(
                            value = bubbleOpacity,
                            onValueChange = {
                                bubbleOpacity = it
                                BubblePrefs.setOpacity(context, it)
                                BubbleService.instance?.refreshOpacity()
                            },
                            valueRange = BubblePrefs.MIN_OPACITY..1f,
                        )
                    }
                    Divider()
                    // Dropping the bubble on "Zzz" (bottom of the screen) puts it to sleep.
                    Padded {
                        Label(tr("Pausa con «Zzz»", "“Zzz” nap"))
                        Text(
                            tr("Arrastra la burbuja abajo al centro y suéltala en «Zzz» para ocultarla un rato, aunque escribas.",
                                "Drag the bubble to the bottom center and drop it on “Zzz” to hide it for a while, even while typing."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(11.dp))
                        Segment(
                            options = BubblePrefs.SNOOZE_CHOICES.map { it.toString() to "$it min" },
                            selected = snoozeMinutes.toString(),
                            onSelect = { m ->
                                snoozeMinutes = m.toInt()
                                BubblePrefs.setSnoozeMinutes(context, snoozeMinutes)
                            },
                        )
                    }
                }
            }

            // ---------------- PRIVACY ----------------
            Section(tr("Privacidad", "Privacy")) {
                Card {
                    ToggleRow(tr("Guardar historial", "Keep history"), tr("Se guarda en este teléfono · mejora la personalización", "Stored on this device · powers personalization"), keepHistory) {
                        keepHistory = it
                        DictationHistory.setKeepHistory(context, it)
                        // "Nothing is saved to disk" has to include the audio, so turning
                        // history off takes the retained recordings with it.
                        if (!it) launch { PendingAudio.purgeAll(context) }
                    }
                    if (keepHistory) {
                        Divider()
                        Padded {
                            Label(tr("Guardar audio", "Keep audio"))
                            Text(
                                tr("Las grabaciones se quedan en este teléfono para poder reintentar un dictado " +
                                    "que falle. Nunca se suben.", "Recordings stay on this device so a dictation that fails can be run " +
                                    "again. Never uploaded."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(11.dp))
                            Segment(
                                options = listOf("7" to tr("7 días", "7 days"), "30" to tr("30 días", "30 days"), "90" to tr("90 días", "90 days"), "0" to tr("Siempre", "Forever")),
                                selected = audioKeepDays.toString(),
                                onSelect = { d ->
                                    audioKeepDays = d.toInt()
                                    launch { PendingAudio.setKeepDays(context, audioKeepDays) }
                                },
                            )
                        }
                    }
                    Divider()
                    NavRow(tr("Borrar todos los datos", "Clear all data"), danger = true, icon = Icons.Default.Delete) {
                        launch {
                            DictationHistory.all(context).forEach { DictationHistory.delete(context, it.id) }
                            PendingAudio.purgeAll(context)
                        }
                        Toast.makeText(context, tr("Se borró el historial del teléfono", "On-device history cleared"), Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // ---------------- PERSONALIZATION ----------------
            Section(tr("Personalización", "Personalization")) {
                Card {
                    NavRow(tr("Diccionario personal", "Personal dictionary"), tr("Palabras, nombres y cómo los pronuncias", "Words, names and how you say them")) { context.startActivity(Intent(context, VocabActivity::class.java)) }
                    Divider()
                    NavRow(tr("Palabras aprendidas", "Learned words"), tr("Correcciones que recordó", "Corrections it remembered")) { context.startActivity(Intent(context, LearnedVocabActivity::class.java)) }
                    Divider()
                    NavRow(tr("Memoria de estilo", "Style memory"), tr("Ejemplos en el teléfono · nunca se suben", "On-device examples · never uploaded")) { context.startActivity(Intent(context, StyleMemoryActivity::class.java)) }
                    Divider()
                    NavRow(tr("Tono por app", "Tone by app"), tr("Correo, chat, redes, notas · elige las apps", "Email, chat, social, notes · pick the apps")) { context.startActivity(Intent(context, AppToneActivity::class.java)) }
                    Divider()
                    NavRow(tr("Importar de contactos", "Import from contacts"), tr("Se procesa en el teléfono · nunca se sube", "Matched on-device · never uploaded")) { context.startActivity(Intent(context, ContactsImportActivity::class.java)) }
                }
            }

            // ---------------- RELIABILITY ----------------
            Section(tr("Confiabilidad", "Reliability")) {
                Card {
                    NavRow(tr("Inicio automático y batería", "Auto-start & battery"), tr("Para Samsung, Xiaomi, OnePlus, Oppo: elige \"Sin restricciones\"", "For Samsung, Xiaomi, OnePlus, Oppo: choose \"Unrestricted\"")) {
                        val intent = SetupUtils.oemAutoStartIntents().firstOrNull { it.resolveActivity(context.packageManager) != null }
                            ?: SetupUtils.appInfoIntent(context)
                        runCatching { context.startActivity(intent) }
                    }
                }
            }

            // ---------------- GENERAL ----------------
            // ---------------- FEEDBACK ----------------
            // There was no way to reach us from inside the app at all, so the only channel was a
            // Play review, which we can reply to but not ask questions in. Both rows prefill the
            // version/device details and then hand off to the user's own mail app or browser;
            // nothing is transmitted by us.
            Section(tr("Comentarios", "Feedback")) {
                Card {
                    NavRow(tr("Enviar comentarios", "Send feedback"), tr("Al equipo original de OpenWispr", "To the original OpenWispr team")) {
                        launchOrNotify(context, Feedback.emailIntent(context), tr("No hay app de correo. Escribe a ${Feedback.EMAIL}", "No email app found. Write to ${Feedback.EMAIL}"))
                    }
                    Divider()
                    NavRow(tr("Reportar un problema", "Report a problem"), tr("Abre un issue en GitHub", "Open an issue on GitHub")) {
                        launchOrNotify(context, Feedback.issueIntent(context), tr("No se pudo abrir el navegador.", "Couldn't open a browser."))
                    }
                    Divider()
                    NavRow(tr("Calificar OpenWispr", "Rate OpenWispr"), tr("El proyecto original en Google Play", "The original project on Google Play")) {
                        launchOrNotify(context, Feedback.playListingIntent(context), tr("No se pudo abrir Google Play.", "Couldn't open Google Play."))
                    }
                }
            }

            Section(tr("General", "General")) {
                Card {
                    NavRow(tr("Repetir la introducción", "Replay onboarding"), tr("Volver a ver la configuración inicial", "Walk through setup again")) { context.startActivity(OnboardingActivity.intent(context)) }
                    Divider()
                    // remember: this is a binder call into PackageManager, and the version can't
                    // change while the screen is up.
                    val version = remember { appVersion(context) }
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Versión", "Version"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        Text("$version · " + tr("código abierto", "open source"), style = MonoEyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/* ------------------------ deleting a downloaded model (issue #53) ------------------------ */

private enum class ModelKind { STT, LLM }

private data class DeletableModel(
    val kind: ModelKind,
    val id: String,
    val label: String,
    val size: String,
)

/**
 * Confirmation before removing a downloaded model. Worth a dialog rather than an instant
 * delete: the file is hundreds of megabytes and getting it back means a download, which on a
 * metered connection is a real cost. The copy says exactly that instead of "are you sure?".
 */
@Composable
private fun DeleteModelDialog(target: DeletableModel, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("¿Eliminar ${target.label}?", "Delete ${target.label}?")) },
        text = {
            Text(
                tr("Esto libera ${target.size.removePrefix("~")} en este teléfono. El modelo sigue " +
                    "disponible: puedes volver a descargarlo desde esta pantalla cuando quieras (requiere conexión).",
                    "This frees ${target.size.removePrefix("~")} on this device. The model stays " +
                    "available — you can download it again from this screen whenever you want it, " +
                    "which needs a connection."),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(tr("Eliminar", "Delete"), color = Color(0xFFDC2626)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Conservar", "Keep")) } },
    )
}

/* ------------------------ model option tables ------------------------ */

private data class SttModelOption(val id: String, val name: String, val meta: String, val recommended: Boolean)

/**
 * Every on-device speech model, with the "recommended" badge on whichever one [DeviceFit]
 * picked for this phone rather than always on Parakeet. The list itself never shrinks — a
 * budget device can still choose the large model deliberately, it just isn't told to.
 */
private fun sttModelOptions(recommendedId: String): List<SttModelOption> = buildList {
    add(
        SttModelOption(
            ParakeetModelManager.MODEL_ID, "Parakeet",
            "${ParakeetModelManager.SIZE_LABEL} · " + tr("el más preciso", "most accurate"),
            OnDeviceStt.isParakeet(recommendedId),
        ),
    )
    WhisperModelManager.MODELS.forEach {
        add(SttModelOption(it.id, it.label, it.sizeLabel, it.id == recommendedId))
    }
}

private fun keyPlaceholder(provider: String) = when (provider) {
    "groq" -> "gsk_..."; "openai" -> "sk-..."; else -> tr("clave o endpoint", "key or endpoint")
}

/* ------------------------ reusable pieces ------------------------ */

@Composable
private fun HeroHeader() {
    Column(
        Modifier.fillMaxWidth().background(FlowBrush).statusBarsPadding().padding(24.dp, 22.dp, 24.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_voiceflow), null, tint = FlowWhite, modifier = Modifier.size(40.dp))
            Text(tr("Ajustes", "Settings"), color = FlowWhite, style = Wordmark)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(title.uppercase(), style = MonoEyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        content()
    }
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
        content = content,
    )
}

@Composable
private fun Padded(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun SetupStatusCard(complete: Boolean, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (complete) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFD1FAE5)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, null, tint = Color(0xFF047857), modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(if (complete) tr("Todo listo", "You're all set") else tr("Termina la configuración", "Finish setup"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(if (complete) tr("Micrófono · Burbuja · Inserción automática activos", "Microphone · Bubble · Auto-insert all active") else tr("Un par de permisos rápidos", "A couple of quick permissions"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        content()
    }
}

@Composable
private fun StatusRow(title: String, subtitle: String, on: Boolean, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
private fun Segment(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (id, label) ->
            val sel = id == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                    .background(if (sel) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(id) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One model in a download list. [onDelete] is only rendered when the model is downloaded and
 * not the active one (issue #53: a second model you no longer want had no way off the device —
 * only "Use"). Deleting the active model is deliberately not offered: switch first, then delete.
 */
@Composable
private fun ModelRow(
    name: String, meta: String, recommended: Boolean, state: String, progress: Float,
    onGet: () -> Unit, onUse: () -> Unit, onDelete: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, cs.outline, RoundedCornerShape(12.dp)).padding(13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                    if (recommended) Badge(tr("Recomendado", "Recommended"), Color(0xFF047857), Color(0xFFD1FAE5))
                }
                Text(meta, style = MonoEyebrow, fontSize = 11.sp, color = cs.onSurfaceVariant)
            }
            when (state) {
                "active" -> Badge(tr("Activo", "Active"), Color(0xFF047857), Color(0xFFD1FAE5))
                "downloaded" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onDelete != null) {
                        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = tr("Eliminar $name", "Delete $name"),
                                tint = cs.onSurfaceVariant,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                    PillOutline(tr("Usar", "Use"), onUse)
                }
                "downloading" -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = cs.primary)
                else -> PillButton(tr("Descargar", "Get"), onGet)
            }
        }
        if (state == "downloading") {
            Spacer(Modifier.height(11.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.outline)) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(cs.primary))
            }
        }
    }
}

@Composable
private fun Badge(text: String, fg: Color, bg: Color) {
    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(bg).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(text.uppercase(), style = MonoEyebrow, fontSize = 10.sp, color = fg)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ToggleRowBare(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NavRow(title: String, subtitle: String? = null, danger: Boolean = false, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    val color = if (danger) Color(0xFFDC2626) else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(icon ?: Icons.Filled.ChevronRight, null, tint = if (danger) color else MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun DisclosureHeader(title: String, open: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun InfoNote(text: String, onSwitch: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(tr("Cambia a \"en el teléfono\" para que todo sea privado.", "Switch to on-device to keep everything private."), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { onSwitch() })
    }
}

@Composable
private fun KeyField(value: String, placeholder: String, onChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(cs.surface).border(1.5.dp, cs.outline, RoundedCornerShape(11.dp)).padding(13.dp)) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                inner()
            },
        )
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.primary).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 7.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun PillOutline(label: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(9.dp)).border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(9.dp)).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 7.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * The installed version, for the Settings footer. This used to be the string literal "1.0", which
 * had been wrong since 1.0.1 and told anyone reporting a bug the wrong version to report against.
 *
 * Read from PackageManager rather than BuildConfig so it reflects the APK actually on the device,
 * and because BuildConfig generation is off by default in AGP 8 and would need a new build flag
 * for one string.
 */
/**
 * Start [intent], or say why it didn't work. A phone with no mail client (or no browser) is
 * unusual but real, and an unhandled ActivityNotFoundException would crash the whole Settings
 * screen for a tap on a feedback row.
 */
private fun launchOrNotify(context: Context, intent: Intent, fallback: String) {
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, fallback, Toast.LENGTH_LONG).show()
    }
}

private fun appVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
} catch (_: Exception) {
    // Can only really happen if the package is being replaced out from under us.
    "unknown"
}
