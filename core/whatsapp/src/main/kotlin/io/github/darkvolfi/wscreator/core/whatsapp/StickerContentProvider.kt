package io.github.darkvolfi.wscreator.core.whatsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * WhatsApp'ın sticker paketlerini okuduğu arayüz.
 *
 * URI yapısı ve sütun adları WhatsApp'ın resmi örnek projesindeki `StickerContentProvider` ile
 * BİREBİR aynıdır (github.com/WhatsApp/stickers) — değiştirilirse WhatsApp paketleri okuyamaz.
 */
class StickerContentProvider : ContentProvider() {

    private lateinit var store: PackStore
    private lateinit var authority: String
    private val matcher = UriMatcher(UriMatcher.NO_MATCH)

    override fun onCreate(): Boolean {
        val context = requireNotNull(context)
        store = PackStore(context)
        authority = authorityFor(context.packageName)
        matcher.addURI(authority, METADATA, METADATA_CODE)
        matcher.addURI(authority, "$METADATA/*", METADATA_SINGLE_CODE)
        matcher.addURI(authority, "$STICKERS/*", STICKERS_CODE)
        matcher.addURI(authority, "$STICKERS_ASSET/*/*", STICKERS_ASSET_CODE)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        when (matcher.match(uri)) {
            METADATA_CODE -> packCursor(uri, store.packs())
            METADATA_SINGLE_CODE -> packCursor(uri, listOfNotNull(store.pack(uri.lastPathSegment.orEmpty())))
            STICKERS_CODE -> stickersCursor(uri, store.pack(uri.lastPathSegment.orEmpty()))
            else -> throw IllegalArgumentException("Bilinmeyen URI: $uri")
        }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (matcher.match(uri) != STICKERS_ASSET_CODE) return null
        val segments = uri.pathSegments
        if (segments.size != 3) return null
        val file = store.assetFile(identifier = segments[1], fileName = segments[2]) ?: return null
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    override fun getType(uri: Uri): String = when (matcher.match(uri)) {
        METADATA_CODE -> "vnd.android.cursor.dir/vnd.$authority.$METADATA"
        METADATA_SINGLE_CODE -> "vnd.android.cursor.item/vnd.$authority.$METADATA"
        STICKERS_CODE -> "vnd.android.cursor.dir/vnd.$authority.$STICKERS"
        STICKERS_ASSET_CODE -> if (uri.lastPathSegment.orEmpty().endsWith(".png")) "image/png" else "image/webp"
        else -> throw IllegalArgumentException("Bilinmeyen URI: $uri")
    }

    private fun packCursor(uri: Uri, packs: List<PackManifest>): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                "sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher", "sticker_pack_icon",
                "android_play_store_link", "ios_app_download_link", "sticker_pack_publisher_email",
                "sticker_pack_publisher_website", "sticker_pack_privacy_policy_website",
                "sticker_pack_license_agreement_website", "image_data_version",
                "whatsapp_will_not_cache_stickers", "animated_sticker_pack",
            ),
        )
        for (p in packs) {
            cursor.addRow(
                arrayOf<Any?>(
                    p.identifier, p.name, p.publisher, p.trayImageFile,
                    "", "", "", "", "", "",
                    p.imageDataVersion.toString(),
                    if (p.avoidCache) 1 else 0,
                    if (p.animated) 1 else 0,
                ),
            )
        }
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
        return cursor
    }

    private fun stickersCursor(uri: Uri, pack: PackManifest?): Cursor {
        val cursor = MatrixCursor(arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text"))
        pack?.stickers?.forEach { cursor.addRow(arrayOf<Any?>(it.imageFile, it.emojis.joinToString(","), it.accessibilityText)) }
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        private const val METADATA = "metadata"
        private const val STICKERS = "stickers"
        private const val STICKERS_ASSET = "stickers_asset"
        private const val METADATA_CODE = 1
        private const val METADATA_SINGLE_CODE = 2
        private const val STICKERS_CODE = 3
        private const val STICKERS_ASSET_CODE = 4

        /** Manifest'teki `${applicationId}.stickers` ile aynı olmalı. */
        fun authorityFor(packageName: String) = "$packageName.stickers"
    }
}
