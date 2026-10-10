package io.github.darkvolfi.dvscreator.core.imaging

import kotlin.math.max

/**
 * Arka plan kaldırıldıktan sonra kenardaki renk taşmasını (ör. koyu arka plandan kalan koyu hale) temizler.
 *
 * Yarı saydam (ve saydama birkaç piksel yakın) kenar pikselinin rengi, eski arka planla konunun karışımıdır. Yerine yakındaki
 * TAM OPAK konu piksellerinin ortalaması konur (alfa aynen kalır): kenar yumuşaklığı korunur, renk konunun
 * rengi olur. Ortalama ayrılabilir kutu bulanıklığıyla O(piksel) hesaplanır.
 */
object EdgeCleanup {

    /** Bu alfanın üstü "konunun kendisi" sayılır. */
    private const val SOLID = 250

    /**
     * [image] yerinde değişir.
     * @param radius bakılacak komşuluk (piksel); görüntünün ~%0.6'sı yeterli
     */
    fun decontaminate(image: ArgbImage, radius: Int = max(2, max(image.width, image.height) * 6 / 1000)) {
        val w = image.width; val h = image.height; val n = w * h
        // Kenar bandı: opak olsa da saydam bölgeye [band] pikselden yakın pikseller (model maskeyi genelde
        // 1-2 px taşırır → bu pikseller eski arka plan rengini taşır, koyu çizgi olarak görünür)
        val band = max(1, radius / 2)
        val nearClear = FloatArray(n) { if ((image.pixels[it] ushr 24) < SOLID) 1f else 0f }
        boxBlur(nearClear, w, h, band, horizontal = true); boxBlur(nearClear, w, h, band, horizontal = false)
        // Yalnızca kenardan uzak (iç) opak konu piksellerinin rengi ve ağırlığı
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n); val wt = FloatArray(n)
        var anyEdge = false
        for (i in 0 until n) {
            val p = image.pixels[i]
            val a = p ushr 24
            if (a > 0 && nearClear[i] > 0.5f) anyEdge = true
            if (a >= SOLID && nearClear[i] < 0.5f) {
                r[i] = ((p shr 16) and 0xFF).toFloat(); g[i] = ((p shr 8) and 0xFF).toFloat(); b[i] = (p and 0xFF).toFloat(); wt[i] = 1f
            }
        }
        if (!anyEdge) return
        for (c in arrayOf(r, g, b, wt)) { boxBlur(c, w, h, radius, horizontal = true); boxBlur(c, w, h, radius, horizontal = false) }
        for (i in 0 until n) {
            val p = image.pixels[i]
            val a = p ushr 24
            if (a == 0 || nearClear[i] < 0.5f || wt[i] < 1e-4f) continue
            val k = 1f / wt[i]
            val nr = (r[i] * k).toInt().coerceIn(0, 255); val ng = (g[i] * k).toInt().coerceIn(0, 255); val nb = (b[i] * k).toInt().coerceIn(0, 255)
            image.pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
    }

    /** Kayan toplamlı kutu bulanıklığı (toplam, ortalama değil — ağırlıkla bölündüğü için ölçek fark etmez). */
    private fun boxBlur(data: FloatArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val line = FloatArray(len)
        for (l in 0 until lines) {
            fun idx(k: Int) = if (horizontal) l * w + k else k * w + l
            for (k in 0 until len) line[k] = data[idx(k)]
            var sum = 0f
            for (k in 0..minOf(radius, len - 1)) sum += line[k]
            for (k in 0 until len) {
                data[idx(k)] = sum
                val add = k + radius + 1; val remove = k - radius
                if (add < len) sum += line[add]
                if (remove >= 0) sum -= line[remove]
            }
        }
    }
}
