package com.vintagelab.camera.ui

import android.graphics.Bitmap
import android.util.Size
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vintagelab.camera.R
import com.vintagelab.camera.camera.FlashMode
import com.vintagelab.camera.capture.FlashGel
import com.vintagelab.camera.capture.MultiExposure
import com.vintagelab.camera.capture.MultiShotRules
import com.vintagelab.camera.capture.MultiMode
import com.vintagelab.camera.camera.Lens
import com.vintagelab.camera.looks.AspectRatio
import com.vintagelab.camera.looks.FrameId
import com.vintagelab.camera.looks.PrismMode
import com.vintagelab.camera.ui.icons.VlIcons
import com.vintagelab.camera.ui.theme.LocalReducedMotion
import com.vintagelab.camera.ui.theme.VlColors
import com.vintagelab.camera.ui.theme.VlMotion
import com.vintagelab.camera.ui.theme.VlRadius
import com.vintagelab.camera.ui.theme.VlType
import com.vintagelab.camera.ui.theme.labelValue
import com.vintagelab.camera.video.RecordingPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 48 dp control with glyph or value text and an optional 6 dp lamp (DESIGN §5.1). */
@Composable
fun ControlButton(
    cd: String,
    state: String?,
    lampOn: Boolean?,
    enabled: Boolean = true,
    role: Role = Role.Button,
    icon: ImageVector? = null,
    text: String? = null,
    iconTint: Color = VlColors.Ink,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .size(48.dp)
            .clickable(source, indication = null, enabled = enabled, role = role, onClick = onClick)
            .semantics {
                contentDescription = cd
                state?.let { stateDescription = it }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (pressed) Box(Modifier.size(40.dp).clip(RoundedCornerShape(VlRadius.Chip)).background(VlColors.Pressed))
        val tint = if (enabled) iconTint else VlColors.InkDisabled
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        if (text != null) Text(text, style = VlType.labelValue(), color = tint)
        if (lampOn != null && enabled) Lamp(lampOn, Modifier.offset(y = 19.dp))
    }
}

@Composable
fun Lamp(on: Boolean, modifier: Modifier = Modifier, color: Color = VlColors.Amber) {
    val shape = CircleShape
    Box(
        modifier
            .size(6.dp)
            .clip(shape)
            .then(if (on) Modifier.background(color) else Modifier.border(1.dp, VlColors.Hairline, shape)),
    )
}

@Composable
fun FlashButton(s: CameraUiState, onIntent: (CameraIntent) -> Unit) {
    val front = s.lens == Lens.FRONT
    val visible = if (front) s.caps.screenFlash else s.caps.hasFlashUnit
    if (!visible) {
        Spacer(Modifier.size(48.dp))
        return
    }
    val effective = if (front && s.flash == FlashMode.AUTO) FlashMode.ON else s.flash
    val icon = when {
        effective == FlashMode.OFF -> VlIcons.FlashOff
        front -> VlIcons.ScreenFlash
        effective == FlashMode.AUTO -> VlIcons.FlashAuto
        else -> VlIcons.FlashOn
    }
    val st = stringResource(
        when {
            effective == FlashMode.OFF -> R.string.state_flash_off
            front -> R.string.state_flash_screen
            effective == FlashMode.AUTO -> R.string.state_flash_auto
            else -> R.string.state_flash_on
        },
    )
    val gelOn = CameraRules.gelVisible(s) && s.flashGel != FlashGel.NONE
    val stateText = if (gelOn) stringResource(R.string.state_flash_with_gel, st, stringResource(gelName(s.flashGel))) else st
    Box {
        ControlButton(stringResource(R.string.cd_flash), stateText, effective != FlashMode.OFF, icon = icon) { onIntent(CameraIntent.CycleFlash) }
        if (gelOn) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 9.dp).size(8.dp).clip(CircleShape)
                    .background(Color(s.flashGel.r, s.flashGel.g, s.flashGel.b)).border(1.dp, VlColors.Hairline, CircleShape),
            )
        }
    }
}

/** SPEC 7.4 */
fun prismName(m: PrismMode): Int = when (m) {
    PrismMode.OFF -> R.string.prism_off
    PrismMode.SPLIT -> R.string.prism_split
    PrismMode.TRI -> R.string.prism_tri
    PrismMode.HALO -> R.string.prism_halo
}

