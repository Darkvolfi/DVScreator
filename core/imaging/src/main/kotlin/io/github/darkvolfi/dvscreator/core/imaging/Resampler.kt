package io.github.darkvolfi.dvscreator.core.imaging

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Lanczos3 yeniden örnekleme (bkz. agents/02 — madde 5).
 *
 * - Ayrılabilir (önce yatay, sonra dikey) — küçültmede çekirdek ölçeğe göre genişletilir,
 *   böylece aliasing (tırtık/moire) oluşmaz.
 * - **Premultiplied alpha** üzerinde çalışır: şeffaf piksellerin görünmeyen rengi kenarlara
 *   sızmaz (halo yok).
 * - [linearLight] açıkken ortalama doğrusal ışıkta alınır (gamma-doğru).
 * - Bellek: kaynak her seferinde bir satır float'a çevrilir; ara tampon yalnızca hedefGenişlik × kaynakYükseklik.
 */
object Resampler {
    private const val LOBES = 3.0

    fun lanczos3(src: ArgbImage, dstWidth: Int, dstHeight: Int, linearLight: Boolean = false): ArgbImage {
        require(dstWidth > 0 && dstHeight > 0)
        if (dstWidth == src.width && dstHeight == src.height) return src

        val toFloat = if (linearLight) SRGB_TO_LINEAR else SRGB_TO_UNIT
        val hTable = contributions(src.width, dstWidth)
        val vTable = contributions(src.height, dstHeight)

        // Yatay geçiş: her kaynak satırı → hedef genişliğinde premultiplied RGBA float satırı
        val tmp = FloatArray(dstWidth * src.height * 4)
        val row = FloatArray(src.width * 4)
        for (y in 0 until src.height) {
            val base = y * src.width
            for (x in 0 until src.width) {
                val p = src.pixels[base + x]
                val a = alphaOf(p) / 255f
                val o = x * 4
                row[o] = toFloat[redOf(p)] * a
                row[o + 1] = toFloat[greenOf(p)] * a
                row[o + 2] = toFloat[blueOf(p)] * a
                row[o + 3] = a
            }
            val outBase = y * dstWidth * 4
            for (x in 0 until dstWidth) {
                var r = 0f; var g = 0f; var b = 0f; var a = 0f
                val start = hTable.start[x]
                val wOff = hTable.offset[x]
                for (k in 0 until hTable.count[x]) {
                    val w = hTable.weights[wOff + k]
                    val i = (start + k) * 4
                    r += row[i] * w; g += row[i + 1] * w; b += row[i + 2] * w; a += row[i + 3] * w
                }
                val o = outBase + x * 4
                tmp[o] = r; tmp[o + 1] = g; tmp[o + 2] = b; tmp[o + 3] = a
            }
        }

        // Dikey geçiş + un-premultiply
        val out = ArgbImage(dstWidth, dstHeight)
        val stride = dstWidth * 4
        for (y in 0 until dstHeight) {
            val start = vTable.start[y]
            val wOff = vTable.offset[y]
            val n = vTable.count[y]
            for (x in 0 until dstWidth) {
                var r = 0f; var g = 0f; var b = 0f; var a = 0f
                var i = start * stride + x * 4
                for (k in 0 until n) {
                    val w = vTable.weights[wOff + k]
                    r += tmp[i] * w; g += tmp[i + 1] * w; b += tmp[i + 2] * w; a += tmp[i + 3] * w
                    i += stride
                }
                out.pixels[y * dstWidth + x] = unpremultiply(r, g, b, a, linearLight)
            }
        }
        return out
    }

