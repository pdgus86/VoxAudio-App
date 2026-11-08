package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Color(0xFF00363A),
    primaryContainer = NeonCyanDark,
    onPrimaryContainer = Color(0xFFB2FDFF),
    secondary = NeonPurple,
    onSecondary = Color(0xFF280058),
    secondaryContainer = NeonPurpleDark,
    onSecondaryContainer = Color(0xFFEADBFF),
    tertiary = RecordingRed,
    onTertiary = Color.White,
    tertiaryContainer = RecordingRedDark,
    onTertiaryContainer = Color(0xFFFFDADA),
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle,
    outlineVariant = Color(0xFF2C2844)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006874),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9EEFFD),
    onPrimaryContainer = Color(0xFF001F24),
    secondary = Color(0xFF7043A6),
    onSecondary = Color.White,
    background = Color(0xFFF9F8FD),
    onBackground = Color(0xFF1B1B22),
    surface = Color.White,
    onSurface = Color(0xFF1B1B22),
    surfaceVariant = Color(0xFFE7E5F0),
    onSurfaceVariant = Color(0xFF47464F),
    outline = Color(0xFF787680)
)

@Composable
fun VoxFlowTheme(
    darkTheme: Boolean = true, // Default to sleek dark mode
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
