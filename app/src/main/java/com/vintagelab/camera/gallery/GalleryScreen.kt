package com.vintagelab.camera.gallery

import android.app.RecoverableSecurityException
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.MediaController
import android.widget.VideoView
import com.vintagelab.camera.R
import com.vintagelab.camera.ui.icons.VlIcons
import com.vintagelab.camera.ui.theme.VlColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** In-memory thumbnail cache for the grid (about 24 MB at 256 px). */
private object ThumbCache : LruCache<Long, Bitmap>(96)

/**
 * In-app gallery (DESIGN section 13): grid of our own photos and a full-screen pager viewer.
 * [focus] opens the viewer on that photo (last-shot thumbnail, or the photo just saved by the editor).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    vm: GalleryViewModel,
    state: GalleryState,
    focus: Uri?,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onRedevelop: (Long) -> Unit,
    onPhotoDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var viewer by remember { mutableIntStateOf(-1) }
    var focusDone by remember(focus) { mutableStateOf(focus == null) }

    // Open the viewer on the requested photo once the list contains it.
    LaunchedEffect(state.items, focus) {
        if (focusDone || focus == null) return@LaunchedEffect
        val id = runCatching { ContentUris.parseId(focus) }.getOrNull()
        val idx = state.items.indexOfFirst { it.id == id }
        when {
            idx >= 0 -> { viewer = idx; focusDone = true }
            state.endReached || (state.loaded && !state.loading && state.items.isEmpty()) -> focusDone = true
            state.loaded -> vm.loadMore()
        }
    }

    LaunchedEffect(state.items.isEmpty()) { if (state.items.isEmpty()) viewer = -1 }

    // Deleting: API 30+ asks the system (createDeleteRequest); API 29 deletes directly, or via RecoverableSecurityException.
    var pendingDelete by remember { mutableStateOf<GalleryItem?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val item = pendingDelete ?: return@rememberLauncherForActivityResult
        pendingDelete = null
        if (r.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // OK on API 30+ means the system already deleted the row.
            vm.onDeleted(item)
            onPhotoDeleted()
            scope.launch { snackbar.showSnackbar(res.getString(R.string.msg_photo_deleted)) }
        } else {
            val ok = runCatching { context.contentResolver.delete(item.uri, null, null) > 0 }.getOrDefault(false)
            if (ok) {
                vm.onDeleted(item)
                onPhotoDeleted()
                scope.launch { snackbar.showSnackbar(res.getString(R.string.msg_photo_deleted)) }
            } else scope.launch { snackbar.showSnackbar(res.getString(R.string.err_delete_failed)) }
        }
    }
    val requestDelete: (GalleryItem) -> Unit = { item ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingDelete = item
            val sender = MediaStore.createDeleteRequest(context.contentResolver, listOf(item.uri)).intentSender
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } else {
            try {
                if (context.contentResolver.delete(item.uri, null, null) > 0) {
                    vm.onDeleted(item)
                    onPhotoDeleted()
                    scope.launch { snackbar.showSnackbar(res.getString(R.string.msg_photo_deleted)) }
                } else scope.launch { snackbar.showSnackbar(res.getString(R.string.err_delete_failed)) }
            } catch (e: RecoverableSecurityException) {
                pendingDelete = item
                deleteLauncher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
            } catch (e: SecurityException) {
                scope.launch { snackbar.showSnackbar(res.getString(R.string.err_delete_failed)) }
            }
        }
    }

    BackHandler { if (viewer >= 0) viewer = -1 else onBack() }
    val title = stringResource(R.string.gallery_title)
    Box(Modifier.fillMaxSize().background(VlColors.Bench).semantics { paneTitle = title }) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(VlIcons.Back, stringResource(R.string.cd_back), tint = VlColors.Ink) } },
                actions = { TextButton(onClick = onImport) { Text(stringResource(R.string.menu_import), color = VlColors.Amber) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = VlColors.Deck, titleContentColor = VlColors.Ink),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !state.loaded -> CircularProgressIndicator(color = VlColors.Amber, modifier = Modifier.align(Alignment.Center))
                    state.items.isEmpty() -> EmptyState(context)
                    else -> Grid(state, vm, onOpen = { viewer = it })
                }
            }
        }
        if (viewer >= 0 && state.items.isNotEmpty()) {
            Viewer(
                vm, state.items, viewer.coerceIn(0, state.items.lastIndex),
                onClose = { viewer = -1 },
                onRedevelop = onRedevelop,
                onDelete = requestDelete,
                snackbar = snackbar,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp))
    }
}

@Composable
private fun EmptyState(context: android.content.Context) {
    val res = LocalResources.current
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.gallery_empty_title), color = VlColors.Ink, textAlign = TextAlign.Center)
        Text(stringResource(R.string.gallery_empty_body), color = VlColors.InkMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        val scope = rememberCoroutineScope()
        val snackbarMsg = res.getString(R.string.err_no_gallery_app)
        TextButton(onClick = { if (!openPhotosApp(context)) scope.launch { android.widget.Toast.makeText(context, snackbarMsg, android.widget.Toast.LENGTH_SHORT).show() } }) {
            Text(stringResource(R.string.gallery_open_photos), color = VlColors.Amber)
        }
    }
}

/** Opens the user's gallery app on all photos (the reinstall case: our own photos are no longer ours to list). */
private fun openPhotosApp(context: android.content.Context): Boolean {
    val intents = listOf(
        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY),
        Intent(Intent.ACTION_VIEW).setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*"),
    )
    for (i in intents) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (e: ActivityNotFoundException) {
            // try the next one
        }
    }
    return false
}

