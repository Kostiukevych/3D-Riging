package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val StudioColorScheme =
  darkColorScheme(
    primary = CyanRigPrimary,
    onPrimary = StudioObsidian,
    primaryContainer = CyanRigContainer,
    onPrimaryContainer = StudioTextPrimary,
    secondary = IndigoJointSecondary,
    onSecondary = StudioObsidian,
    tertiary = AmberHighlight,
    background = StudioObsidian,
    onBackground = StudioTextPrimary,
    surface = StudioSurface,
    onSurface = StudioTextPrimary,
    surfaceVariant = StudioSurfaceElevated,
    onSurfaceVariant = StudioTextSecondary,
    outline = StudioBorder,
    error = StudioError,
    onError = StudioObsidian,
  )

@Composable
fun Mesh2MotionTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = StudioColorScheme,
    typography = Typography,
    content = content,
  )
}
