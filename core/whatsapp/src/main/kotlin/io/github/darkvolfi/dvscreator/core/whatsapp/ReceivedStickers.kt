package io.github.darkvolfi.dvscreator.core.whatsapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import io.github.darkvolfi.dvscreator.core.imaging.AnimationClip
import io.github.darkvolfi.dvscreator.core.imaging.AnimationEncoder
import io.github.darkvolfi.dvscreator.core.imaging.AnimationLoader
import io.github.darkvolfi.dvscreator.core.imaging.ArgbImage
import io.github.darkvolfi.dvscreator.core.webp.NativeWebp
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.ceil
import kotlin.math.min

/** WhatsApp'ın sohbetlerde alınan stickerları sakladığı klasördeki bir dosya. */
data class ReceivedSticker(val uri: Uri, val name: String, val sizeBytes: Long, val modifiedMs: Long)

/**
 * WhatsApp'ta alınan stickerlar (agents/04 — M4b). Klasörde `.nomedia` olduğu için galeri izniyle
 * görünmezler; kullanıcı Android'in klasör seçicisiyle bir kez izin verir (SAF, kalıcı izin).
 *
 * Yalnızca OKUNUR: WhatsApp'ın kendi verisi (favoriler, son kullanılanlar, başka uygulamaların
 * paketleri) Android güvenliği gereği hiçbir uygulamaya açık değildir.
 */
