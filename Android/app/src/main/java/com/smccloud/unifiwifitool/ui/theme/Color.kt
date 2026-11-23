package com.smccloud.unifiwifitool.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Light Mode M3 Brand & Surface Tokens
val LightPrimary = Color(0xFF0F766E)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFCCFBF1)
val LightOnPrimaryContainer = Color(0xFF042F2E)

val LightSecondary = Color(0xFF2F6FED)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFDBEAFE)
val LightOnSecondaryContainer = Color(0xFF1E3A8A)

val LightTertiary = Color(0xFFB7791F)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFEF3C7)
val LightOnTertiaryContainer = Color(0xFF78350F)

val LightError = Color(0xFFD93A4A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFE4E6)
val LightOnErrorContainer = Color(0xFF881337)

val LightBackground = Color(0xFFF3F6FB)
val LightOnBackground = Color(0xFF122033)
val LightSurface = Color(0xFFF7F9FD)
val LightOnSurface = Color(0xFF122033)
val LightSurfaceVariant = Color(0xFFEEF3FA)
val LightOnSurfaceVariant = Color(0xFF5D6D84)
val LightOutline = Color(0xFFD7E0EC)
val LightOutlineVariant = Color(0xFFE8EEF6)

val LightSurfaceDim = Color(0xFFE4EAF3)
val LightSurfaceBright = Color(0xFFFFFFFF)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFFFFFFF)
val LightSurfaceContainer = Color(0xFFF5F9FE)
val LightSurfaceContainerHigh = Color(0xFFEEF3FA)
val LightSurfaceContainerHighest = Color(0xFFE4EAF3)

// Dark Mode M3 Brand & Surface Tokens
val DarkPrimary = Color(0xFF30D6C4)
val DarkOnPrimary = Color(0xFF05201D)
val DarkPrimaryContainer = Color(0xFF0D3B38)
val DarkOnPrimaryContainer = Color(0xFFA7F3D0)

val DarkSecondary = Color(0xFF6AA2FF)
val DarkOnSecondary = Color(0xFF081E4B)
val DarkSecondaryContainer = Color(0xFF172B4D)
val DarkOnSecondaryContainer = Color(0xFFC2D6FF)

val DarkTertiary = Color(0xFFF6B955)
val DarkOnTertiary = Color(0xFF2C1A00)
val DarkTertiaryContainer = Color(0xFF3D2808)
val DarkOnTertiaryContainer = Color(0xFFFFD188)

val DarkError = Color(0xFFFF7080)
val DarkOnError = Color(0xFF3B050C)
val DarkErrorContainer = Color(0xFF3B1219)
val DarkOnErrorContainer = Color(0xFFFFC0C6)

val DarkBackground = Color(0xFF070C16)
val DarkOnBackground = Color(0xFFE7EEFB)
val DarkSurface = Color(0xFF0A1220)
val DarkOnSurface = Color(0xFFE7EEFB)
val DarkSurfaceVariant = Color(0xFF13203A)
val DarkOnSurfaceVariant = Color(0xFF8EA2C0)
val DarkOutline = Color(0xFF1F314D)
val DarkOutlineVariant = Color(0xFF182740)

val DarkSurfaceDim = Color(0xFF070C16)
val DarkSurfaceBright = Color(0xFF1A2942)
val DarkSurfaceContainerLowest = Color(0xFF050911)
val DarkSurfaceContainerLow = Color(0xFF0E1829)
val DarkSurfaceContainer = Color(0xFF13203A)
val DarkSurfaceContainerHigh = Color(0xFF182744)
val DarkSurfaceContainerHighest = Color(0xFF1F3152)

val UniFiLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    surfaceDim = LightSurfaceDim,
    surfaceBright = LightSurfaceBright,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest
)

val UniFiDarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    surfaceDim = DarkSurfaceDim,
    surfaceBright = DarkSurfaceBright,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest
)

/**
 * Static semantic status token pair (container fill, label/icon ink, subtle border).
 * Preserved even when Material You Dynamic Color is enabled so safety-critical
 * network access states never shift meaning.
 */
@Immutable
data class StatusColorToken(
    val container: Color,
    val content: Color,
    val border: Color,
    val indicator: Color
)

@Immutable
data class SemanticStatusColors(
    val allowed: StatusColorToken,
    val blocked: StatusColorToken,
    val scheduled: StatusColorToken,
    val discipline: StatusColorToken,
    val ignored: StatusColorToken
)

val LightSemanticStatusColors = SemanticStatusColors(
    allowed = StatusColorToken(
        container = Color(0xFFE6F7EE),
        content = Color(0xFF08663C),
        border = Color(0x470F9D58),
        indicator = Color(0xFF0F9D58)
    ),
    blocked = StatusColorToken(
        container = Color(0xFFFDECEE),
        content = Color(0xFFA51F2D),
        border = Color(0x47D93A4A),
        indicator = Color(0xFFD93A4A)
    ),
    scheduled = StatusColorToken(
        container = Color(0xFFEBF2FF),
        content = Color(0xFF1E4FA8),
        border = Color(0x422F6FED),
        indicator = Color(0xFF2F6FED)
    ),
    discipline = StatusColorToken(
        container = Color(0xFFFEF5E6),
        content = Color(0xFF8A5A12),
        border = Color(0x47B7791F),
        indicator = Color(0xFFB7791F)
    ),
    ignored = StatusColorToken(
        container = Color(0xFFEEF3FA),
        content = Color(0xFF5D6D84),
        border = Color(0xFFD7E0EC),
        indicator = Color(0xFF8EA2C0)
    )
)

val DarkSemanticStatusColors = SemanticStatusColors(
    allowed = StatusColorToken(
        container = Color(0xFF102B23),
        content = Color(0xFF7BF0B2),
        border = Color(0x593CD68A),
        indicator = Color(0xFF3CD68A)
    ),
    blocked = StatusColorToken(
        container = Color(0xFF2E121B),
        content = Color(0xFFFF9CA6),
        border = Color(0x61FF7080),
        indicator = Color(0xFFFF7080)
    ),
    scheduled = StatusColorToken(
        container = Color(0xFF132242),
        content = Color(0xFFC2D6FF),
        border = Color(0x526AA2FF),
        indicator = Color(0xFF6AA2FF)
    ),
    discipline = StatusColorToken(
        container = Color(0xFF2E2110),
        content = Color(0xFFFFD188),
        border = Color(0x61F6B955),
        indicator = Color(0xFFF6B955)
    ),
    ignored = StatusColorToken(
        container = Color(0xFF13203A),
        content = Color(0xFF8EA2C0),
        border = Color(0xFF1F314D),
        indicator = Color(0xFF5F7290)
    )
)

val LocalStatusColors = staticCompositionLocalOf { DarkSemanticStatusColors }