/** SPEC 7.4 */
fun gelName(g: FlashGel): Int = when (g) {
    FlashGel.NONE -> R.string.gel_none
    FlashGel.WARM -> R.string.gel_warm
    FlashGel.RED -> R.string.gel_red
    FlashGel.BLUE -> R.string.gel_blue
    FlashGel.GREEN -> R.string.gel_green
}

@Composable
fun TimerButton(s: CameraUiState, onIntent: (CameraIntent) -> Unit) {
    val st = stringResource(when (s.timer) { TimerMode.OFF -> R.string.state_timer_off; TimerMode.S3 -> R.string.state_timer_3; TimerMode.S10 -> R.string.state_timer_10 })
    val label = when (s.timer) { TimerMode.OFF -> null; TimerMode.S3 -> stringResource(R.string.label_timer_3); TimerMode.S10 -> stringResource(R.string.label_timer_10) }
    ControlButton(
        stringResource(R.string.cd_timer), st, s.timer != TimerMode.OFF, enabled = CameraRules.timerEnabled(s),
        icon = if (label == null) VlIcons.Timer else null, text = label,
    ) { onIntent(CameraIntent.SetTimer(TimerMode.entries[(s.timer.ordinal + 1) % TimerMode.entries.size])) }
}

/** SPEC 7.7: Off › Night › HDR. Hidden while a multi-shot mode is armed. */
@Composable
fun BoostButton(s: CameraUiState, onIntent: (CameraIntent) -> Unit) {
    if (s.multiMode != MultiMode.None) return
    val st = stringResource(
        when (s.boost) {
            com.vintagelab.camera.capture.BoostMode.OFF -> R.string.state_boost_off
            com.vintagelab.camera.capture.BoostMode.NIGHT -> R.string.state_boost_night
            com.vintagelab.camera.capture.BoostMode.HDR -> R.string.state_boost_hdr
        },
    )
    val on = s.boost != com.vintagelab.camera.capture.BoostMode.OFF
    ControlButton(
        stringResource(R.string.cd_boost), st, on, enabled = CameraRules.boostEnabled(s),
        icon = when (s.boost) {
            com.vintagelab.camera.capture.BoostMode.OFF -> VlIcons.Moon
            com.vintagelab.camera.capture.BoostMode.NIGHT -> VlIcons.MoonOn
            com.vintagelab.camera.capture.BoostMode.HDR -> null
        },
        text = if (s.boost == com.vintagelab.camera.capture.BoostMode.HDR) stringResource(R.string.label_boost_hdr) else null,
        iconTint = if (on) VlColors.Amber else VlColors.Ink,
    ) { onIntent(CameraIntent.CycleBoost) }
}

@Composable
fun RatioButton(s: CameraUiState, onIntent: (CameraIntent) -> Unit) {
    val (label, st) = when (s.ratio) {
        AspectRatio.R4_3 -> R.string.label_ratio_4_3 to R.string.state_ratio_4_3
        AspectRatio.R1_1 -> R.string.label_ratio_1_1 to R.string.state_ratio_1_1
        AspectRatio.R16_9 -> R.string.label_ratio_16_9 to R.string.state_ratio_16_9
    }
    ControlButton(
        stringResource(R.string.cd_ratio), stringResource(st), null, enabled = CameraRules.ratioEnabled(s), text = stringResource(label),
    ) { onIntent(CameraIntent.SetRatio(AspectRatio.entries[(s.ratio.ordinal + 1) % AspectRatio.entries.size])) }
}

/** Icon and state-name string of a frame (shared by the camera and the editor). */
fun frameIconAndName(f: FrameId): Pair<androidx.compose.ui.graphics.vector.ImageVector, Int> = when (f) {
    FrameId.NONE -> VlIcons.FrameNone to R.string.state_frame_none
    FrameId.INSTANT_WHITE -> VlIcons.FrameInstant to R.string.state_frame_instant
    FrameId.FILM_STRIP -> VlIcons.FrameStrip to R.string.state_frame_strip
    FrameId.INSTANT_SQUARE -> VlIcons.FrameInstant to R.string.state_frame_instant_square
    FrameId.INSTANT_MINI -> VlIcons.FrameInstant to R.string.state_frame_instant_mini
    FrameId.FILM_120 -> VlIcons.FrameStrip to R.string.state_frame_film120
    FrameId.SLIDE_MOUNT -> VlIcons.FrameInstant to R.string.state_frame_slide_mount
    FrameId.PANO_SPROCKET -> VlIcons.FrameStrip to R.string.state_frame_pano
}

