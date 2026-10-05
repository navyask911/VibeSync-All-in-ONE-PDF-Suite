package com.vibesync.toolkit.pdf.core.designsystem

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = VibeSyncCrimsonLight,
    onPrimary = SurfaceLight,
    primaryContainer = VibeSyncCrimsonDark,
    onPrimaryContainer = VibeSyncWhite,
    secondary = VibeSyncRubyAccent,
    onSecondary = VibeSyncWhite,
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondaryDark,
    error = DangerRed
)

private val LightColorScheme = lightColorScheme(
    primary = VibeSyncCrimsonPrimary,
    onPrimary = VibeSyncWhite,
    primaryContainer = VibeSyncCrimsonDark,
    onPrimaryContainer = VibeSyncWhite,
    secondary = VibeSyncRubyDeep,
    onSecondary = VibeSyncWhite,
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = TextSecondaryLight,
    error = DangerRed
)

@Composable
fun VibeSyncPdfTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = VibeSyncCrimsonDark.toArgb()
            window.navigationBarColor = if (darkTheme) BackgroundDark.toArgb() else SurfaceLight.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun OmniPdfTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) = VibeSyncPdfTheme(darkTheme = darkTheme, content = content)
