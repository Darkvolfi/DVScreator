package io.github.darkvolfi.dvscreator.core.segmentation

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.darkvolfi.dvscreator.core.imaging.ImageLoader
import io.github.darkvolfi.dvscreator.core.imaging.toBitmap
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Teşhis: `files/seg` içindeki fotoğraflara düzenleme ekranındaki önizlemeyle aynı yoldan maske uygular, PNG kaydeder. */
@RunWith(AndroidJUnit4::class)
class PhotoMatteDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun photos() {
        val dir = File(context.filesDir, "seg")
        val photos = dir.listFiles { f -> f.name.endsWith(".jpg") || f.name.endsWith(".png") && !f.name.startsWith("out_") }.orEmpty()
        assumeTrue(photos.isNotEmpty())
        BackgroundRemover.open(target).use { remover ->
            for (p in photos) {
                val image = ImageLoader(context.contentResolver).load(Uri.fromFile(p), minLongSide = 1080).image
                val mask = remover.maskOf(image)
                val hist = IntArray(5); for (m in mask) hist[(m.toInt() and 0xFF) * 5 / 256]++
                Log.i("WSC_SEGMENT", "${p.name} ${image.width}x${image.height} maske dağılımı (0-51..204-255): ${hist.map { it * 100 / mask.size }}%")
                val out = remover.matte().apply(image, io.github.darkvolfi.dvscreator.core.imaging.Matte.Region.FULL)
                File(dir, "out_${p.nameWithoutExtension}.png").outputStream().use { out.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
                val m = io.github.darkvolfi.dvscreator.core.imaging.ArgbImage(image.width, image.height)
                for (i in mask.indices) { val v = mask[i].toInt() and 0xFF; m.pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
                File(dir, "out_${p.nameWithoutExtension}_mask.png").outputStream().use { m.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
