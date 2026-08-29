package com.avi.journal.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// Indigo, shared with WeightTracker and CheckCheck.
val Indigo500 = Color(0xFF6366F1)
val Indigo400 = Color(0xFF818CF8)
val Indigo600 = Color(0xFF4F46E5)
val Violet500 = Color(0xFF8B5CF6)

val Ink900 = Color(0xFF16161D)
val Ink600 = Color(0xFF52525F)
val Ink400 = Color(0xFF8B8B99)

val Cloud50 = Color(0xFFF6F6FB)
val Cloud0 = Color(0xFFFFFFFF)

val Night950 = Color(0xFF0E0F13)
val Night900 = Color(0xFF131419)
val Night800 = Color(0xFF1A1B22)
val Mist200 = Color(0xFFE6E6EF)
val Mist400 = Color(0xFF9C9CAD)

/* ─── Mood ramp ─────────────────────────────────────────────────────────────
   A DIVERGING scale: two hues either side of a neutral midpoint, not a
   traffic-light rainbow. The previous ramp (red / amber / slate / green /
   green) failed on two counts that were measured, not guessed:

     · Good #22B07D and Great #10B981 sat ΔE 2.5 apart in OKLab under normal
       vision — visually the same colour, so the top of the scale carried no
       information at all.
     · Amber put a third hue on one arm, which is what makes a diverging scale
       read as a rainbow rather than as "distance either side of neutral".

   These steps were chosen by searching the Tailwind ramps and validating each
   candidate rather than by eye — an earlier hand-picked set that looked fine
   collapsed to ΔE 2.4 under deuteranopia.

   Dark is a SELECTED set of steps, not an inversion of the light one: on a
   dark surface the extremes have to be the brightest steps, so the arms run
   the other way. All five dark steps clear 3:1 against the dark card; in
   light, Low and Good sit below 3:1, which is permitted here because every
   mood colour in this app is always accompanied by its text label (the pad
   phrase, the bar labels, the entry card's "Good · Lively").
─────────────────────────────────────────────────────────────────────────── */

val LightMoodRamp = listOf(
    Color(0xFFDC2626),  // Rough — vivid red
    Color(0xFFF87171),  // Low   — softer red
    Color(0xFF475569),  // Okay  — neutral slate
    Color(0xFF22D3EE),  // Good  — bright cyan
    Color(0xFF0891B2),  // Great — deep cyan
)

val DarkMoodRamp = listOf(
    Color(0xFFF87171),  // Rough — bright red
    Color(0xFFDC2626),  // Low   — deeper red
    Color(0xFFA8B0BD),  // Okay  — neutral
    Color(0xFF0891B2),  // Good  — deeper cyan
    Color(0xFF67E8F9),  // Great — bright cyan
)

/**
 * Colour for a continuous pleasantness value in 1..5.
 *
 * Interpolated rather than snapped to five swatches because the mood pad is
 * continuous: 3.4 should not look identical to 3.0, or the control would imply
 * a precision the colour doesn't show.
 */
fun rampColor(ramp: List<Color>, value: Float): Color {
    val clamped = value.coerceIn(1f, 5f)
    val index = (clamped - 1f).toInt().coerceAtMost(ramp.lastIndex - 1)
    return lerp(ramp[index], ramp[index + 1], clamped - 1f - index)
}

/** Theme-aware mood colour. Use this from composables. */
@Composable
@ReadOnlyComposable
fun moodColor(value: Float): Color = rampColor(AppTheme.palette.moodRamp, value)

@Immutable
data class AppPalette(
    val accent: Color,
    val accentSoft: Color,
    val gradientStart: Color,
    val gradientEnd: Color,
    val cardSurface: Color,
    val screenBackground: Color,
    val subtleText: Color,
    val hairline: Color,
    /** Recessed panel behind the mood pad — reads as inset, below the card. */
    val padSurface: Color,
    /**
     * Grid inside the mood pad. A separate token from [hairline] because the
     * hairline sits at 1.2:1 against the dark card — invisible — and the pad
     * needs its lines to actually read as a scale.
     */
    val padGrid: Color,
    /** How strongly the pad's colour field shows. Dark needs more to register. */
    val padFieldAlpha: Float,
    val moodRamp: List<Color>,
)

val LightAppPalette = AppPalette(
    accent = Indigo500,
    accentSoft = Color(0xFFEEF2FF),
    gradientStart = Indigo500,
    gradientEnd = Violet500,
    cardSurface = Cloud0,
    screenBackground = Cloud50,
    subtleText = Ink400,
    hairline = Color(0xFFEDEDF3),
    padSurface = Color(0xFFF4F4F9),
    padGrid = Color(0xFFE2E2EC),
    padFieldAlpha = 0.14f,
    moodRamp = LightMoodRamp,
)

val DarkAppPalette = AppPalette(
    accent = Indigo400,
    accentSoft = Color(0xFF23244A),
    gradientStart = Indigo600,
    gradientEnd = Color(0xFF7C3AED),
    cardSurface = Night800,
    screenBackground = Night950,
    subtleText = Mist400,
    hairline = Color(0xFF2C2D38),
    padSurface = Night900,
    padGrid = Color(0xFF33343F),
    padFieldAlpha = 0.22f,
    moodRamp = DarkMoodRamp,
)
