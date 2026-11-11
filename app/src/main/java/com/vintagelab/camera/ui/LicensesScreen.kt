package com.vintagelab.camera.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vintagelab.camera.BuildConfig
import com.vintagelab.camera.R
import com.vintagelab.camera.ui.icons.VlIcons
import com.vintagelab.camera.ui.theme.VlColors
import com.vintagelab.camera.ui.theme.VlRadius
import com.vintagelab.camera.ui.theme.VlType
import kotlinx.coroutines.CancellationException

private data class LicenseEntry(val name: Int, val license: Int, val asset: String?, val inline: Int? = null)

private val ENTRIES = listOf(
    LicenseEntry(R.string.lic_t3mujinpack, R.string.lic_mit, "licenses/t3mujinpack.txt"),
    LicenseEntry(R.string.lic_plex, R.string.lic_ofl, "licenses/ibm_plex_mono_ofl.txt"),
    LicenseEntry(R.string.lic_androidx, R.string.lic_apache, null, R.string.lic_androidx_notice),
)

/** Open-source licenses (DESIGN §7.2), predictive back scales the page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler { events ->
        try {
            events.collect { progress = it.progress }
            onBack()
        } catch (e: CancellationException) {
            progress = 0f
            throw e
        }
    }
    val title = stringResource(R.string.licenses_title)
    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val s = 1f - 0.1f * progress
                scaleX = s; scaleY = s
                translationX = 24.dp.toPx() * progress
            }
            .background(VlColors.Bench)
            .semantics { paneTitle = title },
    ) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(VlIcons.Back, stringResource(R.string.cd_back), tint = VlColors.Ink) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = VlColors.Deck, titleContentColor = VlColors.Ink),
        )
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            items(ENTRIES) { LicenseCard(it) }
            item {
                Text(
                    stringResource(R.string.licenses_version, BuildConfig.VERSION_NAME),
                    color = VlColors.InkMuted, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun LicenseCard(e: LicenseEntry) {
    val context = LocalContext.current
    var expanded by rememberSaveable(e.name) { mutableStateOf(false) }
    val text = remember(e) {
        e.asset?.let { a -> runCatching { context.assets.open(a).bufferedReader().use { it.readText() } }.getOrDefault("") }
    }
    val cd = stringResource(if (expanded) R.string.cd_collapse else R.string.cd_expand)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(VlRadius.Card)).background(VlColors.Raised)
            .clickable { expanded = !expanded }.semantics { contentDescription = cd }.padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(e.name), color = VlColors.Ink, fontSize = 16.sp)
                Text(stringResource(e.license), color = VlColors.InkMuted)
            }
            Icon(VlIcons.Chevron, null, tint = VlColors.InkMuted, modifier = Modifier.rotate(if (expanded) 180f else 0f))
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(text ?: stringResource(e.inline ?: e.license), style = VlType.licenseText, color = VlColors.Ink)
            }
        }
    }
}
