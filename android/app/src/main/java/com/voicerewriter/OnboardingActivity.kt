package com.voicerewriter

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.voicerewriter.ui.MonoEyebrow
import com.voicerewriter.ui.VoiceFlowTheme
import com.voicerewriter.ui.FlowBrush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run onboarding. Six steps: welcome → why the download exists → microphone →
 * auto-insert (accessibility, with the tap-to-talk bubble/overlay permission folded in) →
 * first dictation → done.
 *
 * The shape is dictated by one constraint: the speech model is hundreds of megabytes, so
 * unlike a cloud dictation app we cannot let the user succeed before asking for anything.
 * Instead the download starts immediately and the permission steps cover it, so by the time
 * they reach the try-it step the model has usually landed — and if it hasn't, that step shows
 * progress rather than a wall. The download is presented as what it is: the reason nothing has
 * to be uploaded, rather than a toll on the way in.
 *
 * Which model that is depends on the phone. [DeviceFit] reads RAM and free storage before the
 * first byte is fetched and picks a pair this device can actually run, so a budget phone gets a
 * smaller on-device model instead of a 1GB download it will OOM on at the try-it step.
 *
 * On-device is the only path here; cloud transcription stays available later in Settings, as
 * does personalization (dictionary, contacts, tone), which is deliberately not in this flow.
 * Special-access grants (overlay, accessibility) deliver no result callback, so live state
 * is re-read from [SetupUtils] on every ON_RESUME. Shown once on first launch (gated by
 * [Settings.hasCompletedOnboarding]); re-launchable from Settings.
 */
class OnboardingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { VoiceFlowTheme { OnboardingScreen(::launchDictation, ::goHome) } }
    }

    private fun launchDictation() {
        startActivity(
            Intent(this, RewriteActivity::class.java)
                .putExtra(RewriteActivity.EXTRA_AUTO_RECORD, true),
        )
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    companion object {
        fun intent(ctx: Context): Intent = Intent(ctx, OnboardingActivity::class.java)
    }
}

// Onboarding downloads exactly one speech model and one polish model — no engine choice is
// put to the user. Which pair it is comes from [DeviceFit.plan]: the full pair on a phone with
// the memory to run it, a smaller on-device pair otherwise. Anything else (a different Whisper
// size, cloud) stays reachable from Settings for people who go looking.

// Read aloud at the try-it step, and chosen by running candidates through the real pipeline:
// this one fires filler removal, spoken punctuation and list formatting, and turns a single
// run-on sentence into a numbered list, which is the most legible proof of the cleanup we can
// get in one breath.
//
// A self-correction ("I mean john") would be the fourth trick, but corrections and spoken
// lists cannot currently be demoed together: a multi-word correction inside a list drops the
// text before it (see PolishHighlightsTest). Until that's fixed, corrections stay in the
// also-handles line rather than the script.
private val TRY_IT_SCRIPT: String
    get() = tr(
        "eh hola equipo, mañana a las tres y media revisamos la presentación, ehm y luego mando el resumen",
        "um first email john, second book the room at two thirty, third send the deck period",
    )

// Apps named on the welcome screen. Concrete names beat "works anywhere" — but we name them
// as text rather than drawing real icons, which would need <queries> manifest entries.
private val WELCOME_APPS: String
    get() = tr("WhatsApp · Gmail · Slack · Notas · donde sea que escribas", "WhatsApp · Gmail · Slack · Notes · anywhere you type")

private const val LAST_STEP = 5

