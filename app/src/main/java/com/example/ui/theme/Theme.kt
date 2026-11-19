package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val LslLightColorScheme = lightColorScheme(
    primary = LslForestGreen,
    onPrimary = LslPureWhite,
    primaryContainer = LslMintTint,
    onPrimaryContainer = LslForestGreenDark,
    secondary = LslEmeraldAccent,
    onSecondary = LslPureWhite,
    secondaryContainer = LslSurfaceSubtle,
    onSecondaryContainer = LslInkPrimary,
    tertiary = LslWarningText,
    onTertiary = LslPureWhite,
    tertiaryContainer = LslWarningBg,
    onTertiaryContainer = LslWarningText,
    background = LslLightGreyBg,
    onBackground = LslInkPrimary,
    surface = LslPureWhite,
    onSurface = LslInkPrimary,
    surfaceVariant = LslSurfaceSubtle,
    onSurfaceVariant = LslInkSecondary,
    outline = LslBorderGrey,
    outlineVariant = LslMintBorder,
    error = LslErrorText,
    onError = LslPureWhite,
    errorContainer = LslErrorBg,
    onErrorContainer = LslErrorText
)

private val LslDarkColorScheme = darkColorScheme(
    primary = LslDarkPrimary,
    onPrimary = LslDarkOnPrimary,
    primaryContainer = LslForestGreen,
    onPrimaryContainer = LslMintTint,
    secondary = LslEmeraldAccent,
    onSecondary = LslPureWhite,
    secondaryContainer = LslDarkSurfaceVariant,
    onSecondaryContainer = LslDarkTextPrimary,
    tertiary = LslWarningBorder,
    onTertiary = LslDarkOnPrimary,
    tertiaryContainer = LslDarkSurfaceVariant,
    onTertiaryContainer = LslWarningBorder,
    background = LslDarkBg,
    onBackground = LslDarkTextPrimary,
    surface = LslDarkSurface,
    onSurface = LslDarkTextPrimary,
    surfaceVariant = LslDarkSurfaceVariant,
    onSurfaceVariant = LslDarkTextSecondary,
    outline = LslDarkBorder,
    outlineVariant = LslForestGreen,
    error = LslErrorBorder,
    onError = LslErrorText,
    errorContainer = LslErrorText,
    onErrorContainer = LslErrorBg
)

private val LslShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun LslPoManagerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) LslDarkColorScheme else LslLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = LslShapes,
        content = content
    )
}
