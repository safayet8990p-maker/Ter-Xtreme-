package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme =
  darkColorScheme(
    primary = TerAccentPink,
    onPrimary = TerBackgroundDark,
    secondary = TerAccentCyan,
    onSecondary = TerBackgroundDark,
    background = TerBackgroundDark,
    onBackground = TerForegroundDark,
    surface = TerBarDark,
    onSurface = TerForegroundDark,
    surfaceVariant = TerBarDark,
    onSurfaceVariant = TerDimDark,
    error = TerErrorDark,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = TerAccentPinkLight,
    onPrimary = TerBackgroundLight,
    secondary = TerAccentCyanLight,
    onSecondary = TerBackgroundLight,
    background = TerBackgroundLight,
    onBackground = TerForegroundLight,
    surface = TerBarLight,
    onSurface = TerForegroundLight,
    surfaceVariant = TerBarLight,
    onSurfaceVariant = TerDimLight,
    error = TerErrorLight,
  )

@Composable
fun TerXtremeTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
