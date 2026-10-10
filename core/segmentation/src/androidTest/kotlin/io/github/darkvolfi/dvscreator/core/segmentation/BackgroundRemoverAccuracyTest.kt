package io.github.darkvolfi.dvscreator.core.segmentation

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.darkvolfi.dvscreator.core.imaging.ArgbImage
import io.github.darkvolfi.dvscreator.core.imaging.toBitmap
import io.github.darkvolfi.dvscreator.core.webp.NativeWebp
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * Doğruluk: şeffaf arka planlı stickerlar (gerçek alfa = doğru cevap) farklı arka planlara yerleştirilir,
 * modelin maskesi gerçek alfayla karşılaştırılır (IoU). Görseller depoda değil: adb ile
 * `run-as` ile test paketinin `files/seg/` klasörüne konur (*.webp; isteğe bağlı orijinal 1024 model: original.onnx).
 */
@RunWith(AndroidJUnit4::class)
class BackgroundRemoverAccuracyTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun iou() {
        val dir = File(context.filesDir, "seg")
        val files = dir.listFiles { f -> f.name.endsWith(".webp") }.orEmpty().sortedBy { it.name }
        assumeTrue("test görseli yok: $dir", files.isNotEmpty())
        val out = File(dir, "out").apply { mkdirs() }
        val models = buildList {
            add("512" to null)
            File(dir, "original.onnx").takeIf { it.exists() }?.let { add("1024" to it) }
        }
        for ((label, file) in models) {
            BackgroundRemover.open(target, modelFile = file).use { remover ->
                val scores = ArrayList<Double>()
                for (f in files) {
                    val sticker = firstFrame(f.readBytes()) ?: continue
                    if (sticker.pixels.count { (it ushr 24) < 128 } < 512 * 512 / 10) continue   // neredeyse hiç şeffaf alan yok: anlamsız
                    for (bg in BACKGROUNDS) {
                        val (scene, truth) = compose(sticker, bg, f.name.hashCode())
                        val mask = remover.maskOf(scene)
                        var inter = 0; var union = 0; var fgMean = 0L; var fgN = 0
                        for (i in truth.indices) {
                            val t = truth[i]; val p = (mask[i].toInt() and 0xFF) >= 128
                            if (t && p) inter++
                            if (t || p) union++
                            if (t) { fgMean += mask[i].toInt() and 0xFF; fgN++ }
                        }
                        val iou = inter.toDouble() / union.coerceAtLeast(1)
                        scores += iou
                        Log.i(TAG, "model=$label ${f.name.take(12)} bg=$bg IoU=%.3f ön plan ort. maske=%d".format(iou, fgMean / fgN.coerceAtLeast(1)))
                        if (bg == "doku") {
                            val vis = ArgbImage(scene.width, scene.height)
                            for (i in vis.pixels.indices) vis.pixels[i] = (scene.pixels[i] and 0xFFFFFF) or ((mask[i].toInt() and 0xFF) shl 24)
                            File(out, "${label}_${f.nameWithoutExtension.take(12)}.png").outputStream().use {
                                vis.toBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                            }
                        }
                    }
                }
                Log.i(TAG, "model=$label ORTALAMA IoU=%.3f en kötü=%.3f (n=${scores.size})".format(scores.average(), scores.min()))
            }
        }
    }

    private fun firstFrame(webp: ByteArray): ArgbImage? {
        val info = NativeWebp.info(webp)
        if (info.canvasWidth != 512 || info.canvasHeight != 512) return null
        var img: ArgbImage? = null
        NativeWebp.forEachFrame(webp) { i, argb, _ -> if (i == 0) img = ArgbImage(512, 512, argb.copyOf()) }
        return img
    }

    /** Sticker 720×720 sahnenin ortasına, arka planın üzerine (alfa karışımıyla) yerleştirilir. */
    private fun compose(sticker: ArgbImage, bg: String, seed: Int): Pair<ArgbImage, BooleanArray> {
        val n = 720; val off = (n - 512) / 2
        val rnd = Random(seed)
        val scene = ArgbImage(n, n)
        val truth = BooleanArray(n * n)
        val c1 = rnd.nextInt(0xFFFFFF); val c2 = rnd.nextInt(0xFFFFFF)
        for (y in 0 until n) for (x in 0 until n) {
            val back = when (bg) {
                "beyaz" -> 0xF4F4F4
                "düz" -> c1
                else -> {   // "doku": iki renk arası yumuşak dalgalar + gürültü (fotoğraf arka planı gibi)
                    val t = (kotlin.math.sin(x / 37.0) * kotlin.math.cos(y / 53.0) + 1) / 2
                    val noise = rnd.nextInt(-12, 13)
                    mix(c1, c2, t, noise)
                }
            }
            var p = back
            val sx = x - off; val sy = y - off
            if (sx in 0 until 512 && sy in 0 until 512) {
                val s = sticker.pixels[sy * 512 + sx]
                val a = s ushr 24
                truth[y * n + x] = a >= 128
                p = blend(s and 0xFFFFFF, back, a)
            }
            scene.pixels[y * n + x] = (0xFF shl 24) or p
        }
        return scene to truth
    }

    private fun mix(a: Int, b: Int, t: Double, noise: Int): Int {
        fun ch(s: Int) = ((((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt() + noise).coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun blend(fg: Int, bg: Int, a: Int): Int {
        fun ch(s: Int) = (((fg shr s) and 0xFF) * a + ((bg shr s) and 0xFF) * (255 - a)) / 255
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    companion object {
        private const val TAG = "WSC_SEGMENT"
        private val BACKGROUNDS = listOf("beyaz", "düz", "doku")
    }
}
