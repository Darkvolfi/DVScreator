package io.github.darkvolfi.dvscreator.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SsimTest {

    private val photo = TestImages.photo(256, 256)

    @Test
    fun `aynı görüntü 1 verir`() {
        assertEquals(1.0, Ssim.compute(photo, photo.copy()), 1e-9)
    }

    @Test
    fun `bozulma arttıkça skor düşer`() {
        fun noisy(amount: Int) = ArgbImage(256, 256, IntArray(photo.pixels.size) { i ->
            val p = photo.pixels[i]
            val rnd = Random(i)
            fun ch(v: Int) = (v + rnd.nextInt(-amount, amount + 1)).coerceIn(0, 255)
            argbOf(255, ch(redOf(p)), ch(greenOf(p)), ch(blueOf(p)))
        })
        val light = Ssim.compute(photo, noisy(4))
        val heavy = Ssim.compute(photo, noisy(40))
        assertTrue("hafif=$light ağır=$heavy", light > heavy)
        assertTrue(light > 0.9)
        assertTrue(heavy < 0.8)
    }

    @Test
    fun `tamamen şeffaf alanlar skoru şişirmez`() {
        // Görüntünün %99'u şeffaf, görünür kısım tamamen farklı → skor düşük olmalı
        val a = ArgbImage(200, 200); val b = ArgbImage(200, 200)
        for (y in 0 until 20) for (x in 0 until 20) {
            a[x, y] = argbOf(255, 255, 255, 255)
            b[x, y] = argbOf(255, if ((x + y) % 2 == 0) 0 else 255, 0, 0)
        }
        assertTrue(Ssim.compute(a, b) < 0.7)
    }
}
