package com.vintagelab.camera.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vintagelab.camera.R
import com.vintagelab.camera.looks.EditAdjust
import com.vintagelab.camera.ui.ControlButton
import com.vintagelab.camera.ui.LookPicker
import com.vintagelab.camera.ui.frameIconAndName
import com.vintagelab.camera.ui.icons.VlIcons
import com.vintagelab.camera.ui.lookName
import com.vintagelab.camera.ui.prismName
import com.vintagelab.camera.ui.theme.VlColors
import java.util.Locale

/** Editor for imported photos and negatives (DESIGN section 12): live preview, look carousel, toggles, two sliders, Save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel, state: EditorState, onBack: () -> Unit) {
    val res = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val saving = state.phase == EditorPhase.Saving
    BackHandler(enabled = !saving) { onBack() }
    LaunchedEffect(state.errorSeq) {
        val e = state.error ?: return@LaunchedEffect
        if (state.phase != EditorPhase.Failed) snackbar.showSnackbar(res.getString(errorText(e)))
    }
    val title = stringResource(if (state.redevelop) R.string.editor_title_redevelop else R.string.editor_title_import)
    Column(Modifier.fillMaxSize().background(VlColors.Bench).semantics { paneTitle = title }) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = onBack, enabled = !saving) { Icon(VlIcons.Back, stringResource(R.string.cd_back), tint = VlColors.Ink) }
            },
            actions = {
                TextButton(onClick = vm::save, enabled = state.phase == EditorPhase.Ready) {
                    Text(stringResource(R.string.editor_save), color = if (state.phase == EditorPhase.Ready) VlColors.Amber else VlColors.InkDisabled)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = VlColors.Deck, titleContentColor = VlColors.Ink),
        )
        Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (state.phase) {
                EditorPhase.Failed -> Message(state.error?.let(::errorText) ?: R.string.err_import_failed, onBack)
                EditorPhase.Idle -> Unit
                else -> EditorBody(vm, state, saving)
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }
}

private fun errorText(e: EditorError): Int = when (e) {
    EditorError.OpenFailed -> R.string.err_import_failed
    EditorError.NegativeMissing -> R.string.err_negative_missing
    EditorError.SaveFailed -> R.string.err_save_failed
    EditorError.StorageFull -> R.string.err_storage_full
    EditorError.DevelopFailed -> R.string.err_develop_failed
}

@Composable
private fun Message(text: Int, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(text), color = VlColors.Ink, textAlign = TextAlign.Center)
        TextButton(onClick = onBack) { Text(stringResource(R.string.cd_back), color = VlColors.Amber) }
    }
}

@Composable
private fun EditorBody(vm: EditorViewModel, st: EditorState, saving: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val preview: @Composable (Modifier) -> Unit = { mod -> PreviewPane(st, saving, mod) }
        val controls: @Composable (Modifier) -> Unit = { mod -> Controls(vm, st, mod) }
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                preview(Modifier.weight(1f).fillMaxHeight())
                controls(Modifier.width(340.dp).fillMaxHeight().verticalScrollable())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                preview(Modifier.weight(1f).fillMaxWidth())
                controls(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Modifier.verticalScrollable(): Modifier = this.verticalScroll(rememberScrollState())

@Composable
private fun PreviewPane(st: EditorState, saving: Boolean, modifier: Modifier) {
    val look = st.looks.firstOrNull { it.id == st.lookId }
    val cd = stringResource(R.string.cd_editor_preview, lookName(look))
    Box(modifier.padding(12.dp), contentAlignment = Alignment.Center) {
        val bmp = st.preview
        if (bmp != null) {
            Image(bmp.asImageBitmap(), cd, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        if (st.phase == EditorPhase.Loading || bmp == null || saving) {
            val label = stringResource(if (saving) R.string.editor_saving else R.string.editor_loading)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics { contentDescription = label }) {
                CircularProgressIndicator(color = VlColors.Amber)
                Text(label, color = VlColors.Ink, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun Controls(vm: EditorViewModel, st: EditorState, modifier: Modifier) {
    val enabled = st.phase == EditorPhase.Ready
    Column(modifier.background(VlColors.Deck).padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            val (icon, name) = frameIconAndName(st.frame)
            ControlButton(stringResource(R.string.cd_frame), stringResource(name), st.frame != com.vintagelab.camera.looks.FrameId.NONE, enabled = enabled, icon = icon) { vm.cycleFrame() }
            ControlButton(
                stringResource(R.string.cd_date_stamp), null, st.dateStamp, enabled = enabled, role = Role.Switch, icon = VlIcons.DateStamp,
                iconTint = if (st.dateStamp) VlColors.Amber else VlColors.Ink,
            ) { vm.toggleStamp() }
            ControlButton(
                stringResource(R.string.cd_prism), stringResource(prismName(st.prism)), st.prism != com.vintagelab.camera.looks.PrismMode.OFF,
                enabled = enabled, text = stringResource(R.string.label_prism),
            ) { vm.cyclePrism() }
            ControlButton(stringResource(R.string.cd_shuffle), null, null, enabled = enabled, icon = VlIcons.Shuffle) { vm.shuffle() }
        }
        AdjustSlider(
            stringResource(R.string.editor_exposure), String.format(Locale.US, "%+.1f EV", st.exposureEv), st.exposureEv,
            -EditAdjust.EV_RANGE..EditAdjust.EV_RANGE, enabled, vm::setExposure,
        )
        AdjustSlider(
            stringResource(R.string.editor_temperature), String.format(Locale.US, "%+d", Math.round(st.temperature * 100)), st.temperature,
            -EditAdjust.TEMP_RANGE..EditAdjust.TEMP_RANGE, enabled, vm::setTemperature,
        )
        if (st.exposureEv != 0f || st.temperature != 0f) {
            TextButton(onClick = vm::resetAdjustments, enabled = enabled, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.editor_reset), color = VlColors.InkMuted)
            }
        }
        LookPicker(
            st.looks, st.lookId, vertical = false, compact = false,
            onSelect = { if (enabled) vm.selectLook(it) },
            modifier = Modifier.fillMaxWidth().height(96.dp),
        )
    }
}

@Composable
private fun AdjustSlider(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = VlColors.InkMuted, modifier = Modifier.width(96.dp))
        Slider(
            value = value, onValueChange = onChange, valueRange = range, enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = VlColors.Amber, activeTrackColor = VlColors.Amber, inactiveTrackColor = VlColors.Hairline),
            modifier = Modifier.weight(1f).semantics { contentDescription = label; stateDescription = valueText },
        )
        Text(valueText, color = VlColors.Ink, modifier = Modifier.width(64.dp), textAlign = TextAlign.End)
    }
}