@Composable
private fun OnboardingScreen(onLaunchDictation: () -> Unit, onGoHome: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val ctx: Context = activity
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var step by remember { mutableIntStateOf(0) }

    var micGranted by remember { mutableStateOf(SetupUtils.micGranted(ctx)) }
    var micBlocked by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(SetupUtils.canDrawOverlays(ctx)) }
    var a11yGranted by remember { mutableStateOf(SetupUtils.accessibilityEnabled(ctx)) }

    // Which models this phone gets, decided from its RAM and free storage before anything is
    // fetched — see [DeviceFit]. Computed once and remembered, because every flow selection
    // below keys off it and it must not change mid-flow.
    val fit = remember { DeviceFit.plan(ctx) }
    val fitNote = remember(fit) { DeviceFit.explain(fit) }

    // Downloads live on the model managers themselves (not this composition), so they keep
    // running across activity/process transitions — e.g. finishing onboarding before either
    // one completes. This screen just observes and, below, kicks them off. Which speech
    // manager it observes follows [fit]: Parakeet on a phone that can run it, Whisper
    // otherwise.
    val sttState = if (fit.usesParakeet) ParakeetModelManager.downloadState else WhisperModelManager.downloadState
    val sttProgress = if (fit.usesParakeet) ParakeetModelManager.downloadProgress else WhisperModelManager.downloadProgress
    val sttError = if (fit.usesParakeet) ParakeetModelManager.downloadError else WhisperModelManager.downloadError
    val dl by sttState.collectAsState()
    val dlPct by sttProgress.collectAsState()
    val dlError by sttError.collectAsState()
    val llmDl by LlmModelManager.downloadState.collectAsState()
    val llmPct by LlmModelManager.downloadProgress.collectAsState()

    // Play Store installs don't hit Android's "restricted settings" lockout that sideloaded
    // (GitHub-sourced) APKs used to — that gate only applies to apps installed outside a
    // trusted app store, so onboarding can go straight to the accessibility list.
    var a11yPhase by remember { mutableStateOf("list") }
    var showA11yConsent by remember { mutableStateOf(false) }
    // Android's accessibility grant reports no result, so the parent ON_RESUME below detects
    // it. This only tracks whether we've sent them out at all, so the manual "I've already
    // turned it on" fallback stays hidden until it could plausibly be needed.
    var sentToA11ySettings by remember { mutableStateOf(false) }

    // The user's own first dictation, shown back to them at the try-it step. `tryBaseline` is
    // the LastDictation value captured when they tapped — anything different afterwards is
    // theirs, which keeps a replayed onboarding from claiming credit for an older dictation.
    var tryBaseline by remember { mutableStateOf<String?>(null) }
    var tryResult by remember { mutableStateOf<String?>(null) }
    var tryRaw by remember { mutableStateOf<String?>(null) }
    // What the cleanup actually did to their sentence, and a line for whatever it didn't get
    // to show off. Derived from the raw transcript by [PolishHighlights].
    var tryDid by remember { mutableStateOf<List<String>>(emptyList()) }
    var tryAlso by remember { mutableStateOf<String?>(null) }

    // Personalization "set up" status — read from the real on-device stores so returning
    // from an editor reflects what the user actually added (see refreshPersonalization).
    var toneDone by remember { mutableStateOf(false) }
    var styleN by remember { mutableIntStateOf(0) }
    var dictN by remember { mutableIntStateOf(0) }
    var contactsDone by remember { mutableStateOf(false) }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (!granted) micBlocked = !activity.shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)
    }

    fun refreshPersonalization() {
        scope.launch {
            val tone = withContext(Dispatchers.IO) { AppToneRepository(ctx).load().let { d -> d.apps.isNotEmpty() || d.tones.values.any { it.isNotEmpty() } } }
            val vocab = withContext(Dispatchers.IO) { VocabRepository(ctx).get() }
            val samples = withContext(Dispatchers.IO) {
                CorrectionCorpus.all(ctx).count { it.edited && it.cleaned != it.final }
            }
            toneDone = tone
            dictN = vocab.count { it.source != "contact" }
            contactsDone = vocab.any { it.source == "contact" }
            styleN = samples
        }
    }

    /**
     * Pull the dictation the user just did back into onboarding. [RewriteActivity] already
     * persists it via [LastDictation] on the way out (and reports RESULT_CANCELED even on
     * success), so reading the file beats any activity-result plumbing. The history entry —
     * when history is on and cleanup actually changed something — additionally gives us the
     * raw transcript, so the polish can be shown on the user's own words.
     */
    fun captureTryResult() {
        val baseline = tryBaseline ?: return
        scope.launch {
            val text = withContext(Dispatchers.IO) { LastDictation.get(ctx) }
            if (text.isBlank() || text == baseline) return@launch
            val raw = withContext(Dispatchers.IO) {
                DictationHistory.all(ctx).firstOrNull()
                    ?.takeIf { it.after == text && it.before.isNotBlank() && it.before.trim() != it.after.trim() }
                    ?.before
            }
            // Naming what the cleanup did is only honest when we have the raw transcript to
            // derive it from; with history off we show the result and nothing more.
            val highlights = withContext(Dispatchers.Default) {
                raw?.let { PolishHighlights.labels(it) to PolishHighlights.alsoHandles(it) }
            }
            tryResult = text
            tryRaw = raw
            tryDid = highlights?.first.orEmpty()
            tryAlso = highlights?.second
        }
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                micGranted = SetupUtils.micGranted(ctx)
                overlayGranted = SetupUtils.canDrawOverlays(ctx)
                a11yGranted = SetupUtils.accessibilityEnabled(ctx)
                if (a11yGranted && a11yPhase != "skipped") a11yPhase = "on"
                refreshPersonalization()
                captureTryResult()
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // The dictation now finishes in the background (tap the bubble to end it), usually while
    // this screen is already back in front, so also pick the result up the moment it lands.
    LaunchedEffect(Unit) { Dictation.delivered.collect { if (it > 0) captureTryResult() } }

    fun next() { step = (step + 1).coerceAtMost(LAST_STEP) }
    fun back() { step = (step - 1).coerceAtLeast(0) }

    fun persistStt() {
        scope.launch(Dispatchers.IO) {
            val repo = SettingsRepository(ctx)
            val cur = repo.get()
            repo.save(
                cur.copy(
                    sttProvider = "local",
                    sttModel = fit.sttModel,
                    // Only steer the polish model when polish is still on-device. If someone
                    // re-runs onboarding after pointing the app at a cloud provider, that's
                    // their choice to keep, not ours to overwrite with a GGUF id.
                    model = if (cur.provider == "local") fit.llmModel else cur.model,
                ),
            )
        }
    }

    // Downloads start the moment onboarding opens — no "download" tap needed. Speech goes
    // first and alone: it's the only model the first dictation needs, and making it share
    // bandwidth with the polish model just pushes back the moment the user can actually talk.
    LaunchedEffect(Unit) {
        if (fit.usesParakeet) ParakeetModelManager.ensureDownloading(ctx)
        else WhisperModelManager.ensureDownloading(ctx, fit.sttModel)
    }
    LaunchedEffect(dl) {
        if (dl == "done") persistStt()
        // Failed speech shouldn't strand polish — start it either way, just not first.
        if (dl == "done" || dl == "error") LlmModelManager.ensureDownloading(ctx, fit.llmModel)
    }

    fun finishOnboarding() {
        scope.launch {
            withContext(Dispatchers.IO) { SettingsRepository(ctx).setOnboardingComplete(true) }
            if (overlayGranted) runCatching { SetupUtils.startBubble(ctx) }
            onGoHome()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            if (step in 1 until LAST_STEP) {
                TopBar(progress = step / LAST_STEP.toFloat(), onBack = { back() })
            }
            // Steps past the download step keep an ambient status, so a silent background
            // download doesn't read as nothing happening. Never a blocker — just a signal.
            if (step in 2 until LAST_STEP && (dl == "downloading" || llmDl == "downloading")) {
                val speechDownloading = dl == "downloading"
                DownloadStatusChip(
                    pct = if (speechDownloading) dlPct else llmPct,
                    label = if (speechDownloading) tr("Modelo de voz", "Speech model") else tr("Modelo de pulido", "Polish model"),
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (step) {
                    0 -> WelcomeStep(onNext = { next() })
                    1 -> PrivacyStep(
                        dl = dl, dlPct = dlPct, dlError = dlError, fitNote = fitNote,
                        onRetry = {
                            if (fit.usesParakeet) ParakeetModelManager.ensureDownloading(ctx)
                            else WhisperModelManager.ensureDownloading(ctx, fit.sttModel)
                        },
                        onNext = { next() },
                    )
                    2 -> MicStep(
                        granted = micGranted, blocked = micBlocked,
                        onAllow = {
                            if (micBlocked) ctx.startActivity(SetupUtils.appInfoIntent(ctx))
                            else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        },
                        onSkip = { next() }, onNext = { next() },
                    )
                    3 -> A11yStep(
                        phase = a11yPhase,
                        overlayGranted = overlayGranted,
                        canConfirmManually = sentToA11ySettings,
                        onOpenA11y = { showA11yConsent = true },
                        onConfirm = { a11yGranted = SetupUtils.accessibilityEnabled(ctx); if (a11yGranted) a11yPhase = "on" },
                        onGrantOverlay = { ctx.startActivity(SetupUtils.overlaySettingsIntent(ctx)) },
                        onSkip = { a11yPhase = "skipped" },
                        onNext = { next() },
                    )
                    4 -> TryItStep(
                        micGranted = micGranted,
                        modelReady = dl == "done", dlPct = dlPct,
                        result = tryResult, raw = tryRaw, did = tryDid, also = tryAlso,
                        onTry = {
                            tryBaseline = LastDictation.get(ctx)
                            onLaunchDictation()
                        },
                        onNext = { next() },
                    )
                    else -> DoneStep(
                        micOn = micGranted,
                        sttOn = dl == "done",
                        a11yOn = a11yPhase == "on", a11ySkipped = a11yPhase == "skipped",
                        personalCount = listOf(toneDone, styleN > 0, dictN > 0, contactsDone).count { it },
                        onPersonalize = { ctx.startActivity(Intent(ctx, VocabActivity::class.java)) },
                        onStart = { finishOnboarding() },
                        onReplay = {
                            step = 0; a11yPhase = "list"
                            tryResult = null; tryRaw = null; tryDid = emptyList(); tryAlso = null
                        },
                    )
                }
            }
        }
    }

    if (showA11yConsent) {
        AccessibilityConsentDialog(
            onConfirm = {
                showA11yConsent = false
                sentToA11ySettings = true
                AccessibilityConsent.record(ctx)
                // The guide walks through Android's "restricted settings" for APK installs.
                ctx.startActivity(AccessibilityGuideActivity.intent(ctx))
            },
            onDismiss = { showA11yConsent = false },
        )
    }
}

/* ----------------------------------------------------------------------------- */
/* shared pieces                                                                  */
/* ----------------------------------------------------------------------------- */

@Composable
private fun TopBar(progress: Float, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(14.dp, 14.dp, 18.dp, 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Filled.ChevronLeft, contentDescription = tr("Atrás", "Back"),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(30.dp).clip(CircleShape).clickable { onBack() }.padding(3.dp),
        )
        Box(
            Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.outline),
        ) {
            Box(
                Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary),
            )
        }
        // No "Skip" here: every step's own actions already include a forward path (the CTA
        // always advances, and the two askable permissions carry their own opt-out link), so
        // a second skip affordance up here was just one more thing to read.
        Spacer(Modifier.width(28.dp))
    }
}