@Composable
fun FrameButton(s: CameraUiState, onIntent: (CameraIntent) -> Unit) {
    val (icon, st) = frameIconAndName(s.frame)
    ControlButton(stringResource(R.string.cd_frame), stringResource(st), s.frame != FrameId.NONE, enabled = CameraRules.frameEnabled(s), icon = icon) {
        onIntent(CameraIntent.SetFrame(CameraRules.nextFrame(s.frame, half = MultiShotRules.skipsForcedFrames(s.multi))))
    }
}

@Composable
fun DateStampButton(s: CameraUiState, onIntent: (CameraIntent) -> Unit) {
    ControlButton(
        stringResource(R.string.cd_date_stamp), null, s.dateStamp, role = Role.Switch, icon = VlIcons.DateStamp,
        iconTint = if (s.dateStamp) VlColors.Amber else VlColors.Ink,
    ) { onIntent(CameraIntent.ToggleDateStamp) }
}

/** Where the camera's menus lead (SPEC 6.7/6.8): licenses, the in-app gallery (optionally on one photo) and photo import. */
@androidx.compose.runtime.Immutable
class CameraNav(
    val onLicenses: () -> Unit,
    val onGallery: (android.net.Uri?) -> Unit,
    val onImport: () -> Unit,
)

@Composable
fun MoreMenu(s: CameraUiState, onIntent: (CameraIntent) -> Unit, nav: CameraNav) {
    var expanded by remember { mutableStateOf(false) }
    val on = stringResource(R.string.toggle_on)
    val off = stringResource(R.string.toggle_off)
    Box {
        ControlButton(stringResource(R.string.cd_more), null, moreLamp(s), icon = VlIcons.More) { expanded = true }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            if (s.mode == CameraMode.Photo) DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_toggle_state, stringResource(R.string.menu_double_exposure), if (s.multiMode == MultiMode.Double) on else off)) },
                enabled = MultiShotRules.canChangeMode(s.multi),
                onClick = { onIntent(CameraIntent.ToggleDoubleExposure); expanded = false },
            )
            if (s.mode == CameraMode.Photo) DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_toggle_state, stringResource(R.string.menu_half_frame), if (s.multiMode == MultiMode.Half) on else off)) },
                enabled = MultiShotRules.canChangeMode(s.multi),
                onClick = { onIntent(CameraIntent.ToggleHalfFrame); expanded = false },
            )
            if (s.mode == CameraMode.Photo) DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_toggle_state, stringResource(R.string.menu_grid), if (s.multiMode == MultiMode.Grid4) on else off)) },
                enabled = MultiShotRules.canChangeMode(s.multi),
                onClick = { onIntent(CameraIntent.ToggleGrid); expanded = false },
            )
            if (s.mode == CameraMode.Photo) DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_prism, stringResource(prismName(s.prism)))) },
                onClick = { onIntent(CameraIntent.CyclePrism) },
            )
            if (s.mode == CameraMode.Photo) DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_flash_gel, stringResource(gelName(s.flashGel)))) },
                onClick = { onIntent(CameraIntent.CycleFlashGel) },
            )
            DropdownMenuItem(text = { Text(stringResource(R.string.menu_import)) }, onClick = { expanded = false; nav.onImport() })
            DropdownMenuItem(text = { Text(stringResource(R.string.menu_gallery)) }, onClick = { expanded = false; nav.onGallery(null) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_toggle_state, stringResource(R.string.menu_keep_negatives), if (s.keepNegatives) on else off)) },
                onClick = { onIntent(CameraIntent.ToggleKeepNegatives) },
            )
            DropdownMenuItem(text = { Text(stringResource(R.string.menu_licenses)) }, onClick = { expanded = false; nav.onLicenses() })
        }
    }
}

fun moreLamp(s: CameraUiState): Boolean? = if (s.multiMode != MultiMode.None || s.flashGel != FlashGel.NONE || s.prism != PrismMode.OFF) true else null

