package io.github.darkvolfi.wscreator.core.imaging

import io.github.darkvolfi.wscreator.core.model.EncodeMode
import io.github.darkvolfi.wscreator.core.webp.WebpCodec
import io.github.darkvolfi.wscreator.core.webp.WebpOptions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class EncodedSticker(
    val bytes: ByteArray,
    val mode: EncodeMode,
    /** Kayıplıda WebP kalitesi; near-lossless'ta seviye; kayıpsızda 100. */
    val quality: Int,
    /** Kodlama öncesi görüntüye göre SSIM. */
    val ssim: Double,
    /** Denenen kodlama sayısı (performans takibi için). */
    val attempts: Int,
)

class StickerTooLargeException(val smallestBytes: Int, val limitBytes: Int) :
    Exception("En düşük kalitede bile $smallestBytes bayt; sınır $limitBytes bayt")

/**
 * Boyut sınırına sığan en yüksek kaliteli WebP'yi bulur (agents/02 — madde 7, BEST_FIT):
 *
 * 1. Kayıpsız → sığarsa bitti (piksel-piksel aynı).
 * 2. Near-lossless 80 → sığarsa bitti (gözle ayırt edilemez).
 * 3. Near-lossless 60/40 ve kayıplı ikili arama birlikte değerlendirilir; SSIM'i yüksek olan seçilir.
 */
class StickerEncoder(
    private val codec: WebpCodec,
    private val limitBytes: Int,
    private val losslessLevel: Int = 6,
) {
    suspend fun encode(image: ArgbImage): EncodedSticker {
        var attempts = 0
        fun tryEncode(options: WebpOptions): ByteArray {
            attempts++
            return codec.encode(image.pixels, image.width, image.height, options)
        }
        suspend fun checkpoint() = currentCoroutineContext().ensureActive()

        // 1. Kayıpsız
        val lossless = tryEncode(WebpOptions.lossless(losslessLevel))
        if (lossless.size <= limitBytes) {
            return EncodedSticker(lossless, EncodeMode.LOSSLESS, 100, 1.0, attempts)
        }
        checkpoint()

        // 2–3. Near-lossless
        var nearLossless: Pair<Int, ByteArray>? = null
        for (level in NEAR_LOSSLESS_LEVELS) {
            val bytes = tryEncode(WebpOptions.nearLossless(level, losslessLevel))
            checkpoint()
            if (bytes.size <= limitBytes) {
                nearLossless = level to bytes
                break
            }
        }
        if (nearLossless != null && nearLossless.first >= NEAR_LOSSLESS_ACCEPT_LEVEL) {
            val (level, bytes) = nearLossless
            return EncodedSticker(bytes, EncodeMode.NEAR_LOSSLESS, level, ssimOf(bytes, image), attempts)
        }

        // 3. Kayıplı: hızlı yöntemle (method 4) sığan en yüksek kaliteyi ikili aramayla bul
        var lo = 0
        var hi = 100
        var bestQuality = -1
        var bestBytes: ByteArray? = null
        var smallest = Int.MAX_VALUE
        while (lo <= hi) {
            val q = (lo + hi) / 2
            val bytes = tryEncode(WebpOptions.lossy(q.toFloat(), method = SEARCH_METHOD))
            checkpoint()
            smallest = minOf(smallest, bytes.size)
            if (bytes.size <= limitBytes) {
                bestQuality = q; bestBytes = bytes; lo = q + 1
            } else {
                hi = q - 1
            }
        }

        // Daha sıkı yöntem (method 5) genelde biraz daha küçük → aynı bütçeye biraz daha yüksek kalite sığabilir
        if (bestBytes != null) {
            var q = bestQuality
            while (q <= 100 && q <= bestQuality + FINAL_PROBE_STEPS) {
                val bytes = tryEncode(WebpOptions.lossy(q.toFloat(), method = FINAL_METHOD))
                checkpoint()
                if (bytes.size > limitBytes) break
                bestBytes = bytes; bestQuality = q
                q++
            }
        }

        val lossy = bestBytes?.let { EncodedSticker(it, EncodeMode.LOSSY, bestQuality, ssimOf(it, image), attempts) }
        val near = nearLossless?.let { (level, bytes) ->
            EncodedSticker(bytes, EncodeMode.NEAR_LOSSLESS, level, ssimOf(bytes, image), attempts)
        }
        return when {
            lossy != null && near != null -> if (near.ssim >= lossy.ssim) near else lossy
            lossy != null -> lossy
            near != null -> near
            else -> throw StickerTooLargeException(smallest, limitBytes)
        }
    }

    private fun ssimOf(bytes: ByteArray, reference: ArgbImage): Double {
        val decoded = codec.decode(bytes)
        return Ssim.compute(reference, ArgbImage(decoded.width, decoded.height, decoded.argb))
    }

    private companion object {
        val NEAR_LOSSLESS_LEVELS = intArrayOf(80, 60, 40)
        const val NEAR_LOSSLESS_ACCEPT_LEVEL = 80
        const val SEARCH_METHOD = 4
        const val FINAL_METHOD = 5   // 6, cihazda ~20 kat yavaş ve yalnızca ~%1 küçük (ölçüm: agents/raporlar/m1-kalite.md)
        const val FINAL_PROBE_STEPS = 3
    }
}
