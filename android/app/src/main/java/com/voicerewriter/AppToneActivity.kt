package com.voicerewriter

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.voicerewriter.textproc.AppContext
import com.voicerewriter.ui.FlowBrush
import com.voicerewriter.ui.VoiceFlowTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Edit the tone the AI polish uses in each kind of app (formal in email, casual in chat, …)
 * and pin specific apps to each tone. Tones are shown and saved in the current app language;
 * the defaults are prefilled and can be replaced, or cleared for "no tone change".
 */
class AppToneActivity : ComponentActivity() {

    private data class InstalledApp(val pkg: String, val label: String, val icon: ImageBitmap?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = AppToneRepository(applicationContext)
        setContent {
            VoiceFlowTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ToneScreen(repo) }
            }
        }
    }

    private val categories = listOf(
        AppContext.Category.EMAIL, AppContext.Category.CHAT,
        AppContext.Category.SOCIAL, AppContext.Category.NOTES,
        AppContext.Category.GENERIC,
    )

    /** Every app with a launcher entry, sorted by name. Icons are rasterized small, off the main thread. */
    private suspend fun loadApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != packageName }
            .mapNotNull { pkg ->
                runCatching {
                    val info = pm.getApplicationInfo(pkg, 0)
                    val icon = runCatching { pm.getApplicationIcon(info).toBitmap(72, 72).asImageBitmap() }.getOrNull()
                    InstalledApp(pkg, pm.getApplicationLabel(info).toString(), icon)
                }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ToneScreen(repo: AppToneRepository) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var loaded by remember { mutableStateOf(false) }
        var data by remember { mutableStateOf(AppToneRepository.Data(emptyMap(), emptyMap())) }
        val values = remember { mutableStateMapOf<String, String>() }   // category key → tone
        val assigned = remember { mutableStateMapOf<String, String>() } // package → category key
        var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
        var pickerFor by remember { mutableStateOf<AppContext.Category?>(null) }

        LaunchedEffect(Unit) {
            data = repo.load()
            val mine = data.tones[Lang.code].orEmpty()
            for (c in categories) values[c.key] = mine[c.key] ?: AppContext.defaultTone(c)
            assigned.putAll(data.apps)
            loaded = true
            apps = loadApps()
        }
        if (!loaded) return

        fun appFor(pkg: String) = apps?.firstOrNull { it.pkg == pkg }

        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(tr("Tono por app", "Tone by app"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    tr(
                        "Cómo se pule el dictado en cada tipo de app. Solo se usa cuando “Pulir con IA” está activado. " +
                            "Deja un tono en blanco para no cambiar el estilo. Elige apps para cada tono y así sabrás " +
                            "exactamente cuál se activa en cada una.",
                        "How dictation is polished in each kind of app, used only when “Polish with AI” is on. " +
                            "Leave a tone blank for no style change. Pick apps for each tone so you know exactly " +
                            "which one applies where.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (c in categories) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth().animateContentSize(),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(FlowBrush))
                                Text(c.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                val def = AppContext.defaultTone(c)
                                if (values[c.key].orEmpty() != def) {
                                    TextButton(onClick = { values[c.key] = def }) { Text(tr("Restablecer", "Reset")) }
                                }
                            }
                            if (c == AppContext.Category.GENERIC) {
                                Text(
                                    tr("Para apps que no encajan en otra categoría.", "For apps that don't fit another category."),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedTextField(
                                value = values[c.key].orEmpty(),
                                onValueChange = { values[c.key] = it },
                                placeholder = { Text(tr("Sin cambio de tono", "No tone change")) },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            val mine = assigned.filterValues { it == c.key }.keys.sortedBy { appFor(it)?.label?.lowercase() ?: it }
                            Text(
                                if (mine.isEmpty()) tr("Apps: se detectan automáticamente", "Apps: detected automatically")
                                else tr("Apps con este tono", "Apps using this tone"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (pkg in mine) {
                                    val app = appFor(pkg)
                                    AppChip(app?.label ?: pkg, app?.icon) { assigned.remove(pkg) }
                                }
                                Row(
                                    Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer)
                                        .clickable { pickerFor = c }.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.Add, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Spacer(Modifier.size(4.dp))
                                    Text(tr("Elegir apps", "Choose apps"), style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = {
                    scope.launch {
                        val tones = data.tones.toMutableMap()
                        tones[Lang.code] = categories.associate { it.key to values[it.key].orEmpty() }
                        repo.save(AppToneRepository.Data(tones, assigned.toMap()))
                        Toast.makeText(context, tr("Guardado", "Saved"), Toast.LENGTH_SHORT).show()
                        finish()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).height(52.dp),
                shape = RoundedCornerShape(26.dp),
            ) { Text(tr("Guardar", "Save")) }
        }

        pickerFor?.let { cat ->
            AppPicker(
                category = cat,
                apps = apps,
                assigned = assigned,
                onToggle = { pkg ->
                    if (assigned[pkg] == cat.key) assigned.remove(pkg) else assigned[pkg] = cat.key
                },
                onDismiss = { pickerFor = null },
            )
        }
    }

    @Composable
    private fun AppChip(label: String, icon: ImageBitmap?, onRemove: () -> Unit) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(icon, 24)
            Spacer(Modifier.size(6.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(max = 24.dp))
            Box(Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, tr("Quitar", "Remove"), Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun AppIcon(icon: ImageBitmap?, sizeDp: Int) {
        if (icon != null) Image(icon, null, Modifier.size(sizeDp.dp).clip(CircleShape))
        else Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(FlowBrush))
    }

    @Composable
    private fun AppPicker(
        category: AppContext.Category,
        apps: List<InstalledApp>?,
        assigned: Map<String, String>,
        onToggle: (String) -> Unit,
        onDismiss: () -> Unit,
    ) {
        var query by remember { mutableStateOf("") }
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(0.94f).fillMaxSize(0.86f),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr("Apps para “${category.label}”", "Apps for “${category.label}”"),
                        style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        placeholder = { Text(tr("Buscar app", "Search apps")) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    if (apps == null) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        val shown = apps.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
                        LazyColumn(Modifier.weight(1f)) {
                            items(shown, key = { it.pkg }) { app ->
                                val current = assigned[app.pkg]
                                val checked = current == category.key
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                        .clickable { onToggle(app.pkg) }.padding(vertical = 8.dp, horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    AppIcon(app.icon, 36)
                                    Column(Modifier.weight(1f)) {
                                        Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val other = AppContext.Category.fromKey(current)
                                        if (other != null && !checked) {
                                            Text(tr("Ahora: ${other.label}", "Now: ${other.label}"),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Checkbox(checked = checked, onCheckedChange = { onToggle(app.pkg) })
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(tr("Listo", "Done")) }
                    }
                }
            }
        }
    }
}
