package io.github.darkvolfi.wscreator.feature.packs

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.darkvolfi.wscreator.core.imaging.AnimationTooLargeException
import io.github.darkvolfi.wscreator.core.imaging.CreatedSticker
import io.github.darkvolfi.wscreator.core.imaging.StickerTooLargeException
import io.github.darkvolfi.wscreator.core.whatsapp.PackManifest
import io.github.darkvolfi.wscreator.core.whatsapp.PackRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Bir pakete sticker eklenirken (videolarda 5-30 sn) ve sonrasında gösterilen durum. */
sealed interface AddState {
    data object Idle : AddState
    data class Working(val pending: Int) : AddState
    data class Done(val sticker: CreatedSticker) : AddState
    data class Failed(val message: String) : AddState
}

/**
 * Paket ekranlarının durumu. İşlemler viewModelScope'ta çalışır: ekran döndürme ya da ekranlar arası
 * geçiş uzun süren video işlemeyi iptal etmez.
 */
class PacksViewModel(application: Application) : AndroidViewModel(application) {
    val repository = PackRepository(application)
    val packs: StateFlow<List<PackManifest>> = repository.packs

    private val _addState = MutableStateFlow<Map<String, AddState>>(emptyMap())
    val addState: StateFlow<Map<String, AddState>> = _addState.asStateFlow()

    fun createPack(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch { onCreated(repository.createPack(name).identifier) }
    }

    /** Birden çok kaynak sırayla işlenir (aynı anda birden fazla video belleğe sığmayabilir). */
    fun addStickers(packId: String, sources: List<Pair<Uri, String>>) {
        if (sources.isEmpty()) return
        viewModelScope.launch {
            var remaining = sources.size
            for ((uri, mime) in sources) {
                _addState.update { it + (packId to AddState.Working(remaining)) }
                val state = runCatching { repository.addSticker(packId, uri, mime) }.fold(
                    onSuccess = { AddState.Done(it) },
                    onFailure = { e ->
                        Log.e(TAG, "sticker eklenemedi", e)
                        AddState.Failed(
                            when (e) {
                                is StickerTooLargeException, is AnimationTooLargeException, is IllegalStateException -> e.message.orEmpty()
                                is OutOfMemoryError -> "Bellek yetmedi — daha kısa bir video deneyin."
                                else -> e.toString()
                            },
                        )
                    },
                )
                _addState.update { it + (packId to state) }
                remaining--
            }
        }
    }

    fun dismissAddState(packId: String) = _addState.update { it - packId }

    fun removeSticker(packId: String, imageFile: String) = viewModelScope.launch { repository.removeSticker(packId, imageFile) }
    fun setEmojis(packId: String, imageFile: String, emojis: List<String>) =
        viewModelScope.launch { repository.setEmojis(packId, imageFile, emojis) }
    fun rename(packId: String, name: String) = viewModelScope.launch { repository.rename(packId, name) }
    fun deletePack(packId: String) = viewModelScope.launch { repository.deletePack(packId) }

    private companion object {
        const val TAG = "WSC_PACKS"
    }
}
