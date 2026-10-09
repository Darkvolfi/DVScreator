package io.github.darkvolfi.wscreator.core.model

import kotlinx.serialization.Serializable

/**
 * Tahribatsız düzenleme tarifi. Tüm koordinatlar ORİJİNAL görselin piksel uzayındadır;
 * dışa aktarım her seferinde orijinalden yeniden üretilir (bkz. agents/02 — madde 3).
 */
@Serializable
data class EditRecipe(
    /** Orijinal görsel üzerindeki kırpma alanı; null ise görselin tamamı. */
    val crop: CropRect? = null,
    /** Saat yönünde, 90'ın katları. */
    val rotationDegrees: Int = 0,
    /** 512 px tuvalde kenar boşluğu (0..32). */
    val marginPx: Int = 16,
    /** Silgi maskesinin dosya yolu (orijinal çözünürlükte, alfa kanalı). */
    val maskPath: String? = null,
    val texts: List<TextLayer> = emptyList(),
    /** Küçültme sonrası keskinleştirme (0 = kapalı). */
    val sharpen: Float = 0f,
)

@Serializable
data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

@Serializable
data class TextLayer(
    val text: String,
    /** Merkez konumu, 0..1 aralığında normalize. */
    val centerX: Float,
    val centerY: Float,
    /** Yazı boyutu, tuval genişliğine oranla. */
    val sizeFraction: Float,
    val colorArgb: Int,
    val outlineArgb: Int? = null,
)
