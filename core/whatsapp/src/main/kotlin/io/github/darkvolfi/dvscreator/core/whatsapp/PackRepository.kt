package io.github.darkvolfi.dvscreator.core.whatsapp

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import io.github.darkvolfi.dvscreator.core.imaging.CreatedSticker
import io.github.darkvolfi.dvscreator.core.imaging.StickerFactory
import io.github.darkvolfi.dvscreator.core.model.EditRecipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Kullanıcının sticker paketleri. Tüm değişiklikler diske yazılır ve [packs] akışında yayınlanır.
 *
 * - Tüm paketler hareketli pakettir (agents/06 — S8, maksimum kalite modu).
 * - Seçilen kaynak dosya `sources/` altına kopyalanır (tahribatsız düzenleme için).
 * - Her içerik değişikliğinde `imageDataVersion` artırılır (yoksa WhatsApp önbellekten eskiyi gösterir).
 */
class PackRepository(context: Context) {
    private val appContext = context.applicationContext
    private val store = PackStore(appContext)
    private val sourcesDir = File(appContext.filesDir, "sources").apply { mkdirs() }
    private val mutex = Mutex()
    private val _packs = MutableStateFlow(store.packs())
    val packs: StateFlow<List<PackManifest>> = _packs.asStateFlow()

    fun store(): PackStore = store

    suspend fun createPack(name: String): PackManifest = edit {
        val pack = PackManifest(
            identifier = "wsc_" + UUID.randomUUID().toString().replace("-", "").take(12),
            name = name.trim().take(WhatsAppLimits.MAX_TEXT_LENGTH),
            publisher = DEFAULT_PUBLISHER,
            trayImageFile = TRAY_FILE,
            animated = true,
            stickers = emptyList(),
            createdAt = System.currentTimeMillis(),
        )
        store.saveManifest(pack)
        pack
    }

    /**
     * Kaynağı uygulamaya kopyalar, stickerı üretir (videolarda 5-30 sn sürebilir) ve pakete ekler.
     * Üretim kilit DIŞINDA yapılır: uzun süren iş sırasında diğer düzenlemeler engellenmez.
     */
    suspend fun addSticker(
        packId: String,
        source: Uri,
        mimeType: String,
        recipe: EditRecipe = EditRecipe(),
        onProgress: (StickerFactory.Progress) -> Unit = {},
    ): CreatedSticker {
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
        val sourceFile = File(sourcesDir, "$id.$ext")
        withContext(Dispatchers.IO) {
            appContext.contentResolver.openInputStream(source)?.use { input -> sourceFile.outputStream().use { input.copyTo(it) } }
                ?: error("Dosya açılamadı")
        }
        val created = try {
            withContext(Dispatchers.Default) { StickerFactory.create(appContext, Uri.fromFile(sourceFile), mimeType, recipe, onProgress) }
        } catch (e: Throwable) {
            sourceFile.delete()
            throw e
        }
        edit {
            val pack = requireNotNull(store.pack(packId)) { "paket bulunamadı" }
            check(pack.stickers.size < WhatsAppLimits.PACK_MAX_STICKERS) { "Paket dolu (en fazla ${WhatsAppLimits.PACK_MAX_STICKERS} sticker)" }
            val fileName = "s_$id.webp"
            store.writeFile(packId, fileName, created.webp)
            val updated = pack.copy(
                stickers = pack.stickers + StickerEntry(fileName, listOf(DEFAULT_EMOJI), sourceFile = sourceFile.name, recipe = recipe),
                imageDataVersion = pack.imageDataVersion + 1,
            )
            if (pack.stickers.isEmpty()) store.writeFile(packId, TRAY_FILE, StickerFactory.trayIcon(created.webp))
            store.saveManifest(updated)
        }
        return created
    }

    suspend fun removeSticker(packId: String, imageFile: String) = edit {
        val pack = store.pack(packId) ?: return@edit
        val entry = pack.stickers.firstOrNull { it.imageFile == imageFile } ?: return@edit
        val remaining = pack.stickers - entry
        store.deleteFile(packId, imageFile)
        entry.sourceFile?.let { File(sourcesDir, it).delete() }
        // Simge ilk stickerdan üretilir; ilk sticker silindiyse yenisinden üret
        if (pack.stickers.firstOrNull() == entry) {
            remaining.firstOrNull()?.let { store.readFile(packId, it.imageFile) }
                ?.let { store.writeFile(packId, TRAY_FILE, StickerFactory.trayIcon(it)) }
                ?: store.deleteFile(packId, TRAY_FILE)
        }
        store.saveManifest(pack.copy(stickers = remaining, imageDataVersion = pack.imageDataVersion + 1))
    }

    suspend fun setEmojis(packId: String, imageFile: String, emojis: List<String>) = edit {
        val pack = store.pack(packId) ?: return@edit
        val stickers = pack.stickers.map { if (it.imageFile == imageFile) it.copy(emojis = emojis.take(WhatsAppLimits.MAX_EMOJIS_PER_STICKER)) else it }
        store.saveManifest(pack.copy(stickers = stickers, imageDataVersion = pack.imageDataVersion + 1))
    }

    suspend fun rename(packId: String, name: String) = edit {
        val pack = store.pack(packId) ?: return@edit
        store.saveManifest(pack.copy(name = name.trim().take(WhatsAppLimits.MAX_TEXT_LENGTH)))
    }

    suspend fun deletePack(packId: String) = edit {
        store.pack(packId)?.stickers?.forEach { s -> s.sourceFile?.let { File(sourcesDir, it).delete() } }
        store.deletePack(packId)
    }

    fun validate(packId: String): List<String> =
        store.pack(packId)?.let { PackValidator.validate(it, store) } ?: listOf("Paket bulunamadı.")

    fun stickerBytes(packId: String, imageFile: String): ByteArray? = store.readFile(packId, imageFile)

    private suspend fun <T> edit(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block().also { _packs.value = store.packs(); notifyWhatsApp() } }
    }

    /**
     * ContentProvider'ı dinleyenlere (WhatsApp) içeriğin değiştiğini bildirir. Tek başına yetmeyebilir:
     * WhatsApp yeni image_data_version'ı ne zaman okuyacağına kendisi karar veriyor (2026-10-10 gözlemi).
     */
    private fun notifyWhatsApp() {
        val authority = StickerContentProvider.authorityFor(appContext.packageName)
        appContext.contentResolver.notifyChange(Uri.parse("content://$authority/metadata"), null)
    }

    companion object {
        const val TRAY_FILE = "tray.png"
        const val DEFAULT_PUBLISHER = "DVScreator"
        const val DEFAULT_EMOJI = "😀"
    }
}
