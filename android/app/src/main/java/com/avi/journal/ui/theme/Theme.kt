package com.avi.journal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LightColors = lightColorScheme(
    primary = Indigo500,
    onPrimary = Cloud0,
    secondary = Violet500,
    onSecondary = Cloud0,
    background = Cloud50,
    onBackground = Ink900,
    surface = Cloud0,
    onSurface = Ink900,
    surfaceVariant = Cloud50,
    onSurfaceVariant = Ink600,
    outline = Ink400,
)

private val DarkColors = darkColorScheme(
    primary = Indigo400,
    onPrimary = Night950,
    secondary = Violet500,
    onSecondary = Night950,
    background = Night950,
    onBackground = Mist200,
    surface = Night800,
    onSurface = Mist200,
    surfaceVariant = Night900,
    onSurfaceVariant = Mist400,
    outline = Mist400,
)

private val LocalAppPalette = staticCompositionLocalOf { LightAppPalette }

/**
 * Dynamic colour is deliberately off, matching WeightTracker: the indigo is the
 * identity these apps share, and matching each other matters more than matching
 * the wallpaper.
 */
@Composable
fun JournalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val palette = if (darkTheme) DarkAppPalette else LightAppPalette

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
    ) {
        // LocalContentColor has to be provided explicitly.
        //
        // Material3 defaults it to BLACK, and normally a Surface() provides it
        // instead — but this app paints its own backgrounds and never wraps
        // content in a Surface, so every Text without an explicit `color` was
        // rendering black, in dark mode too. That is what put black headings
        // ("Today", "Enter PIN") on a near-black background.
        CompositionLocalProvider(
            LocalAppPalette provides palette,
            LocalContentColor provides colorScheme.onBackground,
            content = content,
        )
    }
}

/** The app's own palette (gradients, hairlines) alongside MaterialTheme. */
object AppTheme {
    val palette: AppPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalAppPalette.current
}