/** Sheet variant of the capture-mode controls (Mini layout). */
@Composable
fun CaptureModeButtons(s: CameraUiState, onIntent: (CameraIntent) -> Unit) {
    val on = stringResource(R.string.toggle_on)
    val off = stringResource(R.string.toggle_off)
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(enabled = MultiShotRules.canChangeMode(s.multi), onClick = { onIntent(CameraIntent.ToggleDoubleExposure) }) {
            Text(stringResource(R.string.menu_toggle_state, stringResource(R.string.menu_double_exposure), if (s.multiMode == MultiMode.Double) on else off), color = VlColors.Ink)
        }
        TextButton(enabled = MultiShotRules.canChangeMode(s.multi), onClick = { onIntent(CameraIntent.ToggleHalfFrame) }) {
            Text(stringResource(R.string.menu_toggle_state, stringResource(R.string.menu_half_frame), if (s.multiMode == MultiMode.Half) on else off), color = VlColors.Ink)
        }
        TextButton(enabled = MultiShotRules.canChangeMode(s.multi), onClick = { onIntent(CameraIntent.ToggleGrid) }) {
            Text(stringResource(R.string.menu_toggle_state, stringResource(R.string.menu_grid), if (s.multiMode == MultiMode.Grid4) on else off), color = VlColors.Ink)
        }
        TextButton(onClick = { onIntent(CameraIntent.CyclePrism) }) {
            Text(stringResource(R.string.menu_prism, stringResource(prismName(s.prism))), color = VlColors.Ink)
        }
        TextButton(onClick = { onIntent(CameraIntent.CycleFlashGel) }) {
            Text(stringResource(R.string.menu_flash_gel, stringResource(gelName(s.flashGel))), color = VlColors.Ink)
        }
    }
}

/** T2 controls: top bar (portrait) or start rail (landscape). */
@Composable
fun ControlBar(s: CameraUiState, onIntent: (CameraIntent) -> Unit, nav: CameraNav, vertical: Boolean, modifier: Modifier = Modifier) {
    val items: @Composable () -> Unit = {
        if (s.mode == CameraMode.Photo) {
            FlashButton(s, onIntent)
            TimerButton(s, onIntent)
            BoostButton(s, onIntent)
            RatioButton(s, onIntent)
            FrameButton(s, onIntent)
            DateStampButton(s, onIntent)
        }
    }
    if (vertical) {
        Column(modifier.background(VlColors.Deck).padding(vertical = 8.dp), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
            items()
            Spacer(Modifier.weight(1f))
            MoreMenu(s, onIntent, nav)
        }
    } else {
        Row(modifier.background(VlColors.Deck).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            items()
            Spacer(Modifier.weight(1f))
            MoreMenu(s, onIntent, nav)
        }
    }
}

/** Shutter (DESIGN §5.1): idle disc, cancel square in countdown, safelight sweep while developing. */
@Composable
fun ShutterButton(s: CameraUiState, size: Dp, onIntent: (CameraIntent) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val reduced = LocalReducedMotion.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    LaunchedEffect(pressed) { if (pressed) haptics.performHapticFeedback(HapticFeedbackType.VirtualKey) }
    val video = s.mode == CameraMode.Video
    val recording = s.recording.phase == RecordingPhase.Recording
    val videoBusy = s.recording.phase == RecordingPhase.Starting || s.recording.phase == RecordingPhase.Stopping
    val busy = if (video) videoBusy else s.captureState == CaptureState.Processing
    val countdown = s.captureState is CaptureState.Countdown
    val available = s.camera == CameraStatus.Ready
    val disc by animateFloatAsState(if (pressed) 0.87f else 1f, VlMotion.instant(reduced), label = "disc")
    val sweep = rememberInfiniteTransition(label = "develop")
    val angle by sweep.animateFloat(0f, 360f, infiniteRepeatable(tween(VlMotion.DevelopLoop, easing = LinearEasing)), label = "angle")
    val cd = stringResource(
        when {
            video && recording -> R.string.cd_record_stop
            video -> R.string.cd_record_start
            countdown -> R.string.cd_shutter_cancel
            busy -> R.string.cd_shutter_busy
            else -> R.string.cd_shutter
        },
    )
    Canvas(
        Modifier
            .size(size)
            .clickable(source, indication = null, enabled = available && !busy, role = Role.Button) { onIntent(CameraIntent.Shutter) }
            .semantics {
                contentDescription = cd
                if (busy || !available) disabled()
            },
    ) {
        val k = this.size.minDimension / 80.dp.toPx()
        val ringR = 38.dp.toPx() * k
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val ringColor = when {
            !available -> VlColors.InkDisabled
            busy -> VlColors.Hairline
            else -> VlColors.Ink
        }
        if (busy) drawCircle(VlColors.Safelight.copy(alpha = 0.35f), ringR + 4.dp.toPx() * k, c, style = Stroke(8.dp.toPx() * k))
        drawCircle(ringColor, ringR, c, style = Stroke(3.dp.toPx() * k))
        if (busy) {
            val start = if (reduced) 0f else angle
            drawArc(
                VlColors.Safelight, start, if (reduced) 360f else 90f, false,
                topLeft = Offset(c.x - ringR, c.y - ringR), size = GSize(ringR * 2, ringR * 2), style = Stroke(3.dp.toPx() * k),
            )
        }
        if (video && recording) {
            val h = 12.dp.toPx() * k
            drawRoundRect(VlColors.Signal, Offset(c.x - h, c.y - h), GSize(h * 2, h * 2), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx() * k))
        } else if (countdown) {
            val h = 12.dp.toPx() * k
            drawRoundRect(VlColors.Ink, Offset(c.x - h, c.y - h), GSize(h * 2, h * 2), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx() * k))
        } else {
            val discColor = when {
                !available -> VlColors.Raised
                busy -> VlColors.Pressed
                pressed -> VlColors.ShutterCorePressed
                else -> VlColors.ShutterCore
            }
            drawCircle(if (video) VlColors.Signal else discColor, 31.dp.toPx() * k * disc, c)
        }
    }
}

