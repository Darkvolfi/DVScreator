package io.github.darkvolfi.dvscreator.core.segmentation

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.darkvolfi.dvscreator.core.imaging.ArgbImage
import io.github.darkvolfi.dvscreator.core.imaging.toBitmap
import io.github.darkvolfi.dvscreator.core.webp.NativeWebp
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Teşhis: `files/seg/s_…webp` stickerlarının ilk karesini dama deseni üzerinde PNG olarak kaydeder. */
@RunWith(AndroidJUnit4::class)
class StickerDumpTest {
    @Test
    fun dump() {
        val dir = File(InstrumentationRegistry.getInstrumentation().context.filesDir, "seg")
        for (f in dir.listFiles { f -> f.name.startsWith("s_") && f.name.endsWith(".webp") }.orEmpty()) {
            var img: ArgbImage? = null
            val h = NativeWebp.forEachFrame(f.readBytes()) { i, argb, _ -> if (i == 0) img = ArgbImage(512, 512, argb.copyOf()) }
            val src = img ?: continue
            val out = ArgbImage(h.canvasWidth, h.canvasHeight)
            for (i in out.pixels.indices) {
                val x = i % 512; val y = i / 512
                val bg = if ((x / 16 + y / 16) % 2 == 0) 0xFFFFFF else 0xCCCCCC
                val p = src.pixels[i]; val a = p ushr 24
                fun ch(s: Int) = (((p shr s) and 0xFF) * a + ((bg shr s) and 0xFF) * (255 - a)) / 255
                out.pixels[i] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
            File(dir, "dump_${f.nameWithoutExtension}.png").outputStream().use { out.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