@Composable
private fun Cta(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
            .background(if (enabled) cs.primary else cs.outline)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = MaterialTheme.typography.titleMedium,
            color = if (enabled) cs.onPrimary else cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun SubLink(text: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable { onClick() }.padding(top = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(text.uppercase(), style = MonoEyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LogoCircle(diameter: Int) {
    Box(
        Modifier.size(diameter.dp).clip(CircleShape).background(FlowBrush),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            androidx.compose.ui.res.painterResource(R.drawable.ic_voiceflow), null,
            tint = com.voicerewriter.ui.FlowWhite, modifier = Modifier.size((diameter * 0.5f).dp),
        )
    }
}

/** Ambient download status for the steps after the download step. Deliberately phrased as
 *  something arriving rather than something the user is waiting on. */
@Composable
private fun DownloadStatusChip(pct: Float, label: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(cs.primary))
        Text(
            tr("$label en camino · ${(pct * 100).toInt()}% · sigue adelante", "$label arriving · ${(pct * 100).toInt()}% · keep going"),
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun SuccessPill(text: String) {
    val green = Color(0xFF047857)
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFE7F3EA))
            .border(1.dp, Color(0xFFA7F3D0), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Check, null, tint = green, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = green)
    }
}

@Composable
private fun IconTile(icon: ImageVector, size: Int = 72, corner: Int = 20, iconSize: Int = 34) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(corner.dp)).background(cs.primaryContainer),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = cs.primary, modifier = Modifier.size(iconSize.dp)) }
}

