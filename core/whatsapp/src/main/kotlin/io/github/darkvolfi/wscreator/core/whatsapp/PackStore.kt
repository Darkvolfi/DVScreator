package io.github.darkvolfi.wscreator.core.whatsapp

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** WhatsApp'a sunulan paketin disk üzerindeki tanımı (`packs/<id>/pack.json`). */
@Serializable
data class PackManifest(
    val identifier: String,
    val name: String,
    val publisher: String,
    val trayImageFile: String,
    val animated: Boolean,
    val imageDataVersion: Int = 1,
    val avoidCache: Boolean = false,
    val stickers: List<StickerEntry>,
)

@Serializable
data class StickerEntry(
    val imageFile: String,
    val emojis: List<String>,
    val accessibilityText: String? = null,
)

/**
 * Paket dosyalarının deposu: `filesDir/wa_packs/<identifier>/` altında `pack.json`, tray PNG ve WebP'ler.
 * ContentProvider her sorguda diskten okur — yeni paket uygulamayı yeniden başlatmadan görünür.
 */
class PackStore(context: Context) {
    private val root = File(context.filesDir, DIR).apply { mkdirs() }

    fun packs(): List<PackManifest> = root.listFiles().orEmpty()
        .filter { it.isDirectory }
        .mapNotNull { dir -> File(dir, MANIFEST).takeIf { it.isFile }?.let { runCatching { json.decodeFromString<PackManifest>(it.readText()) }.getOrNull() } }
        .sortedBy { it.identifier }

    fun pack(identifier: String): PackManifest? = packs().firstOrNull { it.identifier == identifier }

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

    fun write(manifest: PackManifest, files: Map<String, ByteArray>) {
        val dir = packDir(manifest.identifier).apply { deleteRecursively(); mkdirs() }
        for ((name, bytes) in files) {
            require(FILE_REGEX.matches(name)) { "geçersiz dosya adı: $name" }
            File(dir, name).writeBytes(bytes)
        }
        File(dir, MANIFEST).writeText(json.encodeToString(PackManifest.serializer(), manifest))
    }

    fun deleteAll() {
        root.deleteRecursively()
        root.mkdirs()
    }

    private companion object {
        const val DIR = "wa_packs"
        const val MANIFEST = "pack.json"
        val IDENTIFIER_REGEX = Regex("[A-Za-z0-9_.-]{1,128}")
        val FILE_REGEX = Regex("[A-Za-z0-9_.-]{1,128}")
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}
