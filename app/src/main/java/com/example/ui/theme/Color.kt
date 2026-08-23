package com.example.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Google Vibrant Palette colors
val GoogleBlue = Color(0xFF4285F4)
val GoogleRed = Color(0xFFEA4335)
val GoogleYellow = Color(0xFFFBBC05)
val GoogleGreen = Color(0xFF34A853)

val PrimaryBlue = Color(0xFF0B57D0)
val PrimaryBlueContainer = Color(0xFFD3E3FD)
val OnPrimaryBlueContainer = Color(0xFF041E49)

val NeutralBackground = Color(0xFFF0F4F9)
val NeutralSurface = Color(0xFFFFFFFF)
val NeutralSurfaceVariant = Color(0xFFE9EEF6)
val NeutralBorder = Color(0xFFDDE3EA)
val TextPrimary = Color(0xFF1F1F1F)
val TextSecondary = Color(0xFF444746)
val TextTertiary = Color(0xFF747775)

// Dark theme colors
val DarkBackground = Color(0xFF131314)
val DarkSurface = Color(0xFF1E1F20)
val DarkSurfaceVariant = Color(0xFF282A2C)
val DarkBorder = Color(0xFF3C4043)
val DarkTextPrimary = Color(0xFFE3E3E3)
val DarkTextSecondary = Color(0xFFC4C7C5)
val DarkTextTertiary = Color(0xFF8E918F)
val DarkPrimaryBlue = Color(0xFFA8C7FA)
val DarkPrimaryBlueContainer = Color(0xFF0842A0)

// Vibrant Google Gradient
val GoogleGradientColors = listOf(
    GoogleBlue,
    GoogleRed,
    GoogleYellow,
    GoogleGreen
)

val GoogleGradientBrush = Brush.linearGradient(
    colors = GoogleGradientColors
)

val GoogleGlowGradientBrush = Brush.sweepGradient(
    colors = listOf(
        GoogleBlue,
        GoogleRed,
        GoogleYellow,
        GoogleGreen,
        GoogleBlue
    )
)
