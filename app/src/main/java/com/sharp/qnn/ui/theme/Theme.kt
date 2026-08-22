package com.sharp.qnn.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Default blue-purple palette (used below Android 12 or when system theme color is off)
private val LightColors = lightColorScheme(
    primary = BluePurple40,
    onPrimary = Color.White,
    primaryContainer = BluePurple90,
    onPrimaryContainer = BluePurple10,
    secondary = Secondary40,
    onSecondary = Color.White,
    secondaryContainer = Secondary90,
    onSecondaryContainer = Neutral20,
    tertiary = Tertiary40,
    onTertiary = Color.White,
    tertiaryContainer = Tertiary90,
    onTertiaryContainer = Neutral20,
    error = Error40,
    onError = Color.White,
    errorContainer = Error90,
    onErrorContainer = Error40,
    background = Neutral99,
    onBackground = Neutral10,
    surface = Neutral99,
    onSurface = Neutral10,
    // surfaceContainer steps: neutral elevations from the same family as surface,
    // keeping tonal hierarchy consistent under the fallback palette
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    surfaceDim = LightSurfaceDim,
    surfaceBright = LightSurfaceBright,
    surfaceTint = BluePurple40,
    surfaceVariant = NeutralVariant90,
    onSurfaceVariant = NeutralVariant30,
    outline = NeutralVariant50,
    outlineVariant = NeutralVariant70,
    inverseSurface = Neutral20,
    inverseOnSurface = Neutral90,
    inversePrimary = BluePurple80
)

private val DarkColors = darkColorScheme(
    primary = BluePurple80,
    onPrimary = BluePurple20,
    primaryContainer = BluePurple30,
    onPrimaryContainer = BluePurple90,
    secondary = Secondary80,
    onSecondary = Neutral20,
    secondaryContainer = Secondary40,
    onSecondaryContainer = Secondary90,
    tertiary = Tertiary80,
    onTertiary = Neutral20,
    tertiaryContainer = Tertiary40,
    onTertiaryContainer = Tertiary90,
    error = Error80,
    onError = Error40,
    errorContainer = Error40,
    onErrorContainer = Error90,
    background = Neutral10,
    onBackground = Neutral90,
    surface = Neutral10,
    onSurface = Neutral90,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    surfaceDim = DarkSurfaceDim,
    surfaceBright = DarkSurfaceBright,
    surfaceTint = BluePurple80,
    surfaceVariant = NeutralVariant30,
    onSurfaceVariant = NeutralVariant80,
    outline = NeutralVariant60,
    outlineVariant = NeutralVariant30,
    inverseSurface = Neutral95,
    inverseOnSurface = Neutral10,
    inversePrimary = BluePurple40
)

/**
 * SHARP QNN 主题。
 * SHARP QNN theme.
 *
 * - Android 12+ 默认启用系统主题色 (Material You)；
 * - System theme color (Material You) is enabled by default on Android 12+.
 * - 低版本或关闭时回退到蓝紫色调色板。
 * - Falls back to the blue-purple palette on older versions or when disabled.
 */
@Composable
fun SHARPQNNTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
