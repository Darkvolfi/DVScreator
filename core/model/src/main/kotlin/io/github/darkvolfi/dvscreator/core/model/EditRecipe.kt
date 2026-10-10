package io.github.darkvolfi.dvscreator.core.model

import kotlinx.serialization.Serializable

/**
 * Tahribatsız düzenleme tarifi. Tüm koordinatlar ORİJİNAL görselin piksel uzayındadır;
 * dışa aktarım her seferinde orijinalden yeniden üretilir (bkz. agents/02 — madde 3).
 */
@Serializable
data class EditRecipe(
    /** Orijinal görsel üzerindeki kırpma alanı; null ise görselin tamamı. */
    val crop: CropRect? = null,
    /** Saat yönünde, 90'ın katları; son sticker tuvaline uygulanır (yazılar düz kalır). */
    val rotationDegrees: Int = 0,
    /** 512 px tuvalde kenar boşluğu (0..32). */
    val marginPx: Int = 16,
    /** Silgi / geri getir fırça izleri, sırayla uygulanır (otomatik arka plan kaldırmadan SONRA). */
    val strokes: List<MaskStroke> = emptyList(),
    val texts: List<TextLayer> = emptyList(),
    /** Küçültme sonrası keskinleştirme (0 = kapalı). */
    val sharpen: Float = 0f,
    /** Hareketli kaynaklarda kullanılan aralık; null = baştan, en fazla 10 sn. */
    val trim: TrimRange? = null,
    /** Otomatik arka plan kaldırma (cihazdaki model, core:segmentation); videoda kare kare. */
    val removeBackground: Boolean = false,
    /** Konunun etrafına dış çizgi kalınlığı (512 tuvalde px; 0 = yok) ve rengi. */
    val outlinePx: Int = 0,
    val outlineArgb: Int = 0xFFFFFFFF.toInt(),
)

@Serializable
data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Video / GIF'te kullanılacak zaman aralığı, kaynak zaman çizelgesinde (ms). */
@Serializable
data class TrimRange(val startMs: Int, val endMs: Int) {
    init {
        require(startMs >= 0 && endMs > startMs) { "geçersiz aralık: $startMs..$endMs" }
    }
    val durationMs: Int get() = endMs - startMs
}

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

/**
 * Bir silgi ya da "geri getir" fırça izi. Koordinatlar KAYNAĞIN TAMAMINA göre 0..1 (kırpma sonradan
 * değişse de iz yerinde kalır); [radius] kaynak genişliğine oranla.
 */
@Serializable
data class MaskStroke(
    /** true: orijinal pikselleri geri getirir; false: siler. */
    val restore: Boolean,
    val radius: Float,
    /** x0, y0, x1, y1, … */
    val points: List<Float>,
)
