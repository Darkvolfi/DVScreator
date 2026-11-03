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
 * Teşhis: ince parçalar (ör. tank namlusu) maskede ne değer alıyor; 512 girdi ile modelin eğitildiği 1024 girdi.
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
        BackgroundRemover.open(context).use { r ->
            fun report(name: String, mask: ByteArray, ms: Long) {
                val values = points.map { (x, y) -> mask[y * image.width + x].toInt() and 0xFF }
                val fg = mask.count { (it.toInt() and 0xFF) >= 128 } * 100.0 / mask.size
                Log.i(TAG, "$name $ms ms ön plan=%.1f%% noktalar=$values".format(fg))
                val cut = r.applyMask(image, mask)
                File(out, "$name.png").outputStream().use { cut.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            r.maskOf(image)   // ısınma
            val t = System.nanoTime()
            report("512", r.maskOf(image), (System.nanoTime() - t) / 1_000_000)
        }
        // Karşılaştırma: modelin eğitildiği 1024 girdi (yamasız model, adb push ile)
        val big = File("/data/local/tmp/isnet1024.onnx")
        if (big.exists()) BackgroundRemover.open(context, modelFile = big).use { r ->
            r.maskOf(image)
            val t = System.nanoTime()
            val mask = r.maskOf(image)
            val ms = (System.nanoTime() - t) / 1_000_000
            val values = points.map { (x, y) -> mask[y * image.width + x].toInt() and 0xFF }
            Log.i(TAG, "1024 girdi $ms ms noktalar=$values")
            File(out, "bin.png").outputStream().use { r.applyMask(image, mask).toBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        Log.i(TAG, "PNG: ${out.path}")
    }

    private companion object {
        const val TAG = "WSC_THIN"
    }
}
