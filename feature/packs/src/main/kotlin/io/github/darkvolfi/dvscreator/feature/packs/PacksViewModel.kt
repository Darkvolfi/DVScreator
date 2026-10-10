package io.github.darkvolfi.dvscreator.feature.packs

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.darkvolfi.dvscreator.core.imaging.AnimationTooLargeException
import io.github.darkvolfi.dvscreator.core.imaging.CreatedSticker
import io.github.darkvolfi.dvscreator.core.imaging.StickerFactory
import io.github.darkvolfi.dvscreator.core.imaging.StickerTooLargeException
import io.github.darkvolfi.dvscreator.core.model.TrimRange
import io.github.darkvolfi.dvscreator.core.whatsapp.PackManifest
import io.github.darkvolfi.dvscreator.core.whatsapp.PackRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Galeriden seçilmiş bir kaynak; videolarda kırpma ekranında seçilen aralıkla. */
data class StickerSource(val uri: Uri, val mimeType: String, val trim: TrimRange? = null) {
    val isVideo get() = mimeType.startsWith("video/")
}

/** Bir pakete sticker eklenirken (videolarda 5-30 sn) ve sonrasında gösterilen durum. */
/** Tek bir kaynağın sonucu: üretilen sticker ya da hata mesajı. */
class ItemResult(val sticker: CreatedSticker? = null, val error: String? = null)

/**
 * Bir pakete eklenen kaynakların durumu. Sonuçlar birikir (kullanıcı kapatana dek): birden çok video
 * eklenirken bir hatanın sonraki başarıyla ekrandan silinmemesi için.
 */
sealed interface AddState {
    val results: List<ItemResult>

    data object Idle : AddState { override val results = emptyList<ItemResult>() }
    data class Working(
        val pending: Int,
        override val results: List<ItemResult>,
        val progress: StickerFactory.Progress? = null,
    ) : AddState
    data class Finished(override val results: List<ItemResult>) : AddState
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

    /** Kırpma ekranını bekleyen videolar (paket kimliği → sıra). */
    private val _pendingTrims = MutableStateFlow<Map<String, List<StickerSource>>>(emptyMap())
    val pendingTrims: StateFlow<Map<String, List<StickerSource>>> = _pendingTrims.asStateFlow()

    /**
     * Galeriden seçilenler: fotoğraf ve GIF'ler hemen işlenir, videolar önce kırpma ekranına sıraya girer.
     */
    fun onPicked(packId: String, sources: List<StickerSource>) {
        val (videos, others) = sources.partition { it.isVideo }
        if (videos.isNotEmpty()) _pendingTrims.update { it + (packId to (it[packId].orEmpty() + videos)) }
        addStickers(packId, others)
    }

    /** Kırpma ekranı onaylandı (ya da [trim] = null ile atlandı): sıradaki videoya geç. */
    fun onTrimmed(packId: String, source: StickerSource, trim: TrimRange?) {
        _pendingTrims.update { map -> map + (packId to map[packId].orEmpty().drop(1)) }
        addStickers(packId, listOf(source.copy(trim = trim)))
    }

    fun onTrimCancelled(packId: String) = _pendingTrims.update { map -> map + (packId to map[packId].orEmpty().drop(1)) }

    fun createPack(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch { onCreated(repository.createPack(name).identifier) }
    }

    /** Aynı anda tek kaynak işlenir: iki video birlikte belleğe (~150 MB/klip) sığmayabilir. */
    private val processing = Mutex()

    private fun addStickers(packId: String, sources: List<StickerSource>) {
        if (sources.isEmpty()) return
        viewModelScope.launch {
            processing.withLock {
                var remaining = sources.size
                for (source in sources) {
                    _addState.update { it + (packId to AddState.Working(remaining, it[packId]?.results.orEmpty())) }
                    val item = runCatching {
                        repository.addSticker(packId, source.uri, source.mimeType, source.trim) { p ->
                            _addState.update { map ->
                                val working = map[packId] as? AddState.Working ?: return@update map
                                map + (packId to working.copy(progress = p))
                            }
                        }
                    }.fold(
                        onSuccess = { ItemResult(sticker = it) },
                        onFailure = { e ->
                            Log.e(TAG, "sticker eklenemedi", e)
                            ItemResult(
                                error = when (e) {
                                    is StickerTooLargeException, is AnimationTooLargeException, is IllegalStateException -> e.message.orEmpty()
                                    is OutOfMemoryError -> "Bellek yetmedi — daha kısa bir video deneyin."
                                    else -> e.toString()
                                },
                            )
                        },
                    )
                    remaining--
                    _addState.update { map ->
                        val results = map[packId]?.results.orEmpty() + item
                        map + (packId to if (remaining > 0) AddState.Working(remaining, results) else AddState.Finished(results))
                    }
                }
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
