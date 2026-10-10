package io.github.darkvolfi.dvscreator.core.whatsapp

import android.content.Context
import io.github.darkvolfi.dvscreator.core.model.TrimRange
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** WhatsApp'a sunulan paketin disk üzerindeki tanımı (`wa_packs/<id>/pack.json`). */
@Serializable
data class PackManifest(
    val identifier: String,
    val name: String,
    val publisher: String,
    val trayImageFile: String,
    val animated: Boolean,
    /** Paket içeriği her değiştiğinde artırılır; yoksa WhatsApp önbellekteki eski sticker'ı gösterir. */
    val imageDataVersion: Int = 1,
    val avoidCache: Boolean = false,
    val stickers: List<StickerEntry>,
    val createdAt: Long = 0,
)

@Serializable
data class StickerEntry(
    val imageFile: String,
    val emojis: List<String>,
    val accessibilityText: String? = null,
    /** Orijinal kaynak dosyası (`sources/` altında) — tahribatsız düzenleme için saklanır. */
    val sourceFile: String? = null,
    /** Video / GIF için kullanılan aralık (yeniden üretim için saklanır). */
    val trim: TrimRange? = null,
)

/**
 * Paket dosyalarının deposu: `filesDir/wa_packs/<identifier>/` altında `pack.json`, tray PNG ve WebP'ler.
 * ContentProvider her sorguda diskten okur — yeni paket uygulamayı yeniden başlatmadan görünür.
 * (agents/03: Room yerine bilinçli olarak JSON — WhatsApp zaten dosya istiyor, ek katmana gerek yok.)
 */
class PackStore(context: Context) {
    private val root = File(context.filesDir, DIR).apply { mkdirs() }

    fun packs(): List<PackManifest> = root.listFiles().orEmpty()
        .filter { it.isDirectory }
        .mapNotNull { dir -> readManifest(dir) }
        .sortedBy { it.createdAt }

    fun pack(identifier: String): PackManifest? =
        runCatching { packDir(identifier) }.getOrNull()?.let { readManifest(it) }

    fun packDir(identifier: String): File {
        require(IDENTIFIER_REGEX.matches(identifier)) { "geçersiz paket kimliği: $identifier" }
        return File(root, identifier)
    }

    /**
     * Pakete ait bir dosya; yalnızca manifest'te adı geçen dosyalar döner (yol geçişi saldırısına karşı).
     */
    fun assetFile(identifier: String, fileName: String): File? {
        val pack = pack(identifier) ?: return null
        val allowed = fileName == pack.trayImageFile || pack.stickers.any { it.imageFile == fileName }
        if (!allowed) return null
        return File(packDir(identifier), fileName).takeIf { it.isFile }
    }

    /** Paketi bütünüyle yeniden yazar (eski dosyalar silinir). */
    fun write(manifest: PackManifest, files: Map<String, ByteArray>) {
        val dir = packDir(manifest.identifier).apply { deleteRecursively(); mkdirs() }
        for ((name, bytes) in files) writeFile(manifest.identifier, name, bytes)
        saveManifest(manifest.copy())
        check(dir.isDirectory)
    }

    fun saveManifest(manifest: PackManifest) {
        val dir = packDir(manifest.identifier).apply { mkdirs() }
        // Önce geçici dosyaya yaz, sonra taşı: yarım yazılmış manifest WhatsApp'a hiç görünmesin
        val tmp = File(dir, "$MANIFEST.tmp")
        tmp.writeText(json.encodeToString(PackManifest.serializer(), manifest))
        check(tmp.renameTo(File(dir, MANIFEST)) || run { File(dir, MANIFEST).delete(); tmp.renameTo(File(dir, MANIFEST)) })
    }

    fun writeFile(identifier: String, name: String, bytes: ByteArray) {
        require(FILE_REGEX.matches(name)) { "geçersiz dosya adı: $name" }
        File(packDir(identifier).apply { mkdirs() }, name).writeBytes(bytes)
    }

    fun readFile(identifier: String, name: String): ByteArray? {
        require(FILE_REGEX.matches(name)) { "geçersiz dosya adı: $name" }
        return File(packDir(identifier), name).takeIf { it.isFile }?.readBytes()
    }

    fun deleteFile(identifier: String, name: String) {
        require(FILE_REGEX.matches(name)) { "geçersiz dosya adı: $name" }
        File(packDir(identifier), name).delete()
    }

    fun deletePack(identifier: String) {
        packDir(identifier).deleteRecursively()
    }

    fun deleteAll() {
        root.deleteRecursively()
        root.mkdirs()
    }

    private fun readManifest(dir: File): PackManifest? =
        File(dir, MANIFEST).takeIf { it.isFile }?.let { runCatching { json.decodeFromString<PackManifest>(it.readText()) }.getOrNull() }

    private companion object {
        const val DIR = "wa_packs"
        const val MANIFEST = "pack.json"
        val IDENTIFIER_REGEX = Regex("[A-Za-z0-9_.-]{1,128}")
        val FILE_REGEX = Regex("[A-Za-z0-9_.-]{1,128}")
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}
