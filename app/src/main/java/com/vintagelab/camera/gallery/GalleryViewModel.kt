package com.vintagelab.camera.gallery

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vintagelab.camera.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class GalleryState(
    val items: List<GalleryItem> = emptyList(),
    /** True after the first page came back (an empty list is then a real empty state). */
    val loaded: Boolean = false,
    val endReached: Boolean = false,
    val loading: Boolean = false,
)

/** Paged list of our own photos (SPEC 6.8), newest first. */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val _state = MutableStateFlow(GalleryState())
    val state: StateFlow<GalleryState> = _state.asStateFlow()
    private var generation = 0

    /** Reloads from the first page (on entering the gallery, after a save or delete). */
    fun refresh() {
        generation++
        _state.update { it.copy(loading = false, endReached = false) }
        loadMore(reset = true)
    }

    fun loadMore(reset: Boolean = false) {
        val s = _state.value
        if (!reset && (s.loading || s.endReached)) return
        val gen = generation
        val offset = if (reset) 0 else s.items.size
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val page = withContext(Dispatchers.IO) { app.gallery.page(offset) }
            if (gen != generation) return@launch
            _state.update {
                it.copy(
                    items = if (reset) page else it.items + page.filter { n -> it.items.none { o -> o.id == n.id } },
                    loaded = true,
                    endReached = page.size < GalleryQuery.PAGE_SIZE,
                    loading = false,
                )
            }
        }
    }

    /** Removes [id] from the list and drops its negative (SPEC 6.9: deleted with its photo). */
    fun onDeleted(item: GalleryItem) {
        _state.update { s -> s.copy(items = s.items.filterNot { it.id == item.id }) }
        if (!item.isVideo) viewModelScope.launch(Dispatchers.IO) { app.negatives.delete(item.id) }
    }

    fun hasNegative(id: Long): Boolean = app.negatives.has(id)
}
