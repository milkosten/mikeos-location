package com.mikeos.location.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// MikeOS Setup palette — matches the boot cinematic: a violet night sky with a
// luminous figure descending, resolving to the MikeOS wordmark in cool white with
// a cyan glow and the tagline "A new experience".

// Sky gradient (top -> bottom): deep indigo -> violet.
val SkyTop = Color(0xFF0A081C)
val SkyMid = Color(0xFF1C1444)
val SkyBottom = Color(0xFF3A2278)

// Accents.
val Cyan = Color(0xFF60E0FF)        // cyan glow — the MikeOS signature accent
val Gold = Color(0xFFFFD68C)        // warm arrival — primary CTA / success, used sparingly
val CoolWhite = Color(0xFFF0F6FF)   // primary text
val MutedViolet = Color(0xFF9678FF) // secondary / labels

// Violet-biased dark surfaces (never flat grey).
val Surface = Color(0xFF150F31)
val SurfaceVariant = Color(0xFF241A4D)
val SurfaceHi = Color(0xFF2E2160)
val DangerRed = Color(0xFFF06A7A)

private val MikeLocationColors = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF241300),
    secondary = Cyan,
    onSecondary = Color(0xFF04121A),
    background = SkyTop,
    onBackground = CoolWhite,
    surface = Surface,
    onSurface = CoolWhite,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = MutedViolet,
    error = DangerRed,
)

@Composable
fun MikeLocationTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MikeLocationColors,
        typography = Typography(),
        content = content,
    )
}
