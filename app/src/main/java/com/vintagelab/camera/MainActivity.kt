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
import com.vintagelab.camera.ui.AppRoot
import com.vintagelab.camera.ui.CameraViewModel
import com.vintagelab.camera.ui.Screen
import com.vintagelab.camera.ui.theme.VlTheme

class MainActivity : ComponentActivity() {
    private val vm: CameraViewModel by viewModels()
    private var screen = Screen.Camera

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContent { VlTheme { AppRoot(vm) { screen = it } } }
        updateDisplay()
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
