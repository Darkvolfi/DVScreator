package io.github.darkvolfi.dvscreator.core.imaging

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.darkvolfi.dvscreator.core.model.TextLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TextOverlayDeviceTest {
    @Test
    fun textIsDrawnOnFinalCanvas() {
        val frame = ArgbImage(512, 512, IntArray(512 * 512) { i -> if ((i % 512) in 100..411) 0xFF4A90D9.toInt() else 0 })
        val before = frame.pixels.copyOf()
        val layers = listOf(
            TextLayer("Merhaba :3", 0.5f, 0.84f, 0.14f, 0xFFFFFFFF.toInt(), 0xFF000000.toInt()),
            TextLayer("üst\nyazı", 0.5f, 0.2f, 0.1f, 0xFFFFD600.toInt(), null),
        )
        TextOverlay.apply(frame, layers)
        // Yazının ortası beyaza, yazısız bölge aynı kalır
        val b = TextOverlay.bounds(layers[0], 512)
        assertNotEquals(before.toList(), frame.pixels.toList())
        assertEquals(before[256 * 512 + 256], frame.pixels[256 * 512 + 256])
        assertEquals(true, b.centerY() > 400)
        val out = File(InstrumentationRegistry.getInstrumentation().context.filesDir, "text.png")
        out.outputStream().use { frame.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun outlineRotationAndText() {
        // Şeffaf zemin üzerinde turuncu bir "konu" (daire + çubuk), dış çizgi + 90° döndürme + yazı
        val src = ArgbImage(800, 600)
        for (y in 0 until 600) for (x in 0 until 800) {
            val inCircle = (x - 400) * (x - 400) + (y - 300) * (y - 300) < 200 * 200
            val inBar = x in 560..760 && y in 280..320
            if (inCircle || inBar) src.pixels[y * 800 + x] = 0xFFE8701A.toInt()
        }
        val recipe = io.github.darkvolfi.dvscreator.core.model.EditRecipe(
            rotationDegrees = 90, outlinePx = 10,
            texts = listOf(TextLayer("Selam", 0.5f, 0.85f, 0.14f, 0xFFFFFFFF.toInt(), 0xFF000000.toInt())),
        )
        val out = StickerPipeline(io.github.darkvolfi.dvscreator.core.webp.NativeWebp).render(src, recipe)
        val file = File(InstrumentationRegistry.getInstrumentation().context.filesDir, "outline.png")
        file.outputStream().use { out.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals(512, out.width)
    }
}
