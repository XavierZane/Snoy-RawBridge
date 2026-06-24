package com.rawbridge.app.ui.theme

import com.rawbridge.app.ThemeMode
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val RawBridgeLightColors: ColorScheme = lightColorScheme(
    primary = RawBridgePrimary,
    onPrimary = RawBridgeOnPrimary,
    primaryContainer = RawBridgePrimaryContainer,
    onPrimaryContainer = RawBridgeOnPrimaryContainer,
    secondary = RawBridgeSecondary,
    onSecondary = RawBridgeOnSecondary,
    secondaryContainer = RawBridgeSecondaryContainer,
    onSecondaryContainer = RawBridgeOnSecondaryContainer,
    background = RawBridgeBackground,
    surface = RawBridgeSurface,
    surfaceVariant = RawBridgeSurfaceVariant,
    onSurface = RawBridgeOnSurface,
    onSurfaceVariant = RawBridgeOnSurfaceVariant,
    outline = RawBridgeOutline,
    error = RawBridgeError,
    errorContainer = RawBridgeErrorContainer,
    onErrorContainer = RawBridgeOnErrorContainer,
)

private val RawBridgeDarkColors: ColorScheme = darkColorScheme(
    primary = RawBridgePrimaryDark,
    onPrimary = RawBridgeOnPrimaryDark,
    primaryContainer = RawBridgePrimaryContainerDark,
    onPrimaryContainer = RawBridgeOnPrimaryContainerDark,
    secondary = RawBridgeSecondaryDark,
    onSecondary = RawBridgeOnSecondaryDark,
    secondaryContainer = RawBridgeSecondaryContainerDark,
    onSecondaryContainer = RawBridgeOnSecondaryContainerDark,
    background = RawBridgeBackgroundDark,
    surface = RawBridgeSurfaceDark,
    surfaceVariant = RawBridgeSurfaceVariantDark,
    onSurface = RawBridgeOnSurfaceDark,
    onSurfaceVariant = RawBridgeOnSurfaceVariantDark,
    outline = RawBridgeOutlineDark,
    error = RawBridgeErrorDark,
    onError = RawBridgeOnErrorDark,
    errorContainer = RawBridgeErrorContainerDark,
    onErrorContainer = RawBridgeOnErrorContainerDark,
)

private val RawBridgeShapes = Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(34.dp),
)

@Composable
fun RawBridgeTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val useDarkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    MaterialTheme(
        colorScheme = if (useDarkTheme) RawBridgeDarkColors else RawBridgeLightColors,
        typography = RawBridgeTypography,
        shapes = RawBridgeShapes,
        content = content,
    )
}
