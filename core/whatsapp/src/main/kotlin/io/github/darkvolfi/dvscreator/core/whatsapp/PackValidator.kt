package io.github.darkvolfi.dvscreator.core.whatsapp

import android.content.res.Resources
import android.graphics.BitmapFactory
import io.github.darkvolfi.dvscreator.core.webp.NativeWebp
import java.util.Locale

/**
 * WhatsApp kurallarını eklemeden ÖNCE kontrol eder (agents/01 — Doğrulama). WhatsApp reddettiğinde
 * yalnızca genel bir hata döndürüyor (`handleStickerPackPreviewResult/failed`, M2 fizibilite) —
 * kullanıcıya nedenini burada, uygulamanın dilinde söylüyoruz.
 */
object PackValidator {

    fun validate(pack: PackManifest, store: PackStore, res: Resources): List<String> = buildList {
        val L = WhatsAppLimits
        val n = pack.stickers.size
        if (n < L.PACK_MIN_STICKERS) add(res.getString(R.string.validate_too_few, n, L.PACK_MIN_STICKERS))
        if (n > L.PACK_MAX_STICKERS) add(res.getString(R.string.validate_too_many, n, L.PACK_MAX_STICKERS))
        if (pack.name.isBlank()) add(res.getString(R.string.validate_name_blank))
        if (pack.name.length > L.MAX_TEXT_LENGTH) add(res.getString(R.string.validate_name_long, L.MAX_TEXT_LENGTH))
        if (pack.publisher.length > L.MAX_TEXT_LENGTH) add(res.getString(R.string.validate_publisher_long, L.MAX_TEXT_LENGTH))

        val tray = store.readFile(pack.identifier, pack.trayImageFile)
        if (tray == null) {
            add(res.getString(R.string.validate_tray_missing))
        } else {
            if (tray.size > L.TRAY_ICON_MAX_BYTES) add(res.getString(R.string.validate_tray_size, tray.size / 1024, L.TRAY_ICON_MAX_BYTES / 1024))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(tray, 0, tray.size, bounds)
            if (bounds.outWidth != L.TRAY_ICON_SIZE_PX || bounds.outHeight != L.TRAY_ICON_SIZE_PX) {
                add(res.getString(R.string.validate_tray_dims, bounds.outWidth, bounds.outHeight, L.TRAY_ICON_SIZE_PX))
            }
        }

        pack.stickers.forEachIndexed { i, s ->
            val label = res.getString(R.string.validate_sticker_label, i + 1)
            if (s.emojis.size < L.MIN_EMOJIS_PER_STICKER) add(res.getString(R.string.validate_emoji_min, label))
            if (s.emojis.size > L.MAX_EMOJIS_PER_STICKER) add(res.getString(R.string.validate_emoji_max, label, L.MAX_EMOJIS_PER_STICKER))
            val a11yMax = if (pack.animated) L.ANIMATED_A11Y_TEXT_MAX else L.STATIC_A11Y_TEXT_MAX
            if ((s.accessibilityText?.length ?: 0) > a11yMax) add(res.getString(R.string.validate_a11y_long, label, a11yMax))

            val bytes = store.readFile(pack.identifier, s.imageFile)
            if (bytes == null) {
                add(res.getString(R.string.validate_file_missing, label))
                return@forEachIndexed
            }
            val limit = if (pack.animated) L.ANIMATED_STICKER_MAX_BYTES else L.STATIC_STICKER_MAX_BYTES
            if (bytes.size > limit) add(res.getString(R.string.validate_file_size, label, kb(res, bytes.size), kb(res, limit)))
            val info = runCatching { NativeWebp.info(bytes) }.getOrNull()
            if (info == null) {
                add(res.getString(R.string.validate_not_webp, label))
                return@forEachIndexed
            }
            if (info.canvasWidth != L.STICKER_SIZE_PX || info.canvasHeight != L.STICKER_SIZE_PX) {
                add(res.getString(R.string.validate_dims, label, info.canvasWidth, info.canvasHeight, L.STICKER_SIZE_PX))
            }
            if (pack.animated) {
                if (info.frameCount < L.ANIMATED_MIN_FRAMES) add(res.getString(R.string.validate_min_frames, label, L.ANIMATED_MIN_FRAMES))
                if (info.frameDurationsMs.any { it < L.ANIMATED_MIN_FRAME_DURATION_MS }) {
                    add(res.getString(R.string.validate_frame_duration, label, L.ANIMATED_MIN_FRAME_DURATION_MS))
                }
                if (info.totalDurationMs > L.ANIMATED_MAX_TOTAL_DURATION_MS) {
                    val seconds = String.format(locale(res), "%.1f", info.totalDurationMs / 1000.0)
                    add(res.getString(R.string.validate_total_duration, label, seconds, L.ANIMATED_MAX_TOTAL_DURATION_MS / 1000))
                }
            } else if (info.frameCount > 1) {
                add(res.getString(R.string.validate_static_animated, label))
            }
        }
    }

    private fun kb(res: Resources, bytes: Int) = res.getString(R.string.size_kb, String.format(locale(res), "%.1f", bytes / 1024.0))

    private fun locale(res: Resources): Locale = res.configuration.locales[0] ?: Locale.getDefault()
}
