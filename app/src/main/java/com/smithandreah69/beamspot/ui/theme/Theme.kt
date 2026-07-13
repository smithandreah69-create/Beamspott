package com.smithandreah69.beamspot.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = HostCyan,
    secondary = GuestAmber,
    background = InkBackground,
    surface = PanelSurface,
    onBackground = PaperText,
    onSurface = PaperText,
    onPrimary = InkBackground,
    onSecondary = InkBackground
  )

private val LightColorScheme =
  lightColorScheme(
    primary = LightHostCyan,
    secondary = LightGuestAmber,
    background = LightBackground,
    surface = LightSurface,
    onBackground = LightPrimaryText,
    onSurface = LightPrimaryText,
    onPrimary = LightSurface,
    onSecondary = LightSurface
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false, // Set to false to enforce our premium brand palette
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