/** Standard step scaffold: scrollable content area + a fixed bottom action block. */
@Composable
private fun StepScaffold(
    content: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) { content() }
        Column(Modifier.fillMaxWidth().padding(bottom = 26.dp, top = 8.dp)) { actions() }
    }
}

/* ----------------------------------------------------------------------------- */
/* steps                                                                          */
/* ----------------------------------------------------------------------------- */

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            LogoCircle(118)
            Spacer(Modifier.height(30.dp))
            Text(
                tr("Habla donde sea.\nEscribe sin teclear.", "Talk anywhere.\nKeep everything."), style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.ExtraBold, color = cs.onBackground, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                tr("VoiceFlow convierte tu voz en texto limpio y listo, directo en tu teléfono. Configurarlo toma un minuto.", "VoiceFlow turns your voice into clean, finished text, right on your phone. Setup takes a minute."),
                style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                WELCOME_APPS,
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Cta(tr("Comenzar", "Get started"), onClick = onNext)
        Spacer(Modifier.height(18.dp))
        Eyebrow(tr("100% en tu teléfono · código abierto", "100% on-device · open source"))
    }
}

/**
 * The setup step. The ~1GB download is the most obvious cost of being on-device, but it's also
 * the only tangible proof of the privacy claim: a cloud dictation app has nothing to download
 * because your voice goes to its servers instead. So this step gives the reason in one line
 * and then gets out of the way.
 *
 * Deliberately withheld: file sizes, model names, and the fact that there are two models at
 * all. None of it changes what the user does next, and every number here is one more thing to
 * feel anxious about. The second model is sequenced behind the first and simply arrives.
 *
 * One deliberate exception: [fitNote]. When [DeviceFit] has downgraded this phone to a smaller
 * model, or found it short on space, the user is going to notice the consequence either way —
 * as lower accuracy, or as a download that stops partway. Saying it once, here, is the only
 * version of that where they know why and what to do about it.
 */
