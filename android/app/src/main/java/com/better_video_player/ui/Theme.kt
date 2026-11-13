package com.better_video_player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The palette and shapes, as in the desktop app's Theme.qml: black surfaces with a light
// purple accent.
object Palette {
    val background = Color(0xFF000000)
    val surface = Color(0xFF0F0F0F)
    val surfaceHigh = Color(0xFF1C1C1C)
    val dialog = Color(0xFF141414)
    val accent = Color(0xFFC9A7FF)
    val accentText = Color(0xFF1E0B3D)            // text and icons on the accent
    val accentSoft = accent.copy(alpha = 0.18f)   // selected tabs, soft buttons
    val error = Color(0xFFF05252)
    val pressed = Color(0x26FFFFFF)
    val textPrimary = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFFADADAD)
    val textTertiary = Color(0xFF858585)
    val border = Color(0x26FFFFFF)
    val borderSoft = Color(0x1FFFFFFF)
    // Grey buttons on dialogs and over the video.
    val control = Color(0xFF2A2A2A)
    // Behind labels laid over thumbnails (durations).
    val badge = Color(0xCC000000)
    // The unfilled part of a progress line on a thumbnail.
    val track = Color(0x59FFFFFF)
}

object Metrics {
    val pagePadding = 16.dp
    val radiusArt = 12.dp
    val radiusCard = 14.dp
    val pageTitleSize = 26.sp
    val sectionSize = 20.sp
    val subsectionSize = 16.sp
}

private val colors = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Palette.accentText,
    primaryContainer = Palette.accentSoft,
    onPrimaryContainer = Palette.accent,
    secondary = Palette.accent,
    onSecondary = Palette.accentText,
    secondaryContainer = Palette.accentSoft,
    onSecondaryContainer = Palette.accent,
    background = Palette.background,
    onBackground = Palette.textPrimary,
    surface = Palette.background,
    onSurface = Palette.textPrimary,
    surfaceVariant = Palette.surfaceHigh,
    onSurfaceVariant = Palette.textSecondary,
    surfaceContainerLowest = Palette.background,
    surfaceContainerLow = Palette.surface,
    surfaceContainer = Palette.surfaceHigh,
    surfaceContainerHigh = Palette.dialog,
    surfaceContainerHighest = Palette.control,
    outline = Palette.border,
    outlineVariant = Palette.borderSoft,
    error = Palette.error,
)

@Composable
fun VesselTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
