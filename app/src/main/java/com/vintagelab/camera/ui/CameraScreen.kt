package com.vintagelab.camera.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.opengl.GLSurfaceView
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vintagelab.camera.App
import com.vintagelab.camera.BuildConfig
import com.vintagelab.camera.R
import com.vintagelab.camera.gl.GlPreviewRenderer
import kotlinx.coroutines.delay

@Composable
fun CameraScreen(vm: CameraViewModel, onOpenLicenses: () -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val windowSize = LocalWindowInfo.current.containerSize
    var focusRing by remember { mutableStateOf<Offset?>(null) }
    val currentState by rememberUpdatedState(state)

    // Display rotation drives target rotation + ViewPort (rebinds on change).
    LaunchedEffect(configuration.orientation, windowSize) {
        vm.setDisplayRotation(view.display?.rotation ?: 0)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    LaunchedEffect(focusRing) {
        if (focusRing != null) {
            delay(1200)
            focusRing = null
        }
    }
    val errorMsg = (state.captureState as? CaptureState.Error)?.msg
    val captureErrorText = errorMsg?.let { stringResource(R.string.error_capture, it) }
    LaunchedEffect(captureErrorText) {
        captureErrorText?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) {
                            val z = currentState.zoom + (zoom - 1f) * 0.5f
                            vm.onIntent(CameraIntent.Zoom(z))
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        focusRing = pos
                        vm.onIntent(CameraIntent.TapFocus(pos.x, pos.y))
                    }
                },
            factory = { ctx ->
                val app = ctx.applicationContext as App
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    preserveEGLContextOnPause = true
                    val renderer = GlPreviewRenderer(this, app::readAsset, app.luts, BuildConfig.DEBUG, ContextCompat.getMainExecutor(ctx))
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    tag = renderer
                    // Forward lifecycle to the GL thread (SPEC §6.3).
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> onResume()
                            Lifecycle.Event.ON_PAUSE -> onPause()
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    setTag(R.id.lifecycle_observer, observer)
                    vm.attach(lifecycleOwner, renderer)
                }
            },
            onRelease = { glView ->
                (glView.getTag(R.id.lifecycle_observer) as? LifecycleEventObserver)?.let {
                    lifecycleOwner.lifecycle.removeObserver(it)
                }
                (glView.tag as? GlPreviewRenderer)?.let { vm.detach(it) }
            },
        )

        focusRing?.let { p ->
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color.White, radius = 36.dp.toPx(), center = p, style = Stroke(width = 2.dp.toPx()))
            }
        }

        (state.captureState as? CaptureState.Countdown)?.let { cd ->
            Text(
                cd.n.toString(),
                color = Color.White,
                fontSize = 120.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TopControls(state, vm::onIntent, onOpenLicenses)
            state.cameraError?.let {
                Text(
                    stringResource(R.string.error_camera, it),
                    color = Color(0xFFFF6B6B),
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth())
            if (state.captureState is CaptureState.Saved) {
                Text(stringResource(R.string.saved), color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            LookPicker(state.looks, state.lookId, { vm.onIntent(CameraIntent.SelectLook(it)) }, Modifier.fillMaxWidth())
            BottomControls(
                state,
                vm::onIntent,
                onOpenLastShot = {
                    val uri = state.lastShot ?: return@BottomControls
                    val intent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "image/jpeg")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        context.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, R.string.no_viewer, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }
}
