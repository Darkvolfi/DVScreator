package io.github.darkvolfi.dvscreator.core.imaging

import kotlin.math.exp

/**
 * Yapısal benzerlik (SSIM, Wang ve ark. 2004): 11×11 Gauss penceresi, σ = 1.5.
 *
 * Şeffaflık: R, G, B kanalları premultiplied (siyah üzerine kompozit) ve A kanalı ayrı ayrı
 * ölçülür, ortalaması alınır. Ortalama yalnızca iki görüntüden en az birinde görünür olan
 * pikseller üzerinden alınır — aksi halde geniş şeffaf alanlar skoru yapay olarak 1'e yaklaştırır.
 *
 * 1.0 = birebir aynı. Kabaca: > 0.99 gözle ayırt edilemez, 0.97–0.99 dikkatle bakınca fark edilir.
 */
object Ssim {
    private const val C1 = (0.01 * 255) * (0.01 * 255)
    private const val C2 = (0.03 * 255) * (0.03 * 255)
    private val KERNEL: FloatArray = run {
        val radius = 5
        val k = FloatArray(2 * radius + 1) { i -> exp(-((i - radius) * (i - radius)) / (2 * 1.5 * 1.5)).toFloat() }
        val sum = k.sum()
        FloatArray(k.size) { k[it] / sum }
    }

    fun compute(a: ArgbImage, b: ArgbImage): Double {
        require(a.width == b.width && a.height == b.height) { "boyutlar farklı" }
        val n = a.width * a.height
        val mask = BooleanArray(n) { alphaOf(a.pixels[it]) > 0 || alphaOf(b.pixels[it]) > 0 }
        val visible = mask.count { it }
        if (visible == 0) return 1.0

        var total = 0.0
        for (channel in 0 until 4) {
            val x = plane(a, channel)
            val y = plane(b, channel)
            total += meanSsim(x, y, a.width, a.height, mask, visible)
        }
        return total / 4
    }

    private fun plane(img: ArgbImage, channel: Int): FloatArray = FloatArray(img.pixels.size) { i ->
        val p = img.pixels[i]
        val a = alphaOf(p)
        when (channel) {
            0 -> redOf(p) * a / 255f
            1 -> greenOf(p) * a / 255f
            2 -> blueOf(p) * a / 255f
            else -> a.toFloat()
        }
    }

    private fun meanSsim(x: FloatArray, y: FloatArray, w: Int, h: Int, mask: BooleanArray, visible: Int): Double {
        val n = x.size
        val xx = FloatArray(n) { x[it] * x[it] }
        val yy = FloatArray(n) { y[it] * y[it] }
        val xy = FloatArray(n) { x[it] * y[it] }
        val muX = blur(x, w, h)
        val muY = blur(y, w, h)
        val sXX = blur(xx, w, h)
        val sYY = blur(yy, w, h)
        val sXY = blur(xy, w, h)
        var sum = 0.0
        for (i in 0 until n) {
            if (!mask[i]) continue
            val mx = muX[i].toDouble()
            val my = muY[i].toDouble()
            val vx = sXX[i] - mx * mx
            val vy = sYY[i] - my * my
            val cov = sXY[i] - mx * my
            sum += ((2 * mx * my + C1) * (2 * cov + C2)) / ((mx * mx + my * my + C1) * (vx + vy + C2))
        }
        return sum / visible
    }

    /** Ayrılabilir Gauss; kenarlarda ağırlıklar yeniden normalize edilir. */
    private fun blur(src: FloatArray, w: Int, h: Int): FloatArray {
        val r = KERNEL.size / 2
        val tmp = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var s = 0f; var ws = 0f
                for (k in -r..r) {
                    val xx = x + k
                    if (xx < 0 || xx >= w) continue
                    val kw = KERNEL[k + r]
                    s += src[row + xx] * kw; ws += kw
                }
                tmp[row + x] = s / ws
            }
        }
        val out = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var ws = 0f
            for (k in -r..r) {
                val yy = y + k
                if (yy < 0 || yy >= h) continue
                val kw = KERNEL[k + r]
                s += tmp[yy * w + x] * kw; ws += kw
            }
            out[y * w + x] = s / ws
        }
        return out
    }
}
