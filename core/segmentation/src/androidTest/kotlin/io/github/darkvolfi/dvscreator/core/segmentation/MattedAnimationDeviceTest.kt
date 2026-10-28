package io.github.darkvolfi.dvscreator.core.segmentation

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.darkvolfi.dvscreator.core.imaging.AnimationTooLargeException
import io.github.darkvolfi.dvscreator.core.imaging.StickerFactory
import io.github.darkvolfi.dvscreator.core.model.EditRecipe
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Teşhis: `files/seg` içindeki GIF / MP4'lerden arka planı kaldırılmış sticker üretir, boyutu ve süreyi yazar. */
@RunWith(AndroidJUnit4::class)
class MattedAnimationDeviceTest {
    @Test
    fun animations() = runTest(timeout = kotlin.time.Duration.parse("30m")) {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.filesDir, "seg")
        val sources = dir.listFiles { f -> f.name.endsWith(".gif") || f.name.endsWith(".mp4") }.orEmpty()
        assumeTrue(sources.isNotEmpty())
        VideoBackgroundRemover.open(target).use { remover ->
            for (f in sources) {
                val mime = if (f.name.endsWith(".gif")) "image/gif" else "video/mp4"
                val t0 = System.currentTimeMillis()
                val msg = try {
                    val s = StickerFactory.create(ctx, Uri.fromFile(f), mime, EditRecipe(removeBackground = true), remover.matte())
                    File(dir, "s_${f.nameWithoutExtension}.webp").writeBytes(s.webp)
                    "${s.webp.size / 1024} KB q=${s.quality} ${s.frameCount} kare %.1f fps degraded=${s.degraded}".format(s.fps)
                } catch (e: AnimationTooLargeException) {
                    "SIĞMADI: ${e.message}"
                }
                Log.i("WSC_SEGMENT", "${f.name}: $msg, ${(System.currentTimeMillis() - t0) / 1000} sn")
            }
        }
    }
}
