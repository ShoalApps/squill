package io.github.shoalapps.squill

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback palette (ink blue) for Android 11 and below. Android 12+ uses your wallpaper colours.
private val Light = lightColorScheme(
    primary = Color(0xFF4A4FA3), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E0FF), onPrimaryContainer = Color(0xFF05006C),
    secondaryContainer = Color(0xFFE1E0F9), onSecondaryContainer = Color(0xFF191A2C),
    background = Color(0xFFFBF8FF), surface = Color(0xFFFBF8FF), onSurface = Color(0xFF1B1B21),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFBEC2FF), onPrimary = Color(0xFF181C6F),
    primaryContainer = Color(0xFF313587), onPrimaryContainer = Color(0xFFE0E0FF),
    secondaryContainer = Color(0xFF424559), onSecondaryContainer = Color(0xFFE1E0F9),
    background = Color(0xFF131318), surface = Color(0xFF131318), onSurface = Color(0xFFE4E1E9),
)

/** Your wallpaper's colours with Material 3 Expressive motion: springy, a little playful. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SquillTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}
