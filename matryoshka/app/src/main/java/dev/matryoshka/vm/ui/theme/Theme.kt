package dev.matryoshka.vm.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

object Brand {
    val Violet = Color(0xFF7C5CFF)
    val VioletDeep = Color(0xFF5B3FE0)
    val Teal = Color(0xFF2EC4B6)
    val Coral = Color(0xFFFF7A59)
    val Amber = Color(0xFFFFB547)
    val Ok = Color(0xFF3DDC97)
    val Error = Color(0xFFFF5A6E)
    val accents = listOf(0xFF7C5CFF, 0xFF2EC4B6, 0xFFFF7A59, 0xFF3D8BFF, 0xFFE556A6, 0xFFFFB547)
}

private val Dark = darkColorScheme(
    primary = Brand.Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A2152),
    onPrimaryContainer = Color(0xFFE3DCFF),
    secondary = Brand.Teal,
    onSecondary = Color(0xFF00201D),
    secondaryContainer = Color(0xFF113B38),
    onSecondaryContainer = Color(0xFFB8F2EB),
    tertiary = Brand.Coral,
    background = Color(0xFF0D0E13),
    onBackground = Color(0xFFE7E7EE),
    surface = Color(0xFF0D0E13),
    onSurface = Color(0xFFE7E7EE),
    surfaceVariant = Color(0xFF1C1E28),
    onSurfaceVariant = Color(0xFFA9ABBA),
    surfaceContainerLowest = Color(0xFF08090C),
    surfaceContainerLow = Color(0xFF13141B),
    surfaceContainer = Color(0xFF171922),
    surfaceContainerHigh = Color(0xFF1E202B),
    surfaceContainerHighest = Color(0xFF262936),
    outline = Color(0xFF3A3D4D),
    outlineVariant = Color(0xFF262936),
    error = Brand.Error,
)

private val Light = lightColorScheme(
    primary = Brand.VioletDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF1C0F5C),
    secondary = Color(0xFF00897B),
    secondaryContainer = Color(0xFFC9F3EE),
    tertiary = Color(0xFFE2603F),
    background = Color(0xFFF7F7FB),
    surface = Color(0xFFF7F7FB),
    surfaceVariant = Color(0xFFE9E8F2),
    onSurfaceVariant = Color(0xFF5A5B6B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F1F8),
    surfaceContainer = Color(0xFFECEBF4),
    surfaceContainerHigh = Color(0xFFE6E5EF),
    surfaceContainerHighest = Color(0xFFE0DFEA),
    outline = Color(0xFFC6C5D3),
    outlineVariant = Color(0xFFE0DFEA),
    error = Color(0xFFD7263D),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val Mono = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

@Composable
fun MatryoshkaTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme: ColorScheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes) {
        // Text outside a Surface/Scaffold must still follow the theme (not default to black).
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}
