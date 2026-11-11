package com.vintagelab.camera.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vintagelab.camera.R

/** IBM Plex Mono (OFL 1.1), bundled. */
val PlexMono = FontFamily(
    Font(R.font.plex_mono_medium, FontWeight.Medium),
    Font(R.font.plex_mono_semibold, FontWeight.SemiBold),
)

/** DESIGN §2.2: M3 roles we use. */
val VlTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.02.em),
)

object VlType {
    val labelEngraved = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.12.em)
    val lookToast = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.16.em)
    val licenseText = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp)
}

/** Text inside fixed 48 dp controls: font scale capped at 1.3× via dp.toSp() (non-linear scaling safe). */
@Composable
fun VlType.labelValue(): TextStyle {
    val d = LocalDensity.current
    val size = with(d) { (13f * minOf(fontScale, 1.3f)).dp.toSp() }
    return TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = size, letterSpacing = 0.04.em)
}

@Composable
fun VlType.zoom(): TextStyle {
    val d = LocalDensity.current
    val size = with(d) { (12f * minOf(fontScale, 1.3f)).dp.toSp() }
    return TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = size, fontFeatureSettings = "tnum")
}

/** Fixed 160 dp numeral (decorative; announced separately). */
@Composable
fun VlType.countdown(): TextStyle {
    val size = with(LocalDensity.current) { 160.dp.toSp() }
    return TextStyle(
        fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = size, lineHeight = size,
        letterSpacing = (-0.02).em, fontFeatureSettings = "tnum",
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )
}
