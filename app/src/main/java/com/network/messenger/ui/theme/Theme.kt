package com.network.messenger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.network.messenger.data.preferences.ThemeMode

@Immutable
data class MessengerExtendedColors(
    val isDark: Boolean,
    val outgoingBubble: Color,
    val onOutgoingBubble: Color,
    val incomingBubble: Color,
    val onIncomingBubble: Color,
    val incomingBubbleBorder: Color,
    val readTick: Color,
    val deliveredTick: Color,
    val queuedTick: Color,
    val onlineDot: Color,
    val pttActive: Color,
    val warningBannerBg: Color,
    val warningBannerAccent: Color,
    val logPanelSurface: Color,
    val logPanelHeader: Color,
    val logPanelBorder: Color,
    val logTextPrimary: Color,
    val logTextMuted: Color
)

private val LightExtendedColors = MessengerExtendedColors(
    isDark = false,
    outgoingBubble = LightOutgoingBubble,
    onOutgoingBubble = LightOnOutgoingBubble,
    incomingBubble = LightIncomingBubble,
    onIncomingBubble = LightOnIncomingBubble,
    incomingBubbleBorder = LightOutline,
    readTick = Color(0xFF10B981),
    deliveredTick = Color(0xFF64748B),
    queuedTick = Color(0xFFD97706),
    onlineDot = Color(0xFF10B981),
    pttActive = PttActiveRed,
    warningBannerBg = Color(0xFFFDE8EC),
    warningBannerAccent = LightError,
    logPanelSurface = LightLogSurface,
    logPanelHeader = LightLogHeader,
    logPanelBorder = LightLogBorder,
    logTextPrimary = ConsoleTextPrimary,
    logTextMuted = ConsoleTextMuted
)

private val DarkExtendedColors = MessengerExtendedColors(
    isDark = true,
    outgoingBubble = DarkOutgoingBubble,
    onOutgoingBubble = DarkOnOutgoingBubble,
    incomingBubble = DarkIncomingBubble,
    onIncomingBubble = DarkOnIncomingBubble,
    incomingBubbleBorder = DarkOutline,
    readTick = SignalEmerald,
    deliveredTick = DarkTextSecondary,
    queuedTick = SignalAmber,
    onlineDot = SignalEmerald,
    pttActive = PttActiveRed,
    warningBannerBg = Color(0xFF3A1620),
    warningBannerAccent = SignalRose,
    logPanelSurface = ConsoleCanvas,
    logPanelHeader = ConsoleSurface,
    logPanelBorder = ConsoleBorder,
    logTextPrimary = ConsoleTextPrimary,
    logTextMuted = ConsoleTextMuted
)

val LocalMessengerColors = staticCompositionLocalOf { DarkExtendedColors }

private val MessengerLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = Color.White,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightBackground,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceVariant,
    outline = LightOutline,
    outlineVariant = Color(0xFFE2E9F0),
    error = LightError,
    onError = Color.White
)

private val MessengerDarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkBackground,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkBackground,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkBackground,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    surfaceContainerLowest = ConsoleCanvas,
    surfaceContainerLow = DarkBackground,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = Color(0xFF1F2B3A),
    error = DarkError,
    onError = DarkBackground
)

@Composable
fun NetworkMessengerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (useDark) MessengerDarkColorScheme else MessengerLightColorScheme
    val extendedColors = if (useDark) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(LocalMessengerColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MeshTypography,
            content = content
        )
    }
}
