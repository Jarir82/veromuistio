package com.example.veromuistio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun VeromuistioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = DarkForestGreen,
            onPrimary = DeepBark,
            primaryContainer = DarkLightSage,
            onPrimaryContainer = DarkForestGreen,
            secondary = EarthBrown,
            onSecondary = Color.White,
            background = DarkSoftWood,
            surface = DarkSurface,
            onSurface = DarkOnSurface
        )
    } else {
        lightColorScheme(
            primary = ForestGreen,
            onPrimary = Color.White,
            primaryContainer = LightSage,
            onPrimaryContainer = ForestGreen,
            secondary = EarthBrown,
            onSecondary = Color.White,
            background = SoftWood,
            surface = Color.White,
            onSurface = DeepBark
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