    /**
     * Tam sayı katlı alan ortalaması (premultiplied): her `factor × factor` blok tek piksel olur.
     * Kenarlarda artan pikseller atılır (en fazla factor-1 px). Hızlı ön küçültme içindir.
     */
    fun boxDownscale(src: ArgbImage, factor: Int): ArgbImage {
        require(factor >= 1)
        if (factor == 1) return src
        val w = src.width / factor
        val h = src.height / factor
        val out = ArgbImage(w.coerceAtLeast(1), h.coerceAtLeast(1))
        val n = factor * factor
        for (y in 0 until out.height) for (x in 0 until out.width) {
            var a = 0L; var r = 0L; var g = 0L; var b = 0L
            for (dy in 0 until factor) {
                var i = (y * factor + dy) * src.width + x * factor
                for (dx in 0 until factor) {
                    val p = src.pixels[i++]
                    val pa = alphaOf(p)
                    a += pa; r += redOf(p) * pa; g += greenOf(p) * pa; b += blueOf(p) * pa
                }
            }
            out.pixels[y * out.width + x] = if (a == 0L) 0 else argbOf(
                ((a + n / 2) / n).toInt(),
                ((r + a / 2) / a).toInt(), ((g + a / 2) / a).toInt(), ((b + a / 2) / a).toInt(),
            )
        }
        return out
    }

    private fun unpremultiply(r: Float, g: Float, b: Float, a: Float, linearLight: Boolean): Int {
        // Lanczos'un negatif lobları aşma (ringing) üretebilir → sınırla
        val alpha = a.coerceIn(0f, 1f)
        val a8 = (alpha * 255f).roundToInt()
        if (a8 == 0) return 0
        fun channel(c: Float): Int {
            val v = (c.coerceIn(0f, alpha) / alpha)
            return if (linearLight) linearToSrgb8(v) else (v * 255f).roundToInt()
        }
        return argbOf(a8, channel(r), channel(g), channel(b))
    }

    /** Her hedef pikseli için katkı veren kaynak aralığı ve normalize ağırlıklar. */
    private class Contributions(
        val start: IntArray, val count: IntArray, val offset: IntArray, val weights: FloatArray,
    )

    private fun contributions(srcSize: Int, dstSize: Int): Contributions {
        val scale = srcSize.toDouble() / dstSize
        val filterScale = max(scale, 1.0)          // küçültmede çekirdeği genişlet
        val support = LOBES * filterScale
        val start = IntArray(dstSize)
        val count = IntArray(dstSize)
        val offset = IntArray(dstSize)
        val maxTaps = ceil(support * 2).toInt() + 2
        val weights = FloatArray(dstSize * maxTaps)
        for (i in 0 until dstSize) {
            val center = (i + 0.5) * scale
            val lo = max(0, floor(center - support).toInt())
            val hi = min(srcSize - 1, ceil(center + support).toInt())
            var sum = 0.0
            val tmp = DoubleArray(hi - lo + 1)
            for (j in lo..hi) {
                val w = lanczos((j + 0.5 - center) / filterScale)
                tmp[j - lo] = w
                sum += w
            }
            // Kenarlarda kesilen çekirdeği yeniden normalize et
            var first = 0
            var last = tmp.size - 1
            while (first < last && tmp[first] == 0.0) first++
            while (last > first && tmp[last] == 0.0) last--
            start[i] = lo + first
            count[i] = last - first + 1
            offset[i] = i * maxTaps
            for (k in first..last) weights[i * maxTaps + k - first] = (tmp[k] / sum).toFloat()
        }
        return Contributions(start, count, offset, weights)
    }

    private fun lanczos(x: Double): Double {
        if (x == 0.0) return 1.0
        if (x <= -LOBES || x >= LOBES) return 0.0
        val px = PI * x
        return LOBES * sin(px) * sin(px / LOBES) / (px * px)
    }

    private val SRGB_TO_UNIT = FloatArray(256) { it / 255f }
    private val SRGB_TO_LINEAR = FloatArray(256) {
        val c = it / 255.0
        (if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)).toFloat()
    }

    private fun linearToSrgb8(v: Float): Int {
        val c = if (v <= 0.0031308f) v * 12.92 else 1.055 * v.toDouble().pow(1 / 2.4) - 0.055
        return (c * 255.0).roundToInt().coerceIn(0, 255)
    }
}
