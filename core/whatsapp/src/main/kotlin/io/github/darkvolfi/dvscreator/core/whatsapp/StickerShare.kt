package io.github.darkvolfi.dvscreator.core.whatsapp

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * Stickerı dosya olarak başka uygulamalara verme. Paylaşım menüsüyle WhatsApp'a giden WebP fotoğraf olur
 * (WhatsApp'ın SEND_STICKER eylemi yalnızca imzası izin listesinde olan uygulamalara açık — 2026-10-13
 * incelemesi); sticker olarak göndermenin yolu klavye: `image/webp.wasticker` içeriği ([keyboardUri]).
 */
object StickerShare {

    /**
     * WhatsApp'ın kişi seçme ekranını açan istek. [whatsAppPackage] null ise sistemin paylaşım menüsü açılır.
     * Dosya önbelleğe yazılır; önceki paylaşımın dosyası bu sırada silinir.
     */
    fun intent(context: Context, webp: ByteArray, whatsAppPackage: String?): Intent {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        dir.listFiles()?.filter { !it.name.startsWith(KEYBOARD_PREFIX) }?.forEach { it.delete() }
        val file = File(dir, "sticker_${System.currentTimeMillis()}.webp").apply { writeBytes(webp) }
        val uri = FileProvider.getUriForFile(context, authorityFor(context.packageName), file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/webp"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return if (whatsAppPackage != null) send.setPackage(whatsAppPackage) else Intent.createChooser(send, null)
    }

    /**
     * Sticker klavyesi için: dosyayı önbelleğe yazar ve başka uygulamaya okuma izniyle verilebilecek adresini döndürür.
     * Klavyeyle gönderilen eski dosyalar ([KEEP_MS]'den eski) bu sırada silinir.
     */
    fun keyboardUri(context: Context, name: String, webp: ByteArray): Uri {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles()?.filter { it.name.startsWith(KEYBOARD_PREFIX) && now - it.lastModified() > KEEP_MS }?.forEach { it.delete() }
        val file = File(dir, KEYBOARD_PREFIX + name).apply { writeBytes(webp) }
        return FileProvider.getUriForFile(context, authorityFor(context.packageName), file)
    }

    /**
     * Stickerı galeriye (Pictures/DVScreator) kaydeder — ör. Samsung Klavye'de "özel sticker" yapmak için:
     * Samsung'un sticker merkezine dışarıdan ekleme yolu yalnızca Samsung uygulamalarına açık (signature izni).
     * Android 10+ (izin istemeden yazılabilen tek yol); başarısızsa false.
     */
    fun saveToGallery(context: Context, webp: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "DVScreator_${System.currentTimeMillis()}.webp")
            put(MediaStore.Images.Media.MIME_TYPE, "image/webp")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DVScreator")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return runCatching {
            resolver.openOutputStream(uri)?.use { it.write(webp) } ?: error("yazılamadı")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        }.getOrElse {
            resolver.delete(uri, null, null)
            false
        }
    }

    /** Galeriye kaydetme bu cihazda yapılabilir mi (bkz. [saveToGallery]). */
    val canSaveToGallery: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Manifest'teki `${applicationId}.share` ile aynı olmalı. */
    fun authorityFor(packageName: String) = "$packageName.share"

    /** res/xml/share_paths.xml ile aynı olmalı. */
    private const val SHARE_DIR = "share"
    private const val KEYBOARD_PREFIX = "kb_"
    /** Alıcı uygulama dosyayı hemen kopyalar; bir süre sonra gereksiz. */
    private const val KEEP_MS = 10 * 60 * 1000L
}