@Composable
fun LastShotThumbnail(s: CameraUiState, onOpen: () -> Unit, onUnavailable: () -> Unit) {
    val context = LocalContext.current
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    val uri = s.lastShot
    LaunchedEffect(uri) {
        thumb = uri?.let {
            withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(it, Size(112, 112), null) }.getOrNull() }
        }
        if (uri != null && thumb == null) onUnavailable()
    }
    val drop by animateFloatAsState(if (thumb != null) 1f else 1.15f, VlMotion.emphasized(LocalReducedMotion.current), label = "drop")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pa by pulse.animateFloat(0.06f, 0.14f, infiniteRepeatable(tween(VlMotion.DevelopLoop), RepeatMode.Reverse), label = "pa")
    val shape = RoundedCornerShape(VlRadius.Card)
    val cd = stringResource(if (uri != null) R.string.cd_thumb else R.string.cd_thumb_empty)
    Box(
        Modifier
            .size(56.dp)
            .clip(shape)
            .background(VlColors.Raised)
            .border(if (thumb != null) 2.dp else 1.5.dp, if (thumb != null) VlColors.Paper else VlColors.Hairline, shape)
            .clickable(enabled = uri != null, role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        val t = thumb
        if (t != null) {
            Image(t.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().padding(2.dp).scale(drop))
        } else {
            Icon(VlIcons.FrameInstant, null, tint = VlColors.InkDisabled, modifier = Modifier.size(20.dp))
        }
        if (s.developing > 0) Box(Modifier.fillMaxSize().background(VlColors.Safelight.copy(alpha = pa)))
    }
}

@Composable
fun LensSwitchButton(s: CameraUiState, onIntent: (CameraIntent) -> Unit, size: Dp = 56.dp) {
    if (!(s.caps.hasFront && s.caps.hasBack)) {
        Spacer(Modifier.size(size))
        return
    }
    val rot by animateFloatAsState(if (s.lens == Lens.FRONT) 180f else 0f, VlMotion.emphasized(LocalReducedMotion.current), label = "lens")
    val enabled = CameraRules.lensRatioEnabled(s)
    val st = stringResource(if (s.lens == Lens.BACK) R.string.state_lens_back else R.string.state_lens_front)
    val cd = stringResource(R.string.cd_lens_switch)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(VlColors.Raised)
            .clickable(enabled = enabled, role = Role.Button) { onIntent(CameraIntent.ToggleLens) }
            .semantics { contentDescription = cd; stateDescription = st },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            VlIcons.LensSwitch, null, tint = if (enabled) VlColors.Ink else VlColors.InkDisabled,
            modifier = Modifier.size(24.dp).rotate(rot),
        )
    }
}

