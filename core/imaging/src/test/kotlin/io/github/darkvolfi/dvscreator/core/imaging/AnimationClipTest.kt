package io.github.darkvolfi.dvscreator.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AnimationClipTest {

    private fun solid(color: Int) = ArgbImage(4, 4, IntArray(16) { color })

    @Test
    fun `kare azaltma toplam süreyi korur`() {
        val clip = AnimationClip(List(7) { solid(0xFF000000.toInt() or it) }, IntArray(7) { 40 })
        val half = clip.decimate(2)
        assertEquals(4, half.frames.size)
        assertEquals(clip.totalDurationMs, half.totalDurationMs)
        assertEquals(listOf(80, 80, 80, 40), half.durationsMs.toList())
    }

    @Test
    fun `sabitleme gürültüyü dondurur, gerçek değişimi korur`() {
        val base = argbOf(255, 100, 100, 100)
        val clip = AnimationClip(
            listOf(solid(base), solid(argbOf(255, 103, 98, 101)), solid(argbOf(255, 160, 100, 100))),
            intArrayOf(40, 40, 40),
        )
        val out = TemporalStabilizer.stabilize(clip, meanThreshold = 4)
        assertTrue(out.frames[1].pixels.all { it == base })                       // gürültü → önceki değer
        assertTrue(out.frames[2].pixels.all { it == argbOf(255, 160, 100, 100) }) // gerçek değişim → korunur
    }

    @Test
    fun `yavaş solma birikip güncellenir, hata eşiği aşmaz`() {
        val frames = (0..20).map { solid(argbOf(255, 100 + it, 100, 100)) }
        val out = TemporalStabilizer.stabilize(AnimationClip(frames, IntArray(frames.size) { 40 }), meanThreshold = 4)
        for (i in frames.indices) {
            assertTrue(abs(redOf(out.frames[i].pixels[0]) - redOf(frames[i].pixels[0])) <= 4)
        }
        assertTrue("solma hiç güncellenmedi", redOf(out.frames.last().pixels[0]) > 110)
    }

    @Test
    fun `sabitleme sentetik videoda arka planı tamamen sabitler`() {
        val clip = TestClips.movingBallOnNoisyBackground(frames = 6, noise = 3)
        val out = TemporalStabilizer.stabilize(clip, meanThreshold = 4)
        // Topun hiç geçmediği köşe bölgesi (ör. 40,40) tüm karelerde aynı olmalı
        val first = out.frames[0][40, 40]
        assertTrue(out.frames.all { it[40, 40] == first })
    }

    @Test
    fun `hızlı küçültme yavaş yöntemle neredeyse aynı`() {
        val src = TestImages.photo(2400, 1600)
        val fast = StickerCanvas.fit(src, 512, 16, fast = true)
        val slow = StickerCanvas.fit(src, 512, 16)
        val ssim = Ssim.compute(slow, fast)
        assertTrue("SSIM=$ssim", ssim > 0.98)
    }
}
