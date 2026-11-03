package io.github.darkvolfi.dvscreator.core.imaging

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Kement seçimi: çokgenin dışı silinir. Otomatik maske ([Matte]) verilirse yalnızca seçimin çevresindeki bölgeye
 * uygulanır — model konuyu büyük ve arka planın büyük kısmı dışarıda görür; karmaşık resimlerde her şeyi silmek yerine
 * konuyu bulur. Kenar, piksel başına 4 alt satır örneklemeyle yumuşatılır.
 */
internal object Selection {

    /**
     * @param polygon kaynağın tamamına göre 0..1 (x0, y0, x1, y1, …)
     * @param region [image]'in kaynaktaki yeri
     * @param snap [auto] varsa kesim çizgiye değil konuya oturur: modelin maskesi, çizginin [PAD] kadar genişletilmiş
     *   hâli içinde kalır — kabaca çizilen kement konunun kenarını kesmez
     */
    fun cut(image: ArgbImage, region: Matte.Region, polygon: List<Float>, auto: Matte?, snap: Boolean = false): ArgbImage {
        val w = image.width; val h = image.height
        val out = ArgbImage(w, h)
        if (polygon.size < 6) return out   // üç noktadan az: alan yok
        val sx = w / (region.right - region.left)
        val sy = h / (region.bottom - region.top)
        val xs = FloatArray(polygon.size / 2) { (polygon[2 * it] - region.left) * sx }
        val ys = FloatArray(polygon.size / 2) { (polygon[2 * it + 1] - region.top) * sy }
        val coverage = coverage(xs, ys, w, h)

        // Seçimin kutusu (biraz payla): model yalnızca burada çalışır
        val pad = max(xs.max() - xs.min(), ys.max() - ys.min()) * PAD
        val left = floor(xs.min() - pad).toInt().coerceIn(0, w - 1)
        val top = floor(ys.min() - pad).toInt().coerceIn(0, h - 1)
        val right = ceil(xs.max() + pad).toInt().coerceIn(left + 1, w)
        val bottom = ceil(ys.max() + pad).toInt().coerceIn(top + 1, h)
        val inner = image.crop(left, top, right, bottom).let { box ->
            if (auto == null) box
            else auto.apply(box, Matte.Region(
                region.left + left / sx, region.top + top / sy, region.left + right / sx, region.top + bottom / sy,
            ))
        }
        val bw = right - left
        val band = if (snap && auto != null) dilate(coverage, w, left, top, right, bottom, pad.roundToInt()) else null
        for (y in top until bottom) for (x in left until right) {
            val c = band?.get((y - top) * bw + (x - left)) ?: coverage[y * w + x]
            if (c <= 0f) continue
            val p = inner.pixels[(y - top) * inner.width + (x - left)]
            val a = ((p ushr 24) * c + 0.5f).toInt()
            out.pixels[y * w + x] = if (a == 0) 0 else (a shl 24) or (p and 0xFFFFFF)
        }
        return out
    }

    /** Her pikselin çokgenin içinde kalan payı (0..1); çift-tek kuralı, 4 alt satır, yatayda tam alan. */
    internal fun coverage(xs: FloatArray, ys: FloatArray, w: Int, h: Int): FloatArray {
        val cov = FloatArray(w * h)
        val n = xs.size
        val crossings = FloatArray(n)
        val y0 = max(0, floor(ys.min()).toInt()); val y1 = min(h - 1, ceil(ys.max()).toInt())
        for (y in y0..y1) for (sub in 0 until SUB) {
            val sy = y + (sub + 0.5f) / SUB
            var count = 0
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ya = ys[i]; val yb = ys[j]
                if ((ya <= sy) != (yb <= sy)) crossings[count++] = xs[i] + (sy - ya) / (yb - ya) * (xs[j] - xs[i])
            }
            crossings.sort(0, count)
            var k = 0
            while (k + 1 < count) {
                val a = crossings[k].coerceIn(0f, w.toFloat()); val b = crossings[k + 1].coerceIn(0f, w.toFloat())
                // [a, b) aralığının her piksele düşen payı
                var x = floor(a).toInt()
                while (x < w && x < b) {
                    val overlap = min(b, x + 1f) - max(a, x.toFloat())
                    if (overlap > 0f) cov[y * w + x] += overlap / SUB
                    x++
                }
                k += 2
            }
        }
        return cov
    }

    /**
     * Seçimin [left]..[right] × [top]..[bottom] kutusunda, çokgenin içine [r] pikselden (kare pencere) yakın her yer 1,
     * gerisi 0. Satır ve sütun önek toplamlarıyla: süre r'den bağımsız.
     */
    internal fun dilate(coverage: FloatArray, w: Int, left: Int, top: Int, right: Int, bottom: Int, r: Int): FloatArray {
        val bw = right - left; val bh = bottom - top
        val rows = BooleanArray(bw * bh)
        val pre = IntArray(max(bw, bh) + 1)
        for (y in 0 until bh) {
            for (x in 0 until bw) pre[x + 1] = pre[x] + if (coverage[(y + top) * w + x + left] > 0f) 1 else 0
            if (pre[bw] == 0) continue
            for (x in 0 until bw) rows[y * bw + x] = pre[min(bw, x + r + 1)] - pre[max(0, x - r)] > 0
        }
        val out = FloatArray(bw * bh)
        for (x in 0 until bw) {
            for (y in 0 until bh) pre[y + 1] = pre[y] + if (rows[y * bw + x]) 1 else 0
            if (pre[bh] == 0) continue
            for (y in 0 until bh) if (pre[min(bh, y + r + 1)] - pre[max(0, y - r)] > 0) out[y * bw + x] = 1f
        }
        return out
    }

    private const val SUB = 4
    /**
     * Model kutusunun seçimin çevresine bıraktığı pay (seçimin büyük kenarına oranla): kenarları bağlamla görsün.
     * Nesneye oturan kement de en fazla bu kadar dışarı taşar.
     */
    private const val PAD = 0.08f
}
