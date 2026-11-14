package com.vintagelab.camera

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.Surface
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vintagelab.camera.editor.EditorViewModel
import com.vintagelab.camera.gallery.GalleryViewModel
import com.vintagelab.camera.ui.AppRoot
import com.vintagelab.camera.ui.CameraViewModel
import com.vintagelab.camera.ui.Screen
import com.vintagelab.camera.ui.theme.VlTheme

class MainActivity : ComponentActivity() {
    private val vm: CameraViewModel by viewModels()
    private val editor: EditorViewModel by viewModels()
    private val gallery: GalleryViewModel by viewModels()
    private var screen = Screen.Camera
    private val monetize get() = (application as App).monetize
    /** SPEC 4.2 App Open: set when this activity launches another one (picker, share, Play, an ad) so the return is not a "warm start". */
    private var launchedOwnActivity = false
    private var stoppedAt = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContent { VlTheme { AppRoot(vm, editor, gallery) { screen = it } } }
        updateDisplay()
        window.decorView.post { monetize.start(this) }
    }

    override fun onStart() {
        super.onStart()
        if (stoppedAt >= 0) {
            val away = android.os.SystemClock.elapsedRealtime() - stoppedAt
            monetize.billing.refresh()
            monetize.ads.onWarmStart(this, away, launchedOwnActivity)
        }
        launchedOwnActivity = false
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = android.os.SystemClock.elapsedRealtime()
        vm.onBackground()
    }

    // Every startActivity / ActivityResult launch / IntentSender request from this activity passes through these.
    @Deprecated("Tracks own launches only")
    override fun startActivityForResult(intent: android.content.Intent, requestCode: Int, options: Bundle?) {
        launchedOwnActivity = true
        @Suppress("DEPRECATION")
        super.startActivityForResult(intent, requestCode, options)
    }

    @Deprecated("Tracks own launches only")
    override fun startIntentSenderForResult(
        intent: android.content.IntentSender, requestCode: Int, fillInIntent: android.content.Intent?,
        flagsMask: Int, flagsValues: Int, extraFlags: Int, options: Bundle?,
    ) {
        launchedOwnActivity = true
        @Suppress("DEPRECATION")
        super.startIntentSenderForResult(intent, requestCode, fillInIntent, flagsMask, flagsValues, extraFlags, options)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateDisplay()
    }

    /** K14: display rotation + natural orientation feed the ViewPort and `imagePortrait`. */
    private fun updateDisplay() {
        val d = window.decorView.display
            ?: getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
            ?: return
        // Display.Mode physical size is reported in the natural orientation.
        val naturalPortrait = d.mode.physicalWidth < d.mode.physicalHeight
        vm.setDisplay(d.rotation, naturalPortrait)
    }

    /** K15: camera key always, volume keys only without touch exploration; fire on key-up. */
    private fun isShutterKey(code: Int): Boolean {
        if (screen != Screen.Camera) return false
        if (code == KeyEvent.KEYCODE_CAMERA) return true
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val am = getSystemService(AccessibilityManager::class.java)
        return am?.isTouchExplorationEnabled != true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (isShutterKey(keyCode) && vm.acceptsHardwareShutter()) true else super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isShutterKey(keyCode) && vm.acceptsHardwareShutter()) {
            if (event.repeatCount == 0) vm.onHardwareShutter()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