class ReceivedStickers(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("received_stickers", Context.MODE_PRIVATE)

    /** Kullanıcının izin verdiği klasör; yoksa ya da izin geri alındıysa null. */
    val folder: Uri?
        get() {
            val uri = prefs.getString(KEY_TREE, null)?.let(Uri::parse) ?: return null
            val granted = appContext.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            return uri.takeIf { granted }
        }

    /** Klasör seçicinin doğrudan WhatsApp'ın sticker klasöründe açılması için. */
    fun initialFolderUri(): Uri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:$WHATSAPP_STICKER_PATH")

    /**
     * Klasör seçiciden dönen ağacı kalıcı izinle kaydeder.
     * @return seçilen klasör beklenen WhatsApp klasörü değilse false (yine de kaydedilir — kullanıcı farklı kaydetmiş olabilir)
     */
    fun saveFolder(tree: Uri): Boolean {
        appContext.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putString(KEY_TREE, tree.toString()).apply()
        return DocumentsContract.getTreeDocumentId(tree).endsWith("WhatsApp Stickers")
    }

    /**
     * Klasördeki .webp dosyaları, en yeni önce. ~2700 dosya tek sorguyla listelenir.
     * `.was` dosyaları (WhatsApp'ın kendi Lottie stickerları) atlanır: diğer uygulamaların paketlerinde desteklenmiyor.
     */
    fun list(): List<ReceivedSticker> {
        val tree = folder ?: return emptyList()
        return children(tree) { it.endsWith(".webp", ignoreCase = true) }.sortedByDescending { it.modifiedMs }
    }

    private fun children(tree: Uri, accept: (String) -> Boolean): List<ReceivedSticker> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = ArrayList<ReceivedSticker>()
        appContext.contentResolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (!accept(name)) continue
                result += ReceivedSticker(
                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                    name = name,
                    sizeBytes = c.getLong(2),
                    modifiedMs = c.getLong(3),
                )
            }
        }
        return result
    }

    // --- Paylaşılan tam paketler ---------------------------------------------------------------

    /** Sohbette bütün olarak paylaşılan paketlerin klasörü ("WhatsApp Sticker Packs"); ayrı izin gerekir. */
    val packsFolder: Uri?
        get() {
            val uri = prefs.getString(KEY_PACKS_TREE, null)?.let(Uri::parse) ?: return null
            val granted = appContext.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            return uri.takeIf { granted }
        }

    fun initialPacksFolderUri(): Uri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:$WHATSAPP_PACKS_PATH")

    /** @return seçilen klasör beklenen paket klasörü değilse false */
    fun savePacksFolder(tree: Uri): Boolean {
        appContext.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putString(KEY_PACKS_TREE, tree.toString()).apply()
        return DocumentsContract.getTreeDocumentId(tree).endsWith("WhatsApp Sticker Packs")
    }

    /** Paylaşılan bir paketin tamamı (zip'ten çıkarılmış stickerlar). */
    class SharedPack(val info: PackInfo, val stickers: List<ReceivedSticker>, val modifiedMs: Long)

    /**
     * Paylaşılan paketler. Her paket bir zip (`.zip` ya da `.enc` uzantılı — ikisi de düz zip):
     * stickerlar + tepsi simgesi. Zip'ler bir kez önbelleğe çıkarılır; aynı paket birden çok kez
     * paylaşıldıysa en kalabalık (eşitse en yeni) kopya tutulur.
     */
    fun sharedPacks(): List<SharedPack> {
        val tree = packsFolder ?: return emptyList()
        val zips = children(tree) { it.endsWith(".zip", true) || it.endsWith(".enc", true) }
        val root = File(appContext.cacheDir, "shared_packs").apply { mkdirs() }
        val keep = HashSet<String>()
        val packs = zips.mapNotNull { zip ->
            val dir = File(root, "${zip.name.substringBeforeLast('.')}_${zip.sizeBytes}")
            keep += dir.name
            runCatching { extractOnce(zip, dir) }.getOrNull()
            val stickers = dir.listFiles { f -> f.name.endsWith(".webp", true) }.orEmpty()
                .sortedBy { it.name }
                .map { ReceivedSticker(Uri.fromFile(it), it.name, it.length(), zip.modifiedMs) }
            val info = stickers.firstNotNullOfOrNull { s -> runCatching { parsePackInfo(tail(File(s.uri.path!!))) }.getOrNull() }
            if (info == null || stickers.isEmpty()) null else SharedPack(info, stickers, zip.modifiedMs)
        }
        root.listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() }
        return packs.groupBy { it.info.id }.values
            .map { same -> same.maxWith(compareBy<SharedPack>({ it.stickers.size }, { it.modifiedMs })) }
            .sortedByDescending { it.modifiedMs }
    }

    private fun extractOnce(zip: ReceivedSticker, dir: File) {
        val done = File(dir, ".done")
        if (done.exists()) return
        dir.deleteRecursively()
        dir.mkdirs()
        appContext.contentResolver.openInputStream(zip.uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { z ->
                while (true) {
                    val entry = z.nextEntry ?: break
                    val name = File(entry.name).name   // yol içermesin (zip slip)
                    if (entry.isDirectory || !name.endsWith(".webp", true)) continue
                    File(dir, name).outputStream().use { z.copyTo(it) }
                }
            }
        } ?: error("zip açılamadı")
        done.createNewFile()
    }

    private fun tail(file: File): ByteArray = java.io.RandomAccessFile(file, "r").use { f ->
        val n = min(f.length(), TAIL_BYTES).toInt()
        f.seek(f.length() - n)
        ByteArray(n).also { f.readFully(it) }
    }

    fun read(sticker: ReceivedSticker): ByteArray? =
        appContext.contentResolver.openInputStream(sticker.uri)?.use { it.readBytes() }

    /** Stickerın geldiği paket (WhatsApp stickerın EXIF bölümüne yazıyor). */
    data class PackInfo(val id: String, val name: String, val publisher: String)

    private val packCache = HashMap<String, PackInfo?>()

    /**
     * Stickerın ait olduğu paket. EXIF dosyanın SONUNDA (gerçek dosyada son ~250 bayt): yalnızca son
     * [TAIL_BYTES] okunur — 2700 dosyada tüm dosyaları okumak yerine ~22 MB. Sonuç önbelleğe alınır.
     */
    fun packOf(sticker: ReceivedSticker): PackInfo? {
        val key = "${sticker.name}|${sticker.sizeBytes}|${sticker.modifiedMs}"
        synchronized(packCache) { if (packCache.containsKey(key)) return packCache[key] }
        val info = runCatching {
            appContext.contentResolver.openFileDescriptor(sticker.uri, "r")?.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                    val skip = (sticker.sizeBytes - TAIL_BYTES).coerceAtLeast(0)
                    input.channel.position(skip)
                    parsePackInfo(input.readBytes())
                }
            }
        }.getOrNull()
        synchronized(packCache) { packCache[key] = info }
        return info
    }

    companion object {
        private const val TAIL_BYTES = 8 * 1024L

        internal fun parsePackInfo(bytes: ByteArray): PackInfo? {
            val text = String(bytes, Charsets.UTF_8)
            fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(text)?.groupValues?.get(1)
                ?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            val id = field("sticker-pack-id") ?: return null
            return PackInfo(id, field("sticker-pack-name").orEmpty(), field("sticker-pack-publisher").orEmpty())
        }

        private const val KEY_TREE = "tree"
        private const val KEY_PACKS_TREE = "packs_tree"
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val WHATSAPP_STICKER_PATH = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers"
        const val WHATSAPP_PACKS_PATH = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Sticker Packs"
    }
}

/**
 * Alınmış bir stickerı pakete uygun hâle getirir — mümkünse **yeniden kodlamadan**:
 * - Geçerli hareketli sticker → birebir kopya
 * - Statik sticker → görüntü verisi kopyalanarak 2 kareli hareketli WebP'ye sarılır (paketler hareketli)
 * - 500 KB'tan büyük ya da kare süreleri kurallara uymayan sticker → [reencode] ile sığdırılır
 *   (WhatsApp'ın kendi oluşturucusu bu sınırları uygulamıyor; paylaşılan paketlerde 800 KB'lık stickerlar var)
 */
