package io.github.darkvolfi.dvscreator.core.imaging

import io.github.darkvolfi.dvscreator.core.model.MaskStroke
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Kareye maske uygular (otomatik arka plan kaldırma, silgi). Kırpmadan SONRA, 512 px tuvale
 * küçültmeden ÖNCE çağrılır: maske kaynağın kendi çözünürlüğünde uygulanır.
 * Videoda birden çok iş parçacığından çağrılabilir.
 */
fun interface Matte {
    /** @param region [image]'in kaynağın tamamındaki yeri, 0..1 (kırpma yoksa 0,0,1,1) */
    fun apply(image: ArgbImage, region: Region): ArgbImage

    /** Kaynak içindeki dikdörtgen, 0..1. */
    data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        companion object {
            val FULL = Region(0f, 0f, 1f, 1f)

            fun of(crop: io.github.darkvolfi.dvscreator.core.model.CropRect?, sourceW: Int, sourceH: Int): Region =
                if (crop == null) FULL
                else Region(crop.left / sourceW.toFloat(), crop.top / sourceH.toFloat(), crop.right / sourceW.toFloat(), crop.bottom / sourceH.toFloat())
        }
    }

    companion object {
        /**
         * Tarifteki maske adımları: önce [auto] (otomatik arka plan kaldırma, varsa), sonra fırça izleri sırayla.
         * Hiçbiri yoksa null (kare olduğu gibi kalır).
         */
        fun of(auto: Matte?, strokes: List<MaskStroke>): Matte? {
            if (auto == null && strokes.isEmpty()) return null
            return Matte { image, region ->
                val masked = auto?.apply(image, region) ?: image
                if (strokes.isEmpty()) masked else StrokePainter.paint(image, masked, strokes, region)
            }
        }
    }
}

/** Fırça izlerini alfa kanalına uygular. */
internal object StrokePainter {

    /**
     * @param original geri getir izleri için orijinal pikseller (otomatik maske öncesi)
     * @param current o ana kadarki sonuç (kopyalanır, yerinde değişmez)
     */
    fun paint(original: ArgbImage, current: ArgbImage, strokes: List<MaskStroke>, region: Matte.Region): ArgbImage {
        val w = current.width; val h = current.height
        val out = ArgbImage(w, h, current.pixels.copyOf())
        // Kaynak (0..1) → bu görüntünün pikseli
        val sx = w / (region.right - region.left)
        val sy = h / (region.bottom - region.top)
        for (stroke in strokes) {
            val r = stroke.radius * sx   // yarıçap kaynak genişliğine oranlı
            val pts = stroke.points
            if (pts.size < 2) continue
            fun px(i: Int) = (pts[i] - region.left) * sx
            fun py(i: Int) = (pts[i + 1] - region.top) * sy
            if (pts.size == 2) dab(out, original, stroke.restore, px(0), py(0), px(0), py(0), r)
            var i = 0
            while (i + 3 < pts.size) {
                dab(out, original, stroke.restore, px(i), py(i), px(i + 2), py(i + 2), r)
                i += 2
            }
        }
        return out
    }

    /** [x0,y0]–[x1,y1] doğru parçası boyunca yuvarlak uçlu fırça; kenarda 1 px yumuşak geçiş. */
    private fun dab(out: ArgbImage, original: ArgbImage, restore: Boolean, x0: Float, y0: Float, x1: Float, y1: Float, r: Float) {
        val minX = max(0, floor(min(x0, x1) - r - 1).toInt()); val maxX = min(out.width - 1, (max(x0, x1) + r + 1).toInt())
        val minY = max(0, floor(min(y0, y1) - r - 1).toInt()); val maxY = min(out.height - 1, (max(y0, y1) + r + 1).toInt())
        if (minX > maxX || minY > maxY) return
        val dx = x1 - x0; val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        for (y in minY..maxY) for (x in minX..maxX) {
            val px = x + 0.5f; val py = y + 0.5f
            val t = if (len2 == 0f) 0f else (((px - x0) * dx + (py - y0) * dy) / len2).coerceIn(0f, 1f)
            val cx = x0 + t * dx - px; val cy = y0 + t * dy - py
            val d = sqrt(cx * cx + cy * cy)
            val cover = (r + 0.5f - d).coerceIn(0f, 1f)   // 1 = tamamen fırça altında
            if (cover <= 0f) continue
            val i = y * out.width + x
            val p = out.pixels[i]
            val a = p ushr 24
            val target = if (restore) original.pixels[i] ushr 24 else 0
            val na = (a + (target - a) * cover).toInt().coerceIn(0, 255)
            val rgb = if (restore) original.pixels[i] and 0xFFFFFF else p and 0xFFFFFF
            out.pixels[i] = (na shl 24) or rgb
        }
    }
}