/** T1 deck: thumbnail | shutter (+ developing label) | lens. */
@Composable
fun Deck(s: CameraUiState, onIntent: (CameraIntent) -> Unit, shutter: Dp, vertical: Boolean, onOpenLastShot: () -> Unit, modifier: Modifier = Modifier) {
    val thumb: @Composable () -> Unit = { LastShotThumbnail(s, onOpenLastShot) { onIntent(CameraIntent.LastShotUnavailable) } }
    val shutterCol: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ShutterButton(s, shutter, onIntent)
            if (s.mode == CameraMode.Video) RecordingLabel(s) else DevelopingLabelSimple(s)
        }
    }
    if (vertical) {
        Column(modifier.background(VlColors.Deck), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
            LensSwitchButton(s, onIntent); shutterCol(); thumb()
        }
    } else {
        Row(modifier.background(VlColors.Deck), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            thumb(); shutterCol(); LensSwitchButton(s, onIntent)
        }
    }
}

@Composable
private fun RecordingLabel(s: CameraUiState) {
    val elapsed = s.recording.elapsedMs.coerceAtLeast(0L) / 1000L
    val text = "%02d:%02d".format(elapsed / 60L, elapsed % 60L)
    val active = s.recording.phase == RecordingPhase.Recording || s.recording.phase == RecordingPhase.Stopping
    Row(Modifier.height(18.dp).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (active) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(VlColors.Signal))
            Text(" ${stringResource(R.string.record_rec)} ", style = VlType.labelEngraved, color = VlColors.Signal)
        }
        Text(text, style = VlType.labelEngraved, color = VlColors.InkMuted)
    }
}

/** Thumb-reach Photo/Video segmented switch, kept adjacent to the look picker. */
@Composable
fun ModeSwitch(s: CameraUiState, onIntent: (CameraIntent) -> Unit, vertical: Boolean, modifier: Modifier = Modifier) {
    val enabled = s.recording.phase !in setOf(RecordingPhase.Starting, RecordingPhase.Recording, RecordingPhase.Stopping)
    val photo = stringResource(R.string.mode_photo)
    val video = stringResource(R.string.mode_video)
    val item: @Composable (CameraMode, String) -> Unit = { mode, label ->
        val selected = s.mode == mode
        Box(
            Modifier
                .then(if (vertical) Modifier.fillMaxWidth().height(48.dp) else Modifier.height(48.dp).padding(horizontal = 10.dp))
                .clip(RoundedCornerShape(VlRadius.Chip))
                .background(if (selected) VlColors.Raised else Color.Transparent)
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) { onIntent(CameraIntent.SetMode(mode)) }
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label.uppercase(), style = VlType.labelEngraved, color = if (selected) VlColors.Amber else VlColors.InkMuted)
        }
    }
    if (vertical) Column(modifier.background(VlColors.Deck), verticalArrangement = Arrangement.Center) {
        item(CameraMode.Photo, photo); item(CameraMode.Video, video)
    } else Row(modifier.background(VlColors.Deck), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        item(CameraMode.Photo, photo); item(CameraMode.Video, video)
    }
}

/** "Developing…" only when Processing lasts > 600 ms (DESIGN §6.2). */
@Composable
fun DevelopingLabelSimple(s: CameraUiState) {
    var show by remember { mutableStateOf(false) }
    val busy = s.captureState == CaptureState.Processing
    LaunchedEffect(busy) {
        show = false
        if (busy) {
            delay(600)
            show = true
        }
    }
    val alpha by animateFloatAsState(if (show && busy) 1f else 0f, VlMotion.quick(), label = "dev")
    Text(
        stringResource(R.string.shutter_developing).uppercase(), style = VlType.labelEngraved,
        color = VlColors.InkMuted.copy(alpha = alpha), maxLines = 1, modifier = Modifier.padding(top = 4.dp).height(14.dp),
    )
}

