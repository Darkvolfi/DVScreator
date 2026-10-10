package io.github.darkvolfi.dvscreator.core.segmentation

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.darkvolfi.dvscreator.core.imaging.ArgbImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Arka plan kaldırma: doğruluk (basit sahne) ve hız ölçümü (XNNPACK açık/kapalı). */
@RunWith(AndroidJUnit4::class)
class BackgroundRemoverDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Yeşilimsi dokulu arka plan önünde turuncu bir daire (sticker konusu gibi). */
    private fun scene(w: Int, h: Int): ArgbImage {
        val img = ArgbImage(w, h)
        val cx = w / 2f; val cy = h / 2f; val r = minOf(w, h) * 0.3f
        for (y in 0 until h) for (x in 0 until w) {
            val inside = (x - cx) * (x - cx) + (y - cy) * (y - cy) < r * r
            val noise = ((x * 7 + y * 13) % 17)
            img.pixels[y * w + x] = if (inside) 0xFFE8701A.toInt()
            else (0xFF shl 24) or ((60 + noise) shl 16) or ((120 + noise) shl 8) or (70 + noise)
        }
        return img
    }

    @Test
    fun maskAndTiming() {
        for (xnn in listOf(true, false)) {
            val t0 = System.nanoTime()
            BackgroundRemover.open(context, useXnnpack = xnn).use { remover ->
                val openMs = (System.nanoTime() - t0) / 1_000_000
                val img = scene(720, 720)
                val times = (0 until 3).map {
                    val s = System.nanoTime()
                    val mask = remover.maskOf(img)
                    if (it == 0) {
                        val center = mask[360 * 720 + 360].toInt() and 0xFF
                        val corner = mask[10 * 720 + 10].toInt() and 0xFF
                        Log.i(TAG, "xnnpack=$xnn merkez=$center köşe=$corner")
                        assertTrue("merkez ön plan olmalı ($center)", center > 200)
                        assertTrue("köşe arka plan olmalı ($corner)", corner < 50)
                    }
                    (System.nanoTime() - s) / 1_000_000 to remover.lastInferenceMs
                }
                Log.i(TAG, "xnnpack=$xnn açılış=${openMs} ms, toplam/çıkarım ms: $times")
            }
        }
    }

    companion object { private const val TAG = "WSC_SEGMENT" }
}