@Composable
private fun PrivacyStep(
    dl: String, dlPct: Float, dlError: String?, fitNote: String?,
    onRetry: () -> Unit, onNext: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    StepScaffold(
        content = {
            Spacer(Modifier.height(4.dp))
            IconTile(Icons.Filled.VerifiedUser, size = 56, corner = 16, iconSize = 27)
            Spacer(Modifier.height(16.dp))
            Text(tr("Tu voz nunca sale de este teléfono", "Your voice never leaves this phone"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground)
            Spacer(Modifier.height(10.dp))
            Text(
                tr("Otras apps de dictado mandan tu voz a un servidor. VoiceFlow no, por eso se prepara aquí. Toma como un minuto.", "Other dictation apps send your voice to a server. VoiceFlow doesn't, so it sets itself up here. Takes about a minute."),
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            ModelCard(icon = Icons.Filled.Mic, name = tr("Preparando", "Setting up"), state = dl, pct = dlPct)

            if (dl == "error") {
                Spacer(Modifier.height(16.dp))
                Text(dlError ?: tr("Falló la descarga", "Download failed"), style = MaterialTheme.typography.bodySmall, color = Color(0xFFDC2626))
            }
            if (fitNote != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    fitNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(20.dp))
            // Concrete and testable by the user later, which is what makes it land as proof
            // rather than as marketing. A cloud app cannot make this claim.
            Eyebrow(tr("Sin internet · sin cuenta · nada se sube", "Works offline · no account · nothing uploaded"))
        },
        actions = {
            // The button always just moves on. Making someone sit and watch a ~1GB download
            // reads as stuck, not as progress, and it was the single biggest place onboarding
            // lost people; the download keeps running regardless of where they are.
            when {
                dl == "error" -> Cta(tr("Intentar de nuevo", "Try again"), onClick = onRetry)
                dl == "done" -> Cta(tr("Continuar", "Continue"), onClick = onNext)
                else -> Cta(tr("Continuar mientras se prepara", "Continue while it sets up"), onClick = onNext)
            }
        },
    )
}

/** Setup progress, with an inline bar once it's running. */
@Composable
private fun ModelCard(icon: ImageVector, name: String, state: String, pct: Float) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(cs.secondaryContainer)
            .border(1.5.dp, if (state == "done") Color(0xFFA7F3D0) else cs.primary, RoundedCornerShape(15.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(FlowBrush), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = com.voicerewriter.ui.FlowWhite, modifier = Modifier.size(20.dp))
            }
            Text(
                name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = cs.onBackground, modifier = Modifier.weight(1f),
            )
            Text(
                when (state) {
                    "done" -> tr("LISTO", "READY")
                    "downloading" -> "${(pct * 100).toInt()}%"
                    "error" -> tr("FALLÓ", "FAILED")
                    else -> tr("INICIANDO", "STARTING")
                },
                style = MonoEyebrow, fontSize = 9.5.sp,
                color = if (state == "done") Color(0xFF047857) else cs.onSurfaceVariant,
            )
        }
        if (state == "downloading") {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.outline)) {
                Box(Modifier.fillMaxWidth(pct.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(cs.primary))
            }
        }
    }
}

