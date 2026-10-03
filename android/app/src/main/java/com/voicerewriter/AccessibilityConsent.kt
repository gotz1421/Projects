package com.voicerewriter

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Prominent disclosure + affirmative consent for the AccessibilityService, required by Google
 * Play policy (the service is used to insert text, not as a disability tool, so it can't declare
 * isAccessibilityTool). The disclosure must appear in the normal enable flow — before the deep-link
 * into system Accessibility settings — and describe what data the service accesses, how it's used,
 * and require an explicit user action. Shown at every enable entry point (onboarding + Settings).
 */
object AccessibilityConsent {
    private const val PREFS = "a11y_consent"
    private const val KEY = "granted"

    /** Whether the user has previously acknowledged the disclosure. */
    fun granted(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    /** Record affirmative consent when the user proceeds from the disclosure dialog. */
    fun record(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
}

@Composable
fun AccessibilityConsentDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                tr("Activa la inserción automática", "Turn on auto-insert"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = cs.onSurface,
            )
        },
        text = {
            Column {
                Text(
                    tr("VoiceFlow usa el servicio de Accesibilidad de Android para dos cosas: mostrar la burbuja " +
                        "cuando se abre el teclado y escribir tu texto dictado en el campo donde estás.",
                        "VoiceFlow uses Android's Accessibility service for two things: showing the bubble " +
                        "when the keyboard opens, and typing your dictated text into the field you're in."),
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                DisclosureBullet(
                    tr("A qué accede", "What it accesses"),
                    tr("Al campo de texto donde estás escribiendo y a si el teclado está abierto, para poner ahí tu texto.",
                        "The text field you're focused in and whether the keyboard is open, so it can place your text there."),
                )
                Spacer(Modifier.height(10.dp))
                DisclosureBullet(
                    tr("Cómo se usa", "How it's used"),
                    tr("Solo para insertar tu texto. No se recopila, guarda, registra ni envía nada fuera de tu teléfono.",
                        "Only to insert your text. Nothing is collected, stored, logged, or sent off your phone."),
                )
                Spacer(Modifier.height(10.dp))
                DisclosureBullet(
                    tr("Tú tienes el control", "You stay in control"),
                    tr("Desactívalo cuando quieras en Ajustes de Android → Accesibilidad, u omítelo y pega el texto a mano.",
                        "Turn it off anytime in Android Settings → Accessibility, or skip it and paste manually."),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(tr("Entendido, abrir ajustes", "I understand, open settings")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Ahora no", "Not now")) }
        },
    )
}

@Composable
private fun DisclosureBullet(heading: String, body: String) {
    val cs = MaterialTheme.colorScheme
    Column {
        Text(heading, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
}
