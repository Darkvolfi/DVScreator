package io.github.darkvolfi.dvscreator.core.segmentation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.darkvolfi.dvscreator.core.imaging.ArgbImage
import io.github.darkvolfi.dvscreator.core.imaging.toArgbImage
import io.github.darkvolfi.dvscreator.core.imaging.toBitmap
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Teşhis: ince parçalar (ör. tank namlusu) maskede ne değer alıyor; aynı model 512 ve 1024 girdiyle.
 * Resim: /data/local/tmp/thin.jpg (adb push). Kesilmiş sonuçlar test uygulamasının dış dosyalarına PNG olarak yazılır.
 * Sonuçlar: `adb logcat -s WSC_THIN`
 */
@RunWith(AndroidJUnit4::class)
class ThinPartsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun thinParts() {
        val file = File("/data/local/tmp/thin.jpg")
        assumeTrue("resim yok", file.exists())
        val image = BitmapFactory.decodeFile(file.path).copy(Bitmap.Config.ARGB_8888, false).toArgbImage()
        // Örnek noktalar: "x:y/x:y" (resim pikseli; Gradle virgülü, kabuk noktalı virgülü böler)
        val points = (InstrumentationRegistry.getArguments().getString("points") ?: "").split('/').filter { it.isNotBlank() }
            .map { p -> p.split(':').let { it[0].toInt() to it[1].toInt() } }
        val out = context.getExternalFilesDir(null)!!
        for (size in listOf(BackgroundRemover.FAST_INPUT, BackgroundRemover.QUALITY_INPUT)) BackgroundRemover.open(context, inputSize = size).use { r ->
            fun report(name: String, mask: ByteArray, ms: Long) {
                val values = points.map { (x, y) -> mask[y * image.width + x].toInt() and 0xFF }
                val fg = mask.count { (it.toInt() and 0xFF) >= 128 } * 100.0 / mask.size
                Log.i(TAG, "$name $ms ms ön plan=%.1f%% noktalar=$values".format(fg))
                val cut = r.applyMask(image, mask)
                File(out, "$name.png").outputStream().use { cut.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            r.maskOf(image)   // ısınma
            val t = System.nanoTime()
            report("$size", r.maskOf(image), (System.nanoTime() - t) / 1_000_000)
        }
        Log.i(TAG, "PNG: ${out.path}")
    }

    private companion object {
        const val TAG = "WSC_THIN"
    }
}
