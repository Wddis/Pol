package com.atlant.space.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The palette from the product spec. Lime is used sparingly, as an accent only. */
object Palette {
    val Bg = Color(0xFF080A08)
    val BgSecondary = Color(0xFF0D100D)
    val Surface = Color(0xFF151915)
    val SurfaceElevated = Color(0xFF1C211C)
    val Accent = Color(0xFFB7F34A)
    val AccentSecondary = Color(0xFFD0FF76)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFA1AAA0)
    val Divider = Color(0xFF293029)

    val Danger = Color(0xFFFF6B5E)
    val Warn = Color(0xFFF3C94A)
}

private val AtlantColors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color(0xFF0A1500),
    primaryContainer = Palette.SurfaceElevated,
    onPrimaryContainer = Palette.AccentSecondary,
    secondary = Palette.AccentSecondary,
    onSecondary = Color(0xFF0A1500),
    background = Palette.Bg,
    onBackground = Palette.TextPrimary,
    surface = Palette.Surface,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceElevated,
    onSurfaceVariant = Palette.TextSecondary,
    outline = Palette.Divider,
    outlineVariant = Palette.Divider,
    error = Palette.Danger,
    onError = Color(0xFF2A0A08),
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceElevated,
    surfaceContainerHighest = Palette.SurfaceElevated,
    surfaceContainerLow = Palette.BgSecondary,
    surfaceContainerLowest = Palette.Bg,
)

private val AtlantShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val AtlantType = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
    )
}

@Composable
fun AtlantTheme(content: @Composable () -> Unit) {
    // The product is dark by design; the system setting does not change it.
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(colorScheme = AtlantColors, typography = AtlantType, shapes = AtlantShapes, content = content)
}
