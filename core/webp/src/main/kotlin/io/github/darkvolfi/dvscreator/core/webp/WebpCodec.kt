package io.github.darkvolfi.dvscreator.core.webp

/** WebP kodlayıcı soyutlaması; testlerde sahte (fake) uygulama kullanılabilsin diye arayüz. */
interface WebpCodec {
    fun encode(argb: IntArray, width: Int, height: Int, options: WebpOptions): ByteArray
    fun decode(webp: ByteArray): DecodedImage
}

class DecodedImage(val width: Int, val height: Int, val argb: IntArray)

/** Bkz. agents/02-kalite-stratejisi.md — madde 7. */
data class WebpOptions(
    val lossless: Boolean,
    /** Kayıpsızda 0 (hızlı) .. 9 (en sıkı). */
    val losslessLevel: Int = 6,
    /** Kayıplıda 0..100. */
    val quality: Float = 90f,
    /** Kayıplıda 0 (hızlı) .. 6 (en iyi sıkıştırma). */
    val method: Int = 6,
    /** Kayıpsızda 0..100; 100 = kapalı (tam kayıpsız). */
    val nearLossless: Int = 100,
    val alphaQuality: Int = 100,
    /** Alfa ön filtresi: 0 yok, 1 hızlı, 2 en iyi. 2, method 6 ile saniyeler sürebiliyor. */
    val alphaFiltering: Int = 1,
    /** Renk alt örneklemede kenar renklerini korur (kayıplı). */
    val sharpYuv: Boolean = true,
    /** false: tamamen şeffaf piksellerin görünmeyen RGB değerleri boyut için değiştirilebilir. */
    val exact: Boolean = false,
) {
    companion object {
        fun lossless(level: Int = 6) = WebpOptions(lossless = true, losslessLevel = level)
        fun nearLossless(level: Int, losslessLevel: Int = 6) =
            WebpOptions(lossless = true, losslessLevel = losslessLevel, nearLossless = level)
        fun lossy(quality: Float, method: Int = 6) =
            WebpOptions(lossless = false, quality = quality, method = method)
    }
}
