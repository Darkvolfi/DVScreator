package io.github.darkvolfi.wscreator.core.whatsapp

/**
 * WhatsApp'ın sticker sınırları — tüm kod bu değerleri buradan okur.
 * Kaynak: https://github.com/WhatsApp/stickers (Android örnek projesi).
 * Değerler değişirse agents/01-whatsapp-gereksinimleri.md ile birlikte güncellenmeli.
 */
object WhatsAppLimits {
    const val STICKER_SIZE_PX = 512
    const val STATIC_STICKER_MAX_BYTES = 100 * 1024
    const val ANIMATED_STICKER_MAX_BYTES = 500 * 1024
    const val ANIMATED_MIN_FRAME_DURATION_MS = 8
    const val ANIMATED_MAX_TOTAL_DURATION_MS = 10_000

    const val TRAY_ICON_SIZE_PX = 96
    const val TRAY_ICON_MAX_BYTES = 50 * 1024

    const val PACK_MIN_STICKERS = 3
    const val PACK_MAX_STICKERS = 30
    const val MIN_EMOJIS_PER_STICKER = 1
    const val MAX_EMOJIS_PER_STICKER = 3
    const val STATIC_A11Y_TEXT_MAX = 125
    const val ANIMATED_A11Y_TEXT_MAX = 255
    const val ANIMATED_MIN_FRAMES = 2
    const val MAX_TEXT_LENGTH = 128

    const val WHATSAPP_PACKAGE = "com.whatsapp"
    const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
    const val ENABLE_STICKER_PACK_ACTION = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
}
