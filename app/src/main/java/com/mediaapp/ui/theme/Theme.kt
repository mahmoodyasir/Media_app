package com.mediaapp.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.mediaapp.data.model.ThemeMode

private val Ink = Color(0xFF07080A)
private val InkSurface = Color(0xFF12141A)
private val InkRaised = Color(0xFF1C1F27)
private val Lime = Color(0xFFD6F25C)
private val LimeInk = Color(0xFF1A2108)
private val Mist = Color(0xFFC5CAD6)
private val Paper = Color(0xFFF6F7F4)
private val PaperSurface = Color(0xFFFFFFFF)
private val Forest = Color(0xFF3E4F12)

private val DarkColors = darkColorScheme(
    primary = Lime,
    onPrimary = LimeInk,
    primaryContainer = Color(0xFF2A3810),
    onPrimaryContainer = Color(0xFFE7F7B0),
    secondary = Color(0xFFB7C0A4),
    onSecondary = Color(0xFF243016),
    tertiary = Color(0xFFFFC56E),
    onTertiary = Color(0xFF3A2800),
    background = Ink,
    onBackground = Color(0xFFF3F4F6),
    surface = InkSurface,
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = InkRaised,
    onSurfaceVariant = Mist,
    outline = Color(0xFF3A3F4B),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0707),
)

private val LightColors = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4F2B8),
    onPrimaryContainer = Color(0xFF1A2408),
    secondary = Color(0xFF55624A),
    onSecondary = Color.White,
    tertiary = Color(0xFF8A5A00),
    background = Paper,
    onBackground = Color(0xFF16171A),
    surface = PaperSurface,
    onSurface = Color(0xFF16171A),
    surfaceVariant = Color(0xFFE8EBE3),
    onSurfaceVariant = Color(0xFF44484F),
    outline = Color(0xFFC5CBBE),
    error = Color(0xFFBA1A1A),
)

private val AppType = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 34.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.2.sp),
)

@Composable
fun MediaAppTheme(
    mode: ThemeMode,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= 31 && !dark -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, typography = AppType, content = content)
}
