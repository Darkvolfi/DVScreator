package io.github.darkvolfi.dvscreator.core.segmentation

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.darkvolfi.dvscreator.core.imaging.ArgbImage
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Teşhis: NNAPI (FP16 / FP32) maskesi CPU maskesiyle aynı mı, ne kadar hızlı, oturum açma ne kadar sürüyor.
 * Sahneler sentetik (figür + farklı arka planlar); doğru cevap CPU maskesidir.
 */
@RunWith(AndroidJUnit4::class)
class NnapiAgreementDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun agreement() {
        val scenes = BACKGROUNDS.map { bg -> bg to scene(960, 720, bg) }
        val cpu = BackgroundRemover.open(context).use { r -> scenes.map { (_, img) -> r.maskOf(img) } }
        for (fp16 in listOf(true, false)) {
            val t0 = System.nanoTime()
            val result = runCatching {
                BackgroundRemover.open(context, useNnapi = true, nnapiFp16 = fp16).use { r ->
                    val openMs = (System.nanoTime() - t0) / 1_000_000
                    val masks = scenes.map { (_, img) -> r.maskOf(img) }
                    val times = scenes.map { (_, img) -> r.maskOf(img); r.lastInferenceMs }
                    Triple(openMs, masks, times)
                }
            }
            result.onFailure { Log.i(TAG, "nnapi fp16=$fp16 HATA: ${it.message?.take(200)}") }
            result.onSuccess { (openMs, masks, times) ->
                for ((i, bg) in BACKGROUNDS.withIndex()) {
                    val a = cpu[i]; val b = masks[i]
                    var inter = 0; var union = 0; var diff = 0L; var fg = 0
                    for (k in a.indices) {
                        val pa = (a[k].toInt() and 0xFF) >= 128; val pb = (b[k].toInt() and 0xFF) >= 128
                        if (pa && pb) inter++
                        if (pa || pb) union++
                        if (pa) fg++
                        diff += abs((a[k].toInt() and 0xFF) - (b[k].toInt() and 0xFF))
                    }
                    Log.i(TAG, "nnapi fp16=$fp16 bg=$bg IoU(cpu)=%.4f ort.fark=%.2f ön plan=%.1f%%".format(
                        inter.toDouble() / union.coerceAtLeast(1), diff.toDouble() / a.size, 100.0 * fg / a.size))
                }
                Log.i(TAG, "nnapi fp16=$fp16 oturum açma $openMs ms, çıkarım ms $times")
            }
        }
    }

    /** Ortada insan benzeri figür (baş + gövde, ten ve giysi renkleri) ve arka plan. */
    private fun scene(w: Int, h: Int, bg: String): ArgbImage {
        val rnd = Random(bg.hashCode())
        val img = ArgbImage(w, h)
        val cx = w / 2.0; val headY = h * 0.32; val headR = h * 0.13
        for (y in 0 until h) for (x in 0 until w) {
            var r: Int; var g: Int; var b: Int
            when (bg) {
                "düz" -> { r = 210; g = 215; b = 220 }
                "gradyan" -> { r = (60 + 150 * x / w); g = (90 + 100 * y / h); b = 180 }
                "doku" -> { val s = (40 * sin(x * 0.21) * sin(y * 0.17)).toInt(); r = 120 + s; g = 140 + s; b = 90 + s }
                else -> { r = rnd.nextInt(256); g = rnd.nextInt(256); b = rnd.nextInt(256) }   // gürültü
            }
            val inHead = hypot(x - cx, y - headY) < headR
            val inBody = y > headY + headR * 0.8 && y < h * 0.95 && abs(x - cx) < h * 0.2 * (0.6 + 0.4 * (y - headY) / (h - headY))
            if (inHead) { r = 225; g = 180; b = 150 } else if (inBody) { r = 40; g = 70; b = 160 }
            img.pixels[y * w + x] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
        return img
    }

    companion object {
        private const val TAG = "WSC_SEGMENT"
        private val BACKGROUNDS = listOf("düz", "gradyan", "doku", "gürültü")
    }
}
