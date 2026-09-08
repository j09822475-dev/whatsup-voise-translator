package com.voisetranslator.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightScheme = lightColorScheme(
    primary = Color(0xFF12694A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F2CD),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4C6358),
    surfaceVariant = Color(0xFFDCE5DC),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF89D6B2),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF005236),
    onPrimaryContainer = Color(0xFFA6F2CD),
    secondary = Color(0xFFB2CCBD),
)

@Composable
fun VoiseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        // Material You on Android 12+; the hand-picked greens elsewhere.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkScheme
        else -> LightScheme
    }

    MaterialTheme(colorScheme = colors, content = content)
}
