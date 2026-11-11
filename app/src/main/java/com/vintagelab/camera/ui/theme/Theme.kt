package com.vintagelab.camera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** Always dark; never reads isSystemInDarkTheme() (DESIGN §2.1). */
@Composable
fun VlTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReducedMotion provides rememberReducedMotion()) {
        MaterialTheme(colorScheme = VlColorScheme, typography = VlTypography, shapes = VlShapes, content = content)
    }
}
