package com.vintagelab.camera.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/** DESIGN §2.1 tokens (single forced dark theme). */
object VlColors {
    val Bench = Color(0xFF0E0D0C)
    val Deck = Color(0xFF171614)
    val Raised = Color(0xFF22201D)
    val Pressed = Color(0xFF2E2B27)
    val Hairline = Color(0xFF3A3631)
    val Ink = Color(0xFFEDE7DC)
    val InkMuted = Color(0xFFA89F92)
    val InkDisabled = Color(0xFF5E5850)
    val Amber = Color(0xFFFF8A1F)
    val AmberPressed = Color(0xFFE07614)
    val OnAmber = Color(0xFF1A0E02)
    val Safelight = Color(0xFFC2361F)
    val Signal = Color(0xFFE5484D)
    val OnSignal = Color(0xFF1A0506)
    val Paper = Color(0xFFF4F1EA)
    val ShutterCore = Color(0xFFD9D2C5)
    val ShutterCorePressed = Color(0xFFB8B0A2)
    val FlashWhite = Color(0xFFFFF8EE)
    val VfScrim = Color(0xB3000000)
    val VfScrimIcon = Color(0x99000000)
    val Halo = Color(0x80000000)
}

internal val VlColorScheme = darkColorScheme(
    primary = VlColors.Amber,
    onPrimary = VlColors.OnAmber,
    secondary = VlColors.InkMuted,
    background = VlColors.Bench,
    onBackground = VlColors.Ink,
    surface = VlColors.Deck,
    onSurface = VlColors.Ink,
    surfaceVariant = VlColors.Raised,
    surfaceContainerLowest = VlColors.Raised,
    surfaceContainerLow = VlColors.Raised,
    surfaceContainer = VlColors.Raised,
    surfaceContainerHigh = VlColors.Raised,
    surfaceContainerHighest = VlColors.Raised,
    onSurfaceVariant = VlColors.InkMuted,
    outline = VlColors.Hairline,
    outlineVariant = VlColors.Hairline,
    inverseSurface = VlColors.Raised,
    inverseOnSurface = VlColors.Ink,
    error = VlColors.Signal,
    onError = VlColors.OnSignal,
    scrim = Color(0x99000000),
    surfaceTint = Color.Transparent,
)
