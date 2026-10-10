package io.github.darkvolfi.dvscreator.core.imaging

import io.github.darkvolfi.dvscreator.core.model.MaskStroke
import org.junit.Assert.assertEquals
import org.junit.Test

class StrokePainterTest {
    private fun opaque(w: Int, h: Int) = ArgbImage(w, h, IntArray(w * h) { 0xFF336699.toInt() })
    private fun alpha(img: ArgbImage, x: Int, y: Int) = img.pixels[y * img.width + x] ushr 24

    @Test
    fun `silgi izi şeffaflaştırır, dışı aynen kalır`() {
        val img = opaque(100, 100)
        val out = Matte.of(null, listOf(MaskStroke(false, 0.05f, listOf(0.2f, 0.5f, 0.8f, 0.5f))))!!.apply(img, Matte.Region.FULL)
        assertEquals(0, alpha(out, 50, 50))
        assertEquals(255, alpha(out, 50, 10))
        assertEquals(0x336699, out.pixels[10 * 100 + 50] and 0xFFFFFF)
    }

    @Test
    fun `geri getir otomatik maskeyi geri alır`() {
        val img = opaque(100, 100)
        val clearAll = Matte { i, _ -> ArgbImage(i.width, i.height, IntArray(i.pixels.size)) }
        val out = Matte.of(clearAll, listOf(MaskStroke(true, 0.1f, listOf(0.5f, 0.5f))))!!.apply(img, Matte.Region.FULL)
        assertEquals(255, alpha(out, 50, 50))
        assertEquals(0xFF336699.toInt(), out.pixels[50 * 100 + 50])
        assertEquals(0, alpha(out, 5, 5))
    }

    @Test
    fun `kırpılmış görüntüde iz kaynağın tamamına göre konumlanır`() {
        // Kaynağın sağ yarısı kırpılmış (200 px genişlik → 100 px), iz kaynağın x=0.75 noktasında
        val img = opaque(100, 100)
        val region = Matte.Region(0.5f, 0f, 1f, 1f)
        val out = Matte.of(null, listOf(MaskStroke(false, 0.02f, listOf(0.75f, 0.5f))))!!.apply(img, region)
        assertEquals(0, alpha(out, 50, 50))
        assertEquals(255, alpha(out, 10, 50))
    }
}