@Composable
private fun Grid(state: GalleryState, vm: GalleryViewModel, onOpen: (Int) -> Unit) {
    val grid = rememberLazyGridState()
    LaunchedEffect(grid, state.items.size) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= state.items.size - 12) vm.loadMore()
        }
    }
    LazyVerticalGrid(
        GridCells.Adaptive(110.dp), Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), grid,
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(state.items, key = { _, it -> it.id }) { i, item ->
            val cd = stringResource(if (item.isVideo) R.string.cd_video_n else R.string.cd_photo_n, i + 1)
            Thumb(item, Modifier.aspectRatio(1f).clickable(role = Role.Button) { onOpen(i) }.semantics { contentDescription = cd })
        }
    }
}

@Composable
private fun Thumb(item: GalleryItem, modifier: Modifier) {
    val context = LocalContext.current
    var bmp by remember(item.id) { mutableStateOf(ThumbCache.get(item.id)) }
    LaunchedEffect(item.id) {
        if (bmp != null) return@LaunchedEffect
        val b = withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(item.uri, Size(256, 256), null) }.getOrNull() }
        if (b != null) { ThumbCache.put(item.id, b); bmp = b }
    }
    Box(modifier.background(VlColors.Raised)) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (item.isVideo) {
            Canvas(Modifier.align(Alignment.Center).fillMaxSize(0.28f)) {
                val path = Path().apply {
                    moveTo(size.width * 0.28f, size.height * 0.15f)
                    lineTo(size.width * 0.82f, size.height * 0.5f)
                    lineTo(size.width * 0.28f, size.height * 0.85f)
                    close()
                }
                drawPath(path, Color.White.copy(alpha = 0.9f))
            }
        }
    }
}

/** Full-screen pager over [items]; actions along the bottom. */
@Composable
private fun Viewer(
    vm: GalleryViewModel,
    items: List<GalleryItem>,
    start: Int,
    onClose: () -> Unit,
    onRedevelop: (Long) -> Unit,
    onDelete: (GalleryItem) -> Unit,
    snackbar: SnackbarHostState,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = start) { items.size }
    val page = pager.currentPage.coerceIn(0, items.lastIndex)
    val item = items[page]
    LaunchedEffect(pager, items.size) {
        snapshotFlow { pager.currentPage }.collect { if (it >= items.size - 6) vm.loadMore() }
    }
    val hasNegative = remember(item.id, item.isVideo) { !item.isVideo && vm.hasNegative(item.id) }
    Box(Modifier.fillMaxSize().background(VlColors.Bench)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { items.getOrNull(it)?.id ?: it }) { p ->
            items.getOrNull(p)?.let { ViewerPage(it) }
        }
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).background(VlColors.VfScrim), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(VlIcons.Back, stringResource(R.string.cd_back), tint = VlColors.Ink) }
            Text(
                stringResource(R.string.viewer_position, page + 1, items.size), color = VlColors.Ink,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(VlColors.VfScrim).windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = { share(context, item, res.getString(R.string.viewer_share)) }) { Text(stringResource(R.string.viewer_share), color = VlColors.Ink) }
            if (hasNegative) {
                TextButton(onClick = { onRedevelop(item.id) }) { Text(stringResource(R.string.viewer_redevelop), color = VlColors.Amber) }
            }
            TextButton(onClick = { onDelete(item) }) { Text(stringResource(R.string.viewer_delete), color = VlColors.Signal) }
        }
    }
}

private fun share(context: android.content.Context, item: GalleryItem, title: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(item.mimeType)
        .putExtra(Intent.EXTRA_STREAM, item.uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, item.uri)
    try {
        context.startActivity(Intent.createChooser(send, title))
    } catch (e: ActivityNotFoundException) {
        // nothing can receive images
    }
}

/** One page: decoded at screen scale (long side at most 2048), never at full resolution. */
@Composable
private fun ViewerPage(item: GalleryItem) {
    if (item.isVideo) {
        val playCd = stringResource(R.string.cd_video_play)
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    val controls = MediaController(context)
                    controls.setAnchorView(this)
                    setMediaController(controls)
                    setVideoURI(item.uri)
                    setOnPreparedListener { player -> player.isLooping = true; start() }
                    contentDescription = playCd
                }
            },
            update = { view -> if (view.tag != item.uri) { view.tag = item.uri; view.setVideoURI(item.uri); view.start() } },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    val context = LocalContext.current
    var bmp by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id) {
        val b = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, item.uri)) { d, info, _ ->
                    val long = max(info.size.width, info.size.height)
                    if (long > 2048) {
                        val k = 2048.0 / long
                        d.setTargetSize(max(1, (info.size.width * k).toInt()), max(1, (info.size.height * k).toInt()))
                    }
                }
            }.getOrNull()
        }
        bmp = b
        failed = b == null
    }
    // Release the page bitmap as soon as the pager disposes this page.
    androidx.compose.runtime.DisposableEffect(item.id) {
        onDispose {
            bmp?.recycle()
            bmp = null
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val b = bmp
        when {
            b != null -> Image(b.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().clip(androidx.compose.ui.graphics.RectangleShape))
            failed -> Text(stringResource(R.string.err_photo_missing), color = VlColors.InkMuted)
            else -> CircularProgressIndicator(color = VlColors.Amber)
        }
    }
}
