package com.vintagelab.camera.ui

import android.view.WindowManager
import androidx.camera.core.ImageCapture
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import com.vintagelab.camera.ui.theme.VlColors
import kotlinx.coroutines.launch

/** Front screen flash (DESIGN §6.6, K12): full-window warm-white overlay + max brightness. */
@Composable
fun ScreenFlashOverlay(register: (ImageCapture.ScreenFlash?) -> Unit) {
    val activity = LocalContext.current.findActivity()
    val alpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    DisposableEffect(activity) {
        val flash = object : ImageCapture.ScreenFlash {
            override fun apply(expirationTimeMillis: Long, listener: ImageCapture.ScreenFlashListener) {
                activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = 1f } }
                scope.launch {
                    try {
                        alpha.animateTo(1f, tween(150))
                    } finally {
                        listener.onCompleted()
                    }
                }
            }

            override fun clear() {
                activity?.window?.let { w ->
                    w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
                }
                scope.launch { alpha.animateTo(0f, tween(200)) }
            }
        }
        register(flash)
        onDispose { register(null) }
    }
    if (alpha.value > 0f) Box(Modifier.fillMaxSize().alpha(alpha.value).background(VlColors.FlashWhite))
}
