package com.vintagelab.camera.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** DESIGN §2.4. */
object VlMotion {
    const val Instant = 70
    const val Quick = 120
    const val Standard = 220
    const val Emphasized = 360
    const val DevelopLoop = 1400

    val StandardEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecel: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Accel: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    fun <T> instant(reduced: Boolean = false): FiniteAnimationSpec<T> = if (reduced) snap() else tween(Instant, easing = LinearEasing)
    fun <T> quick(reduced: Boolean = false): FiniteAnimationSpec<T> = tween(Quick, easing = StandardEasing)
    fun <T> standard(reduced: Boolean = false): FiniteAnimationSpec<T> = if (reduced) tween(Quick) else tween(Standard, easing = StandardEasing)
    fun <T> emphasized(reduced: Boolean = false): FiniteAnimationSpec<T> = if (reduced) tween(Quick) else tween(Emphasized, easing = EmphasizedDecel)
    fun <T> detent(reduced: Boolean = false): FiniteAnimationSpec<T> = if (reduced) snap() else spring(dampingRatio = 0.75f, stiffness = 800f)
}

val LocalReducedMotion = staticCompositionLocalOf { false }

/** Re-reads ANIMATOR_DURATION_SCALE on every ON_RESUME. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    fun read() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduced by remember { mutableStateOf(read()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reduced = read() }
    return reduced
}