@Composable
private fun MicStep(granted: Boolean, blocked: Boolean, onAllow: () -> Unit, onSkip: () -> Unit, onNext: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    StepScaffold(
        content = {
            Spacer(Modifier.height(20.dp))
            IconTile(Icons.Filled.Mic)
            Spacer(Modifier.height(24.dp))
            Text(tr("Deja que VoiceFlow te escuche", "Let VoiceFlow hear you"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground)
            Spacer(Modifier.height(12.dp))
            Text(
                tr("Es lo único sin lo que de verdad no puede funcionar. Tu audio se convierte en texto con el modelo que acabas de descargar. Nunca se sube a ningún lado.", "This is the one thing it genuinely can't work without. Your audio is turned into text by the model you just downloaded. Never uploaded."),
                style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            when {
                granted -> SuccessPill(tr("Micrófono permitido", "Microphone allowed"))
                blocked -> Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFFBEAE2))
                        .border(1.dp, Color(0xFFE9C3B2), RoundedCornerShape(12.dp)).padding(14.dp),
                ) {
                    Text(tr("El micrófono está bloqueado", "Microphone is blocked"), style = MaterialTheme.typography.titleSmall, color = Color(0xFFDC2626))
                    Spacer(Modifier.height(4.dp))
                    Text(tr("Android no volverá a preguntar. Abre Info de la app → Permisos → Micrófono y actívalo.", "Android won't ask again. Open App info → Permissions → Microphone and switch it on."), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        },
        actions = {
            when {
                granted -> Cta(tr("Continuar", "Continue"), onClick = onNext)
                blocked -> Cta(tr("Abrir ajustes", "Open settings"), onClick = onAllow)
                else -> { Cta(tr("Permitir micrófono", "Allow microphone"), onClick = onAllow); SubLink(tr("Configurar después", "Set up later"), onClick = onSkip) }
            }
        },
    )
}

@Composable
private fun A11yStep(
    phase: String, overlayGranted: Boolean, canConfirmManually: Boolean,
    onOpenA11y: () -> Unit, onConfirm: () -> Unit, onGrantOverlay: () -> Unit,
    onSkip: () -> Unit, onNext: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    when (phase) {
        "on" -> StepScaffold(
            content = {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(74.dp).clip(CircleShape).background(Color(0xFFE7F3EA)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, null, tint = Color(0xFF047857), modifier = Modifier.size(36.dp))
                    }
                    Spacer(Modifier.height(22.dp))
                    Text(tr("La inserción automática está activa", "Auto-insert is on"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground)
                    Spacer(Modifier.height(10.dp))
                    Text(tr("Tu texto aparecerá directo en donde estés escribiendo.", "Your text will drop straight into whatever you're typing in."), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                    if (!overlayGranted) {
                        Spacer(Modifier.height(22.dp))
                        BubblePermissionRow(onAllow = onGrantOverlay)
                    }
                }
            },
            actions = { Cta(tr("Continuar", "Continue"), onClick = onNext) },
        )
        "skipped" -> StepScaffold(
            content = {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(74.dp).clip(CircleShape).background(cs.surfaceVariant), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.ContentPaste, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(22.dp))
                    Text(tr("Sin problema, usaremos el portapapeles", "No problem, clipboard it is"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text(tr("Sin la inserción automática, VoiceFlow copia tu texto para que lo pegues tú. Puedes activarla cuando quieras en Ajustes.", "Without auto-insert, VoiceFlow copies your finished text so you can paste it yourself. You can enable auto-insert anytime in Settings."), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                    if (!overlayGranted) {
                        Spacer(Modifier.height(22.dp))
                        BubblePermissionRow(onAllow = onGrantOverlay)
                    }
                }
            },
            actions = { Cta(tr("Continuar", "Continue"), onClick = onNext) },
        )
        // Kept deliberately sparse. This is the scariest screen in the flow, and the instinct
        // is to reassure with more words, which backfires: a wall of text about a permission
        // reads as something being justified. The picture of the row they're hunting for does
        // the real work; everything else is one line or gone.
        else -> StepScaffold( // "list"
            content = {
                Spacer(Modifier.height(16.dp))
                IconTile(Icons.Filled.TouchApp, size = 56, corner = 16, iconSize = 27)
                Spacer(Modifier.height(16.dp))
                Text(tr("Deja que escriba por ti", "Let it type for you"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground)
                Spacer(Modifier.height(10.dp))
                Text(
                    tr("Solo inserta tu texto y detecta cuándo se abre el teclado.", "It only inserts your text and notices when the keyboard opens."),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                )

                Spacer(Modifier.height(26.dp))
                MockSettingsRow(label = "VoiceFlow", subtitle = tr("App descargada", "Downloaded app"), on = true)
                Spacer(Modifier.height(12.dp))
                Text(
                    tr("Busca este interruptor y actívalo.", "Find this switch and turn it on."),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onBackground,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                // The system confirmation is where people bail: it's worded to sound alarming
                // and gives no hint that every accessibility app triggers the identical dialog.
                Text(
                    tr("Android te pedirá confirmar. Es normal.", "Android will ask you to confirm. That's normal."),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )

                if (!overlayGranted) {
                    Spacer(Modifier.height(24.dp))
                    BubblePermissionRow(onAllow = onGrantOverlay)
                }
            },
            actions = {
                Cta(tr("Abrir ajustes de Accesibilidad", "Open Accessibility settings"), onClick = onOpenA11y)
                // The parent's ON_RESUME observer normally detects the grant on its own, so
                // this manual fallback only earns its place once they've actually been out.
                if (canConfirmManually) SubLink(tr("Ya lo activé", "I've already turned it on"), onClick = onConfirm)
                SubLink(tr("Omitir, pegaré a mano", "Skip, I'll paste manually"), onClick = onSkip)
            },
        )
    }
    // confirm-on-resume is handled by the parent ON_RESUME observer flipping phase to "on";
    // the user taps Continue on the "on" screen to advance.
}

/**
 * A mock of the Android settings row the user is about to go hunting for. Drawn rather than
 * screenshotted so it stays correct across themes and OS versions — the point is to make the
 * target recognisable at a glance, since this hand-off into system settings is where an
 * Android dictation app loses the most people.
 */
@Composable
private fun MockSettingsRow(label: String, subtitle: String, on: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surface)
            .border(1.5.dp, cs.primary, RoundedCornerShape(12.dp)).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(FlowBrush), contentAlignment = Alignment.Center) {
            Icon(
                androidx.compose.ui.res.painterResource(R.drawable.ic_voiceflow), null,
                tint = com.voicerewriter.ui.FlowWhite, modifier = Modifier.size(16.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        // Fake switch, matching the platform's shape closely enough to be recognisable.
        Box(
            Modifier.size(width = 40.dp, height = 23.dp).clip(RoundedCornerShape(12.dp))
                .background(if (on) cs.primary else cs.outline),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.padding(horizontal = 3.dp).size(17.dp).clip(CircleShape).background(com.voicerewriter.ui.FlowWhite))
        }
    }
}

/** Folded-in overlay grant for the tap-to-talk bubble (design has no standalone step). */
@Composable
private fun BubblePermissionRow(onAllow: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(cs.surface)
            .border(1.dp, cs.outline, RoundedCornerShape(13.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.TouchApp, null, tint = cs.primary, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(tr("Mostrar la burbuja para dictar", "Show the tap-to-talk bubble"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
            // Pre-empts the usual floating-overlay objection: people assume it parks itself
            // on screen forever.
            Text(tr("Aparece cada vez que se abre el teclado y se va cuando terminas.", "Appears whenever the keyboard opens, and goes away when you're done."), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Text(tr("Permitir", "Allow"), style = MaterialTheme.typography.titleSmall, color = cs.primary, modifier = Modifier.clickable { onAllow() })
    }
}
/** Outlined coral call-to-action chip with a chevron — the not-yet-set-up state. */
@Composable
private fun ActionPill(text: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).border(1.5.dp, cs.primary, RoundedCornerShape(10.dp)).padding(start = 11.dp, end = 7.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cs.primary)
        Icon(Icons.Filled.ChevronRight, null, tint = cs.primary, modifier = Modifier.size(15.dp))
    }
}

/**
 * The payoff. Everything before this was the user spending trust; this is the first moment
 * they get something back, so it has to visibly land — the previous version bounced them to
 * [RewriteActivity] and returned them to an identical screen, which reads as nothing having
 * happened. Now their own sentence comes back, with the cleanup shown on their words rather
 * than on canned examples.
 */
@Composable
private fun TryItStep(
    micGranted: Boolean, modelReady: Boolean, dlPct: Float,
    result: String?, raw: String?, did: List<String>, also: String?,
    onTry: () -> Unit, onNext: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    StepScaffold(
        content = {
            Spacer(Modifier.height(8.dp))
            if (result == null) {
                Text(tr("Pruébalo una vez", "Try it once"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("Toca el círculo, lee esto en voz alta con todo y titubeos, y al terminar toca la burbuja. Las muletillas son justo la idea.", "Tap the circle, read this aloud mistakes and all, then tap the bubble when you're done. The filler and the mid-sentence correction are the point."),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(cs.primaryContainer).padding(14.dp)) {
                    Text("“$TRY_IT_SCRIPT”", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.height(28.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(96.dp).clip(CircleShape).background(FlowBrush)
                            .clickable(enabled = modelReady && micGranted) { onTry() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_voiceflow), null, tint = com.voicerewriter.ui.FlowWhite, modifier = Modifier.size(48.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        // The wait framed as an arrival with a number on it, never a dead end —
                        // and Continue below stays live throughout.
                        !modelReady -> Eyebrow(tr("Modelo de voz ${(dlPct * 100).toInt()}% · ya casi", "Speech model ${(dlPct * 100).toInt()}% · almost there"))
                        !micGranted -> Eyebrow(tr("Necesita el micrófono · regresa un paso", "Needs the microphone · go back a step"))
                        else -> Eyebrow(tr("Toca para hablar", "Tap to talk"))
                    }
                }
            } else {
                Text(tr("Todo eso pasó en tu teléfono.", "That was all on your phone."), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("Sin cuenta, sin subir nada, sin servidor. Esto es lo que dijiste:", "No account, no upload, no server. Here's what you just said:"),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surface)
                        .border(1.dp, cs.outline, RoundedCornerShape(16.dp)).padding(18.dp),
                ) {
                    // Only when cleanup actually changed something — otherwise the "before"
                    // is just the answer twice, which undersells rather than demonstrates.
                    if (raw != null) {
                        Eyebrow(tr("Dijiste", "You said"))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "“$raw”", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        )
                        Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Box(Modifier.weight(1f).height(1.dp).background(cs.outline))
                            Text(tr("LIMPIO", "CLEANED UP"), style = MonoEyebrow, fontSize = 10.sp, color = cs.primary)
                            Box(Modifier.weight(1f).height(1.dp).background(cs.outline))
                        }
                    }
                    Eyebrow(tr("VoiceFlow escribió", "VoiceFlow wrote"))
                    Spacer(Modifier.height(9.dp))
                    Text(result, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
                }
                // Teaching the feature set on the sentence they just said, rather than on
                // canned examples. Every line here is something the cleanup verifiably did to
                // their own words, so it reads as evidence rather than a brochure.
                if (did.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    did.forEach { label ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Icon(Icons.Filled.Check, null, tint = Color(0xFF047857), modifier = Modifier.size(15.dp))
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = cs.onBackground)
                        }
                    }
                }
                if (also != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(also, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                if (did.isEmpty() && also == null) {
                    Spacer(Modifier.height(18.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SuccessPill(tr("¡Listo, funcionó!", "Nice, that worked")) }
                }
            }
        },
        actions = {
            if (result == null) Cta(tr("Omitir por ahora", "Skip for now"), onClick = onNext)
            else Cta(tr("Continuar", "Continue"), onClick = onNext)
        },
    )
}

@Composable
private fun DoneStep(
    micOn: Boolean, sttOn: Boolean,
    a11yOn: Boolean, a11ySkipped: Boolean, personalCount: Int,
    onPersonalize: () -> Unit, onStart: () -> Unit, onReplay: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            LogoCircle(100)
            Spacer(Modifier.height(24.dp))
            Text(tr("Todo listo.", "You're all set."), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold, color = cs.onBackground, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            // Teaches both gestures in one line. Wispr Flow spends a whole onboarding screen on
            // the second one, which suggests hold-to-talk is not self-discoverable.
            Text(tr("Toca la burbuja para dictar, o mantenla presionada y suelta para una frase rápida. Todo se queda en tu teléfono.", "Tap the bubble to talk, or hold it and release for a quick line. Everything stays on your phone."), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface).border(1.dp, cs.outline, RoundedCornerShape(14.dp))) {
                RecapRow(tr("Micrófono", "Microphone"), if (micOn) tr("Activado", "On") else tr("Después", "Later"), micOn, false)
                RecapRow(tr("Modelo de voz", "Speech model"), if (sttOn) tr("Listo", "On") else tr("Descargando", "Downloading"), sttOn, false)
                RecapRow(tr("Inserción automática", "Auto-insert"), if (a11yOn) tr("Activada", "On") else if (a11ySkipped) tr("Portapapeles", "Clipboard") else tr("Después", "Later"), a11yOn, a11ySkipped)
            }
            Spacer(Modifier.height(12.dp))
            // Personalization used to be a whole step of four rows, each launching its own
            // editor — a maze in the middle of onboarding, for something nobody needs before
            // their first dictation. It lives in Settings; this is the signpost to it.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface)
                    .border(1.dp, cs.outline, RoundedCornerShape(14.dp)).clickable { onPersonalize() }.padding(15.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Enséñale tus palabras", "Teach it your words"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = cs.onBackground)
                    Text(
                        tr("Nombres, términos, contactos, tono por app. Todo se aprende en tu teléfono.", "Names, jargon, contacts, tone per app. All learned on-device."),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
                ActionPill(if (personalCount > 0) tr("$personalCount listos", "$personalCount set up") else tr("Configurar", "Set up"))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                tr("Puedes cambiar cualquier permiso después en Ajustes.", "Every permission here can be changed later in Settings."),
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
        Cta(tr("Empezar a usar VoiceFlow", "Start using VoiceFlow"), onClick = onStart)
        SubLink(tr("Repetir la introducción", "Replay onboarding"), onClick = onReplay)
    }
}

@Composable
private fun RecapRow(label: String, status: String, on: Boolean, alt: Boolean) {
    val cs = MaterialTheme.colorScheme
    val green = Color(0xFF047857)
    Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (on || alt) Color(0xFFE7F3EA) else cs.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, null, tint = if (on || alt) green else cs.onSurfaceVariant, modifier = Modifier.size(13.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = cs.onBackground, modifier = Modifier.weight(1f))
        Text(status, style = MonoEyebrow, fontSize = 11.sp, color = if (on) green else cs.onSurfaceVariant)
    }
}
