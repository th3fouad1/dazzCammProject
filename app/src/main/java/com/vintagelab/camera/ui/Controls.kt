package com.vintagelab.camera.ui

import android.graphics.Bitmap
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vintagelab.camera.R
import com.vintagelab.camera.camera.FlashMode
import com.vintagelab.camera.looks.AspectRatio
import com.vintagelab.camera.looks.FrameId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Top bar: flash cycle, ratio, timer, frame, date stamp, shuffle, licenses. */
@Composable
fun TopControls(state: CameraUiState, onIntent: (CameraIntent) -> Unit, onOpenLicenses: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.hasFlash) {
            Chip(
                stringResource(
                    when (state.flash) {
                        FlashMode.OFF -> R.string.flash_off
                        FlashMode.ON -> R.string.flash_on
                        FlashMode.AUTO -> R.string.flash_auto
                    },
                ),
            ) { onIntent(CameraIntent.CycleFlash) }
        }
        Chip(
            stringResource(
                when (state.ratio) {
                    AspectRatio.R4_3 -> R.string.ratio_4_3
                    AspectRatio.R1_1 -> R.string.ratio_1_1
                    AspectRatio.R16_9 -> R.string.ratio_16_9
                },
            ),
        ) {
            val next = AspectRatio.entries[(state.ratio.ordinal + 1) % AspectRatio.entries.size]
            onIntent(CameraIntent.SetRatio(next))
        }
        Chip(
            stringResource(
                when (state.timer) {
                    TimerMode.OFF -> R.string.timer_off
                    TimerMode.S3 -> R.string.timer_3
                    TimerMode.S10 -> R.string.timer_10
                },
            ),
        ) {
            val next = TimerMode.entries[(state.timer.ordinal + 1) % TimerMode.entries.size]
            onIntent(CameraIntent.SetTimer(next))
        }
        Chip(
            stringResource(
                when (state.frame) {
                    FrameId.NONE -> R.string.frame_none
                    FrameId.INSTANT_WHITE -> R.string.frame_instant
                    FrameId.FILM_STRIP -> R.string.frame_strip
                },
            ),
        ) {
            val next = FrameId.entries[(state.frame.ordinal + 1) % FrameId.entries.size]
            onIntent(CameraIntent.SetFrame(next))
        }
        Chip(stringResource(if (state.dateStamp) R.string.date_on else R.string.date_off)) {
            onIntent(CameraIntent.ToggleDateStamp)
        }
        Chip(stringResource(R.string.shuffle)) { onIntent(CameraIntent.Shuffle) }
        Chip(stringResource(R.string.licenses), onClick = onOpenLicenses)
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, color = Color.White, fontSize = 13.sp) }
}

/** Bottom row: last-shot thumbnail, shutter, lens switch. */
@Composable
fun BottomControls(
    state: CameraUiState,
    onIntent: (CameraIntent) -> Unit,
    onOpenLastShot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LastShotThumbnail(state, onOpenLastShot)
        ShutterButton(state) { onIntent(CameraIntent.Shutter) }
        if (state.hasFront) {
            TextButton(onClick = { onIntent(CameraIntent.ToggleLens) }) {
                Text(stringResource(R.string.switch_lens), color = Color.White, fontSize = 12.sp)
            }
        } else {
            Box(Modifier.size(56.dp))
        }
    }
}

@Composable
private fun ShutterButton(state: CameraUiState, onClick: () -> Unit) {
    val cs = state.captureState
    val label = when (cs) {
        CaptureState.Processing -> stringResource(R.string.developing)
        is CaptureState.Countdown -> stringResource(R.string.shutter_cancel)
        else -> stringResource(R.string.shutter)
    }
    val busy = cs == CaptureState.Processing
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(if (busy) Color.Gray else Color.White)
            .border(4.dp, Color.White.copy(alpha = 0.5f), CircleShape)
            .clickable(enabled = !busy, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) Text(label, color = Color.Black, fontSize = 10.sp)
    }
}

@Composable
private fun LastShotThumbnail(state: CameraUiState, onOpen: () -> Unit) {
    val context = LocalContext.current
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    val uri = state.lastShot
    LaunchedEffect(uri) {
        thumb = uri?.let {
            withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.loadThumbnail(it, Size(160, 160), null) }.getOrNull()
            }
        }
    }
    val desc = stringResource(R.string.last_shot)
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.1f))
            .border(1.dp, Color.White.copy(alpha = 0.4f), shape)
            .clickable(enabled = uri != null, onClick = onOpen)
            .semantics { contentDescription = desc },
    ) {
        thumb?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp))
        }
    }
}
