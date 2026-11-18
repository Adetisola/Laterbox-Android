package com.laterbox.app.ui.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laterbox.app.data.repository.LinkRepository
import com.laterbox.app.domain.model.SavedLink
import com.laterbox.app.movies.domain.model.CollectionMediaItem
import com.laterbox.app.movies.domain.repository.MoviesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CollectionDetailUiState(
    val collectionId: Long = 0,
    val collectionName: String = "",
    val links: List<SavedLink> = emptyList(),
    val mediaItems: List<CollectionMediaItem> = emptyList(),
    val isLoading: Boolean = true
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CollectionDetailViewModel(
    private val linkRepository: LinkRepository,
    private val moviesRepository: MoviesRepository? = null
) : ViewModel() {

    private val _collectionInfo = MutableStateFlow<Pair<Long, String>?>(null)

    val uiState: StateFlow<CollectionDetailUiState> = _collectionInfo.flatMapLatest { info ->
        if (info == null) {
            MutableStateFlow(CollectionDetailUiState(isLoading = true))
        } else {
            val (id, name) = info
            val mediaFlow = moviesRepository?.observeMediaInCollection(id) ?: flowOf(emptyList())
            combine(
                linkRepository.getLinksByCollection(id),
                mediaFlow
            ) { links, mediaItems ->
                CollectionDetailUiState(
                    collectionId = id,
                    collectionName = name,
                    links = links,
                    mediaItems = mediaItems,
                    isLoading = false
                )
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CollectionDetailUiState(isLoading = true)
    )

    fun setCollection(id: Long, name: String) {
        _collectionInfo.value = Pair(id, name)
    }

    fun onRemoveFromCollection(link: SavedLink) {
        viewModelScope.launch {
            linkRepository.updateLink(link.copy(collectionId = null))
        }
    }

    fun onRemoveMediaItem(item: CollectionMediaItem) {
        viewModelScope.launch {
            moviesRepository?.removeMediaFromCollection(item.summary, item.collectionId)
        }
    }

    fun onArchive(linkId: Long) {
        viewModelScope.launch {
            linkRepository.setArchived(linkId, true)
        }
    }

    fun onDelete(linkId: Long) {
        viewModelScope.launch {
            linkRepository.deleteLink(linkId)
        }
    }
}
