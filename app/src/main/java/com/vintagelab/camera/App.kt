package com.vintagelab.camera

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import com.vintagelab.camera.capture.CapturePipeline
import com.vintagelab.camera.gl.LutLoader
import com.vintagelab.camera.gl.OffscreenRenderer
import com.vintagelab.camera.gl.PreviewHost
import com.vintagelab.camera.looks.PresetRepository
import com.vintagelab.camera.storage.MediaStoreSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Application-scoped singletons; capture jobs run in [appScope] so they survive the UI. */
class App : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val presets: PresetRepository by lazy { PresetRepository.load(this) }
    val luts: LutLoader by lazy { LutLoader(assets) }
    val saver: MediaStoreSaver by lazy { MediaStoreSaver(contentResolver, packageName) }
    val offscreen: OffscreenRenderer by lazy { OffscreenRenderer(::readAsset, luts, BuildConfig.DEBUG) }
    val capturePipeline: CapturePipeline by lazy {
        val am = getSystemService(ActivityManager::class.java)
        val largeHeap = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_LARGE_HEAP) != 0
        CapturePipeline(offscreen, saver, if (largeHeap) am.largeMemoryClass else am.memoryClass)
    }
    val previewHost: PreviewHost by lazy { PreviewHost(::readAsset, luts, BuildConfig.DEBUG) }

    fun readAsset(path: String): String = assets.open(path).bufferedReader().use { it.readText() }

    override fun onCreate() {
        super.onCreate()
        appScope.launch(Dispatchers.IO) { saver.cleanupPending() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // SPEC 7.4: nothing multi-shot stays in memory once the UI is hidden.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) capturePipeline.clearFirst()
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            appScope.launch { offscreen.trim() }
        }
    }
}