/** Mini mode: look chip, shutter, lens, ⋯ → Controls sheet (DESIGN §4.6). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniBar(s: CameraUiState, onIntent: (CameraIntent) -> Unit, vertical: Boolean, nav: CameraNav, onOpenLastShot: () -> Unit, modifier: Modifier = Modifier) {
    var sheet by remember { mutableStateOf(false) }
    val current = s.looks.firstOrNull { it.id == s.lookId }
    val chipCd = stringResource(R.string.cd_look_chip, lookName(current))
    val chip: @Composable () -> Unit = {
        Box(Modifier.size(48.dp).clickable(role = Role.Button) { sheet = true }.semantics { contentDescription = chipCd }, contentAlignment = Alignment.Center) {
            LookSwatch(s.lookId, Modifier.size(32.dp).clip(RoundedCornerShape(VlRadius.Swatch)))
        }
    }
    val more: @Composable () -> Unit = {
        ControlButton(stringResource(R.string.cd_more), null, s.flash != FlashMode.OFF || s.timer != TimerMode.OFF || moreLamp(s) == true, icon = VlIcons.More) { sheet = true }
    }
    if (vertical) {
        Column(modifier.background(VlColors.Deck), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
            chip(); ShutterButton(s, 64.dp, onIntent); LensSwitchButton(s, onIntent, 48.dp); more()
        }
    } else {
        Row(modifier.background(VlColors.Deck), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            chip(); ShutterButton(s, 64.dp, onIntent); LensSwitchButton(s, onIntent, 48.dp); more()
        }
    }
    if (sheet) {
        val title = stringResource(R.string.cd_controls_sheet)
        ModalBottomSheet(onDismissRequest = { sheet = false }, containerColor = VlColors.Raised, modifier = Modifier.semantics { paneTitle = title }) {
            var pendingClose by remember { mutableStateOf(false) }
            LaunchedEffect(pendingClose) { if (pendingClose) { delay(300); sheet = false } }
            LookPicker(
                s.looks, s.lookId, vertical = false, compact = false,
                enabled = s.recording.phase !in setOf(RecordingPhase.Starting, RecordingPhase.Recording, RecordingPhase.Stopping),
                onSelect = { onIntent(CameraIntent.SelectLook(it)); pendingClose = true },
                modifier = Modifier.fillMaxWidth().height(96.dp),
            )
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (s.mode == CameraMode.Photo) {
                    FlashButton(s, onIntent); TimerButton(s, onIntent); BoostButton(s, onIntent); RatioButton(s, onIntent); FrameButton(s, onIntent); DateStampButton(s, onIntent)
                }
            }
            if (s.mode == CameraMode.Photo) CaptureModeButtons(s, onIntent)
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                LastShotThumbnail(s, onOpenLastShot) { onIntent(CameraIntent.LastShotUnavailable) }
                TextButton(onClick = { sheet = false; nav.onLicenses() }) { Text(stringResource(R.string.menu_licenses), color = VlColors.Ink) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { sheet = false; nav.onImport() }) { Text(stringResource(R.string.menu_import), color = VlColors.Ink) }
                TextButton(onClick = { sheet = false; nav.onGallery(null) }) { Text(stringResource(R.string.menu_gallery), color = VlColors.Ink) }
                TextButton(onClick = { onIntent(CameraIntent.ToggleKeepNegatives) }) {
                    Text(
                        stringResource(R.string.menu_toggle_state, stringResource(R.string.menu_keep_negatives), stringResource(if (s.keepNegatives) R.string.toggle_on else R.string.toggle_off)),
                        color = VlColors.Ink,
                    )
                }
            }
        }
    }
}

/** SPEC 7.4 / DESIGN §6.8: "1/2" progress chip with a cancel control once the first shot is held. */
@Composable
fun MultiShotChip(s: CameraUiState, onIntent: (CameraIntent) -> Unit, modifier: Modifier = Modifier) {
    if (s.multiMode == MultiMode.None) return
    val has = MultiShotRules.holdsFirst(s.multi)
    val canCancel = s.multiExposure == MultiExposure.HasFirst
    val label = when (s.multiMode) {
        MultiMode.Grid4 -> stringResource(R.string.multi_chip_grid, s.gridShots)
        MultiMode.Double -> stringResource(R.string.multi_chip_double, if (has) 1 else 0)
        else -> stringResource(R.string.multi_chip_half, if (has) 1 else 0)
    }
    val cancelCd = stringResource(R.string.cd_multi_cancel)
    Row(
        modifier
            .clip(RoundedCornerShape(VlRadius.Chip))
            .background(VlColors.Raised.copy(alpha = 0.88f))
            .padding(start = 12.dp, end = if (canCancel) 0.dp else 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = VlType.labelValue(), color = VlColors.Ink, modifier = Modifier.padding(vertical = 10.dp))
        if (canCancel) {
            Box(
                Modifier.size(44.dp).clickable(role = Role.Button) { onIntent(CameraIntent.CancelFirstExposure) }.semantics { contentDescription = cancelCd },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.glyph_close), style = VlType.labelValue(), color = VlColors.Ink) }
        }
    }
}
