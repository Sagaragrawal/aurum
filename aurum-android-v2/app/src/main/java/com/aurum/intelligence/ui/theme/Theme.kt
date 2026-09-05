package com.aurum.intelligence.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.aurum.intelligence.data.ThemeChoice

val AurumBlack = Color(0xFF000000)
val AurumSurface = Color(0xFF070707)
val AurumSurface2 = Color(0xFF0B0B0B)
val AurumText = Color(0xFFF7F7F5)
val AurumMuted = Color(0xFFAAAAAA)
val AurumLine = Color(0xFF242424)
val AurumGold = Color(0xFFF2C94C)
val AurumGold2 = Color(0xFFD6AE36)
val AurumGreen = Color(0xFF2FD18B)
val AurumRed = Color(0xFFFF625D)

private val DarkColors = darkColorScheme(
    primary = AurumGold,
    secondary = AurumGold2,
    tertiary = AurumGreen,
    error = AurumRed,
    background = AurumBlack,
    surface = AurumSurface,
    surfaceVariant = AurumSurface2,
    outline = AurumLine,
    outlineVariant = AurumLine,
    onPrimary = AurumBlack,
    onSecondary = AurumBlack,
    onTertiary = AurumBlack,
    onBackground = AurumText,
    onSurface = AurumText,
    onSurfaceVariant = AurumMuted,
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF755B00),
    secondary = Color(0xFF126B4D),
    background = Color(0xFFF7F6F0),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8E9E2),
    onPrimary = Color.White,
    onBackground = Color(0xFF191C19),
    onSurface = Color(0xFF191C19),
)

@Composable
fun AurumTheme(themeChoice: ThemeChoice = ThemeChoice.System, content: @Composable () -> Unit) {
    val useDarkColors = when (themeChoice) {
        ThemeChoice.System -> isSystemInDarkTheme()
        ThemeChoice.Light -> false
        ThemeChoice.Dark -> true
    }
    MaterialTheme(
        colorScheme = if (useDarkColors) DarkColors else LightColors,
        content = content,
    )
}