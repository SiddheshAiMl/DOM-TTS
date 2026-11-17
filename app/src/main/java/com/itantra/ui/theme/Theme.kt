package com.itantra.ui.theme

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

private val LightColorScheme = lightColorScheme(
    primary = DeepSaffron,
    onPrimary = SandstoneCard,
    primaryContainer = SaffronDark,
    onPrimaryContainer = SandstoneCard,
    secondary = SlateNavy,
    onSecondary = SandstoneCard,
    secondaryContainer = SlateNavyLight,
    onSecondaryContainer = SandstoneCard,
    tertiary = EarthTerracotta,
    onTertiary = SandstoneCard,
    background = SandstoneLight,
    onBackground = TextPrimaryLight,
    surface = SandstoneCard,
    onSurface = TextPrimaryLight,
    surfaceVariant = NeutralBorderLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = NeutralBorderLight,
    error = StatusError,
    onError = SandstoneCard
)

private val DarkColorScheme = darkColorScheme(
    primary = DeepSaffron,
    onPrimary = SandstoneCard,
    primaryContainer = SaffronDark,
    onPrimaryContainer = SandstoneCard,
    secondary = SlateNavyLight,
    onSecondary = SandstoneCard,
    secondaryContainer = SlateNavy,
    onSecondaryContainer = SandstoneCard,
    tertiary = EarthTerracotta,
    onTertiary = SandstoneCard,
    background = CharcoalDark,
    onBackground = TextPrimaryDark,
    surface = CharcoalCard,
    onSurface = TextPrimaryDark,
    surfaceVariant = NeutralBorderDark,
    onSurfaceVariant = TextSecondaryDark,
    outline = NeutralBorderDark,
    error = StatusError,
    onError = SandstoneCard
)

@Composable
fun ITantraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
