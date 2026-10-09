package io.github.darkvolfi.wscreator.core.imaging

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.darkvolfi.wscreator.core.model.EditRecipe
import io.github.darkvolfi.wscreator.core.webp.NativeWebp
import io.github.darkvolfi.wscreator.core.webp.WebpOptions
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Teşhis: her WebP ayarının süresi ve boyutu. `adb logcat -s WSC_TIMING` */
@RunWith(AndroidJUnit4::class)
class EncoderTimingDeviceTest {

    @Test
    fun ayar_bazinda_sure_ve_boyut() {
        val pipeline = StickerPipeline(NativeWebp)
        val configs = listOf(
            "lossy q75 m4" to WebpOptions.lossy(75f, 4),
            "lossy q75 m5" to WebpOptions.lossy(75f, 5),
            "lossy q75 m6" to WebpOptions.lossy(75f, 6),
            "m6 alphaF2" to WebpOptions.lossy(75f, 6).copy(alphaFiltering = 2),
            "m6 noSharpYuv" to WebpOptions.lossy(75f, 6).copy(sharpYuv = false),
            "near80 L6" to WebpOptions.nearLossless(80, 6),
        )
        val rows = mutableListOf<String>()
        for (name in listOf("photo_3000x2000", "zone_plate_2048", "noise_1024")) {
            val img = pipeline.render(TestImages.all().first { it.name == name }.image, EditRecipe())
            for ((label, options) in configs) {
                val t = SystemClock.elapsedRealtime()
                val bytes = NativeWebp.encode(img.pixels, img.width, img.height, options)
                val ms = SystemClock.elapsedRealtime() - t
                rows += String.format(Locale.ROOT, "%-16s %-14s %7.1f KB %6d ms", name, label, bytes.size / 1024.0, ms)
            }
        }
        Log.i("WSC_TIMING", "\n" + rows.joinToString("\n"))
    }
}
