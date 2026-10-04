package com.voicerewriter

import android.content.Context
import android.content.Intent
import android.content.pm.InstallSourceInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.voicerewriter.ui.FlowBrush
import com.voicerewriter.ui.FlowCyan
import com.voicerewriter.ui.FlowMint
import com.voicerewriter.ui.FlowWhite
import com.voicerewriter.ui.VoiceFlowTheme

/**
 * Walks the user through turning on VoiceFlow's accessibility service.
 *
 * On Android 13+ an app installed from an APK file is under "restricted settings": its switch in
 * Accessibility is greyed out, and no app can lift that by itself — Android requires the user to
 * do it. The catch is that the "Allow restricted settings" menu item in App info only appears
 * *after* the user has tried the greyed switch once and seen the "access denied" dialog. Nobody
 * guesses that order, so this screen spells it out with a drawing of each system screen and a
 * button that opens the right one.
 */
class AccessibilityGuideActivity : ComponentActivity() {

    companion object {
        private const val PREFS = "a11y_guide"
        private const val KEY_STEP = "step"

        fun intent(ctx: Context) = Intent(ctx, AccessibilityGuideActivity::class.java)

        /**
         * Whether this install is under restricted settings: Android 13+ and not installed by an
         * app store. Store installs skip straight to the last step.
         */
        fun isRestricted(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            val source = runCatching { ctx.packageManager.getInstallSourceInfo(ctx.packageName).packageSource }
                .getOrDefault(InstallSourceInfo.PACKAGE_SOURCE_UNSPECIFIED)
            return source != InstallSourceInfo.PACKAGE_SOURCE_STORE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoiceFlowTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { GuideScreen() }
            }
        }
    }

    private fun savedStep(): Int = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_STEP, 0)
    private fun saveStep(step: Int) = getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_STEP, step).apply()

    private data class Step(
        val title: String,
        val body: String,
        val button: String,
        val open: () -> Unit,
        val art: @Composable () -> Unit,
    )

    @Composable
    private fun GuideScreen() {
        val restricted = remember { isRestricted(this) }
        var enabled by remember { mutableStateOf(SetupUtils.accessibilityEnabled(this)) }
        var current by remember { mutableIntStateOf(if (restricted) savedStep() else 0) }
        // The step whose button sent the user to system settings; coming back means it's done.
        var launched by remember { mutableIntStateOf(-1) }

        val openA11y = { startActivity(SetupUtils.accessibilitySettingsIntent()) }
        val steps = buildList {
            if (restricted) {
                add(Step(
                    tr("Intenta activarla una vez", "Try turning it on once"),
                    tr("En Accesibilidad → Aplicaciones instaladas, toca «VoiceFlow». Saldrá gris y aparecerá " +
                        "«A la app se le negó el acceso». Es normal: toca «Cerrar» y regresa aquí. Este intento " +
                        "es lo que hace aparecer la opción del siguiente paso.",
                        "In Accessibility → Installed apps, tap “VoiceFlow”. It's greyed out and you'll see " +
                        "“App was denied access”. That's expected: tap “Close” and come back here. This attempt " +
                        "is what makes the next step's option appear."),
                    tr("Abrir Accesibilidad", "Open Accessibility"),
                    openA11y,
                ) { DeniedArt() })
                add(Step(
                    tr("Permite los ajustes restringidos", "Allow restricted settings"),
                    tr("En Información de la app, toca los tres puntos ⋮ arriba a la derecha y elige " +
                        "«Permitir ajustes restringidos». Tu teléfono puede pedirte el PIN o la huella.",
                        "In App info, tap the three dots ⋮ at the top right and choose “Allow restricted " +
                        "settings”. Your phone may ask for your PIN or fingerprint."),
                    tr("Abrir información de VoiceFlow", "Open VoiceFlow app info"),
                    { startActivity(SetupUtils.appInfoIntent(this)) },
                ) { AppInfoArt() })
            }
            add(Step(
                tr("Activa VoiceFlow", "Turn on VoiceFlow"),
                tr("Vuelve a Accesibilidad → Aplicaciones instaladas → VoiceFlow y activa el interruptor. " +
                    "Android te pedirá confirmar; es el mismo aviso para cualquier app de accesibilidad.",
                    "Go back to Accessibility → Installed apps → VoiceFlow and turn the switch on. Android " +
                    "will ask you to confirm; it's the same notice for every accessibility app."),
                tr("Abrir Accesibilidad", "Open Accessibility"),
                openA11y,
            ) { ToggleArt() })
        }

        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) {
                    enabled = SetupUtils.accessibilityEnabled(this@AccessibilityGuideActivity)
                    if (enabled) saveStep(0)
                    else if (launched >= 0 && launched == current && current < steps.lastIndex) {
                        current++
                        if (restricted) saveStep(current)
                    }
                    launched = -1
                }
            }
            lifecycle.addObserver(obs)
            onDispose { lifecycle.removeObserver(obs) }
        }

        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(tr("Activa la inserción automática", "Turn on auto-insert"),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (enabled) {
                DoneCard()
                return@Column
            }
            Text(
                if (restricted) tr(
                    "Como VoiceFlow se instaló desde un archivo APK, Android bloquea su accesibilidad hasta que " +
                        "tú la autorices. Ninguna app puede saltarse esto, pero son 3 pasos rápidos:",
                    "Because VoiceFlow was installed from an APK file, Android blocks its accessibility until " +
                        "you allow it. No app can skip this, but it's 3 quick steps:")
                else tr("Un solo paso:", "Just one step:"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            steps.forEachIndexed { i, step ->
                StepCard(
                    number = i + 1,
                    step = step,
                    state = when {
                        i < current -> StepState.DONE
                        i == current -> StepState.CURRENT
                        else -> StepState.LATER
                    },
                    onSelect = { current = i; if (restricted) saveStep(i) },
                    onOpen = { launched = i; step.open() },
                )
            }
            TextButton(onClick = { finish() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(tr("Hacerlo después", "Do it later"))
            }
        }
    }

    private enum class StepState { DONE, CURRENT, LATER }

    @Composable
    private fun StepCard(number: Int, step: Step, state: StepState, onSelect: () -> Unit, onOpen: () -> Unit) {
        val cs = MaterialTheme.colorScheme
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = cs.surface,
            tonalElevation = if (state == StepState.CURRENT) 3.dp else 0.dp,
            modifier = Modifier.fillMaxWidth()
                .border(
                    if (state == StepState.CURRENT) 2.dp else 1.dp,
                    if (state == StepState.CURRENT) FlowCyan else cs.outline,
                    RoundedCornerShape(22.dp),
                )
                .clickable(onClick = onSelect)
                .animateContentSize(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(
                            if (state == StepState.LATER) androidx.compose.ui.graphics.SolidColor(cs.surfaceVariant) else FlowBrush,
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state == StepState.DONE) Icon(Icons.Default.Check, null, tint = FlowWhite, modifier = Modifier.size(18.dp))
                        else Text("$number", color = if (state == StepState.LATER) cs.onSurfaceVariant else FlowWhite, fontWeight = FontWeight.Bold)
                    }
                    Text(step.title, style = MaterialTheme.typography.titleMedium,
                        color = if (state == StepState.LATER) cs.onSurfaceVariant else cs.onSurface)
                }
                AnimatedVisibility(visible = state == StepState.CURRENT) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(step.body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        step.art()
                        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(25.dp)) {
                            Text(step.button)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DoneCard() {
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(FlowBrush), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, tint = FlowWhite, modifier = Modifier.size(34.dp))
                }
                Text(tr("¡Listo! La inserción automática está activa.", "Done! Auto-insert is on."),
                    style = MaterialTheme.typography.titleMedium)
                Text(tr("La burbuja aparecerá al abrir el teclado y tu texto se escribirá solo.",
                    "The bubble will appear when the keyboard opens and your text will type itself."),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { finish() }, shape = RoundedCornerShape(25.dp)) { Text(tr("Continuar", "Continue")) }
            }
        }
    }

    // ---------------- illustrations of the system screens ----------------
    // Drawn, not screenshots: they follow the app's language and theme, and stay generic enough
    // to match Samsung, Pixel and other skins.

    private val screenBg = Color(0xFF0E1116)
    private val rowBg = Color(0xFF1A1F27)
    private val textHi = Color(0xFFE5E7EB)
    private val textLo = Color(0xFF8A93A0)
    private val green = Color(0xFF8BD17C)

    @Composable
    private fun MockScreen(title: String, trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(screenBg)
                .border(1.dp, Color(0xFF2A313B), RoundedCornerShape(18.dp)).padding(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("‹  $title", color = green, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    trailing()
                }
                content()
            }
        }
    }

    @Composable
    private fun MockRow(title: String, subtitle: String, dim: Boolean = false, highlight: Boolean = false, trailing: @Composable () -> Unit = {}) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(rowBg)
                .then(if (highlight) Modifier.border(2.dp, FlowCyan, RoundedCornerShape(12.dp)) else Modifier)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).alpha(if (dim) 0.45f else 1f)) {
                Text(title, color = textHi, fontSize = 14.sp)
                Text(subtitle, color = textLo, fontSize = 11.sp)
            }
            trailing()
        }
    }

    /** A pulsing ring that says "tap here". */
    @Composable
    private fun TapHint(modifier: Modifier = Modifier) {
        val t = rememberInfiniteTransition(label = "tap")
        val s by t.animateFloat(0.8f, 1.15f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "s")
        Box(modifier.size(26.dp).scale(s).clip(CircleShape).background(FlowCyan.copy(alpha = 0.35f))
            .border(2.dp, FlowCyan, CircleShape))
    }

    @Composable
    private fun DeniedArt() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MockScreen(tr("Aplicaciones instaladas", "Installed apps")) {
                MockRow("Voice Access", tr("Controla el dispositivo con la voz", "Control your device by voice"))
                Box {
                    MockRow("VoiceFlow", tr("Función controlada por configuración restringida", "Controlled by restricted setting"),
                        dim = true, highlight = true)
                    TapHint(Modifier.align(Alignment.CenterEnd).offset(x = (-14).dp))
                }
            }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF262B24)).padding(14.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("A la app se le negó el acceso", "App was denied access"), color = textHi, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFAED39B)).padding(horizontal = 28.dp, vertical = 6.dp)) {
                        Text(tr("Cerrar", "Close"), color = Color(0xFF1B2A12), fontSize = 13.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun AppInfoArt() {
        MockScreen(
            tr("Información de la app", "App info"),
            trailing = {
                Box(contentAlignment = Alignment.Center) {
                    TapHint()
                    Icon(Icons.Default.MoreVert, null, tint = textHi, modifier = Modifier.size(20.dp))
                }
            },
        ) {
            Box(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(FlowBrush), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_voiceflow), null, tint = FlowWhite, modifier = Modifier.size(20.dp))
                        }
                        Column {
                            Text("VoiceFlow", color = green, fontSize = 15.sp)
                            Text(tr("Instalada", "Installed"), color = textLo, fontSize = 11.sp)
                        }
                    }
                    MockRow(tr("Notificaciones", "Notifications"), tr("Permitido", "Allowed"))
                    MockRow(tr("Permisos", "Permissions"), tr("Micrófono", "Microphone"))
                }
                // The menu that the ⋮ opens.
                Box(
                    Modifier.align(Alignment.TopEnd).width(210.dp).clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF3A3F47)).border(2.dp, FlowCyan, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(tr("Permitir ajustes restringidos", "Allow restricted settings"), color = textHi, fontSize = 13.sp)
                }
            }
        }
    }

    @Composable
    private fun ToggleArt() {
        MockScreen("VoiceFlow") {
            MockRow(tr("Usar VoiceFlow", "Use VoiceFlow"), tr("Activado", "On"), highlight = true) {
                Box(
                    Modifier.width(44.dp).height(24.dp).clip(RoundedCornerShape(50)).background(FlowMint),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(Modifier.padding(3.dp).size(18.dp).clip(CircleShape).background(FlowWhite))
                }
            }
            Text(
                tr("Confirma con «Permitir» en el aviso de Android.", "Confirm with “Allow” on Android's notice."),
                color = textLo, fontSize = 11.sp,
            )
        }
    }
}
