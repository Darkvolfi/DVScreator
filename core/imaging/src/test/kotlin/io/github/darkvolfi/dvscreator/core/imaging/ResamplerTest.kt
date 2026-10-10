package io.github.darkvolfi.dvscreator.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ResamplerTest {

    @Test
    fun `düz renk küçültmeden sonra aynı kalır`() {
        val color = argbOf(255, 200, 100, 50)
        val src = ArgbImage(1000, 700, IntArray(1000 * 700) { color })
        val out = Resampler.lanczos3(src, 123, 86)
        assertTrue(out.pixels.all { it == color })
    }

    @Test
    fun `şeffaf kenarlarda koyu halo oluşmaz`() {
        // Kırmızı disk; görünmeyen pikseller SİYAH (premultiplied olmayan kötü bir ölçekleyici bunları kenara sızdırır)
        val src = TestImages.softEdgeDisk(2000)
        val out = Resampler.lanczos3(src, 400, 400)
        for (p in out.pixels) {
            if (alphaOf(p) < 8) continue      // neredeyse görünmez pikseller ölçülemeyecek kadar küçük
            assertTrue("kenar rengi kırmızıdan saptı: ${Integer.toHexString(p)}", redOf(p) >= 220)
        }
    }

    @Test
    fun `zone plate küçültmesinde moire bastırılır`() {
        // İdeal küçültmede dış halkalar (Nyquist üstü) gri ortalamaya yakınsar.
        val out = Resampler.lanczos3(TestImages.zonePlate(2048), 512, 512)
        var maxDeviation = 0
        for (y in 0 until 512) for (x in 0 until 512) {
            val dx = x - 256; val dy = y - 256
            if (dx * dx + dy * dy < 240 * 240) continue      // yalnızca köşelerdeki en yüksek frekans
            maxDeviation = maxOf(maxDeviation, abs(redOf(out[x, y]) - 128))
        }
        assertTrue("köşelerde moire genliği çok yüksek: $maxDeviation", maxDeviation < 40)
    }

    @Test
    fun `nearest-neighbor ile karşılaştırıldığında moire belirgin şekilde az`() {
        val src = TestImages.zonePlate(2048)
        val lanczos = Resampler.lanczos3(src, 512, 512)
        val nearest = ArgbImage(512, 512) .also { o -> for (y in 0 until 512) for (x in 0 until 512) o[x, y] = src[x * 4, y * 4] }
        fun cornerEnergy(img: ArgbImage) = (0 until 64).sumOf { y -> (0 until 64).sumOf { x -> abs(redOf(img[x, y]) - 128) } }
        assertTrue(cornerEnergy(lanczos) * 3 < cornerEnergy(nearest))
    }

    @Test
    fun `büyütme de çalışır ve boyut doğru`() {
        val out = Resampler.lanczos3(TestImages.photo(64, 48), 480, 360)
        assertEquals(480, out.width)
        assertEquals(360, out.height)
    }
}
