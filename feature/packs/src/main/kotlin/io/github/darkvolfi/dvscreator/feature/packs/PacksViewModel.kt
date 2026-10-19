package io.github.darkvolfi.dvscreator.feature.packs

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.darkvolfi.dvscreator.core.imaging.AnimationTooLargeException
import io.github.darkvolfi.dvscreator.core.imaging.CreatedSticker
import io.github.darkvolfi.dvscreator.core.imaging.StickerFactory
import io.github.darkvolfi.dvscreator.core.imaging.StickerTooLargeException
import io.github.darkvolfi.dvscreator.core.model.EditRecipe
import io.github.darkvolfi.dvscreator.core.whatsapp.ImportOutcome
import io.github.darkvolfi.dvscreator.core.whatsapp.PackManifest
import io.github.darkvolfi.dvscreator.core.whatsapp.PackRepository
import io.github.darkvolfi.dvscreator.core.whatsapp.ReceivedSticker
import io.github.darkvolfi.dvscreator.core.whatsapp.ReceivedStickers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Galeriden seçilmiş bir kaynak ve düzenleme ekranında seçilen tarif (kırpma, süre aralığı). */
data class StickerSource(val uri: Uri, val mimeType: String, val recipe: EditRecipe = EditRecipe())

/**
 * WhatsApp'tan içe aktarma durumu: [outcome] null iken sürüyor ([done]/[total], yeniden sıkıştırılan sayısı).
 * [newPacks]: "Yeni paket yap" ile oluşturulan paketlerin adları.
 */
data class ImportStatus(
    val done: Int,
    val total: Int,
    val reencoded: Int = 0,
    val outcome: ImportOutcome? = null,
    val newPacks: List<String> = emptyList(),
)

