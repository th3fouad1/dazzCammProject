package com.vintagelab.camera.ui

import android.annotation.SuppressLint
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vintagelab.camera.R
import com.vintagelab.camera.ui.theme.VlColors
import com.vintagelab.camera.ui.theme.VlRadius
import com.vintagelab.camera.ui.theme.VlType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter

/** presets.json references string resources by key so looks stay data-driven. */
@SuppressLint("DiscouragedApi")
@Composable
fun lookName(look: LookUi?): String {
    if (look == null) return ""
    val resources = LocalResources.current
    val id = resources.getIdentifier(look.displayNameRes, "string", LocalContext.current.packageName)
    return if (id != 0) resources.getString(id) else look.id
}

/** True while TalkBack touch exploration is on (scroll-settle must never select then). */
@Composable
fun rememberTouchExploration(): Boolean {
    val am = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var on by remember { mutableStateOf(am?.isTouchExplorationEnabled == true) }
    DisposableEffect(am) {
        val l = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        am?.addTouchExplorationStateChangeListener(l)
        onDispose { am?.removeTouchExplorationStateChangeListener(l) }
    }
    return on
}

/**
 * Look carousel (DESIGN §5.1, §6.4). `lookId` is the source of truth; the list only reflects it.
 * Selection: tap; or a user drag that settles (150 ms, not under TalkBack). Programmatic scrolls never select.
 */
@Composable
fun LookPicker(
    looks: List<LookUi>,
    selectedId: String,
    vertical: Boolean,
    compact: Boolean,
    enabled: Boolean = true,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()
    val touchExploration = rememberTouchExploration()
    val currentSelect by rememberUpdatedState(onSelect)
    val selectedIndex = looks.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)

    LaunchedEffect(selectedIndex, looks.size) { if (looks.isNotEmpty()) state.animateScrollToItem(selectedIndex) }
    SettleSelect(state, looks, touchExploration) { if (enabled) currentSelect(it) }

    val cardMain: Dp = if (compact) 56.dp else if (vertical) 72.dp else LABEL_CARD_PORTRAIT_W.dp
    BoxWithConstraints(modifier) {
        val extent = if (vertical) maxHeight else maxWidth
        val pad = ((extent - cardMain) / 2).coerceAtLeast(0.dp)
        val fling = rememberSnapFlingBehavior(state, SnapPosition.Center)
        val groupMod = Modifier
            .selectableGroup()
            .semantics { collectionInfo = if (vertical) CollectionInfo(looks.size, 1) else CollectionInfo(1, looks.size) }
        val content: @Composable (Int, LookUi) -> Unit = { i, look ->
            LookCard(
                look, look.id == selectedId, compact, vertical, enabled,
                Modifier.semantics {
                    collectionItemInfo = if (vertical) CollectionItemInfo(i, 1, 0, 1) else CollectionItemInfo(0, 1, i, 1)
                },
            ) { onSelect(look.id) }
        }
        if (vertical) {
            LazyColumn(
                groupMod, state, PaddingValues(vertical = pad), verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, flingBehavior = fling,
            ) { itemsIndexed(looks, key = { _, l -> l.id }) { i, l -> content(i, l) } }
        } else {
            LazyRow(
                groupMod, state, PaddingValues(horizontal = pad), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically, flingBehavior = fling,
            ) { itemsIndexed(looks, key = { _, l -> l.id }) { i, l -> content(i, l) } }
        }
    }
}

@Composable
private fun SettleSelect(state: LazyListState, looks: List<LookUi>, touchExploration: Boolean, onSelect: (String) -> Unit) {
    var userDrag by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect { if (it is DragInteraction.Start) userDrag = true }
    }
    LaunchedEffect(state, looks, touchExploration) {
        snapshotFlow { state.isScrollInProgress }.filter { !it }.collect {
            if (!userDrag || touchExploration) return@collect
            delay(150)
            userDrag = false
            val info = state.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val item = info.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - center) } ?: return@collect
            looks.getOrNull(item.index)?.let { onSelect(it.id) }
        }
    }
}

/** Mono glyph advance ~0.6 em plus 0.12 em tracking, with a safety margin; shrinks to [MIN_LABEL_SP], never ellipsised. */
internal const val LABEL_CARD_PORTRAIT_W = 80f
internal const val LABEL_CARD_LANDSCAPE_W = 132f
internal const val MIN_LABEL_SP = 9f
internal const val MAX_LABEL_SP = 11f
internal fun labelFontSizeSp(chars: Int, availDp: Float): Float =
    (availDp / (chars.coerceAtLeast(1) * 0.76f)).coerceIn(MIN_LABEL_SP, MAX_LABEL_SP)

@Composable
private fun LookCard(look: LookUi, selected: Boolean, compact: Boolean, landscape: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val name = lookName(look)
    val shape = RoundedCornerShape(VlRadius.Card)
    val border = if (selected) 2.dp else 1.dp
    val borderColor = if (selected) VlColors.Amber else VlColors.Hairline
    val cd = androidx.compose.ui.res.stringResource(R.string.cd_look_suffix, name)
    val base = modifier
        .clip(shape)
        .background(VlColors.Raised)
        .border(border, borderColor, shape)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .semantics { contentDescription = cd }
    if (compact) {
        LookSwatch(look.id, base.size(56.dp).padding(4.dp).clip(RoundedCornerShape(VlRadius.Swatch)))
        return
    }
    val nameRow: @Composable (Float) -> Unit = { availDp ->
        val fs = labelFontSizeSp(name.length, availDp - if (selected) 10f else 0f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name.uppercase(), style = VlType.labelEngraved.copy(fontSize = fs.sp), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                color = if (selected) VlColors.Amber else VlColors.InkMuted, modifier = Modifier.weight(1f, fill = false),
            )
            if (selected) {
                Spacer(Modifier.width(4.dp))
                Spacer(Modifier.size(6.dp).clip(CircleShape).background(VlColors.Amber))
            }
        }
    }
    if (landscape) {
        Row(base.size(LABEL_CARD_LANDSCAPE_W.dp, 72.dp).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            LookSwatch(look.id, Modifier.size(48.dp).clip(RoundedCornerShape(VlRadius.Swatch)))
            Spacer(Modifier.width(4.dp))
            nameRow(LABEL_CARD_LANDSCAPE_W - 8f - 48f - 4f)
        }
    } else {
        Column(base.size(LABEL_CARD_PORTRAIT_W.dp, 88.dp).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LookSwatch(look.id, Modifier.size(64.dp).clip(RoundedCornerShape(VlRadius.Swatch)))
            Spacer(Modifier.size(4.dp))
            nameRow(LABEL_CARD_PORTRAIT_W - 8f)
        }
    }
}
