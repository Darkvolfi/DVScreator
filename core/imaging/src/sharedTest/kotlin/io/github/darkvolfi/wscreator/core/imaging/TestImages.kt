package io.github.darkvolfi.wscreator.core.imaging

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Sentetik altın görsel seti (agents/02 — Kalite testleri). "grainy_photo" gerçek kamera
 * fotoğrafına benzer zorlukta (kayıpsız sığmaz) — kayıplı yolu test eder. Tohumlu rastgelelik → her çalıştırmada
 * birebir aynı görseller. Gerçek fotoğraflar (lisansı uygun) ileride testdata/ altına eklenecek.
 */
object TestImages {

    class Named(val name: String, val image: ArgbImage)

    fun all(): List<Named> = listOf(
        Named("photo_3000x2000", photo(3000, 2000)),
        Named("grainy_photo_3000x2000", photo(3000, 2000, grain = 22.0, textureEverywhere = true)),
        Named("cartoon_2048", cartoon(2048)),
        Named("text_1600x1200", text(1600, 1200)),
        Named("soft_edge_2000", softEdgeDisk(2000)),
        Named("zone_plate_2048", zonePlate(2048)),
        Named("noise_1024", noise(1024)),
        Named("tiny_64x48", photo(64, 48)),
    )

    /** Fotoğraf benzeri: yumuşak geçişler, nesneler, gren gürültüsü ve ince doku. Opak. */
    fun photo(w: Int, h: Int, seed: Int = 1, grain: Double = 6.0, textureEverywhere: Boolean = false): ArgbImage {
        val rnd = Random(seed)
        val img = ArgbImage(w, h)
        val blobs = List(12) { Triple(rnd.nextDouble() * w, rnd.nextDouble() * h, (0.05 + rnd.nextDouble() * 0.2) * w) }
        val colors = List(12) { Triple(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
        for (y in 0 until h) for (x in 0 until w) {
            val t = y.toDouble() / h
            var r = 90 + 120 * (1 - t); var g = 140 + 80 * (1 - t); var b = 200 + 40 * (1 - t)
            for (i in blobs.indices) {
                val (cx, cy, rad) = blobs[i]
                val d = hypot(x - cx, y - cy) / rad
                if (d < 1) {
                    val k = (1 - d * d) * 0.85
                    r = r * (1 - k) + colors[i].first * k
                    g = g * (1 - k) + colors[i].second * k
                    b = b * (1 - k) + colors[i].third * k
                }
            }
            if (textureEverywhere || (x > w * 0.6 && y > h * 0.6)) {   // ince kumaş dokusu
                val s = 18 * sin(x * 0.9) * sin(y * 0.7)
                r += s; g += s; b += s
            }
            val noise = if (grain > 0) rnd.nextDouble(-grain, grain) else 0.0
            img[x, y] = argbOf(255, c(r + noise), c(g + noise), c(b + noise))
        }
        return img
    }

    /** Çizgi film: şeffaf zemin, düz renkli şekiller, kalın siyah anti-aliased dış hat. */
    fun cartoon(size: Int): ArgbImage {
        val img = ArgbImage(size, size)
        val cx = size / 2.0; val cy = size / 2.0
        val r = size * 0.4; val outline = size * 0.02
        for (y in 0 until size) for (x in 0 until size) {
            val d = hypot(x + 0.5 - cx, y + 0.5 - cy)
            val coverage = (r + 0.5 - d).coerceIn(0.0, 1.0)            // 1 px anti-aliasing
            if (coverage <= 0) continue
            val inner = (r - outline + 0.5 - d).coerceIn(0.0, 1.0)
            val eye = hypot(x - cx * 0.75, y - cy * 0.8) < r * 0.12 || hypot(x - cx * 1.25, y - cy * 0.8) < r * 0.12
            val fill = if (eye) Triple(30, 30, 30) else Triple(255, 205, 40)
            val rr = (fill.first * inner).roundToInt()
            val gg = (fill.second * inner).roundToInt()
            val bb = (fill.third * inner).roundToInt()
            img[x, y] = argbOf((coverage * 255).roundToInt(), rr, gg, bb)
        }
        return img
    }

    /** Metin/ekran görüntüsü benzeri: beyaz zemin, ince siyah "satırlar" ve çizgiler. */
    fun text(w: Int, h: Int): ArgbImage {
        val rnd = Random(3)
        val img = ArgbImage(w, h, IntArray(w * h) { 0xFFFFFFFF.toInt() })
        var y = 40
        while (y < h - 60) {
            var x = 40
            while (x < w - 120) {
                val wordLen = 20 + rnd.nextInt(90)
                for (yy in y until y + 28) for (xx in x until minOf(x + wordLen, w - 40)) {
                    if ((xx / 3 + yy / 4) % 3 != 0) img[xx, yy] = 0xFF101010.toInt()   // harf benzeri desen
                }
                x += wordLen + 18
            }
            y += 56
        }
        return img
    }

    /** Yumuşak kenarlı (feather) kırmızı disk, şeffaf zemin — halo testi için. */
    fun softEdgeDisk(size: Int): ArgbImage {
        val img = ArgbImage(size, size)
        val c = size / 2.0; val r = size * 0.35; val feather = size * 0.05
        for (y in 0 until size) for (x in 0 until size) {
            val a = ((r - hypot(x - c, y - c)) / feather).coerceIn(0.0, 1.0)
            if (a > 0) img[x, y] = argbOf((a * 255).roundToInt(), 230, 20, 20)
        }
        return img
    }

    /** Merkezden dışa artan frekanslı halkalar: kötü küçültmede belirgin moire üretir. */
    fun zonePlate(size: Int): ArgbImage {
        val img = ArgbImage(size, size)
        val k = PI / size
        for (y in 0 until size) for (x in 0 until size) {
            val dx = x - size / 2.0; val dy = y - size / 2.0
            val v = c(127.5 + 127.5 * cos(k * (dx * dx + dy * dy) / 2))
            img[x, y] = argbOf(255, v, v, v)
        }
        return img
    }

    /** Saf rastgele gürültü: sıkıştırılamaz en kötü durum. */
    fun noise(size: Int): ArgbImage {
        val rnd = Random(7)
        return ArgbImage(size, size, IntArray(size * size) { 0xFF000000.toInt() or rnd.nextInt(0x1000000) })
    }

    private fun c(v: Double) = v.roundToInt().coerceIn(0, 255)
}