/** Paketsiz tek sticker akışının adımı. */
sealed interface QuickState {
    data object Idle : QuickState
    data class Editing(val source: StickerSource) : QuickState
    data class Working(val progress: StickerFactory.Progress?) : QuickState
    /** [packId]: eklendiği hızlı paket (henüz eklenmediyse null). */
    data class Done(val sticker: CreatedSticker, val recipe: EditRecipe, val packId: String? = null) : QuickState
    data class Failed(val error: String) : QuickState
}

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

    /**
     * Beklenmeyen hatalar (disk dolu, bozuk paket dosyası…) uygulamayı çökertmesin: kaydedilir ve
     * kısa bir mesajla gösterilir. Sonucunu ekranda gösteren işlemler hatalarını ayrıca kendileri yakalar.
     */
    private val errorHandler = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "işlem başarısız", e)
        val app = getApplication<Application>()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(app, app.getString(R.string.operation_failed, errorText(e)), Toast.LENGTH_LONG).show()
        }
    }

    private fun launchSafely(block: suspend CoroutineScope.() -> Unit): Job = viewModelScope.launch(errorHandler, block = block)
    val packs: StateFlow<List<PackManifest>> = repository.packs

    private val _addState = MutableStateFlow<Map<String, AddState>>(emptyMap())
    val addState: StateFlow<Map<String, AddState>> = _addState.asStateFlow()

    /** Düzenleme ekranını bekleyen kaynaklar (paket kimliği → sıra). */
    private val _pendingEdits = MutableStateFlow<Map<String, List<StickerSource>>>(emptyMap())
    val pendingEdits: StateFlow<Map<String, List<StickerSource>>> = _pendingEdits.asStateFlow()

    /** Galeriden seçilenler sırayla düzenleme ekranından geçer (kare kırpma, videoda süre). */
    fun onPicked(packId: String, sources: List<StickerSource>) {
        if (sources.isNotEmpty()) _pendingEdits.update { it + (packId to (it[packId].orEmpty() + sources)) }
    }

    /** Düzenleme onaylandı: işlemeye gönder, sıradakine geç. */
    fun onEdited(packId: String, source: StickerSource, recipe: EditRecipe) {
        popEdit(packId)
        addStickers(packId, listOf(source.copy(recipe = recipe)))
    }

    /** Bu kaynak eklenmeyecek. */
    fun onEditSkipped(packId: String) = popEdit(packId)

    /** Sırada kalanların hepsi düzenlemeden (tamamı sığdırılarak) eklenir. */
    fun onAddRestAsIs(packId: String) {
        val rest = _pendingEdits.value[packId].orEmpty()
        _pendingEdits.update { it - packId }
        addStickers(packId, rest)
    }

    private fun popEdit(packId: String) = _pendingEdits.update { map -> map + (packId to map[packId].orEmpty().drop(1)) }

    fun createPack(name: String, onCreated: (String) -> Unit) {
        launchSafely { onCreated(repository.createPack(name).identifier) }
    }

    /** Aynı anda tek kaynak işlenir: iki video birlikte belleğe (~150 MB/klip) sığmayabilir. */
    private val processing = Mutex()

    /**
     * Pakete eklenmek üzere işlenen ya da işlenmeyi bekleyen kaynak sayısı (paket kimliği → sayı).
     * Boş yer hesabında sayılır: aksi hâlde aynı anda yapılan içe aktarma paketi doldurur ve uzun
     * video işlemesi sonunda "paket dolu" ile boşa gider.
     */
    private val _inFlight = MutableStateFlow<Map<String, Int>>(emptyMap())
    val inFlight: StateFlow<Map<String, Int>> = _inFlight.asStateFlow()

    private fun addInFlight(packId: String, delta: Int) = _inFlight.update { map ->
        val n = (map[packId] ?: 0) + delta
        if (n > 0) map + (packId to n) else map - packId
    }

    private fun addStickers(packId: String, sources: List<StickerSource>) {
        if (sources.isEmpty()) return
        addInFlight(packId, sources.size)
        launchSafely {
            processing.withLock {
                var remaining = sources.size
                for (source in sources) {
                    _addState.update { it + (packId to AddState.Working(remaining, it[packId]?.results.orEmpty())) }
                    val item = runCatching {
                        repository.addSticker(packId, source.uri, source.mimeType, source.recipe) { p ->
                            _addState.update { map ->
                                val working = map[packId] as? AddState.Working ?: return@update map
                                map + (packId to working.copy(progress = p))
                            }
                        }
                    }.fold(
                        onSuccess = { ItemResult(sticker = it) },
                        onFailure = { e ->
                            Log.e(TAG, "sticker eklenemedi", e)
                            ItemResult(error = errorText(e))
                        },
                    )
                    remaining--
                    addInFlight(packId, -1)
                    _addState.update { map ->
                        val results = map[packId]?.results.orEmpty() + item
                        map + (packId to if (remaining > 0) AddState.Working(remaining, results) else AddState.Finished(results))
                    }
                }
            }
        }
    }

    fun dismissAddState(packId: String) = _addState.update { it - packId }

    /** Paketsiz tek sticker: seç → düzenle → üret → sohbete gönder. */
    private val _quick = MutableStateFlow<QuickState>(QuickState.Idle)
    val quick: StateFlow<QuickState> = _quick.asStateFlow()

    fun onQuickPicked(source: StickerSource) { _quick.value = QuickState.Editing(source) }

    fun onQuickEdited(recipe: EditRecipe) {
        val source = (_quick.value as? QuickState.Editing)?.source ?: return
        _quick.value = QuickState.Working(null)
        launchSafely {
            processing.withLock {
                _quick.value = runCatching {
                    repository.createLoose(source.uri, source.mimeType, recipe) { p -> _quick.update { if (it is QuickState.Working) QuickState.Working(p) else it } }
                }.fold(
                    onSuccess = { QuickState.Done(it, recipe) },
                    onFailure = { e -> Log.e(TAG, "tek sticker üretilemedi", e); QuickState.Failed(errorText(e)) },
                )
            }
        }
    }

    /** Üretilen stickerı hızlı pakete ekler; ardından [onAdded] ile WhatsApp'a ekleme / güncelleme başlatılır. */
    fun addQuickToPack(baseName: String, onAdded: (PackManifest) -> Unit) {
        val done = _quick.value as? QuickState.Done ?: return
        if (done.packId != null) return
        launchSafely {
            val pack = repository.addToQuickPack(done.sticker.webp, done.recipe, baseName)
            _quick.update { if (it === done) done.copy(packId = pack.identifier) else it }
            onAdded(pack)
        }
    }

    fun dismissQuick() { _quick.value = QuickState.Idle }

    /** WhatsApp'tan içe aktarma sonucu (paket kimliği → eklenen sayı, reddedilenler). */
    private val _importResult = MutableStateFlow<Map<String, ImportStatus>>(emptyMap())
    val importResult: StateFlow<Map<String, ImportStatus>> = _importResult.asStateFlow()

    fun importReceived(packId: String, stickers: List<ReceivedSticker>) {
        launchSafely {
            _importResult.update { it + (packId to ImportStatus(0, stickers.size)) }
            val outcome = importInto(packId, stickers) { done, total, re ->
                _importResult.update { it + (packId to ImportStatus(done, total, re)) }
            }
            _importResult.update { it + (packId to ImportStatus(stickers.size, stickers.size, outcome.reencoded, outcome)) }
        }
    }

    private suspend fun importInto(packId: String, stickers: List<ReceivedSticker>, onProgress: (Int, Int, Int) -> Unit): ImportOutcome {
        val folder = ReceivedStickers(getApplication())
        // Okuma hatası da (izin geri alındı, dosya silindi) sonuç olarak gösterilir; durum "sürüyor"da kalmasın
        return runCatching {
            val items = withContext(Dispatchers.IO) { stickers.mapNotNull { s -> folder.read(s)?.let { s.name to it } } }
            repository.importReceived(packId, items, onProgress)
        }.getOrElse { e -> Log.e(TAG, "içe aktarılamadı", e); ImportOutcome(0, 0, listOf(e.message ?: e.toString())) }
    }

    fun dismissImportResult(packId: String) = _importResult.update { it - packId }

    /**
     * Alınan bir paketin stickerlarından yeni paket(ler) oluşturur. WhatsApp paket başına en fazla 30
     * sticker kabul ediyor (60 ile denendi, reddetti) → fazlası eşit parçalara bölünür ("Kediler 1", "Kediler 2").
     * Sonuç, ekranın açıldığı [resultPackId] paketinde gösterilir.
     */
    fun importAsNewPacks(resultPackId: String, name: String, stickers: List<ReceivedSticker>) {
        launchSafely {
            val chunks = splitForPacks(stickers)
            val names = chunks.indices.map { i -> if (chunks.size > 1) "$name ${i + 1}" else name }
            var added = 0
            var reencoded = 0
            val rejected = mutableListOf<String>()
            var offset = 0
            _importResult.update { it + (resultPackId to ImportStatus(0, stickers.size, newPacks = names)) }
            for ((i, chunk) in chunks.withIndex()) {
                val created = runCatching { repository.createPack(names[i]) }
                val pack = created.getOrNull()
                if (pack == null) {
                    val e = created.exceptionOrNull()
                    Log.e(TAG, "paket oluşturulamadı", e)
                    rejected += "${names[i]}: ${e?.message ?: e}"
                    offset += chunk.size
                    continue
                }
                val before = offset
                val r = importInto(pack.identifier, chunk) { done, _, re ->
                    _importResult.update { it + (resultPackId to ImportStatus(before + done, stickers.size, reencoded + re, newPacks = names)) }
                }
                added += r.added
                reencoded += r.reencoded
                rejected += r.rejected
                offset += chunk.size
            }
            _importResult.update {
                it + (resultPackId to ImportStatus(stickers.size, stickers.size, reencoded, ImportOutcome(added, reencoded, rejected), names))
            }
        }
    }

    /** Hata → kullanıcıya gösterilecek metin, uygulamanın dilinde. */
    private fun errorText(e: Throwable): String {
        val app = getApplication<Application>()
        fun kb(bytes: Int) = String.format(app.resources.configuration.locales[0], "%.0f KB", bytes / 1024.0)
        return when (e) {
            is StickerTooLargeException -> app.getString(R.string.error_too_large, kb(e.smallestBytes), kb(e.limitBytes))
            is AnimationTooLargeException -> app.getString(R.string.error_too_large, kb(e.smallestBytes), kb(e.limitBytes))
            is OutOfMemoryError -> app.getString(R.string.error_out_of_memory)
            // PackRepository'nin mesajları zaten uygulamanın dilinde
            is IllegalStateException -> e.message.orEmpty()
            else -> e.toString()
        }
    }

    companion object {
        private const val TAG = "WSC_PACKS"

        /** En az 3, en fazla 30'luk, olabildiğince eşit parçalar. */
        fun <T> splitForPacks(items: List<T>, max: Int = 30): List<List<T>> {
            if (items.isEmpty()) return emptyList()
            val parts = (items.size + max - 1) / max
            val base = items.size / parts
            val extra = items.size % parts
            var start = 0
            return List(parts) { i ->
                val size = base + if (i < extra) 1 else 0
                items.subList(start, start + size).also { start += size }
            }
        }
    }

    fun removeSticker(packId: String, imageFile: String) = launchSafely { repository.removeSticker(packId, imageFile) }
    fun setEmojis(packId: String, imageFile: String, emojis: List<String>) =
        launchSafely { repository.setEmojis(packId, imageFile, emojis) }
    fun rename(packId: String, name: String) = launchSafely { repository.rename(packId, name) }
    fun deletePack(packId: String) = launchSafely { repository.deletePack(packId) }

}
