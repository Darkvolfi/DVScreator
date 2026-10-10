package io.github.darkvolfi.dvscreator.core.imaging

import kotlin.math.sqrt

/**
 * Klasik sticker dış çizgisi: konunun etrafına [radius] px kalınlığında düz renk (genelde beyaz) kenar.
 * Son tuvalde (512 px) çizilir. Her pikselin en yakın konu pikseline (alfa ≥ 128) uzaklığı tam Öklid
 * uzaklık dönüşümüyle (Felzenszwalb–Huttenlocher, O(piksel)) bulunur; kenar 1 px yumuşatılır.
 */
object StickerOutline {

    private const val INF = 1e20f

    /** [image] yerinde değişir: konu, çizginin ÜSTÜNDE kalır. */
    fun apply(image: ArgbImage, radius: Int, colorArgb: Int) {
        if (radius <= 0) return
        val w = image.width; val h = image.height
        val d2 = FloatArray(w * h) { if ((image.pixels[it] ushr 24) >= 128) 0f else INF }
        if (d2.none { it == 0f }) return
        // Önce sütunlar, sonra satırlar (kare uzaklık ayrılabilir)
        val f = FloatArray(maxOf(w, h)); val out = FloatArray(maxOf(w, h))
        val v = IntArray(maxOf(w, h)); val z = FloatArray(maxOf(w, h) + 1)
        for (x in 0 until w) {
            for (y in 0 until h) f[y] = d2[y * w + x]
            edt1d(f, h, out, v, z)
            for (y in 0 until h) d2[y * w + x] = out[y]
        }
        for (y in 0 until h) {
            System.arraycopy(d2, y * w, f, 0, w)
            edt1d(f, w, out, v, z)
            System.arraycopy(out, 0, d2, y * w, w)
        }
        val rgb = colorArgb and 0xFFFFFF
        for (i in 0 until w * h) {
            val coverage = (radius + 0.5f - sqrt(d2[i])).coerceIn(0f, 1f)
            if (coverage <= 0f) continue
            val oa = (coverage * 255).toInt()
            // Konu (üstte) + çizgi (altta), düz alfa ile "üstüne" birleştirme
            val p = image.pixels[i]
            val sa = p ushr 24
            if (sa == 255) continue
            val outA = sa + oa * (255 - sa) / 255
            if (outA == 0) continue
            fun ch(shift: Int): Int {
                val sc = (p shr shift) and 0xFF
                val oc = (rgb shr shift) and 0xFF
                return ((sc * sa + oc * oa * (255 - sa) / 255) / outA).coerceIn(0, 255)
            }
            image.pixels[i] = (outA shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }

    /** 1B kare uzaklık dönüşümü: out[q] = min_p (q − p)² + f[p]. */
    private fun edt1d(f: FloatArray, n: Int, out: FloatArray, v: IntArray, z: FloatArray) {
        var k = 0
        v[0] = 0; z[0] = -INF; z[1] = INF
        fun intersect(q: Int, p: Int) = ((f[q] + q.toFloat() * q) - (f[p] + p.toFloat() * p)) / (2f * (q - p))
        for (q in 1 until n) {
            var s = intersect(q, v[k])
            while (s <= z[k]) { k--; s = intersect(q, v[k]) }   // z[0] = -∞: k 0'ın altına inmez
            k++
            v[k] = q; z[k] = s; z[k + 1] = INF
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val d = (q - v[k]).toFloat()
            out[q] = d * d + f[v[k]]
        }
    }
}
