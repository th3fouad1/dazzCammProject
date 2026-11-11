package com.vintagelab.camera.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object VlRadius {
    val Viewfinder = 0.dp
    val Chip = 4.dp
    val Swatch = 6.dp
    val Card = 8.dp
    val Sheet = 16.dp
}

val VlShapes = Shapes(
    extraSmall = RoundedCornerShape(VlRadius.Chip),
    small = RoundedCornerShape(VlRadius.Card),
    medium = RoundedCornerShape(VlRadius.Card),
    large = RoundedCornerShape(VlRadius.Sheet),
)
