package io.github.darkvolfi.wscreator.core.whatsapp

import android.graphics.BitmapFactory
import io.github.darkvolfi.wscreator.core.webp.NativeWebp

/**
 * WhatsApp kurallarını eklemeden ÖNCE kontrol eder (agents/01 — Doğrulama). WhatsApp reddettiğinde
 * yalnızca genel bir hata döndürüyor (`handleStickerPackPreviewResult/failed`, M2 fizibilite) —
 * kullanıcıya nedenini burada söylüyoruz.
 */
object PackValidator {

    fun validate(pack: PackManifest, store: PackStore): List<String> = buildList {
        val L = WhatsAppLimits
        val n = pack.stickers.size
        if (n < L.PACK_MIN_STICKERS) add("Pakette $n sticker var; WhatsApp en az ${L.PACK_MIN_STICKERS} istiyor.")
        if (n > L.PACK_MAX_STICKERS) add("Pakette $n sticker var; WhatsApp en fazla ${L.PACK_MAX_STICKERS} kabul ediyor.")
        if (pack.name.isBlank()) add("Paket adı boş olamaz.")
        if (pack.name.length > L.MAX_TEXT_LENGTH) add("Paket adı en fazla ${L.MAX_TEXT_LENGTH} karakter olabilir.")
        if (pack.publisher.length > L.MAX_TEXT_LENGTH) add("Yayıncı adı en fazla ${L.MAX_TEXT_LENGTH} karakter olabilir.")

        val tray = store.readFile(pack.identifier, pack.trayImageFile)
        if (tray == null) {
            add("Paket simgesi eksik.")
        } else {
            if (tray.size > L.TRAY_ICON_MAX_BYTES) add("Paket simgesi ${tray.size / 1024} KB; sınır ${L.TRAY_ICON_MAX_BYTES / 1024} KB.")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(tray, 0, tray.size, bounds)
            if (bounds.outWidth != L.TRAY_ICON_SIZE_PX || bounds.outHeight != L.TRAY_ICON_SIZE_PX) {
                add("Paket simgesi ${bounds.outWidth}×${bounds.outHeight}; ${L.TRAY_ICON_SIZE_PX}×${L.TRAY_ICON_SIZE_PX} olmalı.")
            }
        }

        pack.stickers.forEachIndexed { i, s ->
            val label = "Sticker ${i + 1}"
            if (s.emojis.size < L.MIN_EMOJIS_PER_STICKER) add("$label: en az bir emoji seçilmeli.")
            if (s.emojis.size > L.MAX_EMOJIS_PER_STICKER) add("$label: en fazla ${L.MAX_EMOJIS_PER_STICKER} emoji olabilir.")
            val a11yMax = if (pack.animated) L.ANIMATED_A11Y_TEXT_MAX else L.STATIC_A11Y_TEXT_MAX
            if ((s.accessibilityText?.length ?: 0) > a11yMax) add("$label: açıklama en fazla $a11yMax karakter olabilir.")

            val bytes = store.readFile(pack.identifier, s.imageFile)
            if (bytes == null) {
                add("$label: dosya bulunamadı.")
                return@forEachIndexed
            }
            val limit = if (pack.animated) L.ANIMATED_STICKER_MAX_BYTES else L.STATIC_STICKER_MAX_BYTES
            if (bytes.size > limit) add("$label: ${kb(bytes.size)}; sınır ${kb(limit)}.")
            val info = runCatching { NativeWebp.info(bytes) }.getOrNull()
            if (info == null) {
                add("$label: geçerli bir WebP değil.")
                return@forEachIndexed
            }
            if (info.canvasWidth != L.STICKER_SIZE_PX || info.canvasHeight != L.STICKER_SIZE_PX) {
                add("$label: ${info.canvasWidth}×${info.canvasHeight}; ${L.STICKER_SIZE_PX}×${L.STICKER_SIZE_PX} olmalı.")
            }
            if (pack.animated) {
                if (info.frameCount < L.ANIMATED_MIN_FRAMES) add("$label: hareketli pakette en az ${L.ANIMATED_MIN_FRAMES} kare olmalı.")
                if (info.frameDurationsMs.any { it < L.ANIMATED_MIN_FRAME_DURATION_MS }) {
                    add("$label: kare süreleri en az ${L.ANIMATED_MIN_FRAME_DURATION_MS} ms olmalı.")
                }
                if (info.totalDurationMs > L.ANIMATED_MAX_TOTAL_DURATION_MS) {
                    add("$label: animasyon ${info.totalDurationMs / 1000.0} sn; en fazla ${L.ANIMATED_MAX_TOTAL_DURATION_MS / 1000} sn.")
                }
            } else if (info.frameCount > 1) {
                add("$label: statik pakette hareketli sticker olamaz.")
            }
        }
    }

    private fun kb(bytes: Int) = "%.1f KB".format(bytes / 1024.0)
}
