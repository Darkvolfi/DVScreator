package io.github.darkvolfi.wscreator.core.imaging

import kotlin.math.max
import kotlin.math.roundToInt

/** Görseli uzatmadan kare şeffaf tuvale yerleştirir (bkz. agents/02 — madde 4, 5, 6). */
object StickerCanvas {

    /**
     * @param size     tuval kenarı (WhatsApp: 512)
     * @param margin   her kenarda bırakılacak şeffaf boşluk
     */
    fun fit(
        src: ArgbImage,
        size: Int,
        margin: Int,
        linearLight: Boolean = false,
        /** Video kareleri için: önce tam sayı katlı alan ortalaması, sonra Lanczos (~10 kat hızlı). */
        fast: Boolean = false,
    ): ArgbImage {
        require(margin in 0 until size / 2) { "geçersiz kenar boşluğu: $margin" }
        val box = size - 2 * margin
        val scale = box.toDouble() / max(src.width, src.height)
        val w = (src.width * scale).roundToInt().coerceIn(1, box)
        val h = (src.height * scale).roundToInt().coerceIn(1, box)
        // Alan ortalaması alias üretmez; Lanczos'a hedefin en az 2 katı çözünürlük bırakılır
        val factor = if (fast) max(src.width, src.height) / (2 * max(w, h)) else 1
        val pre = if (factor >= 2) Resampler.boxDownscale(src, factor) else src
        val scaled = Resampler.lanczos3(pre, w, h, linearLight)

        val canvas = ArgbImage(size, size)
        val left = (size - w) / 2
        val top = (size - h) / 2
        for (y in 0 until h) System.arraycopy(scaled.pixels, y * w, canvas.pixels, (top + y) * size + left, w)
        cleanAlpha(canvas)
        return canvas
    }

    /**
     * Neredeyse görünmez pikselleri (alfa < [threshold]) tamamen şeffaf yapar ve tam şeffaf
     * piksellerin RGB'sini sıfırlar: kodlayıcı görünmeyen renk gürültüsüne bayt harcamaz.
     */
    fun cleanAlpha(image: ArgbImage, threshold: Int = 3) {
        val px = image.pixels
        for (i in px.indices) if (alphaOf(px[i]) < threshold) px[i] = 0
    }
}
