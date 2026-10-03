package com.voicerewriter.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * VoiceFlow brand theme: cyan → blue on deep navy, with a soft mint accent for waves and glow.
 * Centralized so every screen (and the bubble) shares one identity.
 *
 * Palette (authoritative hex):
 *  #22D3EE cyan (primary: buttons / accents)    #0EA5E9 blue (secondary: hover / states)
 *  #0B1929 navy (dark surface / processing)     #A7F3D0 mint (waves / glow)
 *  #E5E7EB light (secondary text / icons)       #111827 ink (primary text)
 */

// ---- brand constants (also used for gradients / the bubble) ----
val FlowCyan = Color(0xFF22D3EE)
val FlowBlue = Color(0xFF0EA5E9) // primary on light surfaces
val FlowBlueDeep = Color(0xFF0284C7)
val FlowSky = Color(0xFF2563EB)  // deep end of the signature gradient
val FlowNavy = Color(0xFF0B1929)
val FlowMint = Color(0xFFA7F3D0)
val FlowLight = Color(0xFFE5E7EB)
val FlowInk = Color(0xFF111827)
val FlowWhite = Color(0xFFFFFFFF)

/** The signature gradient (top-left → bottom-right): cyan → blue → deep blue. */
val FlowBrush: Brush
    get() = Brush.linearGradient(0f to FlowCyan, 0.55f to FlowBlue, 1f to FlowSky)

private val LightColors = lightColorScheme(
    primary = FlowBlue,
    onPrimary = FlowWhite,
    primaryContainer = Color(0xFFD5F6FC),
    onPrimaryContainer = Color(0xFF053246),
    secondary = FlowBlueDeep,
    onSecondary = FlowWhite,
    secondaryContainer = Color(0xFFDDF0FB),
    onSecondaryContainer = Color(0xFF0B3550),
    tertiary = Color(0xFF059669),
    onTertiary = FlowWhite,
    background = Color(0xFFF3F7FA),
    onBackground = FlowInk,
    surface = FlowWhite,
    onSurface = FlowInk,
    surfaceVariant = Color(0xFFE8F1F7),
    onSurfaceVariant = Color(0xFF4B5563),
    outline = Color(0xFFD7E0E8),
    outlineVariant = Color(0xFFE5E7EB),
)

private val DarkColors = darkColorScheme(
    primary = FlowCyan,
    onPrimary = FlowNavy,
    primaryContainer = Color(0xFF0E4A63),
    onPrimaryContainer = Color(0xFFD5F6FC),
    secondary = FlowBlue,
    onSecondary = FlowNavy,
    secondaryContainer = Color(0xFF16324A),
    onSecondaryContainer = FlowLight,
    tertiary = FlowMint,
    onTertiary = FlowNavy,
    background = FlowNavy,
    onBackground = FlowLight,
    surface = Color(0xFF12263A),
    onSurface = FlowLight,
    surfaceVariant = Color(0xFF1A3249),
    onSurfaceVariant = Color(0xFFA9B6C4),
    outline = Color(0xFF26415A),
    outlineVariant = Color(0xFF1A3249),
)

@Composable
fun VoiceFlowTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = VoiceFlowTypography,
        content = content,
    )
}
