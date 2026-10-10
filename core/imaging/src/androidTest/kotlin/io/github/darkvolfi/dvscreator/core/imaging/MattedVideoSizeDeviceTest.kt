package io.github.darkvolfi.dvscreator.core.imaging

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Arka planı kaldırılmış video benzetimi: hareket eden konu, her karede birkaç piksel oynayan yumuşak
 * maske kenarı ve saydam bölgede değişen (görünmeyen) eski arka plan. Sığmalı (önceden 650–920 KB'lık
 * gerçek videolar hiç sığmıyordu).
 */
@RunWith(AndroidJUnit4::class)
class MattedVideoSizeDeviceTest {

    private fun mattedClip(frames: Int = 100, jitter: Double = 2.0): AnimationClip {
        val rnd = Random(7)
        val list = (0 until frames).map { f ->
            val img = ArgbImage(512, 512)
            val cx = 256 + 60 * kotlin.math.sin(f / 15.0); val cy = 256.0
            for (y in 0 until 512) for (x in 0 until 512) {
                val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
                val edge = 150 + if (jitter > 0) rnd.nextDouble(-jitter, jitter) else 0.0                // maske titremesi
                val a = ((edge - d) * 40 + 128).toInt().coerceIn(0, 255)   // ~6 px yumuşak kenar
                val shade = ((x + y + f) / 4) and 0xFF
                val subject = (0xE0 shl 16) or (shade shl 8) or 0x40
                val oldBackground = rnd.nextInt(0xFFFFFF)                  // görünmez ama her karede farklı
                img.pixels[y * 512 + x] = (a shl 24) or (if (a > 0) subject else oldBackground)
            }
            img
        }
        return AnimationClip(list, IntArray(frames) { 66 })
    }

    /** Ölçüm: boyutu ne büyütüyor? (q50, aynı kareler) */
    @Test
    fun sizeBreakdown() {
        fun size(c: AnimationClip, alphaQ: Int = 100) = io.github.darkvolfi.dvscreator.core.webp.NativeWebp.encodeAnimation(
            c.frames.map { it.pixels }, c.durationsMs, 512, 512,
            io.github.darkvolfi.dvscreator.core.webp.WebpOptions.lossy(50f, method = 4).copy(alphaQuality = alphaQ),
        ).size / 1024
        val raw = mattedClip(40)
        val a = size(raw)
        val s1 = mattedClip(40).also { AlphaStabilizer.stabilize(it) }
        val b = size(s1)
        val c = size(s1, 60)
        val s2 = mattedClip(40).also { AlphaStabilizer.stabilize(it); TemporalStabilizer.stabilize(it, 4, inPlace = true) }
        val d = size(s2, 60)
        val opaque = mattedClip(40).also { cl -> cl.frames.forEach { fr -> for (i in fr.pixels.indices) fr.pixels[i] = fr.pixels[i] or (0xFF shl 24) } ; AlphaStabilizer.stabilize(cl) }
        val e = size(opaque)
        val noAlphaJitter = mattedClip(40, jitter = 0.0).also { AlphaStabilizer.stabilize(it) }
        val g = size(noAlphaJitter)
        Log.i("WSC_ANIM", "40 kare q50 KB: ham=$a alfaSabit=$b +alfaQ60=$c +renkSabit=$d | opak(alfa yok)=$e titremesiz maske=$g")
    }

    @Test
    fun fitsWithAlphaStabilization() = runTest(timeout = kotlin.time.Duration.parse("10m")) {
        val r = AnimationEncoder().encode(mattedClip())
        Log.i("WSC_ANIM", "maskeli video: ${r.bytes.size / 1024} KB q=${r.quality} ${r.frameCount} kare degraded=${r.degraded} ssim=%.3f".format(r.ssimMean))
        assertTrue(r.bytes.size <= AnimationEncoder.ANIMATED_LIMIT_BYTES)
        assertTrue("iyi kalite kademesinde sığmalı", !r.degraded)
    }
}
