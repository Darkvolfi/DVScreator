package io.github.darkvolfi.dvscreator.core.model

import kotlinx.serialization.Serializable

/** Bir WhatsApp sticker paketi. Kurallar için bkz. agents/01-whatsapp-gereksinimleri.md */
@Serializable
data class StickerPack(
    val id: String,
    val name: String,
    val publisher: String,
    val animated: Boolean = false,
    /** Paket içeriği her değiştiğinde artırılır; yoksa WhatsApp önbellekteki eski sticker'ı gösterir. */
    val imageDataVersion: Int = 1,
    val trayStickerId: String? = null,
    val createdAt: Long,
)
