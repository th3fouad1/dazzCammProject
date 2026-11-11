package com.vintagelab.camera.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vintagelab.camera.R
import com.vintagelab.camera.ui.theme.VlColors

/** DESIGN §3.3: auto-request once, rationale only after a denial, nothing persisted. */
@Composable
fun PermissionGate(
    permission: PermissionState,
    onIntent: (CameraIntent) -> Unit,
    onOpenLicenses: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val canAsk = context.findActivity()?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA) } == true
        onIntent(CameraIntent.PermissionResult(ok, canAsk))
    }
    var autoRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (granted()) {
            onIntent(CameraIntent.RefreshPermission(true))
        } else if (!autoRequested) {
            autoRequested = true
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onIntent(CameraIntent.RefreshPermission(granted())) }

    if (permission == PermissionState.Granted) {
        content()
        return
    }
    val denied = permission == PermissionState.PermanentlyDenied
    val title = stringResource(if (denied) R.string.perm_denied_title else R.string.perm_title)
    BoxWithConstraints(
        Modifier.fillMaxSize().background(VlColors.Bench).windowInsetsPadding(WindowInsets.safeDrawing).semantics { paneTitle = title },
    ) {
        val showArt = !(maxWidth > maxHeight && maxHeight < 480.dp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (showArt) {
                Aperture()
                Spacer(Modifier.height(24.dp))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, color = VlColors.Ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (denied) R.string.perm_denied_body else R.string.perm_body),
                style = MaterialTheme.typography.bodyLarge, color = VlColors.InkMuted, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = {
                    if (denied) {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } else {
                        launcher.launch(Manifest.permission.CAMERA)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = VlColors.Amber, contentColor = VlColors.OnAmber),
                modifier = Modifier.height(48.dp).sizeIn(minWidth = 160.dp),
            ) { Text(stringResource(if (denied) R.string.perm_open_settings else R.string.perm_allow)) }
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onOpenLicenses) { Text(stringResource(R.string.menu_licenses), color = VlColors.Ink) }
        }
    }
}

/** Decorative aperture (6 blades on a raised disc). */
@Composable
private fun Aperture() {
    Box(Modifier.size(120.dp).background(VlColors.Raised, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(96.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2
            for (i in 0 until 6) {
                rotate(i * 60f, c) {
                    drawLine(VlColors.InkMuted, Offset(c.x - r * 0.2f, c.y - r * 0.95f), Offset(c.x + r * 0.75f, c.y + r * 0.1f), strokeWidth = 2.dp.toPx())
                }
            }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