object StickerImport {

    sealed interface Result {
        class Ok(val webp: ByteArray, val emojis: List<String>) : Result
        /** Olduğu gibi eklenemez ama yeniden sıkıştırılarak eklenebilir. */
        class NeedsReencode(val reason: String) : Result
        class Rejected(val reason: String) : Result
    }

    fun prepare(bytes: ByteArray): Result {
        val L = WhatsAppLimits
        val info = runCatching { NativeWebp.info(bytes) }.getOrNull() ?: return Result.Rejected("geçerli bir WebP değil")
        if (info.canvasWidth != L.STICKER_SIZE_PX || info.canvasHeight != L.STICKER_SIZE_PX) {
            return Result.Rejected("${info.canvasWidth}×${info.canvasHeight}; 512×512 olmalı")
        }
        val webp = if (info.frameCount >= L.ANIMATED_MIN_FRAMES) {
            if (info.frameDurationsMs.any { it < L.ANIMATED_MIN_FRAME_DURATION_MS } || info.totalDurationMs > L.ANIMATED_MAX_TOTAL_DURATION_MS) {
                return Result.NeedsReencode("kare süreleri WhatsApp kurallarına uymuyor")
            }
            bytes
        } else {
            runCatching { NativeWebp.staticAsAnimation(bytes, L.STICKER_SIZE_PX, L.STICKER_SIZE_PX) }.getOrNull()
                ?: return Result.Rejected("sarılamadı")
        }
        if (webp.size > L.ANIMATED_STICKER_MAX_BYTES) return Result.NeedsReencode("${webp.size / 1024} KB")
        return Result.Ok(webp, emojisOf(bytes))
    }

    /**
     * Kareleri çözüp [AnimationEncoder] ile 500 KB'a sığdırır (video stickerlarıyla aynı kalite araması).
     * Kareler zaten 512×512: yeniden boyutlandırma yok. Bellek için en fazla [AnimationLoader.MAX_FRAMES]
     * kare tutulur (fazlası çözülürken birleştirilir), süre 10 sn'de kesilir.
     */
    suspend fun reencode(bytes: ByteArray, onProgress: (AnimationEncoder.Progress) -> Unit = {}): Result.Ok {
        val L = WhatsAppLimits
        val info = NativeWebp.info(bytes)
        val step = ceil(info.frameCount / AnimationLoader.MAX_FRAMES.toDouble()).toInt().coerceAtLeast(1)
        val frames = ArrayList<ArgbImage>()
        val durations = ArrayList<Int>()
        var prevEnd = 0
        NativeWebp.forEachFrame(bytes) { index, argb, endMs ->
            val end = min(endMs, L.ANIMATED_MAX_TOTAL_DURATION_MS)
            val d = end - prevEnd
            prevEnd = end
            if (d <= 0) return@forEachFrame
            if (index % step == 0 || frames.isEmpty()) {
                frames += ArgbImage(info.canvasWidth, info.canvasHeight, argb.copyOf())
                durations += d
            } else {
                durations[durations.lastIndex] += d   // atlanan karenin süresi öncekine eklenir: hız aynı kalır
            }
        }
        check(frames.isNotEmpty()) { "kare çözülemedi" }
        if (frames.size == 1) { frames += frames[0]; durations += durations[0] }
        val clip = AnimationClip(frames, durations.map { it.coerceAtLeast(L.ANIMATED_MIN_FRAME_DURATION_MS) }.toIntArray())
        val encoded = AnimationEncoder().encode(clip, onProgress)
        return Result.Ok(encoded.bytes, emojisOf(bytes))
    }

    /**
     * WhatsApp stickerları EXIF bölümünde JSON taşır: `{"sticker-pack-id": …, "emojis": ["😀", …]}`.
     * Bulunursa emojiler korunur (en fazla 3); yoksa boş.
     */
    internal fun emojisOf(bytes: ByteArray): List<String> {
        val text = String(bytes, Charsets.UTF_8)
        val start = text.indexOf("\"emojis\"").takeIf { it >= 0 } ?: return emptyList()
        val open = text.indexOf('[', start).takeIf { it >= 0 } ?: return emptyList()
        val close = text.indexOf(']', open).takeIf { it > open && it - open < 200 } ?: return emptyList()
        return Regex("\"([^\"]+)\"").findAll(text.substring(open + 1, close))
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() && it.length <= 16 }
            .take(WhatsAppLimits.MAX_EMOJIS_PER_STICKER)
            .toList()
    }
}
